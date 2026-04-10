package com.foodics.crosscommunicationlibrary.usb

import ConnectionType
import client.WriteType
import com.foodics.crosscommunicationlibrary.core.CommunicationChannel
import kotlinx.coroutines.flow.Flow
import scanner.IoTDevice

/**
 * USB Host communication channel.
 *
 * Discovery  : Enumerates physically connected USB devices via UsbManager on Android.
 *              Updates automatically when devices are plugged or unplugged.
 * Transport  : USB bulk transfers (OUT for send, IN for receive) on the first
 *              bulk-capable interface of the connected device.
 *
 * POS use-cases covered:
 *   - Receipt printers (USB CDC / raw USB class)
 *   - Cash drawers (connected via printer or direct USB)
 *   - Barcode scanners (USB HID — raw bulk mode)
 *   - Card readers / customer-facing displays
 *
 * iOS: USB Host mode is not available to third-party apps without Apple MFi
 * certification. Scan always returns an empty list on iOS.
 *
 * Android: Requires <uses-feature android:name="android.hardware.usb.host"/>
 * in the app's AndroidManifest.xml.
 */
expect class UsbCommunicationChannel() : CommunicationChannel {
    override val connectionType: ConnectionType
    override suspend fun startServer(deviceName: String, identifier: String)
    override fun scan(): Flow<List<IoTDevice>>
    override suspend fun connectToServer(device: IoTDevice)
    override suspend fun sendDataToServer(data: ByteArray, writeType: WriteType)
    override suspend fun receiveDateFromServer(): Flow<ByteArray>
    override suspend fun sendDataToClient(data: ByteArray)
    override suspend fun receiveDataFromClient(): Flow<ByteArray>
    override suspend fun stopServer()
    override suspend fun disconnectClient()
}
