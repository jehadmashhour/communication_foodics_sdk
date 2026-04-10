@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.foodics.crosscommunicationlibrary.sse

import com.appstractive.dnssd.NetService
import com.appstractive.dnssd.createNetService
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import platform.posix.*
import kotlin.concurrent.Volatile

actual class SSEServerHandler {

    companion object {
        private const val HEARTBEAT_INTERVAL_MS = 5_000L
    }

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _fromClient = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    private var netService: NetService? = null

    @Volatile private var serverFd = -1
    @Volatile private var sseFd = -1

    suspend fun start(deviceName: String, identifier: String) {
        stop()
        delay(1_000)

        val sFd = socket(AF_INET, SOCK_STREAM, 0)
        check(sFd >= 0) { "[SSEServer] socket() failed: errno=$errno" }
        memScoped {
            val one = alloc<IntVar>().apply { value = 1 }
            setsockopt(sFd, SOL_SOCKET, SO_REUSEADDR, one.ptr, sizeOf<IntVar>().convert())
            val addr = alloc<sockaddr_in>()
            addr.sin_family = AF_INET.convert()
            addr.sin_port = sseHtons(0u)
            addr.sin_addr.s_addr = INADDR_ANY
            bind(sFd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
        }
        listen(sFd, 5)
        serverFd = sFd
        val port = sseGetBoundPort(sFd)

        val service = createNetService(
            type = SSE_SERVICE_TYPE,
            name = deviceName,
            port = port,
            txt = mapOf("id" to identifier)
        )
        repeat(3) {
            try { withContext(Dispatchers.Main) { service.register() }; return@repeat }
            catch (_: Exception) { delay(800) }
        }
        netService = service
        println("[SSEServer] Started: $deviceName @ port $port")

        scope.launch {
            while (isActive) {
                val cFd = sseAcceptWithTimeout(serverFd) ?: continue
                launch { handleConnection(cFd) }
            }
        }
    }

    private suspend fun handleConnection(fd: Int) {
        println("[SSEServer] Client connection fd=$fd")
        try {
            // Read request line
            val requestLine = sseReadLine(fd) ?: return
            println("[SSEServer] Request: $requestLine")

            // Read headers until blank line
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = sseReadLine(fd) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] =
                    line.substring(colon + 1).trim()
            }

            when {
                requestLine.startsWith("GET") -> handleSseStream(fd)
                requestLine.startsWith("POST") -> handlePost(fd, headers)
                else -> {
                    sseSendString(fd, "HTTP/1.1 405 Method Not Allowed\r\nContent-Length: 0\r\n\r\n")
                    close(fd)
                }
            }
        } catch (e: Exception) {
            println("[SSEServer] Connection error: ${e.message}")
            close(fd)
        }
    }

    private suspend fun handleSseStream(fd: Int) {
        sseSendString(fd, "HTTP/1.1 200 OK\r\n")
        sseSendString(fd, "Content-Type: text/event-stream\r\n")
        sseSendString(fd, "Cache-Control: no-cache\r\n")
        sseSendString(fd, "Connection: keep-alive\r\n")
        sseSendString(fd, "\r\n")

        if (sseFd >= 0) close(sseFd)
        sseFd = fd
        println("[SSEServer] SSE stream opened fd=$fd")

        try {
            while (scope.isActive && sseFd == fd) {
                delay(HEARTBEAT_INTERVAL_MS)
                sseSendString(fd, ": heartbeat\n\n")
            }
        } catch (e: Exception) {
            println("[SSEServer] SSE stream ended: ${e.message}")
        } finally {
            if (sseFd == fd) sseFd = -1
            close(fd)
            println("[SSEServer] SSE stream closed")
        }
    }

    private suspend fun handlePost(fd: Int, headers: Map<String, String>) {
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (contentLength > 0) {
            sseRecvExact(fd, contentLength)?.decodeToString() ?: ""
        } else ""

        val decoded = runCatching { base64Decode(body) }.getOrNull()
        if (decoded != null && decoded.isNotEmpty()) {
            _fromClient.emit(decoded)
            println("[SSEServer] Received ${decoded.size} bytes from POST")
        }

        sseSendString(fd, "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n")
        close(fd)
    }

    fun sendToClient(data: ByteArray) {
        val fd = sseFd
        if (fd < 0) { println("[SSEServer] No SSE client connected"); return }
        val encoded = base64Encode(data)
        sseSendString(fd, "data: $encoded\n\n")
    }

    fun receiveFromClient(): Flow<ByteArray> = _fromClient.asSharedFlow()

    suspend fun stop() {
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val cFd = sseFd; sseFd = -1; if (cFd >= 0) close(cFd)
        val sFd = serverFd; serverFd = -1; if (sFd >= 0) close(sFd)
        try { netService?.let { withContext(Dispatchers.Main) { it.unregister() } } } catch (_: Exception) {}
        netService = null
        println("[SSEServer] Stopped")
    }
}
