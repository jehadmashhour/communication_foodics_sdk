package com.foodics.crosscommunicationlibrary.mdns

import ConnectionType
import client.WriteType
import com.foodics.crosscommunicationlibrary.core.CommunicationChannel
import kotlinx.coroutines.flow.Flow
import scanner.IoTDevice

/**
 * mDNS/Bonjour communication channel with configurable service type.
 *
 * Bridges local-network service discovery (DNS-SD / mDNS) with a reliable
 * TCP data transport, using the same 4-byte length-prefixed framing as
 * TCP_SOCKET for SDK-to-SDK messaging.
 *
 * The key differentiator from TCP_SOCKET is the **user-configurable service
 * type**, which enables discovering any mDNS-advertised device on the network
 * — not only Foodics SDK peers.
 *
 * Discovery  : DNS-SD / mDNS, service type configurable at construction time.
 * Transport  : persistent TCP connection, length-prefixed framing.
 *
 * Common service types:
 *   "_foodics_mdns._tcp." — Foodics SDK devices (default)
 *   "_ipp._tcp."          — IPP printers (AirPrint)
 *   "_pdl-datastream._tcp." — raw TCP printers (ESC/POS)
 *   "_http._tcp."         — HTTP servers
 *   "_scanner._tcp."      — network scanners
 *
 * Note: When connecting to a non-SDK third-party device, the SDK framing
 * (4-byte length header) will not be understood by the remote side.
 * In that case use sendDataToServer() to write raw bytes and treat
 * receiveDataFromServer() as a stream of received byte chunks.
 *
 * POS use-cases:
 *   - Discovering and communicating with AirPrint / IPP printers
 *   - Finding network-attached receipt printers advertising via Bonjour
 *   - SDK-to-SDK local-network messaging with auto-discovery
 *   - Avahi-announced Linux POS services (kitchen display, inventory)
 */

/** Default service type for Foodics SDK device-to-device communication. */
const val MDNS_DEFAULT_SERVICE_TYPE = "_foodics_mdns._tcp."

expect class MDNSCommunicationChannel(
    serviceType: String = MDNS_DEFAULT_SERVICE_TYPE
) : CommunicationChannel {
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
