package com.foodics.crosscommunicationlibrary.amqp

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.Socket

actual class AMQPServerHandler actual constructor(private val brokerUrl: String) {

    companion object {
        private const val TAG = "AMQPServer"
    }

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _fromClient = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

    private var socket: Socket? = null
    private var writer: AmqpWriter? = null
    private var serverId: String? = null

    // ── Start ─────────────────────────────────────────────────────────────────

    suspend fun start(deviceName: String, identifier: String): Unit = withContext(Dispatchers.IO) {
        stop()
        try {
            val cfg = parseAmqpUrl(brokerUrl)
            val (sock, input, output) = amqpHandshake(cfg)
            socket = sock
            val w = AmqpWriter(output); writer = w
            serverId = identifier

            // Declare exchange + queues (synchronous setup before background loops start)
            w.send(amqpExchangeDeclare(AMQP_DISCOVERY_EXCHANGE, "fanout"))
            input.readAmqpFrame() // Exchange.DeclareOk

            w.send(amqpQueueDeclare(amqpQIn(identifier)))
            input.readAmqpFrame() // Queue.DeclareOk

            w.send(amqpQueueDeclare(amqpQOut(identifier)))
            input.readAmqpFrame() // Queue.DeclareOk

            // Subscribe to the "in" queue (server receives data here)
            w.send(amqpBasicConsume(amqpQIn(identifier)))
            input.readAmqpFrame() // Basic.ConsumeOk

            // Beacon coroutine — publishes to discovery exchange every 2 s
            val beaconBytes = """{"id":"$identifier","name":"$deviceName"}""".encodeToByteArray()
            scope.launch {
                while (isActive) {
                    runCatching { w.send(*amqpPublishFrames(AMQP_DISCOVERY_EXCHANGE, "", beaconBytes)) }
                    delay(AMQP_BEACON_INTERVAL_MS)
                }
            }

            // Receive loop (runs until socket closed or scope cancelled)
            scope.launch(Dispatchers.IO) { receiveLoop(input) }

            Log.i(TAG, "AMQP server started: $deviceName [$identifier] @ $brokerUrl")
        } catch (e: Exception) {
            Log.e(TAG, "AMQP server failed to start", e)
        }
    }

    // ── Receive loop ──────────────────────────────────────────────────────────

    private suspend fun receiveLoop(input: BufferedInputStream) {
        try {
            while (scope.isActive) {
                val frame = try { input.readAmqpFrame() } catch (_: Exception) { break }
                if (frame.type == 8) continue // heartbeat
                if (frame.type != 1) continue // skip non-method
                val (cid, mid) = frame.payload.amqpCM()
                if (cid == 60 && mid == 60) { // Basic.Deliver
                    val body = readMessage(input) ?: break
                    _fromClient.emit(body)
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Receive loop ended: ${e.message}")
        }
    }

    private fun readMessage(input: BufferedInputStream): ByteArray? {
        val headerFrame = try { input.readAmqpFrame() } catch (_: Exception) { return null }
        if (headerFrame.type != 2) return null
        val bodySize = amqpBodySize(headerFrame.payload)
        if (bodySize <= 0L || bodySize > 16 * 1024 * 1024L) return null

        val body = ByteArray(bodySize.toInt())
        var read = 0
        while (read < bodySize) {
            val bodyFrame = try { input.readAmqpFrame() } catch (_: Exception) { return null }
            if (bodyFrame.type != 3) return null
            bodyFrame.payload.copyInto(body, read)
            read += bodyFrame.payload.size
        }
        return body
    }

    // ── Send ──────────────────────────────────────────────────────────────────

    suspend fun sendToClient(data: ByteArray): Unit = withContext(Dispatchers.IO) {
        val id = serverId ?: run { Log.w(TAG, "Not started"); return@withContext }
        val w  = writer  ?: run { Log.w(TAG, "Not connected"); return@withContext }
        runCatching { w.send(*amqpPublishFrames("", amqpQOut(id), data)) }
            .onFailure { Log.e(TAG, "Send error", it) }
    }

    fun receiveFromClient(): Flow<ByteArray> = _fromClient.asSharedFlow()

    // ── Stop ──────────────────────────────────────────────────────────────────

    suspend fun stop(): Unit = withContext(Dispatchers.IO) {
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        runCatching { socket?.close() }
        socket = null; writer = null; serverId = null
        Log.i(TAG, "AMQP server stopped")
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

/** Broker URL parser: "amqp://user:pass@host:port[/vhost]" */
internal fun parseAmqpUrl(url: String): AmqpConfig {
    val s    = url.removePrefix("amqps://").removePrefix("amqp://")
    val at   = s.lastIndexOf('@')
    val cred = if (at >= 0) s.substring(0, at) else "guest:guest"
    val rest = if (at >= 0) s.substring(at + 1) else s
    val hp   = rest.substringBefore('/')
    val ci   = hp.lastIndexOf(':')
    val host = if (ci >= 0) hp.substring(0, ci) else hp
    val port = if (ci >= 0) hp.substring(ci + 1).toIntOrNull() ?: 5672 else 5672
    val ci2  = cred.indexOf(':')
    val user = if (ci2 >= 0) cred.substring(0, ci2) else cred
    val pass = if (ci2 >= 0) cred.substring(ci2 + 1) else ""
    return AmqpConfig(host, port, user, pass)
}

internal data class AmqpConfig(val host: String, val port: Int, val user: String, val pass: String)

/** Performs AMQP handshake and opens channel 1. Returns (socket, input, output). */
internal fun amqpHandshake(cfg: AmqpConfig): Triple<Socket, BufferedInputStream, OutputStream> {
    val sock   = Socket(cfg.host, cfg.port)
    val input  = sock.getInputStream().buffered(65_536) as BufferedInputStream
    val output = sock.getOutputStream()

    output.write(AMQP_PROTOCOL_HEADER); output.flush()

    // Connection.Start → StartOk
    var f = input.readAmqpFrame()
    require(f.type == 1 && f.payload.amqpCM() == 10 to 10) { "Expected Connection.Start" }
    output.write(amqpStartOk(cfg.user, cfg.pass)); output.flush()

    // Connection.Tune → TuneOk + Open
    f = input.readAmqpFrame()
    require(f.type == 1 && f.payload.amqpCM() == 10 to 30) { "Expected Connection.Tune" }
    val (chMax, fMax) = amqpParseTune(f.payload)
    output.write(amqpTuneOk(chMax, fMax))
    output.write(amqpConnectionOpen()); output.flush()

    // Connection.OpenOk
    f = input.readAmqpFrame()
    require(f.type == 1 && f.payload.amqpCM() == 10 to 41) { "Expected Connection.OpenOk" }

    // Channel.Open → OpenOk
    output.write(amqpChannelOpen()); output.flush()
    f = input.readAmqpFrame()
    require(f.type == 1 && f.payload.amqpCM() == 20 to 11) { "Expected Channel.OpenOk" }

    return Triple(sock, input, output)
}

/** Thread-safe writer for a shared OutputStream. */
internal class AmqpWriter(private val output: OutputStream) {
    @Synchronized fun send(vararg frames: ByteArray) {
        frames.forEach { output.write(it) }
        output.flush()
    }
}

// ── InputStream frame reader ──────────────────────────────────────────────────

private fun java.io.InputStream.readExact(n: Int): ByteArray {
    val buf = ByteArray(n); var r = 0
    while (r < n) {
        val c = read(buf, r, n - r)
        if (c <= 0) throw EOFException(); r += c
    }
    return buf
}

internal fun BufferedInputStream.readAmqpFrame(): AmqpFrame {
    val h = readExact(7)
    val type = h[0].toInt() and 0xFF
    val ch   = ((h[1].toInt() and 0xFF) shl 8) or (h[2].toInt() and 0xFF)
    val size = ((h[3].toInt() and 0xFF) shl 24) or ((h[4].toInt() and 0xFF) shl 16) or
               ((h[5].toInt() and 0xFF) shl  8) or  (h[6].toInt() and 0xFF)
    val payload = readExact(size)
    val end = read(); if (end != 0xCE) throw IOException("Bad frame-end $end")
    return AmqpFrame(type, ch, payload)
}
