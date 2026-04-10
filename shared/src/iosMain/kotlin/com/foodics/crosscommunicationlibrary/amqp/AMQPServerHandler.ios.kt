@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.foodics.crosscommunicationlibrary.amqp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import platform.posix.*
import kotlin.concurrent.Volatile

actual class AMQPServerHandler actual constructor(private val brokerUrl: String) {

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _fromClient = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

    @Volatile private var fd = -1
    @Volatile private var serverId: String? = null

    // ── Synchronised write ────────────────────────────────────────────────────

    private fun sendFrames(frames: Array<ByteArray>): Boolean =
        frames.all { amqpSendAll(fd, it) }

    // ── Start ─────────────────────────────────────────────────────────────────

    suspend fun start(deviceName: String, identifier: String) {
        stop(); delay(500)
        val cfg = parseAmqpUrlIos(brokerUrl)
        val connFd = amqpHandshakeIos(cfg)
        if (connFd < 0) { println("[AMQPServer] Handshake failed"); return }
        fd = connFd; serverId = identifier

        // Declare exchange
        amqpSendAll(fd, amqpExchangeDeclare(AMQP_DISCOVERY_EXCHANGE, "fanout"))
        amqpReadFrame(fd) ?: return // Exchange.DeclareOk

        // Declare queues
        amqpSendAll(fd, amqpQueueDeclare(amqpQIn(identifier)))
        amqpReadFrame(fd) ?: return // Queue.DeclareOk

        amqpSendAll(fd, amqpQueueDeclare(amqpQOut(identifier)))
        amqpReadFrame(fd) ?: return // Queue.DeclareOk

        // Subscribe to input queue
        amqpSendAll(fd, amqpBasicConsume(amqpQIn(identifier)))
        amqpReadFrame(fd) ?: return // Basic.ConsumeOk

        println("[AMQPServer] Started: $deviceName [$identifier]")

        // Beacon coroutine
        val beaconBytes = """{"id":"$identifier","name":"$deviceName"}""".encodeToByteArray()
        scope.launch {
            while (isActive) {
                runCatching { sendFrames(amqpPublishFrames(AMQP_DISCOVERY_EXCHANGE, "", beaconBytes)) }
                delay(AMQP_BEACON_INTERVAL_MS)
            }
        }

        // Receive loop
        scope.launch { receiveLoop(connFd) }
    }

    // ── Receive loop ──────────────────────────────────────────────────────────

    private suspend fun receiveLoop(connFd: Int) {
        amqpSetTimeout(connFd, 2_000) // 2 s timeout so we can check scope.isActive
        try {
            while (scope.isActive && fd == connFd) {
                val frame = amqpReadFrame(connFd) ?: continue // timeout returns null
                if (frame.type == 8) continue
                if (frame.type != 1) continue
                val (cid, mid) = frame.payload.amqpCM()
                if (cid == 60 && mid == 60) { // Basic.Deliver
                    val body = readMessage(connFd) ?: continue
                    _fromClient.emit(body)
                }
            }
        } catch (e: Exception) {
            println("[AMQPServer] Receive loop ended: ${e.message}")
        }
    }

    private fun readMessage(connFd: Int): ByteArray? {
        val hf = amqpReadFrame(connFd) ?: return null
        if (hf.type != 2) return null
        val bodySize = amqpBodySize(hf.payload)
        if (bodySize <= 0L || bodySize > 16 * 1024 * 1024L) return null
        val body = ByteArray(bodySize.toInt()); var read = 0
        while (read < bodySize) {
            val bf = amqpReadFrame(connFd) ?: return null
            if (bf.type != 3) return null
            bf.payload.copyInto(body, read); read += bf.payload.size
        }
        return body
    }

    // ── Send ──────────────────────────────────────────────────────────────────

    fun sendToClient(data: ByteArray) {
        val id = serverId ?: run { println("[AMQPServer] Not started"); return }
        if (fd < 0) return
        runCatching { sendFrames(amqpPublishFrames("", amqpQOut(id), data)) }
    }

    fun receiveFromClient(): Flow<ByteArray> = _fromClient.asSharedFlow()

    // ── Stop ──────────────────────────────────────────────────────────────────

    suspend fun stop() {
        scope.cancel(); scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val connFd = fd; fd = -1
        if (connFd >= 0) close(connFd)
        serverId = null
        println("[AMQPServer] Stopped")
    }
}
