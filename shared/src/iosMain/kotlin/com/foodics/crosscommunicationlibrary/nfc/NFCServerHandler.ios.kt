package com.foodics.crosscommunicationlibrary.nfc

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * iOS NFC server — not supported.
 *
 * iOS does not expose Host Card Emulation (HCE), so it cannot emulate an NFC
 * tag for another device to tap. Use a different channel (SSDP, QR, BLE…)
 * when the iOS device must act as the server.
 */
actual class NFCServerHandler {

    fun start(deviceName: String, identifier: String) {
        println("[NFCServer] iOS server role is not supported — iOS has no HCE capability.")
    }

    suspend fun sendToClient(data: ByteArray) {
        println("[NFCServer] sendToClient is a no-op on iOS (server not supported)")
    }

    fun receiveFromClient(): Flow<ByteArray> = emptyFlow()

    fun stop() {
        println("[NFCServer] stop() — nothing to stop (iOS server was never started)")
    }
}
