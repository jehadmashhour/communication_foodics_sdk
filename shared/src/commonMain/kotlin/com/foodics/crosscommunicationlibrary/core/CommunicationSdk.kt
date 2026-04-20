package com.foodics.crosscommunicationlibrary.core

import ConnectionType
import client.WriteType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    fun scan(): Flow<List<DiscoveredDevice>> {
        return channels
            .asFlow()
            .flatMapMerge { channel ->
                channel.scan()
                    .map { devices ->
                        channel.connectionType to devices
                            .filter { it.id != null && it.connectionType != null }
                            .map { it.toDiscoveredDevice() }
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


    suspend fun connectToServer(device: IoTDevice) {
        channels
            .first { it.connectionType == device.connectionType }
            .connectToServer(device)
    }

    suspend fun sendDataToServer(
        device: IoTDevice,
        data: ByteArray,
        writeType: WriteType
    ) {
        channels
            .first { it.connectionType == device.connectionType }
            .sendDataToServer(data, writeType)
    }

    suspend fun receiveFromServer(
        connectionType: ConnectionType
    ): Flow<ByteArray> =
        channels.first { it.connectionType == connectionType }
            .receiveDateFromServer()

    fun scanDevices(connectionType: ConnectionType): Flow<List<IoTDevice>> =
        channels.first { it.connectionType == connectionType }.scan()

    suspend fun startServer(connectionType: ConnectionType, deviceName: String, identifier: String) {
        channels.first { it.connectionType == connectionType }.startServer(deviceName, identifier)
    }

    suspend fun sendDataToServer(connectionType: ConnectionType, data: ByteArray) {
        channels.first { it.connectionType == connectionType }
            .sendDataToServer(data, WriteType.DEFAULT)
    }

    suspend fun sendDataToClient(connectionType: ConnectionType, data: ByteArray) {
        channels.first { it.connectionType == connectionType }.sendDataToClient(data)
    }

    suspend fun receiveDataFromClient(connectionType: ConnectionType): Flow<ByteArray> =
        channels.first { it.connectionType == connectionType }.receiveDataFromClient()

    suspend fun stopAllServers() =
        channels.forEach { it.stopServer() }

    companion object {
        fun builder(): CommunicationSdkBuilder = CommunicationSdkBuilder()
    }
}
