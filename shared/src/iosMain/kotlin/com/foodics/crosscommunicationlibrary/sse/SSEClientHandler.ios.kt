@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.foodics.crosscommunicationlibrary.sse

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

actual class SSEClientHandler {

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

    @Volatile private var sseFd = -1
    @Volatile private var serverIp: String? = null
    @Volatile private var serverPort: Int = -1

    // ── Scan ──────────────────────────────────────────────────────────────────

    fun scan(): Flow<List<IoTDevice>> =
        discoverServices(SSE_SERVICE_TYPE)
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
                            connectionType = ConnectionType.SSE
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
        val ip = parts[0]
        val port = parts.getOrNull(1)?.toIntOrNull() ?: return

        serverIp = ip
        serverPort = port

        val fd = sseConnect(ip, port)
        if (fd < 0) { println("[SSEClient] Connect failed: errno=$errno"); return }
        sseFd = fd
        println("[SSEClient] Connected to ${device.name} @ ${device.address}")

        // Send GET /events request
        val req = "GET /events HTTP/1.1\r\nHost: $ip:$port\r\nAccept: text/event-stream\r\nCache-Control: no-cache\r\nConnection: keep-alive\r\n\r\n"
        sseSendString(fd, req)

        scope.launch { sseReceiveLoop(fd) }
    }

    // ── Send ──────────────────────────────────────────────────────────────────

    fun sendToServer(data: ByteArray, writeType: WriteType) {
        val ip = serverIp ?: run { println("[SSEClient] Not connected"); return }
        val port = serverPort.takeIf { it > 0 } ?: return

        val encoded = base64Encode(data)
        val body = encoded.encodeToByteArray()
        val request = buildString {
            append("POST /message HTTP/1.1\r\n")
            append("Host: $ip:$port\r\n")
            append("Content-Type: text/plain\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }

        val postFd = sseConnect(ip, port)
        if (postFd < 0) { println("[SSEClient] POST connect failed: errno=$errno"); return }
        try {
            sseSendString(postFd, request)
            sseSendAll(postFd, body)
            // Drain response headers
            while (true) {
                val line = sseReadLine(postFd) ?: break
                if (line.isEmpty()) break
            }
        } finally {
            close(postFd)
        }
    }

    // ── Receive ───────────────────────────────────────────────────────────────

    fun receiveFromServer(): Flow<ByteArray> = _incoming.asSharedFlow()

    // ── Disconnect ────────────────────────────────────────────────────────────

    fun disconnect() {
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val fd = sseFd; sseFd = -1
        if (fd >= 0) close(fd)
        serverIp = null; serverPort = -1
        println("[SSEClient] Disconnected")
    }

    // ── SSE receive loop ──────────────────────────────────────────────────────

    private suspend fun sseReceiveLoop(fd: Int) {
        try {
            // Consume HTTP response headers
            while (true) {
                val line = sseReadLine(fd) ?: return
                if (line.isEmpty()) break
            }
            println("[SSEClient] SSE stream headers consumed, reading events")

            // Parse SSE event lines
            while (scope.isActive && sseFd == fd) {
                val line = sseReadLine(fd) ?: break
                if (line.startsWith("data: ")) {
                    val encoded = line.removePrefix("data: ").trim()
                    val decoded = runCatching { base64Decode(encoded) }.getOrNull()
                    if (decoded != null && decoded.isNotEmpty()) {
                        _incoming.emit(decoded)
                    }
                }
                // skip comment lines (heartbeats) and blank lines
            }
        } catch (e: Exception) {
            println("[SSEClient] SSE receive loop ended: ${e.message}")
        }
    }
}
