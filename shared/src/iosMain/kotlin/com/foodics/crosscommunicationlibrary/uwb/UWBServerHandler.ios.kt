@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.foodics.crosscommunicationlibrary.uwb

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import platform.posix.*
import kotlin.concurrent.Volatile

/**
 * iOS UWB server using NearbyInteraction (NISession, Controller role).
 *
 * Flow:
 *  1. start()    → obtains local NIDiscoveryToken from Swift bridge
 *                  → opens UDP multicast (port 1901) + TCP server for OOB
 *                  → advertises; waits for a client TCP connection
 *                  → exchanges serialised tokens over TCP
 *                  → calls bridge.startRanging(peerToken) to begin NISession
 *  2. Ranging    → Swift bridge calls onRangingResult; results emitted as ByteArray
 *  3. stop()     → cancels coroutines, closes sockets, stops bridge session
 */
actual class UWBServerHandler : UWBRangingDelegate {

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var udpFd      = -1
    @Volatile private var tcpServerFd = -1

    private val _ranging = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

    init { UWBBridgeProvider.serverBridge?.delegate = this }
    private val bridge get() = UWBBridgeProvider.serverBridge

    // ── UWBRangingDelegate ────────────────────────────────────────────────────

    override fun onRangingResult(distanceMeters: Float, azimuthDegrees: Float, elevationDegrees: Float) {
        _ranging.tryEmit(encodeRangingIos(distanceMeters, azimuthDegrees, elevationDegrees))
    }

    override fun onSessionError(description: String) {
        println("[UWBServer] NI session error: $description")
    }

    override fun onSessionInvalidated() {
        println("[UWBServer] NI session invalidated")
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun start(deviceName: String, identifier: String) {
        stop()

        val myToken = bridge?.getDiscoveryToken() ?: run {
            println("[UWBServer] NIDiscoveryToken unavailable — UWB not supported or bridge not set")
            return
        }
        val myTokenBytes = myToken.toByteArray()

        // UDP multicast socket (port 1901)
        val uFd = socket(AF_INET, SOCK_DGRAM, 0)
        check(uFd >= 0) { "[UWBServer] UDP socket failed: errno=$errno" }
        memScoped {
            val one = alloc<IntVar>().apply { value = 1 }
            setsockopt(uFd, SOL_SOCKET, SO_REUSEADDR, one.ptr, sizeOf<IntVar>().convert())
            setsockopt(uFd, SOL_SOCKET, SO_REUSEPORT, one.ptr, sizeOf<IntVar>().convert())
            val addr = alloc<sockaddr_in>()
            addr.sin_family      = AF_INET.convert()
            addr.sin_port        = uwbHtons(UWB_OOB_PORT_US)
            addr.sin_addr.s_addr = INADDR_ANY
            bind(uFd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
            val mreq = alloc<ip_mreq>()
            mreq.imr_multiaddr.s_addr = uwbInetAddr(UWB_MULTICAST_IP)
            mreq.imr_interface.s_addr = INADDR_ANY
            setsockopt(uFd, IPPROTO_IP, IP_ADD_MEMBERSHIP, mreq.ptr, sizeOf<ip_mreq>().convert())
        }
        udpFd = uFd

        // TCP server socket (random port)
        val tFd = socket(AF_INET, SOCK_STREAM, 0)
        check(tFd >= 0) { "[UWBServer] TCP socket failed: errno=$errno" }
        memScoped {
            val one = alloc<IntVar>().apply { value = 1 }
            setsockopt(tFd, SOL_SOCKET, SO_REUSEADDR, one.ptr, sizeOf<IntVar>().convert())
            val addr = alloc<sockaddr_in>()
            addr.sin_family      = AF_INET.convert()
            addr.sin_port        = uwbHtons(0u)
            addr.sin_addr.s_addr = INADDR_ANY
            bind(tFd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
        }
        listen(tFd, 5)
        tcpServerFd = tFd

        val ip      = uwbGetLocalIpIos()
        val tcpPort = uwbGetTcpServerPort(tFd)
        println("[UWBServer] OOB: $deviceName @ $ip:$tcpPort")

        // Periodic NOTIFY alive
        scope.launch {
            while (isActive) {
                val msg = uwbIosAnnounce(deviceName, identifier, ip, tcpPort)
                uwbUdpSend(uFd, msg, UWB_MULTICAST_IP, UWB_OOB_PORT_US)
                delay(5_000)
            }
        }

        // Reply to M-SEARCH
        scope.launch {
            while (isActive) {
                val msg = uwbUdpRecv(uFd, 2_000) ?: continue
                if (msg.startsWith(UWB_IOS_SEARCH)) {
                    val srcIp   = StringBuilder()
                    val srcPort = IntArray(1)
                    uwbUdpRecv(uFd, 100, srcIp, srcPort) // re-receive to get source address
                    val resp = uwbIosAnnounce(deviceName, identifier, ip, tcpPort)
                    uwbUdpSend(uFd, resp, srcIp.toString().ifEmpty { UWB_MULTICAST_IP }, UWB_OOB_PORT_US)
                }
            }
        }

        // Accept TCP client, exchange tokens
        scope.launch {
            val cFd = acceptWithTimeout(tFd, timeoutMs = 0) ?: run {
                println("[UWBServer] TCP accept failed"); return@launch
            }

            // Send our token first, then read peer token
            uwbTcpSendToken(cFd, myTokenBytes)
            val peerTokenBytes = uwbTcpRecvToken(cFd)
            close(cFd)

            if (peerTokenBytes == null) {
                println("[UWBServer] Failed to receive peer token"); return@launch
            }

            println("[UWBServer] Token exchange complete — starting NI ranging")
            bridge?.startRanging(peerTokenBytes.toNSData())
        }
    }

    fun receiveFromClient(): Flow<ByteArray> = _ranging.asSharedFlow()

    fun stop() {
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        bridge?.stop()

        val uFd = udpFd; udpFd = -1
        val tFd = tcpServerFd; tcpServerFd = -1
        if (uFd >= 0) {
            memScoped {
                val mreq = alloc<ip_mreq>()
                mreq.imr_multiaddr.s_addr = uwbInetAddr(UWB_MULTICAST_IP)
                mreq.imr_interface.s_addr = INADDR_ANY
                setsockopt(uFd, IPPROTO_IP, IP_DROP_MEMBERSHIP, mreq.ptr, sizeOf<ip_mreq>().convert())
            }
            close(uFd)
        }
        if (tFd >= 0) close(tFd)
        println("[UWBServer] Stopped")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun acceptWithTimeout(serverFd: Int, timeoutMs: Int): Int? = memScoped {
        if (timeoutMs > 0) {
            val tv = alloc<timeval>()
            tv.tv_sec  = (timeoutMs / 1000).convert()
            tv.tv_usec = ((timeoutMs % 1000) * 1000).convert()
            setsockopt(serverFd, SOL_SOCKET, SO_RCVTIMEO, tv.ptr, sizeOf<timeval>().convert())
        }
        val cFd = accept(serverFd, null, null)
        if (cFd < 0) null else cFd
    }
}
