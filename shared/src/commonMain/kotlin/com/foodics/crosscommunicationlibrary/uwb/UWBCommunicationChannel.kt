package com.foodics.crosscommunicationlibrary.uwb

import ConnectionType
import client.WriteType
import com.foodics.crosscommunicationlibrary.core.CommunicationChannel
import kotlinx.coroutines.flow.Flow
import scanner.IoTDevice

/**
 * UWB (Ultra-Wideband) communication channel.
 *
 * Discovery  : UDP multicast on 239.255.255.250:1901 — platform-specific
 *              announce messages carry OOB ranging parameters.
 * Ranging    : Hardware UWB session (Android = FiRa/Jetpack UWB,
 *              iOS = NearbyInteraction).
 * Data flow  : receiveDataFromClient / receiveDataFromServer emit 12-byte
 *              ranging results: [distance_float | azimuth_float | elevation_float]
 *              all big-endian IEEE-754, NaN when the value is unavailable.
 *
 * Note: Android UWB and iOS UWB use different underlying protocols (FiRa vs
 * Apple proprietary) and are NOT interoperable across platforms.
 * Requires UWB-capable hardware (Android 12+ / iPhone 11+).
 */
expect class UWBCommunicationChannel() : CommunicationChannel {
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
