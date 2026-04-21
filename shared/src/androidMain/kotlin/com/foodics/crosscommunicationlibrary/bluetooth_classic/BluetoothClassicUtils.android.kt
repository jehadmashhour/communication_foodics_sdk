package com.foodics.crosscommunicationlibrary.bluetooth_classic

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import com.foodics.crosscommunicationlibrary.AppContext
import scanner.IoTDevice
import java.util.UUID

/** Serial Port Profile UUID — universally used for Classic Bluetooth serial communication. */
internal val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

internal fun getAdapter(): BluetoothAdapter? {
    val mgr = AppContext.get()
        .getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    return mgr?.adapter
}

@Suppress("MissingPermission")
internal fun BluetoothDevice.toIoTDevice(): IoTDevice {
    val friendlyName = runCatching { name }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: "BT ${address.takeLast(5)}"
    return IoTDevice(
        name = friendlyName,
        id = address,
        address = address,
        connectionType = ConnectionType.BLUETOOTH_CLASSIC
    )
}
