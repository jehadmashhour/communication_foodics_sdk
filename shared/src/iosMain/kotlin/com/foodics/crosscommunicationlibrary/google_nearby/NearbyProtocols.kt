package com.foodics.crosscommunicationlibrary.google_nearby

import platform.Foundation.NSData

/**
 * Callback interface implemented by GoogleNearbyClientHandler (Kotlin).
 * Becomes an ObjC protocol in the generated framework header.
 * Swift's NearbyClientBridge calls these methods to push events into Kotlin.
 */
interface NearbyClientDelegate {
    fun onEndpointFound(endpointId: String, endpointName: String)
    fun onEndpointLost(endpointId: String)
    fun onConnectionResult(success: Boolean)
    /** Called when a previously established connection to the server is lost. */
    fun onDisconnected()
    fun onDataReceived(data: NSData)
}

/**
 * Bridge interface implemented by Swift's NearbyClientBridge.
 * Becomes an ObjC protocol in the generated framework header.
 * Kotlin calls these methods to drive Nearby discovery and connection.
 */
interface NearbyClientBridgeProtocol {
    var delegate: NearbyClientDelegate?
    fun startDiscovery()
    fun stopDiscovery()
    fun requestConnection(endpointId: String)
    fun sendData(data: NSData)
    fun disconnect()
}

/**
 * Callback interface implemented by GoogleNearbyServerHandler (Kotlin).
 */
interface NearbyServerDelegate {
    /** Called when a client endpoint establishes a connection with the server. */
    fun onClientConnected(endpointId: String)
    /** Called when a previously connected client endpoint disconnects. */
    fun onClientDisconnected(endpointId: String)
    fun onDataReceived(data: NSData)
}

/**
 * Bridge interface implemented by Swift's NearbyServerBridge.
 */
interface NearbyServerBridgeProtocol {
    var delegate: NearbyServerDelegate?
    fun startAdvertising(endpointName: String)
    fun sendData(data: NSData)
    fun stopAdvertising()
}