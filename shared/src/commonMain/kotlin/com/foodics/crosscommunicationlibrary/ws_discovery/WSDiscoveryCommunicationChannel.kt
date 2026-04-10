package com.foodics.crosscommunicationlibrary.ws_discovery

import ConnectionType
import client.WriteType
import com.foodics.crosscommunicationlibrary.core.CommunicationChannel
import kotlinx.coroutines.flow.Flow
import scanner.IoTDevice

/**
 * WS-Discovery (SOAP/UPnP) communication channel.
 *
 * Discovery: multicast UDP on 239.255.255.250:3702 — server sends Hello on start
 * and responds to Probe messages with ProbeMatches (SOAP/XML over UDP).
 * Data transport: TCP with 4-byte length-prefixed framing.
 * Works across Android ↔ Android, iOS ↔ iOS, and Android ↔ iOS.
 */
expect class WSDiscoveryCommunicationChannel() : CommunicationChannel {
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
