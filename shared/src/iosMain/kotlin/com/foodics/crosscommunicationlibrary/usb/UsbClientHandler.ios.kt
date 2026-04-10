package com.foodics.crosscommunicationlibrary.usb

import client.WriteType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import scanner.IoTDevice

/**
 * iOS does not support USB Host mode for third-party apps.
 * Communicating with USB peripherals requires Apple MFi certification for the
 * accessory hardware and entitlements not available to general App Store apps.
 *
 * Scan always returns an empty list. All other methods are no-ops.
 */
actual class UsbClientHandler {

    fun scan(): Flow<List<IoTDevice>> = flowOf(emptyList())

    fun connect(device: IoTDevice) {
        println("[UsbClient] iOS: USB Host mode is not available without MFi certification.")
    }

    fun sendToServer(data: ByteArray, writeType: WriteType) {
        println("[UsbClient] iOS: USB Host mode is not available.")
    }

    fun receiveFromServer(): Flow<ByteArray> = emptyFlow()

    fun disconnect() {}
}
