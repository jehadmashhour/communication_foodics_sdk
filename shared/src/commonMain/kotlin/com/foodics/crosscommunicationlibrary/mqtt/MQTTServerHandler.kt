package com.foodics.crosscommunicationlibrary.mqtt

import kotlinx.coroutines.flow.Flow

expect class MQTTServerHandler(brokerUrl: String) {
    fun start(deviceName: String, identifier: String)
    suspend fun sendToClient(data: ByteArray)
    fun receiveFromClient(): Flow<ByteArray>
    suspend fun stop()
}
