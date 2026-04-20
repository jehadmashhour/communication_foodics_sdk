package com.foodics.crosscommunicationlibrary.apple_multipeer

import client.WriteType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import scanner.IoTDevice

actual class AppleMultipeerClientHandler actual constructor() {
    fun scan(): Flow<List<IoTDevice>> = flowOf(emptyList())
    fun connect(device: IoTDevice) {}
    fun sendToServer(data: ByteArray, writeType: WriteType) {}
    fun receiveFromServer(): Flow<ByteArray> = emptyFlow()
    fun disconnect() {}
}
