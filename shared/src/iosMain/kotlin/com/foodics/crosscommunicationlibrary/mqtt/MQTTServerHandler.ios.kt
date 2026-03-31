package com.foodics.crosscommunicationlibrary.mqtt

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

actual class MQTTServerHandler actual constructor(private val brokerUrl: String) {

    private var scope      = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _fromClient = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

    private var serverId:   String? = null
    private var deviceName: String? = null

    private val bridge get() = MQTTBridgeProvider.serverBridge

    // ── Delegate ──────────────────────────────────────────────────────────────

    private inner class ServerDelegate : MQTTDelegate {

        override fun onConnected() {
            val b   = bridge ?: return
            val id  = serverId ?: return
            val dev = deviceName ?: return

            // Subscribe to data incoming from client
            b.subscribe("$MQTT_DATA_PREFIX$id/$MQTT_TO_SERVER", 1)

            // Publish retained discovery beacon
            val json = buildMqttDiscoveryJsonIos(id, dev)
            b.publish("$MQTT_DISCOVERY_PREFIX$id", json.encodeToByteArray(), 1, retained = true)
            println("[MQTTServer] Connected & discovery beacon published [$id]")
        }

        override fun onMessage(topic: String, payload: ByteArray) {
            if (topic.endsWith(MQTT_TO_SERVER)) {
                scope.launch { _fromClient.emit(payload) }
            }
        }

        override fun onConnectionLost() {
            println("[MQTTServer] Broker connection lost")
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    actual fun start(deviceName: String, identifier: String) {
        val b = bridge ?: run { println("[MQTTServer] Bridge not set"); return }
        serverId        = identifier
        this.deviceName = deviceName
        b.delegate = ServerDelegate()
        val (host, port) = parseMqttBrokerUrlIos(brokerUrl)
        b.connect(host, port, mqttRandomClientId("crosscomm-srv"))
    }

    actual suspend fun sendToClient(data: ByteArray) {
        val id = serverId ?: run { println("[MQTTServer] Not started"); return }
        bridge?.publish("$MQTT_DATA_PREFIX$id/$MQTT_TO_CLIENT", data, 1, retained = false)
    }

    actual fun receiveFromClient(): Flow<ByteArray> = _fromClient.asSharedFlow()

    actual suspend fun stop() {
        val b  = bridge
        val id = serverId
        if (b != null && id != null) {
            // Clear retained discovery beacon
            b.publish("$MQTT_DISCOVERY_PREFIX$id", ByteArray(0), 1, retained = true)
            b.disconnect()
        }
        serverId    = null
        deviceName  = null
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        println("[MQTTServer] Stopped")
    }
}
