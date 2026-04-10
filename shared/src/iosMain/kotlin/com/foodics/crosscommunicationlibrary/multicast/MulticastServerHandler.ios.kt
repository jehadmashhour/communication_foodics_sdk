@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.foodics.crosscommunicationlibrary.multicast

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import platform.posix.*
import kotlin.concurrent.Volatile

actual class MulticastServerHandler {

    companion object {
        private const val BEACON_INTERVAL_MS = 2_000L
    }

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _fromClient = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    @Volatile private var fd = -1

    suspend fun start(deviceName: String, identifier: String) {
        stop()
        delay(500)

        val sFd = mcOpenSocket(recvTimeoutSec = 1L)
        check(sFd >= 0) { "[MulticastServer] Failed to open socket: errno=$errno" }
        fd = sFd
        println("[MulticastServer] Started: $deviceName @ $MULTICAST_GROUP:$MULTICAST_PORT")

        // Periodic BEACON broadcasts
        scope.launch {
            val beacon = buildBeacon(identifier, deviceName)
            while (isActive) {
                mcSendTo(sFd, beacon)
                delay(BEACON_INTERVAL_MS)
            }
        }

        // Receive loop
        scope.launch {
            while (isActive && fd == sFd) {
                val (data, senderIp) = mcRecvFrom(sFd) ?: continue
                val (type, content) = parsePacket(data) ?: continue
                if (type == PKT_DATA && content.isNotEmpty()) {
                    _fromClient.emit(content)
                    println("[MulticastServer] Received ${content.size} bytes from $senderIp")
                }
            }
        }
    }

    fun sendToClient(data: ByteArray) {
        val sFd = fd
        if (sFd < 0) { println("[MulticastServer] Not running"); return }
        mcSendTo(sFd, buildData(data))
    }

    fun receiveFromClient(): Flow<ByteArray> = _fromClient.asSharedFlow()

    suspend fun stop() {
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sFd = fd; fd = -1
        if (sFd >= 0) close(sFd)
        println("[MulticastServer] Stopped")
    }
}
