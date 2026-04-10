@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.foodics.crosscommunicationlibrary.coap

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

actual class CoAPClientHandler {

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

    @Volatile private var clientFd = -1
    @Volatile private var serverIp: String? = null
    @Volatile private var serverPort: Int = 0

    // ── Scan ──────────────────────────────────────────────────────────────────

    fun scan(): Flow<List<IoTDevice>> =
        discoverServices(COAP_SERVICE_TYPE)
            .scan(emptyMap<String, IoTDevice>()) { acc, event ->
                val devices = acc.toMutableMap()
                when (event) {
                    is DiscoveryEvent.Discovered -> event.resolve()
                    is DiscoveryEvent.Resolved -> {
                        val s = event.service
                        val id = s.txt["id"]?.decodeToString()?.trim()?.ifBlank { null } ?: s.name
                        devices[s.key] = IoTDevice(
                            id = id,
                            name = s.name,
                            address = "${s.host}:${s.port}",
                            connectionType = ConnectionType.COAP
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
        serverIp = parts[0]
        serverPort = parts.getOrNull(1)?.toIntOrNull() ?: 5683

        val fd = coapOpenUdpSocket(recvTimeoutSec = 1L)
        if (fd < 0) { println("[CoAPClient] Failed to create socket: errno=$errno"); return }
        clientFd = fd

        scope.launch { receiveLoop() }
        println("[CoAPClient] Connected to ${device.name} @ ${device.address}")
    }

    // ── Send ──────────────────────────────────────────────────────────────────

    fun sendToServer(data: ByteArray, writeType: WriteType) {
        val ip = serverIp ?: run { println("[CoAPClient] Not connected"); return }
        val fd = clientFd
        if (fd < 0) return
        val frame = coapBuildPost(data)
        coapSendTo(fd, frame, ip, serverPort)
    }

    // ── Receive ───────────────────────────────────────────────────────────────

    fun receiveFromServer(): Flow<ByteArray> = _incoming.asSharedFlow()

    // ── Disconnect ────────────────────────────────────────────────────────────

    fun disconnect() {
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val fd = clientFd; clientFd = -1
        if (fd >= 0) close(fd)
        serverIp = null; serverPort = 0
        println("[CoAPClient] Disconnected")
    }

    // ── Receive loop ──────────────────────────────────────────────────────────

    private suspend fun receiveLoop() {
        while (scope.isActive) {
            val fd = clientFd
            if (fd < 0) break
            val (data, _, _) = coapRecvFrom(fd) ?: continue
            if (!isValidCoap(data)) continue
            val payload = coapParsePayload(data)
            if (payload.isNotEmpty()) {
                _incoming.emit(payload)
                println("[CoAPClient] Received ${payload.size} bytes from server")
            }
        }
    }
}
