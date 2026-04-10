@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.foodics.crosscommunicationlibrary.tcp

import com.appstractive.dnssd.NetService
import com.appstractive.dnssd.createNetService
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import platform.posix.*
import kotlin.concurrent.Volatile

actual class TcpServerHandler {

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _fromClient = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    private var netService: NetService? = null

    @Volatile private var serverFd = -1
    @Volatile private var clientFd = -1

    suspend fun start(deviceName: String, identifier: String) {
        stop()
        delay(1_000)

        val sFd = socket(AF_INET, SOCK_STREAM, 0)
        check(sFd >= 0) { "[TcpServer] socket() failed: errno=$errno" }
        memScoped {
            val one = alloc<IntVar>().apply { value = 1 }
            setsockopt(sFd, SOL_SOCKET, SO_REUSEADDR, one.ptr, sizeOf<IntVar>().convert())
            val addr = alloc<sockaddr_in>()
            addr.sin_family = AF_INET.convert()
            addr.sin_port = tcpHtons(0u)
            addr.sin_addr.s_addr = INADDR_ANY
            bind(sFd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
        }
        listen(sFd, 5)
        serverFd = sFd
        val port = tcpGetBoundPort(sFd)

        val service = createNetService(
            type = TCP_SERVICE_TYPE,
            name = deviceName,
            port = port,
            txt = mapOf("id" to identifier)
        )
        repeat(3) {
            try { withContext(Dispatchers.Main) { service.register() }; return@repeat }
            catch (_: Exception) { delay(800) }
        }
        netService = service
        println("[TcpServer] Started: $deviceName @ port $port")

        scope.launch {
            while (isActive) {
                val cFd = tcpAcceptWithTimeout(serverFd) ?: continue
                if (clientFd >= 0) close(clientFd)
                clientFd = cFd
                launch { handleClient(cFd) }
            }
        }
    }

    private suspend fun handleClient(fd: Int) {
        println("[TcpServer] Client connected")
        try {
            while (scope.isActive && clientFd == fd) {
                val frame = tcpReadFrame(fd) ?: break
                _fromClient.emit(frame)
            }
        } finally {
            if (clientFd == fd) clientFd = -1
            close(fd)
            println("[TcpServer] Client disconnected")
        }
    }

    fun sendToClient(data: ByteArray) {
        val fd = clientFd
        if (fd < 0) { println("[TcpServer] No client connected"); return }
        tcpWriteFrame(fd, data)
    }

    fun receiveFromClient(): Flow<ByteArray> = _fromClient.asSharedFlow()

    suspend fun stop() {
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val cFd = clientFd; clientFd = -1; if (cFd >= 0) close(cFd)
        val sFd = serverFd; serverFd = -1; if (sFd >= 0) close(sFd)
        try { netService?.let { withContext(Dispatchers.Main) { it.unregister() } } } catch (_: Exception) {}
        netService = null
        println("[TcpServer] Stopped")
    }
}
