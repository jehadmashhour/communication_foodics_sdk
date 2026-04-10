package com.foodics.crosscommunicationlibrary.tcp

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

actual class TcpServerHandler {

    companion object {
        private const val TAG = "TcpServer"
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
            type = TCP_SERVICE_TYPE,
            name = deviceName,
            port = port,
            txt = mapOf("id" to identifier)
        )
        service.register()
        netService = service
        Log.i(TAG, "TCP server: $deviceName @ port $port")

        scope.launch {
            while (isActive) {
                val client = runCatching { srv.accept() }.getOrNull() ?: break
                scope.launch { handleClient(client) }
            }
        }
    }

    private suspend fun handleClient(socket: Socket) = withContext(Dispatchers.IO) {
        Log.i(TAG, "Client connected: ${socket.inetAddress}")
        try {
            val input = socket.getInputStream().buffered(65_536)
            val output = socket.getOutputStream()
            clientOutput = output

            while (isActive && !socket.isClosed) {
                val frame = runCatching { input.readTcpFrame() }.getOrNull() ?: break
                _fromClient.emit(frame)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Client session ended: ${e.message}")
        } finally {
            if (clientOutput == socket.getOutputStream()) clientOutput = null
            runCatching { socket.close() }
            Log.i(TAG, "Client disconnected")
        }
    }

    suspend fun sendToClient(data: ByteArray): Unit = withContext(Dispatchers.IO) {
        val out = clientOutput ?: run { Log.w(TAG, "No client connected"); return@withContext }
        runCatching { out.writeTcpFrame(data) }.onFailure { Log.e(TAG, "Send error", it) }
    }

    fun receiveFromClient(): Flow<ByteArray> = _fromClient.asSharedFlow()

    suspend fun stop(): Unit = withContext(Dispatchers.IO) {
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        runCatching { netService?.unregister() }
        runCatching { serverSocket?.close() }
        netService = null; serverSocket = null; clientOutput = null
        Log.i(TAG, "TCP server stopped")
    }
}
