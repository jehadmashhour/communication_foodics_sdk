package com.foodics.crosscommunicationlibrary.http

import android.util.Log
import com.appstractive.dnssd.NetService
import com.appstractive.dnssd.createNetService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference

actual class HttpRestServerHandler {

    companion object {
        private const val TAG = "HttpRestServer"
    }

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _fromClient = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    private val pendingResponse = AtomicReference<ByteArray?>(null)
    private var serverSocket: ServerSocket? = null
    private var netService: NetService? = null

    suspend fun start(deviceName: String, identifier: String) {
        stop()
        val srv = ServerSocket(0)
        serverSocket = srv
        val port = srv.localPort

        val service = createNetService(
            type = HTTP_SERVICE_TYPE,
            name = deviceName,
            port = port,
            txt = mapOf("id" to identifier)
        )
        service.register()
        netService = service
        Log.i(TAG, "HTTP REST server: $deviceName @ port $port")

        scope.launch {
            while (isActive) {
                val client = runCatching { srv.accept() }.getOrNull() ?: break
                scope.launch { handleClient(client) }
            }
        }
    }

    private suspend fun handleClient(socket: Socket) = withContext(Dispatchers.IO) {
        try {
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            val headers = readHttpHeaders(input)
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            val body = readBody(input, contentLength)
            if (body.isNotEmpty()) _fromClient.emit(body)
            val responseBody = pendingResponse.getAndSet(null) ?: ByteArray(0)
            output.write(httpOkResponse(responseBody))
            output.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Client handling error", e)
        } finally {
            runCatching { socket.close() }
        }
    }

    fun sendToClient(data: ByteArray) { pendingResponse.set(data) }

    fun receiveFromClient(): Flow<ByteArray> = _fromClient.asSharedFlow()

    suspend fun stop() {
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        runCatching { netService?.unregister() }
        runCatching { serverSocket?.close() }
        netService = null
        serverSocket = null
        Log.i(TAG, "HTTP REST server stopped")
    }
}
