package com.foodics.crosscommunicationlibrary.cloud

import android.util.Log
import io.ably.lib.realtime.AblyRealtime
import io.ably.lib.realtime.Channel
import io.ably.lib.realtime.CompletionListener
import io.ably.lib.types.ClientOptions
import io.ably.lib.types.ErrorInfo
import io.ably.lib.types.Message
import io.ably.lib.types.PresenceMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import scanner.IoTDevice
import ConnectionType
import client.WriteType
import java.util.UUID

actual class CloudClientHandler {

    companion object {
        private const val TAG = "CloudClientHandler"
        private const val API_KEY =
            "S4ZTiA.IC7hEQ:qsUR5drP3Ew6Zj3aHc2Qq93floLOcaYyzo7hisEn9s0"

        private const val DISCOVERY_CHANNEL = "cloud_discovery"
    }

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var ably: AblyRealtime? = null
    private var discoveryChannel: Channel? = null
    private var communicationChannel: Channel? = null

    private val clientId = "client_${UUID.randomUUID()}"

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    private val _onlineDevices = MutableStateFlow<List<IoTDevice>>(emptyList())

    fun scan(): Flow<List<IoTDevice>> {

        if (ably != null) return _onlineDevices.asStateFlow()

        val options = ClientOptions(API_KEY).apply {
            clientId = this@CloudClientHandler.clientId
            autoConnect = true
        }

        ably = AblyRealtime(options)

        ably?.connection?.once {
            Log.i(TAG, "Client connected: ${it.current}")
        }

        discoveryChannel = ably?.channels?.get(DISCOVERY_CHANNEL)

        val devicesMap = mutableMapOf<String, IoTDevice>()

        discoveryChannel?.presence?.subscribe { msg ->

            when (msg.action) {

                PresenceMessage.Action.enter,
                PresenceMessage.Action.present,
                PresenceMessage.Action.update -> {

                    val metadata = when (val data = msg.data) {
                        is String -> JSONObject(data)
                        else -> null
                    } ?: return@subscribe

                    val name = metadata.optString("deviceName")
                    val identifier = metadata.optString("identifier")
                    val channel = metadata.optString("channel")

                    if (channel.isNullOrBlank()) return@subscribe

                    val device = IoTDevice(
                        id = identifier,
                        name = name.ifBlank { identifier },
                        address = channel,
                        connectionType = ConnectionType.CLOUD
                    )

                    devicesMap[msg.clientId] = device
                }

                PresenceMessage.Action.leave,
                PresenceMessage.Action.absent -> {
                    devicesMap.remove(msg.clientId)
                }

                else -> {}
            }

            _onlineDevices.value = devicesMap.values.toList()
        }

        Log.i(TAG, "Cloud scan started")

        return _onlineDevices.asStateFlow()
    }

    suspend fun connect(serverChannelName: String): Unit =
        withContext(Dispatchers.IO) {

            val instance = ably ?: error("Call scan() first")

            communicationChannel = instance.channels.get(serverChannelName)

            communicationChannel?.subscribe("server_to_client") { message: Message ->

                val data = when (val payload = message.data) {
                    is ByteArray -> payload
                    is String -> payload.toByteArray()
                    else -> null
                }

                data?.let { scope.launch { _incoming.emit(it) } }
            }

            Log.i(TAG, "Connected to server channel: $serverChannelName")
        }

    suspend fun sendToServer(data: ByteArray, writeType: WriteType) {

        val ch = communicationChannel ?: error("Not connected to server")

        ch.publish("client_to_server", data, object : CompletionListener {
            override fun onSuccess() {
                Log.i(TAG, "Sent to server successfully")
            }

            override fun onError(reason: ErrorInfo) {
                Log.e(TAG, "Send failed: ${reason.message}")
            }
        })
    }

    fun receiveFromServer(): Flow<ByteArray> = _incoming.asSharedFlow()

    suspend fun disconnect(): Unit = withContext(Dispatchers.IO) {
        try {

            communicationChannel?.unsubscribe()
            discoveryChannel?.unsubscribe()

            ably?.close()

            scope.cancel()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

            communicationChannel = null
            discoveryChannel = null
            ably = null

            _onlineDevices.value = emptyList()

            Log.i(TAG, "Client disconnected")

        } catch (e: Exception) {
            Log.e(TAG, "Disconnect error", e)
        }
    }
}