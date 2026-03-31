@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.foodics.crosscommunicationlibrary.ssdp

import ConnectionType
import client.WriteType
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import platform.posix.*
import scanner.IoTDevice
import kotlin.concurrent.Volatile

actual class SSDPClientHandler {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var tcpFd = -1
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

    // ── Scan ──────────────────────────────────────────────────────────────────

    fun scan(): Flow<List<IoTDevice>> = channelFlow {
        // Open UDP multicast socket (port 1900)
        val uFd = socket(AF_INET, SOCK_DGRAM, 0)
        if (uFd < 0) { println("[SSDPClient] UDP socket failed: errno=$errno"); return@channelFlow }

        memScoped {
            val one = alloc<IntVar>().apply { value = 1 }
            setsockopt(uFd, SOL_SOCKET, SO_REUSEADDR, one.ptr, sizeOf<IntVar>().convert())
            setsockopt(uFd, SOL_SOCKET, SO_REUSEPORT, one.ptr, sizeOf<IntVar>().convert())

            val addr = alloc<sockaddr_in>()
            addr.sin_family      = AF_INET.convert()
            addr.sin_port        = ssdpHtons(SSDP_PORT_US)
            addr.sin_addr.s_addr = INADDR_ANY
            bind(uFd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())

            val mreq = alloc<ip_mreq>()
            mreq.imr_multiaddr.s_addr = ssdpInetAddr(SSDP_MULTICAST_IP)
            mreq.imr_interface.s_addr = INADDR_ANY
            setsockopt(uFd, IPPROTO_IP, IP_ADD_MEMBERSHIP,
                mreq.ptr, sizeOf<ip_mreq>().convert())
        }

        val devicesMap = mutableMapOf<String, IoTDevice>()

        fun sendMSearch() {
            ssdpUdpSend(uFd, ssdpMSearchIos(), SSDP_MULTICAST_IP, SSDP_PORT_US)
            println("[SSDPClient] M-SEARCH sent")
        }
        sendMSearch()

        // Repeat M-SEARCH every 5 s
        launch { while (isActive) { delay(5_000); sendMSearch() } }

        // Receive 200 OK / NOTIFY responses
        launch {
            while (isActive) {
                val msg  = ssdpUdpRecv(uFd, 2_000) ?: continue
                val info = parseSSDPMessageIos(msg) ?: continue
                if (devicesMap.containsKey(info.id)) continue
                devicesMap[info.id] = IoTDevice(
                    id             = info.id,
                    name           = info.name,
                    address        = "${info.ip}:${info.port}",
                    connectionType = ConnectionType.SSDP
                )
                trySend(devicesMap.values.toList())
                println("[SSDPClient] Discovered: ${info.name} @ ${info.ip}:${info.port}")
            }
        }

        awaitClose {
            memScoped {
                val mreq = alloc<ip_mreq>()
                mreq.imr_multiaddr.s_addr = ssdpInetAddr(SSDP_MULTICAST_IP)
                mreq.imr_interface.s_addr = INADDR_ANY
                setsockopt(uFd, IPPROTO_IP, IP_DROP_MEMBERSHIP,
                    mreq.ptr, sizeOf<ip_mreq>().convert())
            }
            close(uFd)
        }
    }

    // ── Connect ───────────────────────────────────────────────────────────────

    fun connect(device: IoTDevice) {
        val parts = device.address.split(":")
        val ip    = parts[0]
        val port  = parts[1].toInt()

        val fd = socket(AF_INET, SOCK_STREAM, 0)
        if (fd < 0) { println("[SSDPClient] TCP socket failed: errno=$errno"); return }

        val connected = memScoped {
            val addr = alloc<sockaddr_in>()
            addr.sin_family      = AF_INET.convert()
            addr.sin_port        = ssdpHtons(port.toUShort())
            addr.sin_addr.s_addr = ssdpInetAddr(ip)
            platform.posix.connect(fd, addr.ptr.reinterpret(),
                sizeOf<sockaddr_in>().convert()) == 0
        }

        if (!connected) {
            println("[SSDPClient] TCP connect failed: errno=$errno")
            close(fd)
            return
        }

        tcpFd = fd
        println("[SSDPClient] TCP connected to $ip:$port")

        scope.launch {
            while (isActive) {
                val data = ssdpTcpRecv(fd) ?: break
                _incoming.emit(data)
            }
            println("[SSDPClient] TCP disconnected")
        }
    }

    // ── Send / Receive / Disconnect ───────────────────────────────────────────

    fun sendToServer(data: ByteArray, writeType: WriteType) {
        val fd = tcpFd
        if (fd < 0) { println("[SSDPClient] Not connected"); return }
        ssdpTcpSend(fd, data)
    }

    fun receiveFromServer(): Flow<ByteArray> = _incoming.asSharedFlow()

    fun disconnect() {
        val fd = tcpFd; tcpFd = -1
        if (fd >= 0) close(fd)
        println("[SSDPClient] Disconnected")
    }
}
