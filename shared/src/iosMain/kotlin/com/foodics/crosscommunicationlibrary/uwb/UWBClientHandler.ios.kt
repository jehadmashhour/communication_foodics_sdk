@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.foodics.crosscommunicationlibrary.uwb

import ConnectionType
import client.WriteType
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import platform.posix.*
import scanner.IoTDevice
import kotlin.concurrent.Volatile

/**
 * iOS UWB client using NearbyInteraction (NISession, Responder role).
 *
 * Flow:
 *  scan()      → UDP multicast discovery; emits list of UWB-IOS servers.
 *  connect()   → obtains local token from Swift bridge
 *               → connects to server TCP port, exchanges serialised tokens
 *               → calls bridge.startRanging(peerToken) to begin NISession.
 *  Ranging     → Swift bridge calls onRangingResult; results emitted as ByteArray.
 *  disconnect()→ stops bridge session.
 */
actual class UWBClientHandler : UWBRangingDelegate {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var tcpFd = -1
    private val _ranging = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

    init { UWBBridgeProvider.clientBridge?.delegate = this }
    private val bridge get() = UWBBridgeProvider.clientBridge

    // ── UWBRangingDelegate ────────────────────────────────────────────────────

    override fun onRangingResult(distanceMeters: Float, azimuthDegrees: Float, elevationDegrees: Float) {
        _ranging.tryEmit(encodeRangingIos(distanceMeters, azimuthDegrees, elevationDegrees))
    }

    override fun onSessionError(description: String) {
        println("[UWBClient] NI session error: $description")
    }

    override fun onSessionInvalidated() {
        println("[UWBClient] NI session invalidated")
    }

    // ── Scan ──────────────────────────────────────────────────────────────────

    fun scan(): Flow<List<IoTDevice>> = channelFlow {
        val uFd = socket(AF_INET, SOCK_DGRAM, 0)
        if (uFd < 0) { println("[UWBClient] UDP socket failed: errno=$errno"); return@channelFlow }

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

        val discovered = mutableMapOf<String, IoTDevice>()

        fun sendSearch() {
            uwbUdpSend(uFd, UWB_IOS_SEARCH, UWB_MULTICAST_IP, UWB_OOB_PORT_US)
            println("[UWBClient] M-SEARCH sent")
        }
        sendSearch()

        // Repeat every 5 s
        launch { while (isActive) { delay(5_000); sendSearch() } }

        // Receive announce messages
        launch {
            while (isActive) {
                val msg  = uwbUdpRecv(uFd, 2_000) ?: continue
                val info = parseUwbIosAnnounce(msg) ?: continue
                if (discovered.containsKey(info.id)) continue
                discovered[info.id] = IoTDevice(
                    id             = info.id,
                    name           = info.name,
                    address        = info.address,
                    connectionType = ConnectionType.UWB
                )
                trySend(discovered.values.toList())
                println("[UWBClient] Discovered UWB server: ${info.name} @ ${info.ip}:${info.tcpPort}")
            }
        }

        awaitClose {
            memScoped {
                val mreq = alloc<ip_mreq>()
                mreq.imr_multiaddr.s_addr = uwbInetAddr(UWB_MULTICAST_IP)
                mreq.imr_interface.s_addr = INADDR_ANY
                setsockopt(uFd, IPPROTO_IP, IP_DROP_MEMBERSHIP, mreq.ptr, sizeOf<ip_mreq>().convert())
            }
            close(uFd)
        }
    }

    // ── Connect ───────────────────────────────────────────────────────────────

    fun connect(device: IoTDevice) {
        val myToken = bridge?.getDiscoveryToken() ?: run {
            println("[UWBClient] NIDiscoveryToken unavailable — UWB not supported or bridge not set")
            return
        }
        val myTokenBytes = myToken.toByteArray()

        // address format: "<ip>:<tcpPort>"
        val parts = device.address.split(":")
        val ip    = parts.getOrNull(0) ?: return
        val port  = parts.getOrNull(1)?.toIntOrNull() ?: return

        scope.launch {
            // Open TCP connection to server
            val fd = socket(AF_INET, SOCK_STREAM, 0)
            if (fd < 0) { println("[UWBClient] TCP socket failed: errno=$errno"); return@launch }

            val connected = memScoped {
                val addr = alloc<sockaddr_in>()
                addr.sin_family      = AF_INET.convert()
                addr.sin_port        = uwbHtons(port.toUShort())
                addr.sin_addr.s_addr = uwbInetAddr(ip)
                platform.posix.connect(fd, addr.ptr.reinterpret(),
                    sizeOf<sockaddr_in>().convert()) == 0
            }
            if (!connected) { println("[UWBClient] TCP connect failed: errno=$errno"); close(fd); return@launch }

            tcpFd = fd
            println("[UWBClient] TCP connected to $ip:$port")

            // Receive server token first, then send ours
            val serverTokenBytes = uwbTcpRecvToken(fd)
            uwbTcpSendToken(fd, myTokenBytes)
            close(fd)
            tcpFd = -1

            if (serverTokenBytes == null) {
                println("[UWBClient] Failed to receive server token"); return@launch
            }

            println("[UWBClient] Token exchange complete — starting NI ranging")
            bridge?.startRanging(serverTokenBytes.toNSData())
        }
    }

    // ── Receive / Send / Disconnect ───────────────────────────────────────────

    fun receiveFromServer(): Flow<ByteArray> = _ranging.asSharedFlow()

    fun sendToServer(data: ByteArray, writeType: WriteType) {
        println("[UWBClient] sendToServer is a no-op — UWB is a ranging-only channel")
    }

    fun disconnect() {
        val fd = tcpFd; tcpFd = -1
        if (fd >= 0) close(fd)
        bridge?.stop()
        println("[UWBClient] Disconnected")
    }
}
