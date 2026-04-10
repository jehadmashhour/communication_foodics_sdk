package com.foodics.crosscommunicationlibrary.sse

import android.util.Log
import com.appstractive.dnssd.NetService
import com.appstractive.dnssd.createNetService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.OutputStream
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket

actual class SSEServerHandler {

    companion object {
        private const val TAG = "SSEServer"
        private const val HEARTBEAT_INTERVAL_MS = 5_000L
    }

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _fromClient = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    private var serverSocket: ServerSocket? = null
    private var netService: NetService? = null
    @Volatile private var sseOutput: PrintWriter? = null

    suspend fun start(deviceName: String, identifier: String): Unit = withContext(Dispatchers.IO) {
        stop()
        val srv = ServerSocket(0)
        serverSocket = srv
        val port = srv.localPort

        val service = createNetService(
            type = SSE_SERVICE_TYPE,
            name = deviceName,
            port = port,
            txt = mapOf("id" to identifier)
        )
        service.register()
        netService = service
        Log.i(TAG, "SSE server: $deviceName @ port $port")

        scope.launch {
            while (isActive) {
                val client = runCatching { srv.accept() }.getOrNull() ?: break
                scope.launch { handleConnection(client) }
            }
        }
    }

    private suspend fun handleConnection(socket: Socket) = withContext(Dispatchers.IO) {
        Log.i(TAG, "SSE: connection from ${socket.inetAddress}")
        try {
            val input = socket.getInputStream().buffered(8_192)
            val output = socket.getOutputStream()

            // Read request line
            val requestLine = input.readHttpLine() ?: return@withContext
            Log.d(TAG, "SSE request: $requestLine")

            // Read remaining headers until blank line
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = input.readHttpLine() ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] =
                    line.substring(colon + 1).trim()
            }

            when {
                requestLine.startsWith("GET") -> handleSseStream(socket, output)
                requestLine.startsWith("POST") -> handlePost(input, output, headers)
                else -> {
                    output.write("HTTP/1.1 405 Method Not Allowed\r\nContent-Length: 0\r\n\r\n".toByteArray())
                    output.flush()
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Connection ended: ${e.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    private suspend fun handleSseStream(socket: Socket, output: OutputStream) {
        val writer = PrintWriter(output, false)
        // SSE response headers
        writer.print("HTTP/1.1 200 OK\r\n")
        writer.print("Content-Type: text/event-stream\r\n")
        writer.print("Cache-Control: no-cache\r\n")
        writer.print("Connection: keep-alive\r\n")
        writer.print("\r\n")
        writer.flush()

        sseOutput = writer
        Log.i(TAG, "SSE stream opened")

        try {
            while (scope.isActive && !socket.isClosed) {
                delay(HEARTBEAT_INTERVAL_MS)
                writer.print(": heartbeat\n\n")
                writer.flush()
            }
        } catch (e: Exception) {
            Log.d(TAG, "SSE stream closed: ${e.message}")
        } finally {
            if (sseOutput == writer) sseOutput = null
            Log.i(TAG, "SSE stream ended")
        }
    }

    private suspend fun handlePost(
        input: java.io.InputStream,
        output: OutputStream,
        headers: Map<String, String>
    ) = withContext(Dispatchers.IO) {
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (contentLength > 0) {
            val buf = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = input.read(buf, read, contentLength - read)
                if (n <= 0) break
                read += n
            }
            buf.decodeToString(0, read)
        } else ""

        val decoded = runCatching { base64Decode(body) }.getOrNull()
        if (decoded != null && decoded.isNotEmpty()) {
            _fromClient.emit(decoded)
            Log.d(TAG, "SSE: received ${decoded.size} bytes from POST")
        }

        val response = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"
        output.write(response.toByteArray())
        output.flush()
    }

    suspend fun sendToClient(data: ByteArray): Unit = withContext(Dispatchers.IO) {
        val writer = sseOutput ?: run { Log.w(TAG, "No SSE client connected"); return@withContext }
        val encoded = base64Encode(data)
        runCatching {
            writer.print("data: $encoded\n\n")
            writer.flush()
        }.onFailure { Log.e(TAG, "SSE send error", it) }
    }

    fun receiveFromClient(): Flow<ByteArray> = _fromClient.asSharedFlow()

    suspend fun stop(): Unit = withContext(Dispatchers.IO) {
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        sseOutput = null
        runCatching { netService?.unregister() }
        runCatching { serverSocket?.close() }
        netService = null; serverSocket = null
        Log.i(TAG, "SSE server stopped")
    }
}
