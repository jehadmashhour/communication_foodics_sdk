package com.foodics.crosscommunicationlibrary.apple_multipeer

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

actual class AppleMultipeerServerHandler actual constructor() {
    fun start(deviceName: String, identifier: String) {}
    fun sendToClient(data: ByteArray) {}
    fun receiveFromClient(): Flow<ByteArray> = emptyFlow()
    fun stop() {}
}
