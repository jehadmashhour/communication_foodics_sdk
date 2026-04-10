package com.foodics.crosscommunicationlibrary.nats

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.OutputStream
import java.net.Socket

actual class NATSServerHandler actual constructor(private val brokerUrl: String) {

    companion object { private const val TAG = "NATSServer" }

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _fromClient = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

    private var socket: Socket? = null
    private var writer: NatsWriter? = null
    private var serverId: String? = null

    // ── Start ─────────────────────────────────────────────────────────────────

    suspend fun start(deviceName: String, identifier: String): Unit = withContext(Dispatchers.IO) {
        stop()
        try {
            val (host, port) = parseNatsUrl(brokerUrl)
            val sock = Socket(host, port)
            socket = sock
            val input = sock.getInputStream().buffered(65_536) as BufferedInputStream
            val w = NatsWriter(sock.getOutputStream()); writer = w
            serverId = identifier

            // Consume INFO, send CONNECT + SUB
            input.readNatsLine() // INFO line
            w.sendCmd(NATS_CONNECT_CMD)
            w.sendCmd(natsSub(natsSubjectIn(identifier), NATS_SID_DATA))

            Log.i(TAG, "NATS server started: $deviceName [$identifier] @ $brokerUrl")

            // Beacon coroutine
            val beaconJson = """{"id":"$identifier","name":"$deviceName"}"""
            scope.launch {
                while (isActive) {
                    runCatching { w.publish(NATS_DISCOVERY_SUBJECT, beaconJson.encodeToByteArray()) }
                    delay(NATS_BEACON_INTERVAL_MS)
                }
            }

            scope.launch(Dispatchers.IO) { receiveLoop(input, w) }
        } catch (e: Exception) {
            Log.e(TAG, "NATS server failed to start", e)
        }
    }

    // ── Receive loop ──────────────────────────────────────────────────────────

    private suspend fun receiveLoop(input: BufferedInputStream, w: NatsWriter) {
        try {
            while (scope.isActive) {
                val line = input.readNatsLine() ?: break
                when {
                    line.startsWith("MSG") -> {
                        val (_, bytes) = parseNatsMsgLine(line) ?: continue
                        val data = input.readExactNats(bytes) ?: break
                        input.readExactNats(2) // consume trailing \r\n
                        _fromClient.emit(data)
                    }
                    line == "PING" -> w.sendCmd("PONG\r\n")
                    line.startsWith("-ERR") -> { Log.w(TAG, "NATS error: $line"); break }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Receive loop ended: ${e.message}")
        }
    }

    // ── Send ──────────────────────────────────────────────────────────────────

    suspend fun sendToClient(data: ByteArray): Unit = withContext(Dispatchers.IO) {
        val id = serverId ?: run { Log.w(TAG, "Not started"); return@withContext }
        runCatching { writer?.publish(natsSubjectOut(id), data) }
            .onFailure { Log.e(TAG, "Send error", it) }
    }

    fun receiveFromClient(): Flow<ByteArray> = _fromClient.asSharedFlow()

    // ── Stop ──────────────────────────────────────────────────────────────────

    suspend fun stop(): Unit = withContext(Dispatchers.IO) {
        scope.cancel(); scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        runCatching { socket?.close() }
        socket = null; writer = null; serverId = null
        Log.i(TAG, "NATS server stopped")
    }
}

// ── Shared helpers ────────────────────────────────────────────────────────────

/** Thread-safe NATS writer. */
internal class NatsWriter(private val out: OutputStream) {
    @Synchronized fun publish(subject: String, data: ByteArray) {
        out.write(natsPubHeader(subject, data.size).toByteArray())
        out.write(data)
        out.write("\r\n".toByteArray())
        out.flush()
    }
    @Synchronized fun sendCmd(cmd: String) {
        out.write(cmd.toByteArray()); out.flush()
    }
}

/** Read a \r\n-terminated line from a raw InputStream. Returns null on EOF. */
internal fun BufferedInputStream.readNatsLine(): String? {
    val sb = StringBuilder(); var prev = -1
    while (true) {
        val b = read()
        if (b == -1) return if (sb.isEmpty()) null else sb.toString()
        if (b == '\n'.code) {
            if (prev == '\r'.code && sb.isNotEmpty()) sb.deleteCharAt(sb.length - 1)
            return sb.toString()
        }
        sb.append(b.toChar()); prev = b
    }
}

/** Read exactly [n] bytes. Returns null on EOF. */
internal fun BufferedInputStream.readExactNats(n: Int): ByteArray? {
    if (n == 0) return ByteArray(0)
    val buf = ByteArray(n); var r = 0
    while (r < n) {
        val c = read(buf, r, n - r); if (c <= 0) return null; r += c
    }
    return buf
}
