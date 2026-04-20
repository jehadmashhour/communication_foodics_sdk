package com.foodics.crosscommunicationlibrary.redis

import kotlinx.coroutines.flow.Flow

expect class RedisServerHandler(brokerUrl: String) {
    suspend fun start(deviceName: String, identifier: String)
    fun sendToClient(data: ByteArray)
    fun receiveFromClient(): Flow<ByteArray>
    suspend fun stop()
}
