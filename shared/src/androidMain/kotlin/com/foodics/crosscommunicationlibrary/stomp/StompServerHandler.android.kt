package com.foodics.crosscommunicationlibrary.stomp

import android.util.Log
import com.appstractive.dnssd.NetService
import com.appstractive.dnssd.createNetService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket

actual class StompServerHandler {

    companion object {
        private const val TAG = "StompServer"
    }

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _fromClient = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    private var serverSocket: ServerSocket? = null
    private var netService: NetService? = null
    @Volatile private var clientOutput: OutputStream? = null

    suspend fun start(deviceName: String, identifier: String): Unit = withContext(Dispatchers.IO) {
        stop()
        val srv = ServerSocket(0)
        serverSocket = srv
        val port = srv.localPort

        val service = createNetService(
            type = STOMP_SERVICE_TYPE,
            name = deviceName,
            port = port,
            txt = mapOf("id" to identifier)
        )
        service.register()
        netService = service
        Log.i(TAG, "STOMP server: $deviceName @ port $port")

        scope.launch {
            while (isActive) {
                val client = runCatching { srv.accept() }.getOrNull() ?: break
                scope.launch { handleClient(client) }
            }
        }
    }

    private suspend fun handleClient(socket: Socket) = withContext(Dispatchers.IO) {
        try {
            val input = socket.getInputStream().bufferedStomp()
            val output = socket.getOutputStream()

            // ── STOMP handshake ───────────────────────────────────────────────
            val connectFrame = readStompFrame(input) ?: return@withContext
            if (connectFrame.command != "CONNECT" && connectFrame.command != "STOMP") {
                Log.w(TAG, "Expected CONNECT, got ${connectFrame.command}"); return@withContext
            }
            output.write(buildStompFrame("CONNECTED", mapOf("version" to "1.2", "heart-beat" to "0,0")))
            output.flush()
            clientOutput = output
            Log.i(TAG, "STOMP client connected from ${socket.inetAddress}")

            // ── Frame dispatch loop ───────────────────────────────────────────
            while (isActive && !socket.isClosed) {
                val frame = runCatching { readStompFrame(input) }.getOrNull() ?: break
                when (frame.command) {
                    "SEND" -> {
                        if (frame.body.isNotEmpty()) _fromClient.emit(frame.body)
                        // honour receipt header
                        frame.headers["receipt"]?.let { receipt ->
                            output.write(buildStompFrame("RECEIPT", mapOf("receipt-id" to receipt)))
                            output.flush()
                        }
                    }
                    "SUBSCRIBE" -> { /* accepted — no further action needed */ }
                    "DISCONNECT" -> {
                        frame.headers["receipt"]?.let { receipt ->
                            output.write(buildStompFrame("RECEIPT", mapOf("receipt-id" to receipt)))
                            output.flush()
                        }
                        break
                    }
                    else -> Log.d(TAG, "Unhandled frame: ${frame.command}")
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Client session ended: ${e.message}")
        } finally {
            if (clientOutput == socket.getOutputStream()) clientOutput = null
            runCatching { socket.close() }
            Log.i(TAG, "STOMP client disconnected")
        }
    }

    suspend fun sendToClient(data: ByteArray): Unit = withContext(Dispatchers.IO) {
        val out = clientOutput ?: run { Log.w(TAG, "No client connected"); return@withContext }
        val frame = buildStompFrame(
            command = "MESSAGE",
            headers = mapOf(
                "destination" to STOMP_DESTINATION,
                "message-id" to System.currentTimeMillis().toString(),
                "subscription" to "sub-0"
            ),
            body = data
        )
        runCatching { out.write(frame); out.flush() }.onFailure { Log.e(TAG, "sendToClient error", it) }
    }

    fun receiveFromClient(): Flow<ByteArray> = _fromClient.asSharedFlow()

    suspend fun stop(): Unit = withContext(Dispatchers.IO) {
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        runCatching { netService?.unregister() }
        runCatching { serverSocket?.close() }
        netService = null; serverSocket = null; clientOutput = null
        Log.i(TAG, "STOMP server stopped")
    }
}
