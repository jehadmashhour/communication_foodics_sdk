package com.foodics.crosscommunicationlibrary.mqtt

/**
 * Singleton that holds the Swift CocoaMQTT bridge instances.
 *
 * Populate in iOSApp.init() before any MQTT channel is used:
 *   MQTTBridgeProvider.shared.serverBridge = MQTTBridge()
 *   MQTTBridgeProvider.shared.clientBridge = MQTTBridge()
 *
 * Each bridge wraps an independent CocoaMQTT client so the server and client
 * can maintain simultaneous broker connections.
 */
object MQTTBridgeProvider {
    var serverBridge: MQTTBridgeProtocol? = null
    var clientBridge: MQTTBridgeProtocol? = null
}
