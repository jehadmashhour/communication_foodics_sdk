@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.foodics.crosscommunicationlibrary.redis

import ConnectionType
import client.WriteType
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import platform.posix.*
import scanner.IoTDevice
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource

actual class RedisClientHandler actual constructor(private val brokerUrl: String) {

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

    // Stable per-instance inbox channel for server replies
    private val clientId  = buildString { repeat(32) { append(kotlin.random.Random.nextInt(16).toString(16)) } }
    private val myChannel = "foodics:redis:$clientId:in"

    @Volatile private var subFd = -1
    @Volatile private var pubFd = -1
    @Volatile private var serverSubject: String? = null

    // ── Scan ──────────────────────────────────────────────────────────────────

    actual fun scan(): Flow<List<IoTDevice>> = channelFlow {
        val (host, port) = parseRedisUrl(brokerUrl)
        val scanFd = redisTcpConnect(host, port)
        if (scanFd < 0) { println("[RedisClient] Scan connect failed"); return@channelFlow }

        try {
            redisSendAll(scanFd, redisBuildCmd("SUBSCRIBE", REDIS_DISCOVERY_CHANNEL))
            redisSetTimeout(scanFd, 2_000)

            val devices = mutableMapOf<String, Pair<IoTDevice, TimeSource.Monotonic.ValueTimeMark>>()
            val evictJob = launch {
                while (isActive) {
                    delay(2_000)
                    val before = devices.size
                    val iter = devices.iterator()
                    while (iter.hasNext()) {
                        if (iter.next().value.second.elapsedNow().inWholeMilliseconds > REDIS_DEVICE_TTL_MS)
                            iter.remove()
                    }
                    if (devices.size != before) trySend(devices.values.map { it.first })
                }
            }

            while (isActive) {
                val msg = redisReadPubSub(scanFd) ?: continue
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

            awaitClose { evictJob.cancel(); close(scanFd) }
        } catch (e: Exception) {
            println("[RedisClient] Scan error: ${e.message}")
            awaitClose { close(scanFd) }
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

        val sFd = redisTcpConnect(host, port)
        val pFd = redisTcpConnect(host, port)
        if (sFd < 0 || pFd < 0) {
            println("[RedisClient] Connect failed")
            if (sFd >= 0) close(sFd); if (pFd >= 0) close(pFd)
            return
        }
        subFd = sFd; pubFd = pFd
        redisSendAll(sFd, redisBuildCmd("SUBSCRIBE", myChannel))
        println("[RedisClient] Connected to $serverId @ ${device.address}")
        scope.launch { receiveLoop(sFd) }
    }

    private suspend fun receiveLoop(connFd: Int) {
        redisSetTimeout(connFd, 2_000)
        try {
            while (scope.isActive && subFd == connFd) {
                val msg = redisReadPubSub(connFd) ?: continue
                val (channel, data) = msg
                if (channel == myChannel) _incoming.emit(data)
            }
        } catch (e: Exception) {
            println("[RedisClient] Receive loop ended: ${e.message}")
        }
    }

    // ── Send ──────────────────────────────────────────────────────────────────

    actual fun sendToServer(data: ByteArray, writeType: WriteType) {
        val subject = serverSubject ?: run { println("[RedisClient] Not connected"); return }
        val pFd = pubFd; if (pFd < 0) return
        scope.launch {
            val payload = redisEncodePayload(myChannel, data)
            runCatching { redisSendAll(pFd, redisBuildPublish(subject, payload)) }
        }
    }

    actual fun receiveFromServer(): Flow<ByteArray> = _incoming.asSharedFlow()

    // ── Disconnect ────────────────────────────────────────────────────────────

    actual fun disconnect() {
        scope.cancel(); scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val s = subFd; subFd = -1; if (s >= 0) close(s)
        val p = pubFd; pubFd = -1; if (p >= 0) close(p)
        serverSubject = null
        println("[RedisClient] Disconnected")
    }
}
