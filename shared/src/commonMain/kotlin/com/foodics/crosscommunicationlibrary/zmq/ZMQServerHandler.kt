package com.foodics.crosscommunicationlibrary.zmq

import kotlinx.coroutines.flow.Flow

expect class ZMQServerHandler() {
    suspend fun start(deviceName: String, identifier: String)
    fun sendToClient(data: ByteArray)
    fun receiveFromClient(): Flow<ByteArray>
    suspend fun stop()
}
