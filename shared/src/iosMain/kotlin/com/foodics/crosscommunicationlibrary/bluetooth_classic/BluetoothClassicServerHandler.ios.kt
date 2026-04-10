package com.foodics.crosscommunicationlibrary.bluetooth_classic

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

actual class BluetoothClassicServerHandler {
    fun start(deviceName: String, identifier: String) {
        println("[BtClassicServer] iOS: Classic Bluetooth RFCOMM server is not available without MFi certification.")
    }
    fun sendToClient(data: ByteArray) {}
    fun receiveFromClient(): Flow<ByteArray> = emptyFlow()
    suspend fun stop() {}
}
