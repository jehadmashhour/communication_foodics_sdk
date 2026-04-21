package com.foodics.crosscommunicationlibrary.bluetooth

import BluetoothConstants.CHAR_FROM_CLIENT_UUID
import BluetoothConstants.CHAR_TO_CLIENT_UUID
import BluetoothConstants.SERVICE_UUID
import BluetoothConstants.TAG
import android.content.Context
import android.util.Log
import client.Client
import client.ClientCharacteristic
import client.WriteType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import scanner.IoTDevice
import scanner.Scanner

actual class BluetoothClientHandler(context: Context) {

    private val client: Client = Client(context)
    private val scanner: Scanner = Scanner(context)
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var clientToServerChar: ClientCharacteristic
    private lateinit var clientFromServerChar: ClientCharacteristic

    fun scan(): Flow<List<IoTDevice>> = scanner.scan()

    suspend fun connect(device: IoTDevice) {
        Log.i(TAG, "Attempting to connect to device: ${device.name} (${device.address})")

        client.connect(device, scope)

        val service = client.discoverServices().findService(SERVICE_UUID)
            ?: throw Exception(
                "Bluetooth service with UUID $SERVICE_UUID not found on device ${device.name}"
            )

        clientToServerChar = service.findCharacteristic(CHAR_FROM_CLIENT_UUID)
            ?: throw Exception(
                "Required 'to-server' characteristic (${CHAR_FROM_CLIENT_UUID}) not found " +
                        "in service $SERVICE_UUID on device ${device.name}"
            )

        clientFromServerChar = service.findCharacteristic(CHAR_TO_CLIENT_UUID)
            ?: throw Exception(
                "Required 'from-server' characteristic (${CHAR_TO_CLIENT_UUID}) not found " +
                        "in service $SERVICE_UUID on device ${device.name}"
            )

        Log.i(TAG, "Connected successfully to ${device.name}")
    }

    suspend fun sendToServer(data: ByteArray, writeType: WriteType) {
        clientToServerChar.write(data, writeType)
        Log.d(TAG, "Sent to server: ${String(data)}")
    }

    suspend fun receiveFromServer(): Flow<ByteArray> = merge(
        clientFromServerChar.getNotifications(),
        client.disconnectEvent().map { throw Exception("Server disconnected") }
    )

    suspend fun disconnect() {
        client.disconnect()
        Log.i(TAG, "Disconnected from server")
    }
}
