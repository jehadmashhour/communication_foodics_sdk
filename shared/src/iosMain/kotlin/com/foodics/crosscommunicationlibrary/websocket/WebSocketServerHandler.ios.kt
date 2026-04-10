@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.foodics.crosscommunicationlibrary.websocket

import com.appstractive.dnssd.NetService
import com.appstractive.dnssd.createNetService
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import platform.posix.*
import kotlin.concurrent.Volatile

actual class WebSocketServerHandler {

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var tcpServerFd = -1
    @Volatile private var tcpClientFd = -1
    private val _fromClient = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    private var netService: NetService? = null

    suspend fun start(deviceName: String, identifier: String) {
        stop()
        delay(1500)

        // ── TCP server socket on random port ──────────────────────────────────
        val tFd = socket(AF_INET, SOCK_STREAM, 0)
        check(tFd >= 0) { "[WSServer] TCP socket failed: errno=$errno" }
        memScoped {
            val one = alloc<IntVar>().apply { value = 1 }
            setsockopt(tFd, SOL_SOCKET, SO_REUSEADDR, one.ptr, sizeOf<IntVar>().convert())
            val addr = alloc<sockaddr_in>()
            addr.sin_family = AF_INET.convert(); addr.sin_port = wsHtons(0u); addr.sin_addr.s_addr = INADDR_ANY
            bind(tFd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
        }
        listen(tFd, 5)
        tcpServerFd = tFd
        val port = wsGetServerPort(tFd)

        // ── mDNS advertisement ────────────────────────────────────────────────
        val service = createNetService(
            type = WS_SERVICE_TYPE, name = deviceName, port = port, txt = mapOf("id" to identifier)
        )
        repeat(3) { try { withContext(Dispatchers.Main) { service.register() }; return@repeat } catch (_: Exception) { delay(1000) } }
        netService = service
        println("[WSServer] Started: $deviceName @ port $port")

        // ── Accept loop ───────────────────────────────────────────────────────
        scope.launch {
            while (isActive) {
                val cFd = wsAcceptWithTimeout(tFd, 2_000) ?: continue
                val prev = tcpClientFd
                if (prev >= 0) { wsSendClose(prev); close(prev) }
                tcpClientFd = cFd
                scope.launch { handleClient(cFd) }
            }
        }
    }

    private suspend fun handleClient(fd: Int) {
        if (!performWsServerHandshake(fd)) {
            println("[WSServer] Handshake failed")
            close(fd)
            if (tcpClientFd == fd) tcpClientFd = -1
            return
        }
        println("[WSServer] Client connected")
        while (true) {
            val frame = wsReadFrame(fd) ?: break
            _fromClient.emit(frame)
        }
        println("[WSServer] Client disconnected")
        if (tcpClientFd == fd) tcpClientFd = -1
        close(fd)
    }

    fun sendToClient(data: ByteArray) {
        val fd = tcpClientFd
        if (fd < 0) { println("[WSServer] No client connected"); return }
        wsWriteFrame(fd, data, mask = false)
    }

    fun receiveFromClient(): Flow<ByteArray> = _fromClient.asSharedFlow()

    suspend fun stop() {
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val cFd = tcpClientFd; tcpClientFd = -1
        val sFd = tcpServerFd; tcpServerFd = -1
        if (cFd >= 0) { wsSendClose(cFd); close(cFd) }
        if (sFd >= 0) close(sFd)
        try { netService?.let { withContext(Dispatchers.Main) { it.unregister() } } } catch (_: Exception) {}
        netService = null
        println("[WSServer] Stopped")
    }
}
