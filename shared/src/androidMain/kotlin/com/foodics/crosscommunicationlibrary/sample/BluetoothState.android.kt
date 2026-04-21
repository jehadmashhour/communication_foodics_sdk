package com.foodics.crosscommunicationlibrary.sample

import android.bluetooth.BluetoothAdapter

@Suppress("DEPRECATION")
actual fun isBluetoothEnabled(): Boolean =
    BluetoothAdapter.getDefaultAdapter()?.isEnabled ?: false
