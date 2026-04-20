package com.foodics.crosscommunicationlibrary.modbus_tcp

import kotlinx.coroutines.flow.Flow

expect class ModbusServerHandler() {
    suspend fun start(deviceName: String, identifier: String)
    fun sendToClient(data: ByteArray)
    fun receiveFromClient(): Flow<ByteArray>
    suspend fun stop()
}
