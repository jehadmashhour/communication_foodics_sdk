/*
 * Copyright (c) 2023, Nordic Semiconductor
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of
 * conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list
 * of conditions and the following disclaimer in the documentation and/or other materials
 * provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors may be
 * used to endorse or promote products derived from this software without specific prior
 * written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 * PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA,
 * OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY
 * OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE,
 * EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package sdk.server.real

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import sdk.core.data.util.DataByteArray
import sdk.core.RealClientDevice
import sdk.core.data.BleGattConnectionStatus
import sdk.core.data.BleGattOperationStatus
import sdk.core.data.BleGattPhy
import sdk.core.data.GattConnectionState
import sdk.core.wrapper.IBluetoothGattService
import sdk.core.wrapper.NativeBluetoothGattCharacteristic
import sdk.core.wrapper.NativeBluetoothGattDescriptor
import sdk.core.wrapper.NativeBluetoothGattService
import sdk.server.api.ServerGattEvent
import sdk.server.api.ServerGattEvent.CharacteristicReadRequest
import sdk.server.api.ServerGattEvent.CharacteristicWriteRequest
import sdk.server.api.ServerGattEvent.ClientConnectionStateChanged
import sdk.server.api.ServerGattEvent.DescriptorReadRequest
import sdk.server.api.ServerGattEvent.DescriptorWriteRequest
import sdk.server.api.ServerGattEvent.ExecuteWrite
import sdk.server.api.ServerGattEvent.NotificationSent
import sdk.server.api.ServerGattEvent.ServerMtuChanged
import sdk.server.api.ServerGattEvent.ServerPhyRead
import sdk.server.api.ServerGattEvent.ServerPhyUpdate
import sdk.server.api.ServerGattEvent.ServiceAdded

/**
 * A class which maps [BluetoothGattServerCallback] methods into [ServerGattEvent] events.
 *
 * @param bufferSize A buffer size for events emitted by [BluetoothGattServerCallback].
 */
class ServerBleGattCallback(
    bufferSize: Int
) : BluetoothGattServerCallback() {

    private val _event = MutableSharedFlow<ServerGattEvent>(
        extraBufferCapacity = bufferSize,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val event = _event.asSharedFlow()

    var onServiceAdded: ((IBluetoothGattService, BleGattOperationStatus) -> Unit)? = null

    override fun onCharacteristicReadRequest(
        device: BluetoothDevice?,
        requestId: Int,
        offset: Int,
        characteristic: BluetoothGattCharacteristic
    ) {
        val native = NativeBluetoothGattCharacteristic(characteristic)
        _event.tryEmit(CharacteristicReadRequest(RealClientDevice(device!!), requestId, offset, native))
    }

    override fun onCharacteristicWriteRequest(
        device: BluetoothDevice?,
        requestId: Int,
        characteristic: BluetoothGattCharacteristic,
        preparedWrite: Boolean,
        responseNeeded: Boolean,
        offset: Int,
        value: ByteArray?
    ) {
        val native = NativeBluetoothGattCharacteristic(characteristic)
        _event.tryEmit(
            CharacteristicWriteRequest(
                RealClientDevice(device!!),
                requestId,
                native,
                preparedWrite,
                responseNeeded,
                offset,
                DataByteArray(value!!)
            )
        )
    }

    override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
        val operationStatus = BleGattConnectionStatus.create(status)
        val state = GattConnectionState.create(newState)
        _event.tryEmit(ClientConnectionStateChanged(RealClientDevice(device!!), operationStatus, state))
    }

    override fun onDescriptorReadRequest(
        device: BluetoothDevice?,
        requestId: Int,
        offset: Int,
        descriptor: BluetoothGattDescriptor
    ) {
        val native = NativeBluetoothGattDescriptor(descriptor)
        _event.tryEmit(DescriptorReadRequest(RealClientDevice(device!!), requestId, offset, native))
    }

    override fun onDescriptorWriteRequest(
        device: BluetoothDevice?,
        requestId: Int,
        descriptor: BluetoothGattDescriptor,
        preparedWrite: Boolean,
        responseNeeded: Boolean,
        offset: Int,
        value: ByteArray?
    ) {
        val native = NativeBluetoothGattDescriptor(descriptor)
        _event.tryEmit(
            DescriptorWriteRequest(
                RealClientDevice(device!!),
                requestId,
                native,
                preparedWrite,
                responseNeeded,
                offset,
                DataByteArray(value!!)
            )
        )
    }

    override fun onExecuteWrite(device: BluetoothDevice?, requestId: Int, execute: Boolean) {
        _event.tryEmit(ExecuteWrite(RealClientDevice(device!!), requestId, execute))
    }

    override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
        _event.tryEmit(ServerMtuChanged(RealClientDevice(device!!), mtu))
    }

    override fun onNotificationSent(device: BluetoothDevice?, status: Int) {
        _event.tryEmit(NotificationSent(RealClientDevice(device!!), BleGattOperationStatus.create(status)))
    }

    override fun onPhyRead(device: BluetoothDevice?, txPhy: Int, rxPhy: Int, status: Int) {
        _event.tryEmit(
            ServerPhyRead(
                RealClientDevice(device!!),
                BleGattPhy.create(txPhy),
                BleGattPhy.create(rxPhy),
                BleGattOperationStatus.create(status)
            )
        )
    }

    override fun onPhyUpdate(device: BluetoothDevice?, txPhy: Int, rxPhy: Int, status: Int) {
        _event.tryEmit(
            ServerPhyUpdate(
                RealClientDevice(device!!),
                BleGattPhy.create(txPhy),
                BleGattPhy.create(rxPhy),
                BleGattOperationStatus.create(status)
            )
        )
    }

    override fun onServiceAdded(status: Int, service: BluetoothGattService) {
        val native = NativeBluetoothGattService(service)
        val opStatus = BleGattOperationStatus.create(status)
        _event.tryEmit(ServiceAdded(native, opStatus))
        onServiceAdded?.invoke(native, opStatus)
    }

    fun onEvent(event: ServerGattEvent) {
        _event.tryEmit(event)
    }
}
