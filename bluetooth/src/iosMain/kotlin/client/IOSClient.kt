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

package client

import BluetoothConstants.ADVERTISER_UUID
import com.benasher44.uuid.uuidFrom
import io.github.aakira.napier.Napier
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import platform.CoreBluetooth.*
import platform.Foundation.*
import platform.darwin.NSObject
import scanner.IoTDevice
import scanner.PeripheralDevice
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import platform.Foundation.NSData

private const val TAG = "BLE-TAG"

@Suppress("CONFLICTING_OVERLOADS")
class IOSClient : NSObject(), CBCentralManagerDelegateProtocol, CBPeripheralDelegateProtocol {

    private lateinit var peripheral: CBPeripheral
    private val manager = CBCentralManager(this, null)

    private var onDeviceConnected: ((DeviceConnectionState) -> Unit)? = null
    private var onDeviceDisconnected: (() -> Unit)? = null
    private var onServicesDiscovered: ((OperationStatus) -> Unit)? = null
    private var services: ClientServices? = null

    private val _scannedDevices = MutableStateFlow<List<IoTDevice>>(emptyList())
    val scannedDevices: StateFlow<List<IoTDevice>> = _scannedDevices.asStateFlow()

    private val _bleState = MutableStateFlow(CBManagerStateUnknown)
    val bleState: StateFlow<CBManagerState> = _bleState.asStateFlow()

    private val _disconnectEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val disconnectEvent: SharedFlow<Unit> = _disconnectEvent.asSharedFlow()

    // deviceName -> Pair(device, lastSeenTime)
    private val devicesMap = mutableMapOf<String, Pair<IoTDevice, Long>>()

    fun scan(): Flow<List<IoTDevice>> = callbackFlow {
        stopScan()

        // Wait until
        // BLE is ON
        bleState.first { it == CBCentralManagerStatePoweredOn }

        val options = mapOf<Any?, Any?>(
            CBCentralManagerScanOptionAllowDuplicatesKey to true
        )

        manager.scanForPeripheralsWithServices(null, options)

        // Cleanup stale devices every second (same as Android)
        val cleanupJob = CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                val now = (NSDate().timeIntervalSince1970 * 1000).toLong()

                val iterator = devicesMap.iterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    val lastSeen = entry.value.second

                    if (now - lastSeen > 5000) {
                        iterator.remove()
                    }
                }

