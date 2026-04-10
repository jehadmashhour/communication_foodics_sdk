@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.foodics.crosscommunicationlibrary.ws_discovery

import ConnectionType
import client.WriteType
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import platform.posix.*
import scanner.IoTDevice
import kotlin.concurrent.Volatile

actual class WSDiscoveryClientHandler {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var tcpFd = -1
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

    // ── Scan ──────────────────────────────────────────────────────────────────

    fun scan(): Flow<List<IoTDevice>> = channelFlow {
        val uFd = socket(AF_INET, SOCK_DGRAM, 0)
        if (uFd < 0) { println("[WSDClient] UDP socket failed: errno=$errno"); return@channelFlow }

        memScoped {
            val one = alloc<IntVar>().apply { value = 1 }
            setsockopt(uFd, SOL_SOCKET, SO_REUSEADDR, one.ptr, sizeOf<IntVar>().convert())
            setsockopt(uFd, SOL_SOCKET, SO_REUSEPORT, one.ptr, sizeOf<IntVar>().convert())

            val addr = alloc<sockaddr_in>()
            addr.sin_family      = AF_INET.convert()
            addr.sin_port        = wsdHtons(WSD_PORT_US)
            addr.sin_addr.s_addr = INADDR_ANY
            bind(uFd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())

            val mreq = alloc<ip_mreq>()
            mreq.imr_multiaddr.s_addr = wsdInetAddr(WSD_MULTICAST_IP)
            mreq.imr_interface.s_addr = INADDR_ANY
            setsockopt(uFd, IPPROTO_IP, IP_ADD_MEMBERSHIP,
                mreq.ptr, sizeOf<ip_mreq>().convert())
        }

        val devicesMap = mutableMapOf<String, IoTDevice>()
        var probeCounter = 0

        fun sendProbe() {
            val msgId = "probe-${probeCounter++}"
            wsdUdpSend(uFd, wsdProbeIos(msgId), WSD_MULTICAST_IP, WSD_PORT_US)
            println("[WSDClient] Probe sent")
        }
        sendProbe()

        launch { while (isActive) { delay(5_000); sendProbe() } }

        launch {
            while (isActive) {
                val msg  = wsdUdpRecv(uFd, 2_000) ?: continue
                val info = parseWSDMessageIos(msg) ?: continue
                if (devicesMap.containsKey(info.id)) continue
                devicesMap[info.id] = IoTDevice(
                    id             = info.id,
                    name           = info.name,
                    address        = "${info.ip}:${info.port}",
                    connectionType = ConnectionType.WS_DISCOVERY
                )
                trySend(devicesMap.values.toList())
                println("[WSDClient] Discovered: ${info.name} @ ${info.ip}:${info.port}")
            }
        }

        awaitClose {
            memScoped {
                val mreq = alloc<ip_mreq>()
                mreq.imr_multiaddr.s_addr = wsdInetAddr(WSD_MULTICAST_IP)
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
        if (fd < 0) { println("[WSDClient] TCP socket failed: errno=$errno"); return }

        val connected = memScoped {
            val addr = alloc<sockaddr_in>()
            addr.sin_family      = AF_INET.convert()
            addr.sin_port        = wsdHtons(port.toUShort())
            addr.sin_addr.s_addr = wsdInetAddr(ip)
            platform.posix.connect(fd, addr.ptr.reinterpret(),
                sizeOf<sockaddr_in>().convert()) == 0
        }

        if (!connected) {
            println("[WSDClient] TCP connect failed: errno=$errno")
            close(fd)
            return
        }

        tcpFd = fd
        println("[WSDClient] TCP connected to $ip:$port")

        scope.launch {
            while (isActive) {
                val data = wsdTcpRecv(fd) ?: break
                _incoming.emit(data)
            }
            println("[WSDClient] TCP disconnected")
        }
    }

    // ── Send / Receive / Disconnect ───────────────────────────────────────────

    fun sendToServer(data: ByteArray, writeType: WriteType) {
        val fd = tcpFd
        if (fd < 0) { println("[WSDClient] Not connected"); return }
        wsdTcpSend(fd, data)
    }

    fun receiveFromServer(): Flow<ByteArray> = _incoming.asSharedFlow()

    fun disconnect() {
        val fd = tcpFd; tcpFd = -1
        if (fd >= 0) close(fd)
        println("[WSDClient] Disconnected")
    }
}
