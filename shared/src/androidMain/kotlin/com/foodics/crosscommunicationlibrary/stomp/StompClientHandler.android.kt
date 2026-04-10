package com.foodics.crosscommunicationlibrary.stomp

import android.util.Log
import client.WriteType
import ConnectionType
import com.appstractive.dnssd.DiscoveryEvent
import com.appstractive.dnssd.discoverServices
import com.appstractive.dnssd.key
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import scanner.IoTDevice
import java.io.OutputStream
import java.net.Socket

actual class StompClientHandler {

    companion object {
        private const val TAG = "StompClient"
        private const val SUB_ID = "sub-0"
    }

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    private var socket: Socket? = null
    @Volatile private var output: OutputStream? = null

    // ── Scan ──────────────────────────────────────────────────────────────────

    fun scan(): Flow<List<IoTDevice>> =
        discoverServices(STOMP_SERVICE_TYPE)
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
                            connectionType = ConnectionType.STOMP
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
        val port = parts.getOrNull(1)?.toIntOrNull() ?: 61613

        val sock = runCatching { Socket(host, port) }.getOrElse {
            Log.e(TAG, "TCP connect failed: ${it.message}"); return@withContext
        }
        socket = sock
        val input = sock.getInputStream().bufferedStomp()
        val out = sock.getOutputStream()
        output = out

        // ── STOMP handshake ───────────────────────────────────────────────────
        out.write(buildStompFrame("CONNECT", mapOf(
            "accept-version" to "1.2",
            "host" to host,
            "heart-beat" to "0,0"
        )))
        out.flush()

        val connectedFrame = runCatching { readStompFrame(input) }.getOrNull()
        if (connectedFrame?.command != "CONNECTED") {
            Log.e(TAG, "STOMP handshake failed: got ${connectedFrame?.command}")
            runCatching { sock.close() }; socket = null; output = null; return@withContext
        }

        // Subscribe so the server can push MESSAGE frames to us
        out.write(buildStompFrame("SUBSCRIBE", mapOf(
            "destination" to STOMP_DESTINATION,
            "id" to SUB_ID,
            "ack" to "auto"
        )))
        out.flush()
        Log.i(TAG, "STOMP connected to ${device.name} @ ${device.address}")

        scope.launch { receiveLoop(input) }
    }

    // ── Send ──────────────────────────────────────────────────────────────────

    suspend fun sendToServer(data: ByteArray, writeType: WriteType): Unit = withContext(Dispatchers.IO) {
        val out = output ?: run { Log.w(TAG, "Not connected"); return@withContext }
        val frame = buildStompFrame("SEND", mapOf("destination" to STOMP_DESTINATION), data)
        runCatching { out.write(frame); out.flush() }.onFailure { Log.e(TAG, "Send error", it) }
    }

    // ── Receive ───────────────────────────────────────────────────────────────

    fun receiveFromServer(): Flow<ByteArray> = _incoming.asSharedFlow()

    // ── Disconnect ────────────────────────────────────────────────────────────

    fun disconnect() {
        val out = output
        if (out != null) {
            runCatching { out.write(buildStompFrame("DISCONNECT", mapOf("receipt" to "r-bye"))); out.flush() }
        }
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        runCatching { socket?.close() }
        socket = null; output = null
        Log.i(TAG, "STOMP client disconnected")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private suspend fun receiveLoop(input: java.io.InputStream) {
        while (scope.isActive) {
            val frame = runCatching { readStompFrame(input) }.getOrNull() ?: break
            when (frame.command) {
                "MESSAGE" -> if (frame.body.isNotEmpty()) _incoming.emit(frame.body)
                "ERROR" -> { Log.e(TAG, "STOMP ERROR: ${frame.headers["message"]}"); break }
            }
        }
    }
}
