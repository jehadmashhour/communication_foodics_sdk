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

package scanner

import platform.CoreBluetooth.CBCentral
import platform.CoreBluetooth.CBPeripheral
import ConnectionType

actual data class IoTDevice(
    actual val name: String,
    actual val address: String,
    actual val connectionType: ConnectionType?,
    actual val id: String?
) {
    internal var device: NativeDevice? = null

    constructor(
        device: NativeDevice,
        id: String? = null,
        deviceName: String? = null,
        connectionType: ConnectionType? = null
    ) : this(
        name = deviceName ?: device.name,
        address = device.address,
        connectionType = connectionType,
        id = id
    ) {
        this.device = device
    }
}


sealed interface NativeDevice {

    val name: String
    val address: String

    companion object {
        var counter: Int = 0
    }
}

data class PeripheralDevice(internal val peripheral: CBPeripheral) : NativeDevice {

    override val name: String
        get() = peripheral.name ?: "Unknown"

    override val address: String
        get() = "00:00:00:00:${NativeDevice.counter++}"
}

data class CentralDevice(internal val central: CBCentral) : NativeDevice {

    override val name: String
        get() = "No name"

    override val address: String
        get() = "00:00:00:00:${NativeDevice.counter++}"
}
