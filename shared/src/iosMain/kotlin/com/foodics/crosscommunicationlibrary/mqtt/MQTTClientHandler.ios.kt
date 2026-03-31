package com.foodics.crosscommunicationlibrary.mqtt

import ConnectionType
import client.WriteType
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import scanner.IoTDevice

actual class MQTTClientHandler actual constructor(private val brokerUrl: String) {

    private val scope     = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _devices  = MutableStateFlow<List<IoTDevice>>(emptyList())
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

    private var connectedServerId: String? = null
    private val devicesMap = mutableMapOf<String, IoTDevice>()

    private val bridge get() = MQTTBridgeProvider.clientBridge

    // ── Scan ──────────────────────────────────────────────────────────────────

    actual fun scan(): Flow<List<IoTDevice>> = channelFlow {
        val b = bridge ?: run { println("[MQTTClient] Bridge not set"); return@channelFlow }

        b.delegate = object : MQTTDelegate {

            override fun onConnected() {
                // Subscribing to the wildcard delivers all retained discovery messages instantly
                b.subscribe("$MQTT_DISCOVERY_PREFIX+", 1)
                println("[MQTTClient] Subscribed to discovery topic")
            }

            override fun onMessage(topic: String, payload: ByteArray) {
                when {
                    topic.startsWith(MQTT_DISCOVERY_PREFIX) -> {
                        if (payload.isEmpty()) return   // cleared retained message
                        val info = parseMqttDiscoveryJsonIos(payload.decodeToString()) ?: return
                        if (devicesMap.containsKey(info.id)) return
                        devicesMap[info.id] = IoTDevice(
                            id             = info.id,
                            name           = info.name,
                            address        = info.id,   // serverId used as address/topic key
                            connectionType = ConnectionType.MQTT
                        )
                        _devices.value = devicesMap.values.toList()
                        println("[MQTTClient] Discovered: ${info.name} [${info.id}]")
                    }
                    topic.endsWith(MQTT_TO_CLIENT) -> {
                        scope.launch { _incoming.emit(payload) }
                    }
                }
            }

            override fun onConnectionLost() {
                println("[MQTTClient] Broker connection lost during scan")
            }
        }

        val (host, port) = parseMqttBrokerUrlIos(brokerUrl)
        b.connect(host, port, mqttRandomClientId("crosscomm-scan"))

        val job = launch { _devices.collect { trySend(it) } }

        awaitClose {
            job.cancel()
            b.unsubscribe("$MQTT_DISCOVERY_PREFIX+")
        }
    }

    // ── Connect ───────────────────────────────────────────────────────────────

    actual fun connect(device: IoTDevice) {
        connectedServerId = device.address
        // Subscribe to the server's toClient topic on the already-open broker connection.
        // The scan delegate will route MQTT_TO_CLIENT messages to _incoming.
        bridge?.subscribe("$MQTT_DATA_PREFIX${device.address}/$MQTT_TO_CLIENT", 1)
        println("[MQTTClient] Connected to server [${device.address}]")
    }

    // ── Send / Receive / Disconnect ───────────────────────────────────────────

    actual fun sendToServer(data: ByteArray, writeType: WriteType) {
        val id = connectedServerId ?: run { println("[MQTTClient] Not connected"); return }
        bridge?.publish("$MQTT_DATA_PREFIX$id/$MQTT_TO_SERVER", data, 1, retained = false)
    }

    actual fun receiveFromServer(): Flow<ByteArray> = _incoming.asSharedFlow()

    actual fun disconnect() {
        connectedServerId?.let { id ->
            bridge?.unsubscribe("$MQTT_DATA_PREFIX$id/$MQTT_TO_CLIENT")
        }
        connectedServerId = null
        devicesMap.clear()
        _devices.value = emptyList()
        bridge?.disconnect()
        println("[MQTTClient] Disconnected")
    }
}
