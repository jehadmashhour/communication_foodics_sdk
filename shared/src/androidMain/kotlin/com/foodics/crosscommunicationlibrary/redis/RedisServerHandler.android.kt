package com.foodics.crosscommunicationlibrary.redis

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.BufferedInputStream
import java.net.Socket

actual class RedisServerHandler actual constructor(private val brokerUrl: String) {

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _fromClient = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    private var subSocket: Socket? = null
    private var pubWriter: RedisWriter? = null
    @Volatile private var clientReplyChannel: String? = null

    actual suspend fun start(deviceName: String, identifier: String) = withContext(Dispatchers.IO) {
        stop()
        try {
            val (host, port) = parseRedisUrl(brokerUrl)
            val myChannel    = redisSubjectIn(identifier)

            // Sub socket: subscribes to server's data channel
            val sub = Socket(host, port); subSocket = sub
            sub.getOutputStream().apply { write(buildRespCmd("SUBSCRIBE", myChannel)); flush() }

            // Pub socket: publishes beacons + replies to clients
            val pub = Socket(host, port)
            val w   = RedisWriter(pub.getOutputStream()); pubWriter = w

            val beaconData = "$deviceName|$identifier".toByteArray()
            scope.launch {
                while (isActive) {
                    runCatching { w.publish(REDIS_DISCOVERY_CHANNEL, beaconData) }
                    delay(REDIS_BEACON_INTERVAL_MS)
                }
            }

            scope.launch(Dispatchers.IO) {
                val input = sub.getInputStream().buffered(65_536) as BufferedInputStream
                try {
                    while (scope.isActive) {
                        val msg = input.redisReadPubSub() ?: continue
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

            println("[RedisServer] Started: $deviceName [$identifier] @ $brokerUrl")
        } catch (e: Exception) {
            println("[RedisServer] Start failed: ${e.message}")
        }
    }

    actual fun sendToClient(data: ByteArray) {
        val replyChannel = clientReplyChannel ?: run { println("[RedisServer] No client yet"); return }
        scope.launch(Dispatchers.IO) {
            runCatching { pubWriter?.publish(replyChannel, data) }
                .onFailure { println("[RedisServer] Send error: ${it.message}") }
        }
    }

    actual fun receiveFromClient(): Flow<ByteArray> = _fromClient.asSharedFlow()

    actual suspend fun stop() {
        scope.cancel(); scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        withContext(Dispatchers.IO) {
            runCatching { subSocket?.close() }; subSocket = null
            pubWriter = null
        }
        clientReplyChannel = null
        println("[RedisServer] Stopped")
    }
}
