@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.foodics.crosscommunicationlibrary.ws_discovery

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import platform.posix.*
import kotlin.concurrent.Volatile

actual class WSDiscoveryServerHandler {

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var udpFd       = -1
    @Volatile private var tcpServerFd = -1
    @Volatile private var tcpClientFd = -1

    private val _fromClient = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

    fun start(deviceName: String, identifier: String) {
        stop()

        // ── UDP multicast socket (port 3702) ─────────────────────────────────
        val uFd = socket(AF_INET, SOCK_DGRAM, 0)
        check(uFd >= 0) { "[WSDServer] UDP socket failed: errno=$errno" }
        memScoped {
            val one = alloc<IntVar>().apply { value = 1 }
            setsockopt(uFd, SOL_SOCKET, SO_REUSEADDR, one.ptr, sizeOf<IntVar>().convert())
            setsockopt(uFd, SOL_SOCKET, SO_REUSEPORT, one.ptr, sizeOf<IntVar>().convert())

            val addr = alloc<sockaddr_in>()
            addr.sin_family  = AF_INET.convert()
            addr.sin_port    = wsdHtons(WSD_PORT_US)
            addr.sin_addr.s_addr = INADDR_ANY
            bind(uFd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())

            val mreq = alloc<ip_mreq>()
            mreq.imr_multiaddr.s_addr = wsdInetAddr(WSD_MULTICAST_IP)
            mreq.imr_interface.s_addr = INADDR_ANY
            setsockopt(uFd, IPPROTO_IP, IP_ADD_MEMBERSHIP,
                mreq.ptr, sizeOf<ip_mreq>().convert())
        }
        udpFd = uFd

        // ── TCP server socket (random port) ──────────────────────────────────
        val tFd = socket(AF_INET, SOCK_STREAM, 0)
        check(tFd >= 0) { "[WSDServer] TCP socket failed: errno=$errno" }
        memScoped {
            val one = alloc<IntVar>().apply { value = 1 }
            setsockopt(tFd, SOL_SOCKET, SO_REUSEADDR, one.ptr, sizeOf<IntVar>().convert())

            val addr = alloc<sockaddr_in>()
            addr.sin_family  = AF_INET.convert()
            addr.sin_port    = wsdHtons(0u)
            addr.sin_addr.s_addr = INADDR_ANY
            bind(tFd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
        }
        listen(tFd, 5)
        tcpServerFd = tFd

        val ip      = wsdGetLocalIpIos()
        val tcpPort = getTcpServerPort(tFd)
        println("[WSDServer] Started: $deviceName @ $ip:$tcpPort")

        // Send Hello on startup
        val helloMsgId = identifier + "-hello-start"
        val hello = wsdHelloIos(identifier, deviceName, ip, tcpPort, helloMsgId)
        wsdUdpSend(uFd, hello, WSD_MULTICAST_IP, WSD_PORT_US)
        println("[WSDServer] Hello sent")

        // ── Probe listener ────────────────────────────────────────────────────
        scope.launch {
            while (isActive) {
                val srcIp   = StringBuilder()
                val srcPort = IntArray(1)
                val msg = wsdUdpRecv(uFd, 2_000, srcIp, srcPort) ?: continue
                if (msg.contains("Probe") && !msg.contains("ProbeMatches") &&
                    msg.contains(WSD_SERVICE_TYPE)
                ) {
                    val relatesTo = extractMessageIdIos(msg) ?: "unknown"
                    val matchMsgId = identifier + "-match-" + relatesTo.takeLast(8)
                    val resp = wsdProbeMatchIos(identifier, deviceName, ip, tcpPort, relatesTo, matchMsgId)
                    wsdUdpSend(uFd, resp, srcIp.toString(), srcPort[0].toUShort())
                    println("[WSDServer] ProbeMatch sent to $srcIp")
                }
            }
        }

        // ── Periodic Hello ────────────────────────────────────────────────────
        scope.launch {
            while (isActive) {
                delay(30_000)
                val periodicMsgId = identifier + "-hello-" + time(null)
                val notify = wsdHelloIos(identifier, deviceName, ip, tcpPort, periodicMsgId)
                wsdUdpSend(uFd, notify, WSD_MULTICAST_IP, WSD_PORT_US)
                println("[WSDServer] Periodic Hello sent")
            }
        }

        // ── TCP accept loop ───────────────────────────────────────────────────
        scope.launch {
            while (isActive) {
                val cFd = acceptWithTimeout(tFd, 2_000) ?: continue
                val prevFd = tcpClientFd
                tcpClientFd = cFd
                if (prevFd >= 0) close(prevFd)
                println("[WSDServer] TCP client connected")
                scope.launch {
                    while (isActive) {
                        val data = wsdTcpRecv(cFd) ?: break
                        _fromClient.emit(data)
                    }
                    println("[WSDServer] TCP client disconnected")
                }
            }
        }
    }

    fun sendToClient(data: ByteArray) {
        val fd = tcpClientFd
        if (fd < 0) { println("[WSDServer] No client connected"); return }
        wsdTcpSend(fd, data)
    }

    fun receiveFromClient(): Flow<ByteArray> = _fromClient.asSharedFlow()

    fun stop() {
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val uFd = udpFd;       udpFd       = -1
        val tFd = tcpServerFd; tcpServerFd = -1
        val cFd = tcpClientFd; tcpClientFd = -1

        if (uFd >= 0) { leaveMulticast(uFd); close(uFd) }
        if (cFd >= 0) close(cFd)
        if (tFd >= 0) close(tFd)
        println("[WSDServer] Stopped")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun acceptWithTimeout(serverFd: Int, timeoutMs: Int): Int? = memScoped {
        val tv = alloc<timeval>()
        tv.tv_sec  = (timeoutMs / 1000).convert()
        tv.tv_usec = ((timeoutMs % 1000) * 1000).convert()
        setsockopt(serverFd, SOL_SOCKET, SO_RCVTIMEO, tv.ptr, sizeOf<timeval>().convert())
        val cFd = accept(serverFd, null, null)
        if (cFd < 0) null else cFd
    }

    private fun getTcpServerPort(fd: Int): Int = memScoped {
        val addr = alloc<sockaddr_in>()
        val len  = alloc<socklen_tVar>()
        len.value = sizeOf<sockaddr_in>().convert()
        getsockname(fd, addr.ptr.reinterpret(), len.ptr)
        wsdHtons(addr.sin_port).toInt()
    }

    private fun leaveMulticast(fd: Int) = memScoped {
        val mreq = alloc<ip_mreq>()
        mreq.imr_multiaddr.s_addr = wsdInetAddr(WSD_MULTICAST_IP)
        mreq.imr_interface.s_addr = INADDR_ANY
        setsockopt(fd, IPPROTO_IP, IP_DROP_MEMBERSHIP,
            mreq.ptr, sizeOf<ip_mreq>().convert())
    }
}
