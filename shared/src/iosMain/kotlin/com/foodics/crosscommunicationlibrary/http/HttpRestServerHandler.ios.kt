@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.foodics.crosscommunicationlibrary.http

import com.appstractive.dnssd.NetService
import com.appstractive.dnssd.createNetService
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import platform.posix.*
import kotlin.concurrent.Volatile

actual class HttpRestServerHandler {

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var tcpServerFd = -1
    @Volatile private var pendingResponseData: ByteArray? = null
    private val _fromClient = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    private var netService: NetService? = null

    suspend fun start(deviceName: String, identifier: String) {
        stop()
        delay(1500) // give iOS time to release previous mDNS registration

        // ── TCP server socket on a random port ────────────────────────────────
        val tFd = socket(AF_INET, SOCK_STREAM, 0)
        check(tFd >= 0) { "[HttpRestServer] TCP socket failed: errno=$errno" }
        memScoped {
            val one = alloc<IntVar>().apply { value = 1 }
            setsockopt(tFd, SOL_SOCKET, SO_REUSEADDR, one.ptr, sizeOf<IntVar>().convert())
            val addr = alloc<sockaddr_in>()
            addr.sin_family = AF_INET.convert()
            addr.sin_port = httpHtons(0u)
            addr.sin_addr.s_addr = INADDR_ANY
            bind(tFd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
        }
        listen(tFd, 5)
        tcpServerFd = tFd
        val port = httpGetServerPort(tFd)

        // ── mDNS advertisement via dns-sd-kt ──────────────────────────────────
        val service = createNetService(
            type = HTTP_SERVICE_TYPE,
            name = deviceName,
            port = port,
            txt = mapOf("id" to identifier)
        )
        repeat(3) { attempt ->
            try { withContext(Dispatchers.Main) { service.register() }; return@repeat } catch (_: Exception) { delay(1000) }
        }
        netService = service
        println("[HttpRestServer] Started: $deviceName @ port $port")

        // ── Accept loop ───────────────────────────────────────────────────────
        scope.launch {
            while (isActive) {
                val cFd = httpAcceptWithTimeout(tFd, 2_000) ?: continue
                scope.launch { handleClient(cFd) }
            }
        }
    }

    private suspend fun handleClient(fd: Int) {
        try {
            val (_, body) = httpReadRequest(fd) ?: return
            if (body.isNotEmpty()) _fromClient.emit(body)
            val responseBody = pendingResponseData ?: ByteArray(0)
            pendingResponseData = null
            httpSendResponse(fd, responseBody)
        } finally {
            close(fd)
        }
    }

    fun sendToClient(data: ByteArray) { pendingResponseData = data }

    fun receiveFromClient(): Flow<ByteArray> = _fromClient.asSharedFlow()

    suspend fun stop() {
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val fd = tcpServerFd; tcpServerFd = -1
        if (fd >= 0) close(fd)
        try { netService?.let { withContext(Dispatchers.Main) { it.unregister() } } } catch (_: Exception) {}
        netService = null
        println("[HttpRestServer] Stopped")
    }
}
