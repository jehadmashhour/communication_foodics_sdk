package com.foodics.crosscommunicationlibrary.serial

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

actual class SerialServerHandler actual constructor(
    private val portPath: String,
    private val baudRate: Int
) {
    fun start(deviceName: String, identifier: String) {
        println("[SerialServer] Serial port access is not available on iOS")
    }

    fun sendToClient(data: ByteArray) {}

    fun receiveFromClient(): Flow<ByteArray> = emptyFlow()

    suspend fun stop() {}
}
