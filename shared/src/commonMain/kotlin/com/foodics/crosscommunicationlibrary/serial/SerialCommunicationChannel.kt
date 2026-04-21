package com.foodics.crosscommunicationlibrary.serial

import ConnectionType
import client.WriteType
import com.foodics.crosscommunicationlibrary.core.CommunicationChannel
import kotlinx.coroutines.flow.Flow
import scanner.IoTDevice

/**
 * RS-232 / serial port communication channel with 4-byte length-prefix framing.
 *
 * Models direct serial cable connections between devices. Each device exposes its
 * own local serial ports via scan(); the physical cable is the "connection".
 *
 * Wire format  : [length: 4 bytes BE][payload: length bytes]
 * Discovery    : enumerates local /dev/ttyS*, /dev/ttyUSB*, /dev/ttyACM* paths
 * Baud rate    : configurable (default 9600); configuration applied via stty on
 *                Android (requires appropriate device permissions) and termios on iOS.
 *
 * Roles:
 *   Server — opens [portPath], listens for incoming framed messages, can push data back.
 *   Client — scans local ports, picks one, opens it, and sends/receives framed messages.
 *
 * POS use-cases:
 *   - Receipt printers (ESC/POS over RS-232)
 *   - Cash drawers triggered over serial
 *   - Barcode scanners wired via RS-232
 *   - Payment terminals with serial interface
 *   - Pole / customer displays
 *
 * Android: requires the app's UID to have read/write access to the serial device node
 * (e.g., membership in the "dialout" group or a device-specific permission grant).
 * iOS: serial port access is OS-restricted; this implementation is a no-op stub.
 */
const val SERIAL_DEFAULT_BAUD_RATE = 9600

expect class SerialCommunicationChannel(
    portPath: String = "",
    baudRate: Int = SERIAL_DEFAULT_BAUD_RATE
) : CommunicationChannel {
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
