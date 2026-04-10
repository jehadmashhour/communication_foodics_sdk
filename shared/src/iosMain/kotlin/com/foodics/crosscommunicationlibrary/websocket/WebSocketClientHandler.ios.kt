@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.foodics.crosscommunicationlibrary.websocket

import ConnectionType
import client.WriteType
import com.appstractive.dnssd.DiscoveryEvent
import com.appstractive.dnssd.discoverServices
import com.appstractive.dnssd.key
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import platform.posix.*
import scanner.IoTDevice
import kotlin.concurrent.Volatile

actual class WebSocketClientHandler {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    @Volatile private var tcpFd = -1
    @Volatile private var serverIp: String? = null
    @Volatile private var serverPort: Int = 0

    // ── Scan ──────────────────────────────────────────────────────────────────

    fun scan(): Flow<List<IoTDevice>> =
        discoverServices(WS_SERVICE_TYPE)
            .scan(emptyMap<String, IoTDevice>()) { acc, event ->
                val devices = acc.toMutableMap()
                when (event) {
                    is DiscoveryEvent.Discovered -> event.resolve()
                    is DiscoveryEvent.Resolved -> {
                        val s = event.service
                        val id = s.txt["id"]?.decodeToString()?.trim()?.ifBlank { null } ?: s.name
                        devices[s.key] = IoTDevice(
                            id = id, name = s.name,
                            address = "${s.host}:${s.port}",
                            connectionType = ConnectionType.WEBSOCKET
                        )
                    }
                    is DiscoveryEvent.Removed -> devices.remove(event.service.key)
                }
                devices
            }
            .map { it.values.toList() }
            .distinctUntilChanged()

    // ── Connect ───────────────────────────────────────────────────────────────

    fun connect(device: IoTDevice) {
        disconnect()
        val parts = device.address.split(":")
        val ip = parts[0]; val port = parts.getOrNull(1)?.toIntOrNull() ?: 80

        val fd = socket(AF_INET, SOCK_STREAM, 0)
        if (fd < 0) { println("[WSClient] Socket failed: errno=$errno"); return }

        val connected = memScoped {
            val addr = alloc<sockaddr_in>()
            addr.sin_family = AF_INET.convert()
            addr.sin_port = wsHtons(port.toUShort())
            addr.sin_addr.s_addr = wsInetAddr(ip)
            platform.posix.connect(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) == 0
        }
        if (!connected) { println("[WSClient] Connect failed: errno=$errno"); close(fd); return }

        performWsClientHandshake(fd, ip, port)
        tcpFd = fd; serverIp = ip; serverPort = port
        println("[WSClient] Connected to ${device.name} @ $ip:$port")

        scope.launch {
            while (isActive) {
                val frame = wsReadFrame(fd) ?: break
                _incoming.emit(frame)
            }
            println("[WSClient] Disconnected from $ip:$port")
        }
    }

    // ── Send / Receive / Disconnect ───────────────────────────────────────────

    fun sendToServer(data: ByteArray, writeType: WriteType) {
        val fd = tcpFd
        if (fd < 0) { println("[WSClient] Not connected"); return }
        wsWriteFrame(fd, data, mask = true)
    }

    fun receiveFromServer(): Flow<ByteArray> = _incoming.asSharedFlow()

    fun disconnect() {
        val fd = tcpFd; tcpFd = -1
        serverIp = null; serverPort = 0
        if (fd >= 0) { wsSendClose(fd); close(fd) }
        println("[WSClient] Disconnected")
    }
}
