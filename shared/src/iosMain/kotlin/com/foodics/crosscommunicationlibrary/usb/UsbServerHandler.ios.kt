package com.foodics.crosscommunicationlibrary.usb

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

actual class UsbServerHandler {
    fun start(deviceName: String, identifier: String) {}
    fun sendToClient(data: ByteArray) {}
    fun receiveFromClient(): Flow<ByteArray> = emptyFlow()
    fun stop() {}
}
