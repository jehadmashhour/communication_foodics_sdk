package com.foodics.crosscommunicationlibrary.wifi_direct

import platform.Foundation.NSData

/**
 * Kotlin delegate implemented by WifiDirectServerHandler.
 * Becomes an ObjC protocol in the generated framework header.
 * Swift's MCPServerBridge calls these methods to push MPC events into Kotlin.
 */
interface MCPServerDelegate {
    fun onPeerConnected(peerId: String)
    fun onPeerDisconnected(peerId: String)
    fun onDataReceived(data: NSData)
}

/**
 * Bridge protocol implemented by Swift's MCPServerBridge.
 * Kotlin calls these methods to drive MCNearbyServiceAdvertiser / MCSession.
 */
interface MCPServerBridgeProtocol {
    var delegate: MCPServerDelegate?
    fun startAdvertising(deviceName: String, identifier: String)
    fun sendData(data: NSData)
    fun stopAdvertising()
}

/**
 * Kotlin delegate implemented by WifiDirectClientHandler.
 * Becomes an ObjC protocol in the generated framework header.
 * Swift's MCPClientBridge calls these methods to push MPC browser events into Kotlin.
 */
interface MCPClientDelegate {
    fun onPeerFound(peerId: String, deviceName: String, identifier: String)
    fun onPeerLost(peerId: String)
    fun onConnectionResult(success: Boolean)
    fun onDisconnected()
    fun onDataReceived(data: NSData)
}

/**
 * Bridge protocol implemented by Swift's MCPClientBridge.
 * Kotlin calls these methods to drive MCNearbyServiceBrowser / MCSession.
 */
interface MCPClientBridgeProtocol {
    var delegate: MCPClientDelegate?
    fun startBrowsing()
    fun stopBrowsing()
    /** [peerId] is the MCPeerID displayName of the peer to invite. */
    fun invitePeer(peerId: String)
    fun sendData(data: NSData)
    fun disconnect()
}
