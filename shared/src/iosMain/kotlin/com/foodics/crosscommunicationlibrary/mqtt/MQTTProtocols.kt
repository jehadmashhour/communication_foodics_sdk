package com.foodics.crosscommunicationlibrary.mqtt

/**
 * Implemented by the Kotlin MQTT handlers (server or client).
 * Swift's bridge calls these methods when MQTT events arrive.
 */
interface MQTTDelegate {
    /** Called when the broker connection is successfully established. */
    fun onConnected()
    /** Called for every incoming PUBLISH packet. */
    fun onMessage(topic: String, payload: ByteArray)
    /** Called when the broker connection is dropped unexpectedly. */
    fun onConnectionLost()
}

/**
 * Implemented by Swift's MQTTBridge (backed by CocoaMQTT).
 *
 * Register two instances via [MQTTBridgeProvider] before the MQTT channel is used:
 *   MQTTBridgeProvider.shared.serverBridge = MQTTBridge()
 *   MQTTBridgeProvider.shared.clientBridge = MQTTBridge()
 *
 * Requires the `CocoaMQTT` CocoaPod and network access.
 */
interface MQTTBridgeProtocol {
    /** Receive MQTT events; set before calling [connect]. */
    var delegate: MQTTDelegate?
    /** Connect to the broker using the given credentials. */
    fun connect(brokerHost: String, brokerPort: Int, clientId: String)
    /** Subscribe to a topic with the given QoS level (0, 1, or 2). */
    fun subscribe(topic: String, qos: Int)
    /** Unsubscribe from a topic. */
    fun unsubscribe(topic: String)
    /**
     * Publish a message.
     * @param retained  True to publish as a retained message.
     * @param qos       Quality-of-service level (0, 1, or 2).
     */
    fun publish(topic: String, payload: ByteArray, qos: Int, retained: Boolean)
    /** Gracefully disconnect from the broker. */
    fun disconnect()
}
