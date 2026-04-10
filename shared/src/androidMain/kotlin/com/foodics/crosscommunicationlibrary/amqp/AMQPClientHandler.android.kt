package com.foodics.crosscommunicationlibrary.amqp

import android.util.Log
import client.WriteType
import ConnectionType
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import scanner.IoTDevice
import java.io.BufferedInputStream
import java.net.Socket
import kotlin.time.TimeSource

actual class AMQPClientHandler actual constructor(private val brokerUrl: String) {

    companion object {
        private const val TAG = "AMQPClient"
    }

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

    private var socket: Socket? = null
    private var writer: AmqpWriter? = null
    private var serverId: String? = null

    // ── Scan ──────────────────────────────────────────────────────────────────

    fun scan(): Flow<List<IoTDevice>> = channelFlow {
        var scanSock: Socket? = null
        try {
            val cfg = parseAmqpUrl(brokerUrl)
            val (sock, input, output) = withContext(Dispatchers.IO) { amqpHandshake(cfg) }
            scanSock = sock
            val w = AmqpWriter(output)

            // Declare discovery exchange
            w.send(amqpExchangeDeclare(AMQP_DISCOVERY_EXCHANGE, "fanout"))
            input.readAmqpFrame() // Exchange.DeclareOk

            // Declare exclusive auto-delete temp queue
            w.send(amqpQueueDeclare("", exclusive = true, autoDelete = true))
            val declOkFrame = input.readAmqpFrame()
            val tempQueue = amqpParseQDeclOk(declOkFrame.payload)

            // Bind temp queue to discovery exchange
            w.send(amqpQueueBind(tempQueue, AMQP_DISCOVERY_EXCHANGE))
            input.readAmqpFrame() // Queue.BindOk

            // Start consuming
            w.send(amqpBasicConsume(tempQueue))
            input.readAmqpFrame() // Basic.ConsumeOk

            val devices = mutableMapOf<String, Pair<IoTDevice, TimeSource.Monotonic.ValueTimeMark>>()

            // Evict stale devices
            val evictJob = launch {
                while (isActive) {
                    delay(2_000)
                    val before = devices.size
                    val iter = devices.iterator()
                    while (iter.hasNext()) {
                        if (iter.next().value.second.elapsedNow().inWholeMilliseconds > AMQP_DEVICE_TTL_MS)
                            iter.remove()
                    }
                    if (devices.size != before) trySend(devices.values.map { it.first })
                }
            }

            // Receive beacons (with 2 s SO_TIMEOUT so we can evict stale devices)
            sock.soTimeout = 2_000
            while (isActive) {
                val frame = try {
                    withContext(Dispatchers.IO) { input.readAmqpFrame() }
                } catch (_: java.net.SocketTimeoutException) { continue }
                  catch (_: Exception) { break }

                if (frame.type != 1) continue
                val (cid, mid) = frame.payload.amqpCM()
                if (cid == 60 && mid == 60) { // Basic.Deliver
                    val hf = try { withContext(Dispatchers.IO) { input.readAmqpFrame() } }
                             catch (_: Exception) { break }
                    val bodySize = amqpBodySize(hf.payload)
                    val bf = try { withContext(Dispatchers.IO) { input.readAmqpFrame() } }
                             catch (_: Exception) { break }
                    if (bf.type != 3) continue
                    val json = bf.payload.decodeToString(0, minOf(bf.payload.size, bodySize.toInt()))
                    val (id, name) = amqpParseBeacon(json) ?: continue
                    val device = IoTDevice(
                        id = id, name = name,
                        address = "${cfg.host}:${cfg.port}?id=$id",
                        connectionType = ConnectionType.AMQP
                    )
                    devices[id] = device to TimeSource.Monotonic.markNow()
                    trySend(devices.values.map { it.first })
                }
            }

            awaitClose {
                evictJob.cancel()
                runCatching { scanSock?.close() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Scan error", e)
            awaitClose { runCatching { scanSock?.close() } }
        }
    }.distinctUntilChanged()

    // ── Connect ───────────────────────────────────────────────────────────────

    suspend fun connect(device: IoTDevice): Unit = withContext(Dispatchers.IO) {
        disconnect()
        try {
            // Address format: "host:port?id=<identifier>"
            val parts    = device.address.split("?id=")
            val hostPort = parts[0].split(":")
            val host     = hostPort[0]
            val port     = hostPort.getOrNull(1)?.toIntOrNull() ?: 5672
            val id       = parts.getOrNull(1) ?: return@withContext
            serverId = id

            val cfg = AmqpConfig(host, port,
                parseAmqpUrl(brokerUrl).user, parseAmqpUrl(brokerUrl).pass)
            val (sock, input, output) = amqpHandshake(cfg)
            socket = sock
            val w = AmqpWriter(output); writer = w

            // Subscribe to server's output queue
            w.send(amqpQueueDeclare(amqpQOut(id)))
            input.readAmqpFrame() // Queue.DeclareOk

            w.send(amqpBasicConsume(amqpQOut(id)))
            input.readAmqpFrame() // Basic.ConsumeOk

            scope.launch(Dispatchers.IO) { receiveLoop(input) }
            Log.i(TAG, "AMQP client connected to $id @ ${device.address}")
        } catch (e: Exception) {
            Log.e(TAG, "Connect error", e)
        }
    }

    // ── Send ──────────────────────────────────────────────────────────────────

    suspend fun sendToServer(data: ByteArray, writeType: WriteType): Unit = withContext(Dispatchers.IO) {
        val id = serverId ?: run { Log.w(TAG, "Not connected"); return@withContext }
        val w  = writer  ?: run { Log.w(TAG, "Not connected"); return@withContext }
        runCatching { w.send(*amqpPublishFrames("", amqpQIn(id), data)) }
            .onFailure { Log.e(TAG, "Send error", it) }
    }

    fun receiveFromServer(): Flow<ByteArray> = _incoming.asSharedFlow()

    // ── Disconnect ────────────────────────────────────────────────────────────

    fun disconnect() {
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        runCatching { socket?.close() }
        socket = null; writer = null; serverId = null
        Log.i(TAG, "AMQP client disconnected")
    }

    // ── Receive loop ──────────────────────────────────────────────────────────

    private suspend fun receiveLoop(input: BufferedInputStream) {
        try {
            while (scope.isActive) {
                val frame = try { input.readAmqpFrame() } catch (_: Exception) { break }
                if (frame.type == 8) continue
                if (frame.type != 1) continue
                val (cid, mid) = frame.payload.amqpCM()
                if (cid == 60 && mid == 60) {
                    val hf   = try { input.readAmqpFrame() } catch (_: Exception) { break }
                    val size = amqpBodySize(hf.payload)
                    val body = ByteArray(size.toInt())
                    var read = 0
                    while (read < size) {
                        val bf = try { input.readAmqpFrame() } catch (_: Exception) { return }
                        if (bf.type != 3) return
                        bf.payload.copyInto(body, read); read += bf.payload.size
                    }
                    _incoming.emit(body)
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Receive loop ended: ${e.message}")
        }
    }
}
