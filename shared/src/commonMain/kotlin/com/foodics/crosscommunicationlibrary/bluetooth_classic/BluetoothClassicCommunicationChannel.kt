package com.foodics.crosscommunicationlibrary.bluetooth_classic

import ConnectionType
import client.WriteType
import com.foodics.crosscommunicationlibrary.core.CommunicationChannel
import kotlinx.coroutines.flow.Flow
import scanner.IoTDevice

/**
 * Classic Bluetooth (RFCOMM / SPP) communication channel.
 *
 * Distinct from the BLE [com.foodics.crosscommunicationlibrary.bluetooth.BluetoothCommunicationChannel]:
 *   - Uses Bluetooth Classic BR/EDR radio, not BLE.
 *   - Communicates via RFCOMM sockets using the Serial Port Profile (SPP) UUID.
 *   - Higher throughput and simpler serial-like API — no GATT, no characteristics.
 *
 * Discovery  : Active inquiry via BluetoothAdapter + immediate emission of already-paired devices.
 * Server     : Listens via BluetoothServerSocket (RFCOMM), accepts one client at a time.
 * Client     : Connects via RFCOMM socket to the SPP service record on the remote device.
 *
 * POS use-cases:
 *   - Wireless receipt printers (Epson, Star Micronics, Bixolon, Zebra)
 *   - Bluetooth cash drawer triggers
 *   - Barcode scanners in SPP mode
 *   - Mobile payment terminals
 *
 * iOS: Classic Bluetooth RFCOMM is not available to third-party apps without Apple MFi
 * certification. All methods are no-ops and scan returns an empty list on iOS.
 *
 * Android permissions required in the app manifest:
 *   API ≤ 30: BLUETOOTH, BLUETOOTH_ADMIN, ACCESS_FINE_LOCATION
 *   API ≥ 31: BLUETOOTH_SCAN, BLUETOOTH_CONNECT
 */
expect class BluetoothClassicCommunicationChannel() : CommunicationChannel {
    override val connectionType: ConnectionType
    override suspend fun startServer(deviceName: String, identifier: String)
    override fun scan(): Flow<List<IoTDevice>>
    override suspend fun connectToServer(device: IoTDevice)
    override suspend fun sendDataToServer(data: ByteArray, writeType: WriteType)
    override suspend fun receiveDataFromServer(): Flow<ByteArray>
    override suspend fun sendDataToClient(data: ByteArray)
    override suspend fun receiveDataFromClient(): Flow<ByteArray>
    override suspend fun stopServer()
    override suspend fun disconnectClient()
}
