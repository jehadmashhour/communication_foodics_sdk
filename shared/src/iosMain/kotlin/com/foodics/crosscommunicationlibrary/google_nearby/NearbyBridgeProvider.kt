package com.foodics.crosscommunicationlibrary.google_nearby

/**
 * Singleton that holds the Swift bridge implementations.
 *
 * Swift populates this before the KMP shared framework initialises any
 * Nearby handler (i.e. inside iOSApp.init(), before ContentView is shown):
 *
 *   NearbyBridgeProvider.shared.clientBridge = NearbyClientBridge()
 *   NearbyBridgeProvider.shared.serverBridge = NearbyServerBridge()
 *
 * Kotlin handlers then read their bridge from here — no NSClassFromString,
 * no performSelector, no string-based reflection.
 */
object NearbyBridgeProvider {
    var clientBridge: NearbyClientBridgeProtocol? = null
    var serverBridge: NearbyServerBridgeProtocol? = null
}