                trySend(devicesMap.values.map { it.first })
                delay(1000)
            }
        }

        awaitClose {
            manager.stopScan()
            cleanupJob.cancel()
        }
    }

    private suspend fun stopScan() {
        manager.stopScan()
        delay(300)
    }
    // ---- CONNECTION -----------------------------------------------------

    suspend fun connect(device: IoTDevice) {
        peripheral = (device.device as PeripheralDevice).peripheral
        peripheral.delegate = this

        bleState.first { it == CBCentralManagerStatePoweredOn }

        return suspendCoroutine { continuation ->
            onDeviceConnected = {
                onDeviceConnected = null
                continuation.resume(Unit)
            }
            manager.connectPeripheral(peripheral, null)
        }
    }

    suspend fun disconnect() {
        return suspendCoroutine { continuation ->
            onDeviceDisconnected = {
                onDeviceDisconnected = null
                continuation.resume(Unit)
            }
            manager.cancelPeripheralConnection(peripheral)
        }
    }

    suspend fun discoverServices(): ClientServices {
        return suspendCoroutine { continuation ->
            onServicesDiscovered = {
                onServicesDiscovered = null
                val nativeServices = peripheral.services?.map { it as CBService } ?: emptyList()
                val clientServices = ClientServices(peripheral, nativeServices)
                services = clientServices
                continuation.resume(clientServices)
            }
            peripheral.discoverServices(null)
        }
    }

    // ---- DISCOVERY ------------------------------------------------------

    override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?) {
        peripheral.services?.map { it as CBService }?.forEach {
            peripheral.discoverCharacteristics(null, it)
        }
    }

    override fun peripheral(
        peripheral: CBPeripheral,
        didDiscoverCharacteristicsForService: CBService,
        error: NSError?
    ) {
        peripheral.services
            ?.map { it as CBService }
            ?.flatMap { service ->
                service.characteristics?.map { it as CBCharacteristic } ?: emptyList()
            }
            ?.forEach {
                peripheral.discoverDescriptorsForCharacteristic(it)
            }
    }

    @ObjCSignatureOverride
    override fun peripheral(
        peripheral: CBPeripheral,
        didDiscoverDescriptorsForCharacteristic: CBCharacteristic,
        error: NSError?
    ) {
        onServicesDiscovered?.invoke(getOperationStatus(error))
    }

    override fun centralManagerDidUpdateState(central: CBCentralManager) {
        _bleState.value = central.state
    }

    @ObjCSignatureOverride
    override fun centralManager(
        central: CBCentralManager,
        didFailToConnectPeripheral: CBPeripheral,
        error: NSError?
    ) {
        onDeviceConnected?.invoke(DeviceDisconnected)
    }

    override fun centralManager(central: CBCentralManager, didConnectPeripheral: CBPeripheral) {
        onDeviceConnected?.invoke(DeviceConnected)
    }

    @ObjCSignatureOverride
    override fun centralManager(
        central: CBCentralManager,
        didDisconnectPeripheral: CBPeripheral,
        error: NSError?
    ) {
        _disconnectEvent.tryEmit(Unit)
        onDeviceDisconnected?.invoke()
    }

    // ---- CHARACTERISTIC EVENTS -----------------------------------------

    @ObjCSignatureOverride
    override fun peripheral(
        peripheral: CBPeripheral,
        didUpdateValueForCharacteristic: CBCharacteristic,
        error: NSError?
    ) {
        services?.onEvent(
            OnGattCharacteristicRead(
                peripheral,
                didUpdateValueForCharacteristic.value,
                error
            )
        )
    }

    @ObjCSignatureOverride
    override fun peripheral(
        peripheral: CBPeripheral,
        didWriteValueForCharacteristic: CBCharacteristic,
        error: NSError?
    ) {
        services?.onEvent(
            OnGattCharacteristicWrite(
                peripheral,
                didWriteValueForCharacteristic.value,
                error
            )
        )
    }

    @ObjCSignatureOverride
    override fun peripheral(
        peripheral: CBPeripheral,
        didWriteValueForDescriptor: CBDescriptor,
        error: NSError?
    ) {
        services?.onEvent(
            OnGattDescriptorWrite(
                peripheral,
                didWriteValueForDescriptor.value as NSData?,
                error
            )
        )
    }

    @ObjCSignatureOverride
    override fun peripheral(
        peripheral: CBPeripheral,
        didUpdateValueForDescriptor: CBDescriptor,
        error: NSError?
    ) {
        services?.onEvent(
            OnGattDescriptorRead(
                peripheral,
                didUpdateValueForDescriptor.value as NSData?,
                error
            )
        )
    }

    // ---- MAIN SCAN CALLBACK (FIXED VERSION) -----------------------------

    override fun centralManager(
        central: CBCentralManager,
        didDiscoverPeripheral: CBPeripheral,
        advertisementData: Map<Any?, *>,
        RSSI: NSNumber
    ) {
        // Match on service UUID list OR service data UUID (iOS peripheral puts payload in service data)
        val serviceUUIDs = advertisementData[CBAdvertisementDataServiceUUIDsKey] as? List<CBUUID>
        val serviceDataMap = advertisementData[CBAdvertisementDataServiceDataKey] as? Map<CBUUID, NSData>

        val matchesServiceUUID = serviceUUIDs?.any {
            try { it.toUuid() == ADVERTISER_UUID } catch (_: Exception) { false }
        } == true
        val matchesServiceData = serviceDataMap?.keys?.any {
            try { it.toUuid() == ADVERTISER_UUID } catch (_: Exception) { false }
        } == true

        if (!matchesServiceUUID && !matchesServiceData) return

        val device = PeripheralDevice(didDiscoverPeripheral)

        // ---- 1. Service data (your custom payload) ----
        val serviceData =
            serviceDataMap?.get(ADVERTISER_UUID.toCBUUID())?.toByteArray()?.decodeToString()

        // With split advertising (serviceUuid in adv PDU, serviceData in scan response), iOS fires
        // this callback twice. Skip the first event (no serviceData yet) to avoid a temporary
        // "Unknown" entry that would appear until the scan response arrives.
        if (serviceData == null) return

        val nameFromServiceData = serviceData?.split("|")?.firstOrNull()
        val identifierFromServiceData = serviceData?.split("|")?.getOrNull(1)

        // ---- 2. Local Name from advertisement ----
        val localName = advertisementData[CBAdvertisementDataLocalNameKey] as? String

        val nameFromLocalName = localName?.split("|")?.firstOrNull()
        val identifierFromLocalName = localName?.split("|")?.getOrNull(1)

        // ---- 3. Peripheral.name (iOS cached GAP name) ----
        val nameFromPeripheral = device.name.split("|").firstOrNull()
        val identifierFromPeripheral = device.name.split("|").getOrNull(1)

        // ---- FINAL VALUES with fallback priority ----
        val finalName =
            nameFromServiceData
                ?: nameFromLocalName
                ?: nameFromPeripheral
                ?: device.name                      // last fallback, raw

        val finalIdentifier =
            identifierFromServiceData
                ?: identifierFromLocalName
                ?: identifierFromPeripheral
                ?: device.address                   // last fallback

        val ioTDevice = IoTDevice(
            id = finalIdentifier,
            device = device,
            connectionType = ConnectionType.BLUETOOTH,
            deviceName = finalName
        )

        val now = (NSDate().timeIntervalSince1970 * 1000).toLong()

        // Refresh device timestamp every advertisement
        devicesMap[finalIdentifier] = ioTDevice to now

        // Immediately update flow
        _scannedDevices.value = devicesMap.values.map { it.first }
    }


    private fun getOperationStatus(error: NSError?): OperationStatus {
        return error?.let { OperationError } ?: OperationSuccess
    }
}
