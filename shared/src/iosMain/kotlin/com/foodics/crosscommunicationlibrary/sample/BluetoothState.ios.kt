package com.foodics.crosscommunicationlibrary.sample

import kotlinx.coroutines.flow.MutableStateFlow
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCentralManagerOptionShowPowerAlertKey
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.darwin.NSObject

// Kotlin/Native does not support `object` inheriting NSObject — use a class + top-level val.
internal class BleStateMonitor : NSObject(), CBCentralManagerDelegateProtocol {
    private val _enabled = MutableStateFlow(false)

    private val manager: CBCentralManager by lazy {
        CBCentralManager(
            this,
            null,
            mapOf<Any?, Any?>(CBCentralManagerOptionShowPowerAlertKey to false)
        )
    }

    override fun centralManagerDidUpdateState(central: CBCentralManager) {
        _enabled.value = central.state == CBManagerStatePoweredOn
    }

    fun warmUp() { manager }
    fun isEnabled(): Boolean = _enabled.value
}

internal val bleStateMonitor = BleStateMonitor()

actual fun isBluetoothEnabled(): Boolean {
    bleStateMonitor.warmUp()
    return bleStateMonitor.isEnabled()
}
