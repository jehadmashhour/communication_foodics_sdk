@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.foodics.crosscommunicationlibrary.coap

import com.appstractive.dnssd.NetService
import com.appstractive.dnssd.createNetService
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import platform.posix.*
import kotlin.concurrent.Volatile

actual class CoAPServerHandler {

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _fromClient = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    private var netService: NetService? = null

    @Volatile private var serverFd = -1
    @Volatile private var clientIp: String? = null
    @Volatile private var clientPort: Int = 0

    suspend fun start(deviceName: String, identifier: String) {
        stop()
        delay(1_000) // let OS release previous socket/mDNS registration

        val fd = coapOpenUdpSocket(recvTimeoutSec = 1L)
        check(fd >= 0) { "[CoAPServer] Failed to create UDP socket: errno=$errno" }
        serverFd = fd
        val port = coapGetBoundPort(fd)

        val service = createNetService(
            type = COAP_SERVICE_TYPE,
            name = deviceName,
            port = port,
            txt = mapOf("id" to identifier)
        )
        repeat(3) { attempt ->
            try { withContext(Dispatchers.Main) { service.register() }; return@repeat }
            catch (_: Exception) { delay(800L) }
        }
        netService = service
        println("[CoAPServer] Started: $deviceName @ UDP port $port")

        scope.launch {
            while (isActive) {
                val (data, ip, port2) = coapRecvFrom(serverFd) ?: continue
                if (!isValidCoap(data)) continue
                clientIp = ip
                clientPort = port2
                val payload = coapParsePayload(data)
                if (payload.isNotEmpty()) {
                    _fromClient.emit(payload)
                    println("[CoAPServer] Received ${payload.size} bytes from $ip:$port2")
                }
            }
        }
    }

    fun sendToClient(data: ByteArray) {
        val ip = clientIp ?: run { println("[CoAPServer] No client known"); return }
        val fd = serverFd
        if (fd < 0) return
        val frame = coapBuildContent(data)
        coapSendTo(fd, frame, ip, clientPort)
    }

    fun receiveFromClient(): Flow<ByteArray> = _fromClient.asSharedFlow()

    suspend fun stop() {
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val fd = serverFd; serverFd = -1
        if (fd >= 0) close(fd)
        try { netService?.let { withContext(Dispatchers.Main) { it.unregister() } } } catch (_: Exception) {}
        netService = null; clientIp = null; clientPort = 0
        println("[CoAPServer] Stopped")
    }
}
