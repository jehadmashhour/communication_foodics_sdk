package com.foodics.crosscommunicationlibrary.sample

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import platform.CoreBluetooth.CBCentralManager

@Composable
actual fun rememberBluetoothEnableLauncher(onEnabled: () -> Unit): () -> Unit {
    LaunchedEffect(Unit) { bleStateMonitor.warmUp() }

    val currentOnEnabled = rememberUpdatedState(onEnabled)

    // Holds the temporary CBCentralManager alive until the native dialog is dismissed.
    // Creating CBCentralManager without ShowPowerAlertKey=false triggers the iOS
    // "Bluetooth is Off" system dialog, which has a "Settings" button that goes
    // directly to the Bluetooth settings page.
    var powerAlertManager by remember { mutableStateOf<CBCentralManager?>(null) }

    return remember {
        {
            if (bleStateMonitor.isEnabled()) {
                currentOnEnabled.value()
            } else {
                powerAlertManager = CBCentralManager(null, null)
            }
        }
    }
}
