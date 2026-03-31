package com.foodics.crosscommunicationlibrary.mqtt

import client.WriteType
import kotlinx.coroutines.flow.Flow
import scanner.IoTDevice

expect class MQTTClientHandler(brokerUrl: String) {
    fun scan(): Flow<List<IoTDevice>>
    fun connect(device: IoTDevice)
    fun sendToServer(data: ByteArray, writeType: WriteType)
    fun receiveFromServer(): Flow<ByteArray>
    fun disconnect()
}
