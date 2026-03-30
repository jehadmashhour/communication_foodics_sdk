package sdk.server.main

import sdk.core.ClientDevice
import sdk.server.main.service.ServerBluetoothGattConnection

sealed interface ServerConnectionEvent {
    data class DeviceConnected(val connection : ServerBluetoothGattConnection): ServerConnectionEvent
    data class DeviceDisconnected(val device: ClientDevice): ServerConnectionEvent
}