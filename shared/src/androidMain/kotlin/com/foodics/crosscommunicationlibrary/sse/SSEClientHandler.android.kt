package com.foodics.crosscommunicationlibrary.sse

import android.util.Log
import client.WriteType
import ConnectionType
import com.appstractive.dnssd.DiscoveryEvent
import com.appstractive.dnssd.discoverServices
import com.appstractive.dnssd.key
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import scanner.IoTDevice
import java.net.Socket

actual class SSEClientHandler {

    companion object {
        private const val TAG = "SSEClient"
    }

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    private var sseSocket: Socket? = null
    @Volatile private var serverHost: String? = null
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

    suspend fun connect(device: IoTDevice): Unit = withContext(Dispatchers.IO) {
        disconnect()
        val parts = device.address.split(":")
        val host = parts[0]
        val port = parts.getOrNull(1)?.toIntOrNull() ?: return@withContext

        serverHost = host
        serverPort = port

        val sock = runCatching { Socket(host, port) }.getOrElse {
            Log.e(TAG, "SSE connect failed: ${it.message}"); return@withContext
        }
        sseSocket = sock
        Log.i(TAG, "SSE connected to ${device.name} @ ${device.address}")

        // Send GET /events
        val req = "GET /events HTTP/1.1\r\nHost: $host:$port\r\nAccept: text/event-stream\r\nCache-Control: no-cache\r\nConnection: keep-alive\r\n\r\n"
        sock.getOutputStream().write(req.toByteArray())
        sock.getOutputStream().flush()

        scope.launch { sseReceiveLoop(sock) }
    }

    // ── Send ──────────────────────────────────────────────────────────────────

    suspend fun sendToServer(data: ByteArray, writeType: WriteType): Unit = withContext(Dispatchers.IO) {
        val host = serverHost ?: run { Log.w(TAG, "Not connected"); return@withContext }
        val port = serverPort.takeIf { it > 0 } ?: return@withContext

        val encoded = base64Encode(data)
        val body = encoded.toByteArray()
        val request = buildString {
            append("POST /message HTTP/1.1\r\n")
            append("Host: $host:$port\r\n")
            append("Content-Type: text/plain\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }

        runCatching {
            val sock = Socket(host, port)
            val out = sock.getOutputStream()
            out.write(request.toByteArray())
            out.write(body)
            out.flush()
            // Drain response
            val input = sock.getInputStream().buffered()
            while (input.readHttpLine()?.isNotEmpty() == true) Unit
            sock.close()
        }.onFailure { Log.e(TAG, "SSE POST error", it) }
    }

    // ── Receive ───────────────────────────────────────────────────────────────

    fun receiveFromServer(): Flow<ByteArray> = _incoming.asSharedFlow()

    // ── Disconnect ────────────────────────────────────────────────────────────

    fun disconnect() {
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        runCatching { sseSocket?.close() }
        sseSocket = null; serverHost = null; serverPort = -1
        Log.i(TAG, "SSE client disconnected")
    }

    // ── SSE receive loop ──────────────────────────────────────────────────────

    private suspend fun sseReceiveLoop(sock: Socket) = withContext(Dispatchers.IO) {
        try {
            val input = sock.getInputStream().buffered(8_192)

            // Consume HTTP response headers
            while (true) {
                val line = input.readHttpLine() ?: return@withContext
                if (line.isEmpty()) break
            }
            Log.d(TAG, "SSE stream headers consumed, reading events")

            // Parse SSE events
            while (scope.isActive && !sock.isClosed) {
                val line = input.readHttpLine() ?: break
                if (line.startsWith("data: ")) {
                    val encoded = line.removePrefix("data: ").trim()
                    val decoded = runCatching { base64Decode(encoded) }.getOrNull()
                    if (decoded != null && decoded.isNotEmpty()) {
                        _incoming.emit(decoded)
                    }
                }
                // skip comment lines (heartbeats start with ':') and blank lines
            }
        } catch (e: Exception) {
            Log.d(TAG, "SSE receive loop ended: ${e.message}")
        }
    }
}
