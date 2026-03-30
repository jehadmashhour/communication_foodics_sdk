package com.foodics.crosscommunicationlibrary.cloud

import android.util.Log
import io.ably.lib.realtime.AblyRealtime
import io.ably.lib.realtime.Channel
import io.ably.lib.realtime.CompletionListener
import io.ably.lib.types.ClientOptions
import io.ably.lib.types.ErrorInfo
import io.ably.lib.types.Message
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.util.UUID

actual class CloudServerHandler {

    companion object {
        private const val TAG = "CloudServerHandler"
        private const val API_KEY =
            "S4ZTiA.IC7hEQ:qsUR5drP3Ew6Zj3aHc2Qq93floLOcaYyzo7hisEn9s0"

        private const val DISCOVERY_CHANNEL = "cloud_discovery"
    }

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var ably: AblyRealtime? = null
    private var communicationChannel: Channel? = null
    private var discoveryChannel: Channel? = null

    private var serverChannelName: String? = null

    private val _fromClient = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val fromClientFlow: Flow<ByteArray> = _fromClient.asSharedFlow()

    suspend fun start(deviceName: String, identifier: String): Unit =
        withContext(Dispatchers.IO) {

            val channelName = "server_${identifier}_${UUID.randomUUID()}"
            serverChannelName = channelName

            val options = ClientOptions(API_KEY).apply {
                clientId = "server_$identifier"
                autoConnect = true
            }

            ably = AblyRealtime(options)

            ably?.connection?.once {
                Log.i(TAG, "Server connected: ${it.current}")
            }

            communicationChannel = ably?.channels?.get(channelName)
            discoveryChannel = ably?.channels?.get(DISCOVERY_CHANNEL)

            // 1️⃣ Announce in discovery channel
            val discoveryPayload = JSONObject().apply {
                put("deviceName", deviceName)
                put("identifier", identifier)
                put("channel", channelName)
            }.toString()

            discoveryChannel?.presence?.enter(
                discoveryPayload,
                object : CompletionListener {
                    override fun onSuccess() {
                        Log.i(TAG, "Server announced in discovery channel")
                    }

                    override fun onError(reason: ErrorInfo) {
                        Log.e(TAG, "Discovery enter failed: ${reason.message}")
                    }
                }
            )

            // 2️⃣ Listen for client messages
            communicationChannel?.subscribe("client_to_server") { message: Message ->

                val data = when (val payload = message.data) {
                    is ByteArray -> payload
                    is String -> payload.toByteArray()
                    else -> null
                }

                data?.let { scope.launch { _fromClient.emit(it) } }
            }

            Log.i(TAG, "Cloud server started on channel: $channelName")
        }

    suspend fun sendToClient(data: ByteArray) {
        val ch = communicationChannel ?: error("Server not started")

        ch.publish("server_to_client", data, object : CompletionListener {
            override fun onSuccess() {
                Log.i(TAG, "Sent to client successfully")
            }

            override fun onError(reason: ErrorInfo) {
                Log.e(TAG, "Send to client failed: ${reason.message}")
            }
        })
    }

    fun receiveFromClient(): Flow<ByteArray> = fromClientFlow

    suspend fun stop(): Unit = withContext(Dispatchers.IO) {
        try {

            communicationChannel?.presence?.leave(null)
            discoveryChannel?.presence?.leave(null)

            communicationChannel?.unsubscribe()
            discoveryChannel?.unsubscribe()

            ably?.close()

            scope.cancel()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

            communicationChannel = null
            discoveryChannel = null
            ably = null
            serverChannelName = null

            Log.i(TAG, "Server stopped")

        } catch (e: Exception) {
            Log.e(TAG, "Stop error", e)
        }
    }
}