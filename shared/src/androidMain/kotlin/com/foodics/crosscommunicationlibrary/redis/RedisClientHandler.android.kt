package com.foodics.crosscommunicationlibrary.redis

import client.WriteType
import ConnectionType
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import scanner.IoTDevice
import java.io.BufferedInputStream
import java.net.Socket
import java.util.UUID
import kotlin.time.TimeSource

actual class RedisClientHandler actual constructor(private val brokerUrl: String) {

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

    // Stable per-instance inbox channel for receiving server replies
    private val clientId  = UUID.randomUUID().toString().replace("-", "")
    private val myChannel = "foodics:redis:$clientId:in"

    @Volatile private var serverSubject: String? = null
    private var subSocket: Socket? = null
    private var pubWriter: RedisWriter? = null

    // ── Scan ──────────────────────────────────────────────────────────────────

    actual fun scan(): Flow<List<IoTDevice>> = channelFlow {
        val (host, port) = parseRedisUrl(brokerUrl)
        val scanSock = withContext(Dispatchers.IO) { Socket(host, port) }
        try {
            scanSock.soTimeout = 2_000
            val input = scanSock.getInputStream().buffered(65_536) as BufferedInputStream
            scanSock.getOutputStream().apply { write(buildRespCmd("SUBSCRIBE", REDIS_DISCOVERY_CHANNEL)); flush() }

            val devices = mutableMapOf<String, Pair<IoTDevice, TimeSource.Monotonic.ValueTimeMark>>()
            val evictJob = launch {
                while (isActive) {
                    delay(2_000)
                    val before = devices.size
                    devices.entries.removeIf {
                        it.value.second.elapsedNow().inWholeMilliseconds > REDIS_DEVICE_TTL_MS
                    }
                    if (devices.size != before) trySend(devices.values.map { it.first })
                }
            }

            while (isActive) {
                val msg = try {
                    withContext(Dispatchers.IO) { input.redisReadPubSub() } ?: continue
                } catch (_: java.net.SocketTimeoutException) { continue }
                  catch (_: Exception) { break }

                val (channel, data) = msg
                if (channel != REDIS_DISCOVERY_CHANNEL) continue
                val parts = data.decodeToString().split("|")
                if (parts.size < 2) continue
                val name = parts[0]; val id = parts[1]
                val device = IoTDevice(
                    id = id, name = name,
                    address = "$host:$port?id=$id",
                    connectionType = ConnectionType.REDIS
                )
                devices[id] = device to TimeSource.Monotonic.markNow()
                trySend(devices.values.map { it.first })
            }

            awaitClose { evictJob.cancel(); runCatching { scanSock.close() } }
        } catch (e: Exception) {
            println("[RedisClient] Scan error: ${e.message}")
            awaitClose { runCatching { scanSock.close() } }
        }
    }.distinctUntilChanged()

    // ── Connect ───────────────────────────────────────────────────────────────

    actual fun connect(device: IoTDevice) {
        disconnect()
        val parts    = device.address.split("?id=")
        val hostPort = parts[0].split(":")
        val host     = hostPort[0]
        val port     = hostPort.getOrNull(1)?.toIntOrNull() ?: 6379
        val serverId = parts.getOrNull(1) ?: return
        serverSubject = redisSubjectIn(serverId)

        scope.launch(Dispatchers.IO) {
            try {
                val sub = Socket(host, port); subSocket = sub
                val pub = Socket(host, port)
                pubWriter = RedisWriter(pub.getOutputStream())
                sub.getOutputStream().apply { write(buildRespCmd("SUBSCRIBE", myChannel)); flush() }

                val input = sub.getInputStream().buffered(65_536) as BufferedInputStream
                while (scope.isActive) {
                    val msg = input.redisReadPubSub() ?: continue
                    val (channel, data) = msg
                    if (channel == myChannel) _incoming.emit(data)
                }
            } catch (e: Exception) {
                println("[RedisClient] Receive loop ended: ${e.message}")
            }
        }
        println("[RedisClient] Connected to $serverId @ ${device.address}")
    }

    // ── Send ──────────────────────────────────────────────────────────────────

    actual fun sendToServer(data: ByteArray, writeType: WriteType) {
        val subject = serverSubject ?: run { println("[RedisClient] Not connected"); return }
        scope.launch(Dispatchers.IO) {
            val payload = redisEncodePayload(myChannel, data)
            runCatching { pubWriter?.publish(subject, payload) }
                .onFailure { println("[RedisClient] Send error: ${it.message}") }
        }
    }

    actual fun receiveFromServer(): Flow<ByteArray> = _incoming.asSharedFlow()

    // ── Disconnect ────────────────────────────────────────────────────────────

    actual fun disconnect() {
        scope.cancel(); scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        runCatching { subSocket?.close() }; subSocket = null
        pubWriter = null; serverSubject = null
        println("[RedisClient] Disconnected")
    }
}
