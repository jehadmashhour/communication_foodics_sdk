package com.foodics.crosscommunicationlibrary.core

import ConnectionType
import client.WriteType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.IO
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import scanner.IoTDevice
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.*


class CommunicationSDK(
    private val channels: List<CommunicationChannel>
) {
    private val deviceCache = mutableMapOf<Pair<ConnectionType, String>, IoTDevice>()

    private fun IoTDevice.toDiscoveredDevice(): DiscoveredDevice =
        DiscoveredDevice(
            id = id!!,
            name = name,
            addressByType = mapOf(connectionType!! to address),
            connectionTypes = setOf(connectionType!!)
        )

    private fun DiscoveredDevice.mergeWith(
        other: DiscoveredDevice
    ): DiscoveredDevice =
        copy(
            addressByType = addressByType + other.addressByType,
            connectionTypes = connectionTypes + other.connectionTypes
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    fun scan(): Flow<List<DiscoveredDevice>> {
        return channels
            .asFlow()
            .flatMapMerge { channel ->
                channel.scan()
                    .map { devices ->
                        val filtered = devices.filter { it.id != null && it.connectionType != null }
                        filtered.forEach { device ->
                            deviceCache[device.connectionType!! to device.id!!] = device
                        }
                        channel.connectionType to filtered.map { it.toDiscoveredDevice() }
                    }
            }
            .scan(emptyMap<ConnectionType, List<DiscoveredDevice>>()) { acc, (type, devices) ->
                acc + (type to devices)
            }
            .map { channelDevices ->
                channelDevices
                    .values
                    .flatten()
                    .groupBy { it.id }
                    .map { (_, devices) ->
                        devices.reduce { a, b -> a.mergeWith(b) }
                    }
            }
            .flowOn(Dispatchers.IO)
    }

    suspend fun startServers(deviceName: String, identifier: String) = coroutineScope {
        channels.forEach { channel ->
            launch(Dispatchers.IO) {
                channel.startServer(deviceName, identifier)
            }
        }
    }

    suspend fun startServer(connectionType: ConnectionType, deviceName: String, identifier: String) {
        channels.first { it.connectionType == connectionType }.startServer(deviceName, identifier)
    }

    suspend fun connectToServer(device: DiscoveredDevice, connectionType: ConnectionType) {
        val iotDevice = deviceCache[connectionType to device.id]
            ?: error("Device '${device.name}' not found in scan cache for $connectionType. Scan before connecting.")
        channels.first { it.connectionType == connectionType }.connectToServer(iotDevice)
    }

    suspend fun sendDataToServer(connectionType: ConnectionType, data: ByteArray, writeType: WriteType = WriteType.DEFAULT) {
        channels.first { it.connectionType == connectionType }
            .sendDataToServer(data, writeType)
    }

    suspend fun receiveFromServer(connectionType: ConnectionType): Flow<ByteArray> =
        channels.first { it.connectionType == connectionType }.receiveDataFromServer()

    suspend fun sendDataToClient(connectionType: ConnectionType, data: ByteArray) {
        channels.first { it.connectionType == connectionType }.sendDataToClient(data)
    }

    suspend fun receiveDataFromClient(connectionType: ConnectionType): Flow<ByteArray> =
        channels.first { it.connectionType == connectionType }.receiveDataFromClient()

    suspend fun stopServer(connectionType: ConnectionType) {
        channels.first { it.connectionType == connectionType }.stopServer()
    }

    suspend fun stopAllServers() =
        channels.forEach { it.stopServer() }

    suspend fun disconnectClient(connectionType: ConnectionType) {
        channels.first { it.connectionType == connectionType }.disconnectClient()
    }

    fun clientConnectionState(connectionType: ConnectionType): Flow<Boolean> =
        channels.first { it.connectionType == connectionType }.clientConnectionState()

    fun connectedClients(connectionType: ConnectionType): Flow<List<ConnectedClient>> =
        channels.first { it.connectionType == connectionType }.connectedClients()

    suspend fun receiveMessagesFromClient(connectionType: ConnectionType): Flow<ClientMessage> =
        channels.first { it.connectionType == connectionType }.receiveMessagesFromClient()

    fun scanDevices(connectionType: ConnectionType): Flow<List<IoTDevice>> =
        channels.first { it.connectionType == connectionType }.scan()

    companion object {
        fun builder(): CommunicationSdkBuilder = CommunicationSdkBuilder()
    }
}
