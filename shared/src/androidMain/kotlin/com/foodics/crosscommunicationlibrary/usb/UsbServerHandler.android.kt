package com.foodics.crosscommunicationlibrary.usb

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * USB operates in Host mode only — this device connects TO USB peripherals.
 * There is no "server" role (peripheral/device mode) in this implementation.
 */
actual class UsbServerHandler {

    companion object {
        private const val TAG = "UsbServer"
    }

    fun start(deviceName: String, identifier: String) {
        Log.d(TAG, "USB Host mode — no server role needed")
    }

    fun sendToClient(data: ByteArray) {
        Log.d(TAG, "USB Host mode — sendToClient is not applicable")
    }

    fun receiveFromClient(): Flow<ByteArray> = emptyFlow()

    fun stop() {
        Log.d(TAG, "USB server stopped")
    }
}
