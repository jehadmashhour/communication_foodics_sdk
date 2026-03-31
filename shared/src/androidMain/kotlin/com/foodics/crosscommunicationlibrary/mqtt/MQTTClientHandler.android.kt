package com.foodics.crosscommunicationlibrary.mqtt

import android.util.Log
import client.WriteType
import ConnectionType
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import scanner.IoTDevice

actual class MQTTClientHandler actual constructor(private val brokerUrl: String) {

    companion object { private const val TAG = "MQTTClient" }

    private var scope      = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _devices   = MutableStateFlow<List<IoTDevice>>(emptyList())
    private val _incoming  = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

    private var dataMqttClient:  MqttClient? = null
    private var connectedServerId: String?   = null

    // ── Scan ──────────────────────────────────────────────────────────────────

    actual fun scan(): Flow<List<IoTDevice>> = channelFlow {
        val devicesMap = mutableMapOf<String, IoTDevice>()
        val clientId   = "crosscomm-scan-${java.util.UUID.randomUUID().toString().take(8)}"
        val scanClient = MqttClient(brokerUrl, clientId, MemoryPersistence())

        scanClient.setCallback(object : MqttCallback {
            override fun connectionLost(cause: Throwable?) { Log.w(TAG, "Scan connection lost") }
            override fun messageArrived(topic: String, message: MqttMessage) {
                if (!topic.startsWith(MQTT_DISCOVERY_PREFIX)) return
                val payload = message.payload ?: return
                if (payload.isEmpty()) return   // cleared retained message
                val info = parseMqttDiscoveryJson(String(payload)) ?: return
                if (devicesMap.containsKey(info.id)) return
                devicesMap[info.id] = IoTDevice(
                    id             = info.id,
                    name           = info.name,
                    address        = info.id,   // serverId acts as the address/topic key
                    connectionType = ConnectionType.MQTT
                )
                _devices.value = devicesMap.values.toList()
                Log.i(TAG, "Discovered MQTT server: ${info.name} [${info.id}]")
            }
            override fun deliveryComplete(token: IMqttDeliveryToken) {}
        })

        withContext(Dispatchers.IO) {
            val opts = MqttConnectOptions().apply {
                isCleanSession    = true
                connectionTimeout = 10
                keepAliveInterval = 30
            }
            runCatching { scanClient.connect(opts) }.onFailure {
                Log.e(TAG, "Scan connect failed", it); return@withContext
            }
            // Subscribing to the wildcard instantly delivers all retained discovery messages
            runCatching { scanClient.subscribe("$MQTT_DISCOVERY_PREFIX+", 1) }
        }

        val job = launch { _devices.collect { trySend(it) } }

        awaitClose {
            job.cancel()
            runCatching { scanClient.disconnect() }
            runCatching { scanClient.close() }
        }
    }

    // ── Connect ───────────────────────────────────────────────────────────────

    actual fun connect(device: IoTDevice) {
        connectedServerId = device.address   // address = serverId
        scope.launch {
            val clientId = "crosscomm-cli-${java.util.UUID.randomUUID().toString().take(8)}"
            val client   = MqttClient(brokerUrl, clientId, MemoryPersistence())

            client.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) { Log.w(TAG, "Data connection lost") }
                override fun messageArrived(topic: String, message: MqttMessage) {
                    if (topic.endsWith(MQTT_TO_CLIENT)) {
                        scope.launch { _incoming.emit(message.payload) }
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
                Log.e(TAG, "Data connect failed", it); return@launch
            }
            runCatching {
                client.subscribe("$MQTT_DATA_PREFIX${device.address}/$MQTT_TO_CLIENT", 1)
            }
            dataMqttClient = client
            Log.i(TAG, "MQTT client connected to [${device.address}]")
        }
    }

    // ── Send / Receive / Disconnect ───────────────────────────────────────────

    actual fun sendToServer(data: ByteArray, writeType: WriteType) {
        val id     = connectedServerId ?: run { Log.w(TAG, "Not connected"); return }
        val client = dataMqttClient    ?: run { Log.w(TAG, "No data client"); return }
        val msg    = MqttMessage(data).apply { qos = 1 }
        runCatching { client.publish("$MQTT_DATA_PREFIX$id/$MQTT_TO_SERVER", msg) }
            .onFailure { Log.e(TAG, "Publish error", it) }
    }

    actual fun receiveFromServer(): Flow<ByteArray> = _incoming.asSharedFlow()

    actual fun disconnect() {
        runCatching { dataMqttClient?.disconnect() }
        runCatching { dataMqttClient?.close() }
        dataMqttClient    = null
        connectedServerId = null
        _devices.value    = emptyList()
        Log.i(TAG, "MQTT client disconnected")
    }
}
