package com.foodics.crosscommunicationlibrary.mqtt

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

actual class MQTTServerHandler actual constructor(private val brokerUrl: String) {

    companion object { private const val TAG = "MQTTServer" }

    private var scope      = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _fromClient = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

    private var mqttClient: MqttClient? = null
    private var serverId: String?       = null

    actual fun start(deviceName: String, identifier: String) {
        serverId = identifier
        scope.launch {
            val clientId = "crosscomm-srv-${identifier.take(8)}"
            val client   = MqttClient(brokerUrl, clientId, MemoryPersistence())

            client.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) {
                    Log.w(TAG, "Connection lost", cause)
                }
                override fun messageArrived(topic: String, message: MqttMessage) {
                    if (topic.endsWith(MQTT_TO_SERVER)) {
                        scope.launch { _fromClient.emit(message.payload) }
                    }
                }
                override fun deliveryComplete(token: IMqttDeliveryToken) {}
            })

            val opts = MqttConnectOptions().apply {
                isCleanSession    = true
                connectionTimeout = 10
                keepAliveInterval = 30
            }
            runCatching { client.connect(opts) }.onFailure {
                Log.e(TAG, "Connect failed", it); return@launch
            }

            // Subscribe to incoming data from client
            runCatching {
                client.subscribe("$MQTT_DATA_PREFIX$identifier/$MQTT_TO_SERVER", 1)
            }

            // Publish retained discovery beacon
            val json = buildMqttDiscoveryJson(identifier, deviceName)
            val msg  = MqttMessage(json.toByteArray()).apply { qos = 1; isRetained = true }
            runCatching { client.publish("$MQTT_DISCOVERY_PREFIX$identifier", msg) }

            mqttClient = client
            Log.i(TAG, "MQTT server running: $deviceName [$identifier] @ $brokerUrl")
        }
    }

    actual suspend fun sendToClient(data: ByteArray): Unit = withContext(Dispatchers.IO) {
        val id     = serverId ?: return@withContext
        val client = mqttClient ?: run { Log.w(TAG, "Not connected"); return@withContext }
        val msg    = MqttMessage(data).apply { qos = 1 }
        runCatching {
            client.publish("$MQTT_DATA_PREFIX$id/$MQTT_TO_CLIENT", msg)
        }.onFailure { Log.e(TAG, "Publish error", it) }
    }

    actual fun receiveFromClient(): Flow<ByteArray> = _fromClient.asSharedFlow()

    actual suspend fun stop(): Unit = withContext(Dispatchers.IO) {
        val id     = serverId
        val client = mqttClient
        mqttClient = null
        serverId   = null

        if (client != null && id != null && client.isConnected) {
            // Clear retained discovery beacon
            runCatching {
                client.publish(
                    "$MQTT_DISCOVERY_PREFIX$id",
                    MqttMessage(ByteArray(0)).apply { qos = 1; isRetained = true }
                )
            }
            runCatching { client.disconnect() }
            runCatching { client.close() }
        }
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        Log.i(TAG, "MQTT server stopped")
    }
}
