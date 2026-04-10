@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.foodics.crosscommunicationlibrary.nats

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import platform.posix.*
import kotlin.concurrent.Volatile

actual class NATSServerHandler actual constructor(private val brokerUrl: String) {

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _fromClient = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

    @Volatile private var fd = -1
    @Volatile private var serverId: String? = null

    // ── Start ─────────────────────────────────────────────────────────────────

    suspend fun start(deviceName: String, identifier: String) {
        stop(); delay(500)
        val (host, port) = parseNatsUrl(brokerUrl)
        val connFd = natsTcpConnect(host, port)
        if (connFd < 0) { println("[NATSServer] Connect failed"); return }
        fd = connFd; serverId = identifier

        natsReadLine(fd)                                     // INFO
        natsSendCmd(fd, NATS_CONNECT_CMD)
        natsSendCmd(fd, natsSub(natsSubjectIn(identifier), NATS_SID_DATA))

        println("[NATSServer] Started: $deviceName [$identifier] @ $brokerUrl")

        // Beacon coroutine
        val beaconJson = """{"id":"$identifier","name":"$deviceName"}"""
        scope.launch {
            while (isActive) {
                runCatching { natsPublish(fd, NATS_DISCOVERY_SUBJECT, beaconJson.encodeToByteArray()) }
                delay(NATS_BEACON_INTERVAL_MS)
            }
        }

        scope.launch { receiveLoop(connFd) }
    }

    // ── Receive loop ──────────────────────────────────────────────────────────

    private suspend fun receiveLoop(connFd: Int) {
        natsSetTimeout(connFd, 2_000) // allows periodic scope.isActive check
        try {
            while (scope.isActive && fd == connFd) {
                val line = natsReadLine(connFd) ?: continue // null on timeout → retry
                when {
                    line.startsWith("MSG") -> {
                        val (_, bytes) = parseNatsMsgLine(line) ?: continue
                        val data = natsRecvExact(connFd, bytes) ?: break
                        natsRecvExact(connFd, 2) // \r\n
                        _fromClient.emit(data)
                    }
                    line == "PING" -> natsSendCmd(connFd, "PONG\r\n")
                    line.startsWith("-ERR") -> { println("[NATSServer] Error: $line"); break }
                }
            }
        } catch (e: Exception) {
            println("[NATSServer] Receive loop ended: ${e.message}")
        }
    }

    // ── Send ──────────────────────────────────────────────────────────────────

    fun sendToClient(data: ByteArray) {
        val id = serverId ?: run { println("[NATSServer] Not started"); return }
        if (fd < 0) return
        runCatching { natsPublish(fd, natsSubjectOut(id), data) }
    }

    fun receiveFromClient(): Flow<ByteArray> = _fromClient.asSharedFlow()

    // ── Stop ──────────────────────────────────────────────────────────────────

    suspend fun stop() {
        scope.cancel(); scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val connFd = fd; fd = -1
        if (connFd >= 0) close(connFd)
        serverId = null
        println("[NATSServer] Stopped")
    }
}
