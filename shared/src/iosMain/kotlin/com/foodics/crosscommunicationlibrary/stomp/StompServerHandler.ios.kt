@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.foodics.crosscommunicationlibrary.stomp

import com.appstractive.dnssd.NetService
import com.appstractive.dnssd.createNetService
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import platform.posix.*
import kotlin.concurrent.Volatile

actual class StompServerHandler {

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _fromClient = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    private var netService: NetService? = null

    @Volatile private var serverFd = -1
    @Volatile private var clientFd = -1

    suspend fun start(deviceName: String, identifier: String) {
        stop()
        delay(1_000)

        val tFd = socket(AF_INET, SOCK_STREAM, 0)
        check(tFd >= 0) { "[StompServer] socket() failed: errno=$errno" }
        memScoped {
            val one = alloc<IntVar>().apply { value = 1 }
            setsockopt(tFd, SOL_SOCKET, SO_REUSEADDR, one.ptr, sizeOf<IntVar>().convert())
            val addr = alloc<sockaddr_in>()
            addr.sin_family = AF_INET.convert()
            addr.sin_port = stompHtons(0u)
            addr.sin_addr.s_addr = INADDR_ANY
            bind(tFd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
        }
        listen(tFd, 5)
        serverFd = tFd
        val port = stompGetBoundPort(tFd)

        val service = createNetService(
            type = STOMP_SERVICE_TYPE,
            name = deviceName,
            port = port,
            txt = mapOf("id" to identifier)
        )
        repeat(3) { try { withContext(Dispatchers.Main) { service.register() }; return@repeat } catch (_: Exception) { delay(800) } }
        netService = service
        println("[StompServer] Started: $deviceName @ port $port")

        scope.launch {
            while (isActive) {
                val cFd = stompAcceptWithTimeout(serverFd) ?: continue
                if (clientFd >= 0) close(clientFd)
                clientFd = cFd
                // 5 s receive timeout so the frame-read loop stays interruptible
                memScoped {
                    val tv = alloc<timeval>()
                    tv.tv_sec = 5; tv.tv_usec = 0
                    setsockopt(cFd, SOL_SOCKET, SO_RCVTIMEO, tv.ptr, sizeOf<timeval>().convert())
                }
                launch { handleClient(cFd) }
            }
        }
    }

    private suspend fun handleClient(fd: Int) {
        try {
            // ── STOMP handshake ───────────────────────────────────────────────
            val connectFrame = stompReadFrame(fd) ?: return
            if (connectFrame.command != "CONNECT" && connectFrame.command != "STOMP") return
            stompSendFrame(fd, buildStompFrame("CONNECTED", mapOf("version" to "1.2", "heart-beat" to "0,0")))
            println("[StompServer] Client CONNECTED")

            // ── Frame dispatch loop ───────────────────────────────────────────
            while (scope.isActive) {
                val frame = stompReadFrame(fd) ?: break
                when (frame.command) {
                    "SEND" -> {
                        if (frame.body.isNotEmpty()) _fromClient.emit(frame.body)
                        frame.headers["receipt"]?.let { receipt ->
                            stompSendFrame(fd, buildStompFrame("RECEIPT", mapOf("receipt-id" to receipt)))
                        }
                    }
                    "SUBSCRIBE" -> { /* acknowledged */ }
                    "DISCONNECT" -> {
                        frame.headers["receipt"]?.let { receipt ->
                            stompSendFrame(fd, buildStompFrame("RECEIPT", mapOf("receipt-id" to receipt)))
                        }
                        break
                    }
                }
            }
        } finally {
            if (clientFd == fd) clientFd = -1
            close(fd)
            println("[StompServer] Client session ended")
        }
    }

    fun sendToClient(data: ByteArray) {
        val fd = clientFd
        if (fd < 0) { println("[StompServer] No client connected"); return }
        val frame = buildStompFrame(
            command = "MESSAGE",
            headers = mapOf(
                "destination" to STOMP_DESTINATION,
                "message-id" to platform.posix.time(null).toString(),
                "subscription" to "sub-0"
            ),
            body = data
        )
        stompSendFrame(fd, frame)
    }

    fun receiveFromClient(): Flow<ByteArray> = _fromClient.asSharedFlow()

    suspend fun stop() {
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val cFd = clientFd; clientFd = -1; if (cFd >= 0) close(cFd)
        val sFd = serverFd; serverFd = -1; if (sFd >= 0) close(sFd)
        try { netService?.let { withContext(Dispatchers.Main) { it.unregister() } } } catch (_: Exception) {}
        netService = null
        println("[StompServer] Stopped")
    }
}
