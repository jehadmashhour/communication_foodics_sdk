package com.foodics.crosscommunicationlibrary.wifi_direct

/**
 * Singleton that holds the Swift-side MPC bridge instances.
 *
 * Swift must populate these before any WifiDirect handler is created,
 * e.g. inside iOSApp.init() before ContentView appears:
 *
 *   MultipeerBridgeProvider.shared.serverBridge = MCPServerBridge()
 *   MultipeerBridgeProvider.shared.clientBridge = MCPClientBridge()
 */
object MultipeerBridgeProvider {
    var serverBridge: MCPServerBridgeProtocol? = null
    var clientBridge: MCPClientBridgeProtocol? = null
}
