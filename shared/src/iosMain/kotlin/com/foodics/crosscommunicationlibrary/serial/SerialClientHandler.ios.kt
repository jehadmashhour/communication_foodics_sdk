package com.foodics.crosscommunicationlibrary.serial

import client.WriteType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import scanner.IoTDevice

actual class SerialClientHandler actual constructor(private val baudRate: Int) {

    fun scan(): Flow<List<IoTDevice>> = flowOf(emptyList())

    fun connect(device: IoTDevice) {
        println("[SerialClient] Serial port access is not available on iOS")
    }

    fun sendToServer(data: ByteArray, writeType: WriteType) {}

    fun receiveFromServer(): Flow<ByteArray> = emptyFlow()

    fun disconnect() {}
}
