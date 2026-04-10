package com.foodics.crosscommunicationlibrary.websocket

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

actual class WebSocketServerHandler {

    companion object {
        private const val TAG = "WebSocketServer"
    }

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _fromClient = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

    private var serverSocket: ServerSocket? = null
    private var clientOutput: OutputStream? = null
    private var clientSocket: Socket? = null
    private var netService: NetService? = null

    suspend fun start(deviceName: String, identifier: String) {
        stop()
        val srv = ServerSocket(0)
        serverSocket = srv
        val port = srv.localPort

        val service = createNetService(
            type = WS_SERVICE_TYPE,
            name = deviceName,
            port = port,
            txt = mapOf("id" to identifier)
        )
        service.register()
        netService = service
        Log.i(TAG, "WebSocket server: $deviceName @ port $port")

        scope.launch {
            while (isActive) {
                val client = runCatching { srv.accept() }.getOrNull() ?: break
                // Accept one client at a time — close any previous connection
                clientSocket?.close()
                clientSocket = client
                scope.launch { handleClient(client) }
            }
        }
    }

    private suspend fun handleClient(socket: Socket) = withContext(Dispatchers.IO) {
        try {
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            if (!performServerHandshake(input, output)) {
                Log.w(TAG, "WS handshake failed — not a WebSocket upgrade request")
                socket.close()
                return@withContext
            }
            clientOutput = output
            Log.i(TAG, "WebSocket client connected: ${socket.inetAddress.hostAddress}")

            while (isActive && !socket.isClosed) {
                val frame = runCatching { readWsFrame(input) }.getOrNull() ?: break
                _fromClient.emit(frame)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Client error", e)
        } finally {
            if (clientSocket == socket) { clientOutput = null; clientSocket = null }
            runCatching { socket.close() }
            Log.i(TAG, "WebSocket client disconnected")
        }
    }

    suspend fun sendToClient(data: ByteArray) = withContext(Dispatchers.IO) {
        val out = clientOutput ?: run { Log.w(TAG, "No client connected"); return@withContext }
        runCatching { writeWsFrame(out, data, mask = false) }
            .onFailure { Log.e(TAG, "Send error", it) }
    }

    fun receiveFromClient(): Flow<ByteArray> = _fromClient.asSharedFlow()

    suspend fun stop() {
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        runCatching { clientSocket?.getOutputStream()?.let { sendWsClose(it) } }
        runCatching { clientSocket?.close() }
        runCatching { serverSocket?.close() }
        runCatching { netService?.unregister() }
        clientSocket = null; clientOutput = null; serverSocket = null; netService = null
        Log.i(TAG, "WebSocket server stopped")
    }
}
