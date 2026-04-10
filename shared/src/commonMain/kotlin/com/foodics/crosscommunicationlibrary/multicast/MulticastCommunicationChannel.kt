package com.foodics.crosscommunicationlibrary.multicast

import ConnectionType
import client.WriteType
import com.foodics.crosscommunicationlibrary.core.CommunicationChannel
import kotlinx.coroutines.flow.Flow
import scanner.IoTDevice

/**
 * IP Multicast communication channel (RFC 1112 / RFC 3376).
 *
 * All participating devices join the multicast group 239.255.42.42 on UDP port 5422.
 * This is a site-local multicast address reserved for application use (RFC 2365).
 *
 * Packet types (first byte):
 *   0x01 = BEACON  — server periodically broadcasts its name/ID so clients can discover it.
 *   0x02 = DATA    — binary payload sent to the group.
 *
 * Discovery  : Passive — devices receive BEACON packets to build the device list.
 *              No mDNS or DNS-SD required.
 * Data push  : Server calls [sendDataToClient] → multicast DATA packet received by all clients.
 *              Client calls [sendDataToServer] → multicast DATA packet received by server.
 *
 * Key differences from other channels:
 *   - One-to-many: a single send reaches every group member simultaneously.
 *   - Connectionless: no TCP handshake; join the group and start communicating.
 *   - No mDNS dependency: discovery is built into the protocol via BEACON packets.
 *
 * Android: requires WifiManager.MulticastLock (acquired automatically).
 * iOS    : requires the Local Network permission entitlement.
 *
 * POS use-cases:
 *   - Broadcasting promotions / price updates to multiple display terminals at once.
 *   - Synchronising state across a fleet of POS devices on the same LAN.
 *   - Group messaging between multiple SDK instances without N separate connections.
 */
expect class MulticastCommunicationChannel() : CommunicationChannel {
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
