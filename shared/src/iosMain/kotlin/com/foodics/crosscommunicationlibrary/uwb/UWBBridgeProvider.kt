package com.foodics.crosscommunicationlibrary.uwb

/**
 * Singleton that holds the Swift bridge implementations for UWB.
 *
 * Populate this in iOSApp.init() before any UWB channel is used:
 *   UWBBridgeProvider.shared.serverBridge = UWBServerBridge()
 *   UWBBridgeProvider.shared.clientBridge = UWBClientBridge()
 */
object UWBBridgeProvider {
    var serverBridge: UWBServerBridgeProtocol? = null
    var clientBridge: UWBClientBridgeProtocol? = null
}
