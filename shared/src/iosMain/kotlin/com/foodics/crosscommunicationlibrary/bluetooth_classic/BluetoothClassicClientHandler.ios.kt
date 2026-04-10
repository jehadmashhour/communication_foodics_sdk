package com.foodics.crosscommunicationlibrary.bluetooth_classic

import client.WriteType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import scanner.IoTDevice

/**
 * Classic Bluetooth RFCOMM is not available to iOS third-party apps without Apple MFi
 * certification for the peripheral hardware. Scan returns an empty list; all other
 * operations are no-ops.
 */
actual class BluetoothClassicClientHandler {
    fun scan(): Flow<List<IoTDevice>> = flowOf(emptyList())
    fun connect(device: IoTDevice) {
        println("[BtClassicClient] iOS: Classic Bluetooth RFCOMM is not available without MFi certification.")
    }
    fun sendToServer(data: ByteArray, writeType: WriteType) {}
    fun receiveFromServer(): Flow<ByteArray> = emptyFlow()
    fun disconnect() {}
}
