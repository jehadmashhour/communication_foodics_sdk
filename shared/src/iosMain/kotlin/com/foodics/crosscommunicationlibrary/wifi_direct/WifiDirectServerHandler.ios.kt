package com.foodics.crosscommunicationlibrary.wifi_direct

import com.foodics.crosscommunicationlibrary.cloud.toByteArray
import com.foodics.crosscommunicationlibrary.cloud.toNSData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import platform.Foundation.NSData

actual class WifiDirectServerHandler : MCPServerDelegate {

    private val _fromClient = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    private val connectedPeers = mutableSetOf<String>()

    init {
        MultipeerBridgeProvider.serverBridge?.delegate = this
    }

    private val bridge get() = MultipeerBridgeProvider.serverBridge

    // ── MCPServerDelegate (called by Swift) ──────────────────────────────────

    override fun onPeerConnected(peerId: String) {
        connectedPeers += peerId
        println("[MCPServer] Peer connected: $peerId (total: ${connectedPeers.size})")
    }

    override fun onPeerDisconnected(peerId: String) {
        connectedPeers -= peerId
        println("[MCPServer] Peer disconnected: $peerId (remaining: ${connectedPeers.size})")
    }

    override fun onDataReceived(data: NSData) {
        _fromClient.tryEmit(data.toByteArray())
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun start(deviceName: String, identifier: String) {
        bridge?.delegate = this
        bridge?.startAdvertising(deviceName, identifier)
        println("[MCPServer] Advertising started: $deviceName / $identifier")
    }

    fun sendToClient(data: ByteArray) {
        if (connectedPeers.isEmpty()) {
            println("[MCPServer] sendToClient: no peers connected")
            return
        }
        bridge?.sendData(data.toNSData())
    }

    fun receiveFromClient(): Flow<ByteArray> = _fromClient.asSharedFlow()

    fun stop() {
        bridge?.stopAdvertising()
        connectedPeers.clear()
        println("[MCPServer] Stopped")
    }
}
