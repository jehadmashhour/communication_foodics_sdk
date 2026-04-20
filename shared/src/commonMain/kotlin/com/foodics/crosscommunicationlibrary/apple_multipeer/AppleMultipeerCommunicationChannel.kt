package com.foodics.crosscommunicationlibrary.apple_multipeer

import ConnectionType
import client.WriteType
import com.foodics.crosscommunicationlibrary.core.CommunicationChannel
import kotlinx.coroutines.flow.Flow
import scanner.IoTDevice

/**
 * Apple Multipeer Connectivity communication channel.
 *
 * Uses Apple's MultipeerConnectivity framework to establish peer-to-peer sessions
 * between nearby iOS/macOS devices over Bluetooth and Wi-Fi simultaneously —
 * no router, internet, or pairing required.
 *
 * Service type : "foodics-mpc" (1-15 chars, lowercase alphanumeric + hyphens)
 * Encryption   : MCEncryptionOptional (configurable at the session level)
 * Send mode    : MCSessionSendDataReliable
 *
 * Server role  — advertises presence via MCNearbyServiceAdvertiser; auto-accepts
 *                all incoming connection invitations.
 * Client role  — browses via MCNearbyServiceBrowser; invites a discovered peer
 *                into an MCSession.
 *
 * POS use-cases:
 *   - iOS cashier tablet ↔ iOS customer display (Bluetooth + Wi-Fi)
 *   - Mobile order-taking device ↔ kitchen display
 *   - Card reader integration over Bluetooth
 *   - Proximity-triggered device pairing without network setup
 *
 * Android: this channel is a no-op stub. Use GOOGLE_NEARBY for Android P2P.
 */
expect class AppleMultipeerCommunicationChannel() : CommunicationChannel {
    override val connectionType: ConnectionType
    override suspend fun startServer(deviceName: String, identifier: String)
    override fun scan(): Flow<List<IoTDevice>>
    override suspend fun connectToServer(device: IoTDevice)
    override suspend fun sendDataToServer(data: ByteArray, writeType: WriteType)
    override suspend fun receiveDateFromServer(): Flow<ByteArray>
    override suspend fun sendDataToClient(data: ByteArray)
    override suspend fun receiveDataFromClient(): Flow<ByteArray>
    override suspend fun stopServer()
    override suspend fun disconnectClient()
}
