@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.foodics.crosscommunicationlibrary.redis

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import platform.posix.*
import kotlin.concurrent.Volatile

actual class RedisServerHandler actual constructor(private val brokerUrl: String) {

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _fromClient = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

    @Volatile private var subFd = -1
    @Volatile private var pubFd = -1
    @Volatile private var clientReplyChannel: String? = null

    actual suspend fun start(deviceName: String, identifier: String) {
        stop(); delay(500)
        val (host, port) = parseRedisUrl(brokerUrl)
        val myChannel    = redisSubjectIn(identifier)

        val sFd = redisTcpConnect(host, port)
        val pFd = redisTcpConnect(host, port)
        if (sFd < 0 || pFd < 0) {
            println("[RedisServer] Connect failed")
            if (sFd >= 0) close(sFd); if (pFd >= 0) close(pFd)
            return
        }
        subFd = sFd; pubFd = pFd
        redisSendAll(sFd, redisBuildCmd("SUBSCRIBE", myChannel))
        println("[RedisServer] Started: $deviceName [$identifier] @ $brokerUrl")

        val beacon = "$deviceName|$identifier".encodeToByteArray()
        scope.launch {
            while (isActive) {
                runCatching { redisSendAll(pubFd, redisBuildPublish(REDIS_DISCOVERY_CHANNEL, beacon)) }
                delay(REDIS_BEACON_INTERVAL_MS)
            }
        }

        scope.launch { receiveLoop(sFd, myChannel) }
    }

    private suspend fun receiveLoop(connFd: Int, myChannel: String) {
        redisSetTimeout(connFd, 2_000)
        try {
            while (scope.isActive && subFd == connFd) {
                val msg = redisReadPubSub(connFd) ?: continue
                val (channel, payload) = msg
                if (channel != myChannel) continue
                val (replyChannel, data) = redisDecodePayload(payload) ?: continue
                clientReplyChannel = replyChannel
                _fromClient.emit(data)
            }
        } catch (e: Exception) {
            println("[RedisServer] Receive loop ended: ${e.message}")
        }
    }

    actual fun sendToClient(data: ByteArray) {
        val replyChannel = clientReplyChannel ?: run { println("[RedisServer] No client yet"); return }
        val pFd = pubFd; if (pFd < 0) return
        scope.launch {
            runCatching { redisSendAll(pFd, redisBuildPublish(replyChannel, data)) }
        }
    }

    actual fun receiveFromClient(): Flow<ByteArray> = _fromClient.asSharedFlow()

    actual suspend fun stop() {
        scope.cancel(); scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val s = subFd; subFd = -1; if (s >= 0) close(s)
        val p = pubFd; pubFd = -1; if (p >= 0) close(p)
        clientReplyChannel = null
        println("[RedisServer] Stopped")
    }
}
