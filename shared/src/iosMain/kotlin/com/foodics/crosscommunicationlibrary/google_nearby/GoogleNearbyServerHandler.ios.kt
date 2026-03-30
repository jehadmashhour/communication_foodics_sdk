@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.foodics.crosscommunicationlibrary.google_nearby

import com.foodics.crosscommunicationlibrary.cloud.toByteArray
import com.foodics.crosscommunicationlibrary.cloud.toNSData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import platform.Foundation.NSData

/**
 * iOS actual for GoogleNearbyServerHandler.
 *
 * Implements NearbyServerDelegate so the Swift bridge can deliver received
 * payloads back to Kotlin via a typed protocol method.
 */
actual class GoogleNearbyServerHandler : NearbyServerDelegate {

    private val _fromClient = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    private val connectedClients = mutableSetOf<String>()

    init {
        NearbyBridgeProvider.serverBridge?.delegate = this
    }

    private val bridge get() = NearbyBridgeProvider.serverBridge

    // ── NearbyServerDelegate (called by Swift) ────────────────────────────────

    override fun onClientConnected(endpointId: String) {
        connectedClients += endpointId
        println("[NearbyServer] Client connected: $endpointId (total: ${connectedClients.size})")
    }

    override fun onClientDisconnected(endpointId: String) {
        connectedClients -= endpointId
        println("[NearbyServer] Client disconnected: $endpointId (remaining: ${connectedClients.size})")
    }

    override fun onDataReceived(data: NSData) {
        _fromClient.tryEmit(data.toByteArray())
    }

    // ── Public API ────────────────────────────────────────────────────────────

    suspend fun start(deviceName: String, identifier: String) {
        bridge?.startAdvertising("$deviceName|$identifier")
    }

    suspend fun sendToClient(data: ByteArray) {
        bridge?.sendData(data.toNSData())
    }

    fun receiveFromClient(): Flow<ByteArray> = _fromClient.asSharedFlow()

    suspend fun stop() {
        bridge?.stopAdvertising()
    }
}