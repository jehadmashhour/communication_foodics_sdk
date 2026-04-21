package com.foodics.crosscommunicationlibrary.nfc

import ConnectionType
import client.WriteType
import com.foodics.crosscommunicationlibrary.core.CommunicationChannel
import kotlinx.coroutines.flow.Flow
import scanner.IoTDevice

/**
 * NFC Bootstrapping communication channel.
 *
 * Uses NFC tap for initial parameter exchange (bootstrapping), then falls back
 * to TCP for bidirectional data transport.
 *
 * Server  : Android HCE (Host Card Emulation) emulates an NFC Type 4 NDEF tag
 *           that carries the server's TCP connection parameters as a JSON payload.
 *           iOS cannot act as NFC server (no HCE support).
 *
 * Client  : Taps the server device, reads the NDEF record via NFC reader mode
 *           (Android) or NFCNDEFReaderSession (iOS via Swift bridge), then
 *           establishes a TCP connection for data exchange.
 *
 * Android: Requires NFC permission and NFC-capable hardware (API 19+, minSdk 30).
 * iOS    : Requires CoreNFC and NSNearbyInteractionUsageDescription in Info.plist.
 *          iOS client only — server role is not supported.
 */
expect class NFCCommunicationChannel() : CommunicationChannel {
    override val connectionType: ConnectionType
    override suspend fun startServer(deviceName: String, identifier: String)
    override fun scan(): Flow<List<IoTDevice>>
    override suspend fun connectToServer(device: IoTDevice)
    override suspend fun sendDataToServer(data: ByteArray, writeType: WriteType)
    override suspend fun receiveDataFromServer(): Flow<ByteArray>
    override suspend fun sendDataToClient(data: ByteArray)
    override suspend fun receiveDataFromClient(): Flow<ByteArray>
    override suspend fun stopServer()
    override suspend fun disconnectClient()
}
