package com.foodics.crosscommunicationlibrary.mqtt

import platform.Foundation.NSUUID

// ── Topic constants ───────────────────────────────────────────────────────────

internal const val MQTT_DISCOVERY_PREFIX = "crosscomm/discovery/"
internal const val MQTT_DATA_PREFIX      = "crosscomm/data/"
internal const val MQTT_TO_SERVER        = "toServer"
internal const val MQTT_TO_CLIENT        = "toClient"

/** Default public test broker — replace with your own for production. */
const val DEFAULT_MQTT_BROKER = "tcp://broker.hivemq.com:1883"

// ── JSON helpers ──────────────────────────────────────────────────────────────

internal fun buildMqttDiscoveryJsonIos(id: String, name: String): String =
    """{"id":"$id","name":"$name"}"""

internal data class MqttDeviceInfoIos(val id: String, val name: String)

internal fun parseMqttDiscoveryJsonIos(json: String): MqttDeviceInfoIos? = runCatching {
    val id   = Regex(""""id"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: return null
    val name = Regex(""""name"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: return null
    MqttDeviceInfoIos(id, name)
}.getOrNull()

// ── Broker URL parser ─────────────────────────────────────────────────────────

/**
 * Parses `tcp://host:port` or `ssl://host:port` into a (host, port) pair.
 * Defaults to port 1883 if the port segment is missing or invalid.
 */
internal fun parseMqttBrokerUrlIos(url: String): Pair<String, Int> {
    val withoutScheme = if ("://" in url) url.substringAfter("://") else url
    val colonIdx      = withoutScheme.lastIndexOf(':')
    if (colonIdx < 0) return withoutScheme to 1883
    val host = withoutScheme.substring(0, colonIdx)
    val port = withoutScheme.substring(colonIdx + 1).toIntOrNull() ?: 1883
    return host to port
}

// ── Client ID generator ───────────────────────────────────────────────────────

internal fun mqttRandomClientId(prefix: String): String =
    "$prefix-${NSUUID().UUIDString().take(8)}"
