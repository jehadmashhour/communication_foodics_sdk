package com.foodics.crosscommunicationlibrary.mqtt

// ── Topic constants ───────────────────────────────────────────────────────────

internal const val MQTT_DISCOVERY_PREFIX = "crosscomm/discovery/"
internal const val MQTT_DATA_PREFIX      = "crosscomm/data/"
internal const val MQTT_TO_SERVER        = "toServer"
internal const val MQTT_TO_CLIENT        = "toClient"

/** Default public test broker — replace with your own for production. */
const val DEFAULT_MQTT_BROKER = "tcp://broker.hivemq.com:1883"

// ── JSON helpers ──────────────────────────────────────────────────────────────

internal fun buildMqttDiscoveryJson(id: String, name: String): String =
    """{"id":"$id","name":"$name"}"""

internal data class MqttDeviceInfo(val id: String, val name: String)

internal fun parseMqttDiscoveryJson(json: String): MqttDeviceInfo? = runCatching {
    val id   = Regex(""""id"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: return null
    val name = Regex(""""name"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: return null
    MqttDeviceInfo(id, name)
}.getOrNull()
