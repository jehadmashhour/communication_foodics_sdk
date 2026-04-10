@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.foodics.crosscommunicationlibrary.amqp

import ConnectionType
import client.WriteType
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import platform.posix.*
import scanner.IoTDevice
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource

actual class AMQPClientHandler actual constructor(private val brokerUrl: String) {

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

    @Volatile private var fd = -1
    @Volatile private var serverId: String? = null

    private fun sendFrames(frames: Array<ByteArray>): Boolean =
        frames.all { amqpSendAll(fd, it) }

    // ── Scan ──────────────────────────────────────────────────────────────────

    fun scan(): Flow<List<IoTDevice>> = channelFlow {
        val cfg = parseAmqpUrlIos(brokerUrl)
        val scanFd = amqpHandshakeIos(cfg)
        if (scanFd < 0) { println("[AMQPClient] Scan connect failed"); return@channelFlow }

        try {
            // Declare discovery exchange
            amqpSendAll(scanFd, amqpExchangeDeclare(AMQP_DISCOVERY_EXCHANGE, "fanout"))
            amqpReadFrame(scanFd) ?: return@channelFlow // Exchange.DeclareOk

            // Exclusive auto-delete temp queue
            amqpSendAll(scanFd, amqpQueueDeclare("", exclusive = true, autoDelete = true))
            val declOkFrame = amqpReadFrame(scanFd) ?: return@channelFlow
            val tempQueue = amqpParseQDeclOk(declOkFrame.payload)

            // Bind and consume
            amqpSendAll(scanFd, amqpQueueBind(tempQueue, AMQP_DISCOVERY_EXCHANGE))
            amqpReadFrame(scanFd) ?: return@channelFlow // Queue.BindOk

            amqpSendAll(scanFd, amqpBasicConsume(tempQueue))
            amqpReadFrame(scanFd) ?: return@channelFlow // Basic.ConsumeOk

            val devices = mutableMapOf<String, Pair<IoTDevice, TimeSource.Monotonic.ValueTimeMark>>()
            amqpSetTimeout(scanFd, 2_000)

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

            while (isActive) {
                val frame = amqpReadFrame(scanFd) ?: continue // timeout
                if (frame.type != 1) continue
                val (cid, mid) = frame.payload.amqpCM()
                if (cid == 60 && mid == 60) { // Basic.Deliver
                    val hf = amqpReadFrame(scanFd) ?: break
                    if (hf.type != 2) continue
                    val bodySize = amqpBodySize(hf.payload)
                    val bf = amqpReadFrame(scanFd) ?: break
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
                close(scanFd)
            }
        } catch (e: Exception) {
            println("[AMQPClient] Scan error: ${e.message}")
            awaitClose { close(scanFd) }
        }
    }.distinctUntilChanged()

    // ── Connect ───────────────────────────────────────────────────────────────

    fun connect(device: IoTDevice) {
        disconnect()
        val parts    = device.address.split("?id=")
        val hostPort = parts[0].split(":")
        val host     = hostPort[0]
        val port     = hostPort.getOrNull(1)?.toIntOrNull() ?: 5672
        val id       = parts.getOrNull(1) ?: return

        val urlCfg = parseAmqpUrlIos(brokerUrl)
        val cfg = AmqpIosConfig(host, port, urlCfg.user, urlCfg.pass)
        val connFd = amqpHandshakeIos(cfg)
        if (connFd < 0) { println("[AMQPClient] Connect failed"); return }
        fd = connFd; serverId = id

        // Declare + subscribe to server's output queue
        amqpSendAll(fd, amqpQueueDeclare(amqpQOut(id)))
        amqpReadFrame(fd) // Queue.DeclareOk

        amqpSendAll(fd, amqpBasicConsume(amqpQOut(id)))
        amqpReadFrame(fd) // Basic.ConsumeOk

        println("[AMQPClient] Connected to $id @ ${device.address}")
        scope.launch { receiveLoop(connFd) }
    }

    // ── Send ──────────────────────────────────────────────────────────────────

    fun sendToServer(data: ByteArray, writeType: WriteType) {
        val id = serverId ?: run { println("[AMQPClient] Not connected"); return }
        if (fd < 0) return
        runCatching { sendFrames(amqpPublishFrames("", amqpQIn(id), data)) }
    }

    fun receiveFromServer(): Flow<ByteArray> = _incoming.asSharedFlow()

    // ── Disconnect ────────────────────────────────────────────────────────────

    fun disconnect() {
        scope.cancel(); scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val connFd = fd; fd = -1
        if (connFd >= 0) close(connFd)
        serverId = null
        println("[AMQPClient] Disconnected")
    }

    // ── Receive loop ──────────────────────────────────────────────────────────

    private suspend fun receiveLoop(connFd: Int) {
        amqpSetTimeout(connFd, 2_000)
        try {
            while (scope.isActive && fd == connFd) {
                val frame = amqpReadFrame(connFd) ?: continue
                if (frame.type == 8) continue
                if (frame.type != 1) continue
                val (cid, mid) = frame.payload.amqpCM()
                if (cid == 60 && mid == 60) {
                    val hf = amqpReadFrame(connFd) ?: continue
                    val size = amqpBodySize(hf.payload)
                    val body = ByteArray(size.toInt()); var read = 0
                    while (read < size) {
                        val bf = amqpReadFrame(connFd) ?: return
                        if (bf.type != 3) return
                        bf.payload.copyInto(body, read); read += bf.payload.size
                    }
                    _incoming.emit(body)
                }
            }
        } catch (e: Exception) {
            println("[AMQPClient] Receive loop ended: ${e.message}")
        }
    }
}
