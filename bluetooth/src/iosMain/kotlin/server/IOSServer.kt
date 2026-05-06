package server

import BluetoothConstants
import advertisement.AdvertisementSettings
import client.toCBUUID
import client.toByteArray
import client.toNSData
import client.toUuid
import com.benasher44.uuid.Uuid
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import platform.CoreBluetooth.CBATTErrorSuccess
import platform.CoreBluetooth.CBATTRequest
import platform.CoreBluetooth.CBAdvertisementDataLocalNameKey
import platform.CoreBluetooth.CBAdvertisementDataServiceDataKey
import platform.CoreBluetooth.CBAdvertisementDataServiceUUIDsKey
import platform.CoreBluetooth.CBAttributePermissions
import platform.CoreBluetooth.CBAttributePermissionsReadable
import platform.CoreBluetooth.CBAttributePermissionsWriteable
import platform.CoreBluetooth.CBCentral
import platform.CoreBluetooth.CBCentralManagerStatePoweredOn
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBCharacteristicProperties
import platform.CoreBluetooth.CBCharacteristicPropertyIndicate
import platform.CoreBluetooth.CBCharacteristicPropertyNotify
import platform.CoreBluetooth.CBCharacteristicPropertyRead
import platform.CoreBluetooth.CBCharacteristicPropertyWrite
import platform.CoreBluetooth.CBMutableCharacteristic
import platform.CoreBluetooth.CBMutableDescriptor
import platform.CoreBluetooth.CBMutableService
import platform.CoreBluetooth.CBPeripheralManager
import platform.CoreBluetooth.CBPeripheralManagerDelegateProtocol
import platform.CoreBluetooth.CBPeripheralManagerState
import platform.CoreBluetooth.CBPeripheralManagerStateUnknown
import platform.CoreBluetooth.CBService
import platform.Foundation.NSError
import platform.darwin.NSObject
import scanner.CentralDevice
import scanner.IoTDevice

private const val TAG = "BLE-TAG"

@Suppress("CONFLICTING_OVERLOADS")
class IOSServer(
    private val notificationsRecords: NotificationsRecords,
) : NSObject(), CBPeripheralManagerDelegateProtocol {
    private val manager = CBPeripheralManager(this, null)

    private val _bleState = MutableStateFlow(CBPeripheralManagerStateUnknown)
    val bleState: StateFlow<CBPeripheralManagerState> = _bleState.asStateFlow()

    private val _connections = MutableStateFlow<Map<CBCentral, ServerProfile>>(emptyMap())
    val connections: Flow<Map<IoTDevice, ServerProfile>> = _connections.map {
        it.mapKeys { IoTDevice(CentralDevice(it.key)) }
    }

    // Direct write events: (centralId, characteristicUuid, data) — __HELLO__ messages are consumed here and never emitted.
    private val _receivedWrites = MutableSharedFlow<Triple<String, Uuid, ByteArray>>(extraBufferCapacity = 64)
    val receivedWrites: SharedFlow<Triple<String, Uuid, ByteArray>> = _receivedWrites.asSharedFlow()

    // centralId → display name (id used as name until __HELLO__ arrives)
    private val _clientNames = MutableStateFlow<Map<String, String>>(emptyMap())
    val clientNames: StateFlow<Map<String, String>> = _clientNames.asStateFlow()

    // Emits (centralId, rssiDbm, mtu?) for each __QREP__ received — used by BluetoothServerHandler for quality.
    private val _qualityReportEvents = MutableSharedFlow<Triple<String, Int, Int?>>(extraBufferCapacity = 64)
    val qualityReportEvents: SharedFlow<Triple<String, Int, Int?>> = _qualityReportEvents.asSharedFlow()

    // Emits the centralId whenever a central unsubscribes (used for bridge disconnect detection)
    private val _centralDisconnectedEvent = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val centralDisconnectedEvent: SharedFlow<String> = _centralDisconnectedEvent.asSharedFlow()

    private var services = listOf<CBService>()

    override fun peripheralManagerDidUpdateState(peripheral: CBPeripheralManager) {
        _bleState.value = peripheral.state
    }

    override fun peripheralManagerDidStartAdvertising(
        peripheral: CBPeripheralManager,
        error: NSError?
    ) {
        if (error != null) {
            println("[$TAG] peripheralManagerDidStartAdvertising ERROR: ${error.localizedDescription} code=${error.code}")
        } else {
            println("[$TAG] peripheralManagerDidStartAdvertising OK")
        }
    }

    override fun peripheralManagerIsReadyToUpdateSubscribers(peripheral: CBPeripheralManager) {
        println("[$TAG] peripheralManagerIsReadyToUpdateSubscribers")
    }

    override fun peripheralManager(
        peripheral: CBPeripheralManager,
        didReceiveReadRequest: CBATTRequest
    ) {
        println("[$TAG] peripheralManager:didReceiveReadRequest: central=${didReceiveReadRequest.central.identifier.UUIDString} char=${didReceiveReadRequest.characteristic().UUID.UUIDString}")
        val central = didReceiveReadRequest.central
        val profile = getProfile(central)

        profile.onEvent(ReadRequest(didReceiveReadRequest))
    }

    override fun peripheralManager(
        peripheral: CBPeripheralManager,
        didReceiveWriteRequests: List<*>
    ) {
        try {
            val requests = didReceiveWriteRequests.map { it as CBATTRequest }
            requests.forEach { request ->
                val data = request.value?.toByteArray() ?: byteArrayOf()
                if (data.isNotEmpty()) {
                    val centralId = request.central.identifier.UUIDString
                    val text = data.decodeToString()
                    when {
                        text.startsWith(BluetoothConstants.HELLO_PREFIX) -> {
                            val name = text.removePrefix(BluetoothConstants.HELLO_PREFIX)
                            println("[$TAG] peripheralManager:didReceiveWriteRequests: HELLO from central=$centralId name=$name")
                            _clientNames.value = _clientNames.value + (centralId to name)
                        }
                        text == BluetoothConstants.CLIENT_DISCONNECT_SIGNAL -> {
                            println("[$TAG] peripheralManager:didReceiveWriteRequests: CSTOP from central=$centralId")
                            _clientNames.value = _clientNames.value - centralId
                            _centralDisconnectedEvent.tryEmit(centralId)
                        }
                        text.startsWith(BluetoothConstants.QUALITY_REPORT_PREFIX) -> {
                            val parts = text.removePrefix(BluetoothConstants.QUALITY_REPORT_PREFIX).split(":")
                            val rssi = parts[0].toIntOrNull() ?: Int.MIN_VALUE
                            val mtu = parts.getOrNull(1)?.toIntOrNull()
                            _qualityReportEvents.tryEmit(Triple(centralId, rssi, mtu))
                        }
                        else -> {
                            println("[$TAG] peripheralManager:didReceiveWriteRequests: data from central=$centralId bytes=${data.size}")
                            _receivedWrites.tryEmit(Triple(centralId, request.characteristic().UUID.toUuid(), data))
                        }
                    }
                }
            }
            requests.firstOrNull()?.let { manager.respondToRequest(it, CBATTErrorSuccess) }
        } catch (t: Throwable) {
            println("[$TAG] peripheralManager:didReceiveWriteRequests ERROR: ${t.message}")
        }
    }

    @ObjCSignatureOverride
    override fun peripheralManager(
        peripheral: CBPeripheralManager,
        central: CBCentral,
        didSubscribeToCharacteristic: CBCharacteristic
    ) {
        println("[$TAG] peripheralManager:didSubscribeToCharacteristic: central=${central.identifier.UUIDString} char=${didSubscribeToCharacteristic.UUID.UUIDString} maximumUpdateValueLength=${central.maximumUpdateValueLength}")
        notificationsRecords.addCentral(didSubscribeToCharacteristic.UUID.toUuid(), central)
        // Don't add to _clientNames yet — wait for __HELLO__ to supply the display name.
        getProfile(central)
    }

    @ObjCSignatureOverride
    override fun peripheralManager(
        peripheral: CBPeripheralManager,
        central: CBCentral,
        didUnsubscribeFromCharacteristic: CBCharacteristic
    ) {
        println("[$TAG] peripheralManager:didUnsubscribeFromCharacteristic: central=${central.identifier.UUIDString} char=${didUnsubscribeFromCharacteristic.UUID.UUIDString}")
        notificationsRecords.removeCentral(didUnsubscribeFromCharacteristic.UUID.toUuid(), central)
        val centralId = central.identifier.UUIDString
        _clientNames.value = _clientNames.value - centralId
        _centralDisconnectedEvent.tryEmit(centralId)
    }

    override fun peripheralManager(
        peripheral: CBPeripheralManager,
        didAddService: CBService,
        error: NSError?
    ) {
        if (error != null) {
            println("[$TAG] peripheralManager:didAddService ERROR: service=${didAddService.UUID.UUIDString} error=${error.localizedDescription} code=${error.code}")
        } else {
            println("[$TAG] peripheralManager:didAddService OK: service=${didAddService.UUID.UUIDString}")
        }
        services = services + didAddService
    }

    suspend fun advertise(settings: AdvertisementSettings) {
        bleState.first { it == CBCentralManagerStatePoweredOn }
        val combinedData = "${settings.name}|${settings.identifier}"

        // CBAdvertisementDataServiceDataKey puts the UUID + payload in the MAIN advertisement
        // packet, guaranteeing Android scanners can match on it even without a scan response.
        // CBAdvertisementDataLocalNameKey goes to the scan response on iOS, which is not
        // reliably received by Android hardware scan filters.
        val map: Map<Any?, Any> = mapOf(
            CBAdvertisementDataServiceUUIDsKey to listOf(settings.uuid.toCBUUID()),
            CBAdvertisementDataLocalNameKey to combinedData
        )
        manager.startAdvertising(map)
    }

    suspend fun startServer(services: List<BleServerServiceConfig>) {
        bleState.first { it == CBCentralManagerStatePoweredOn }
        val iosServices = services.map { serviceConfig ->
            val characteristics = serviceConfig.characteristics.map { charConfig ->
                val descriptors = charConfig.descriptors.map { descConfig ->
                    CBMutableDescriptor(descConfig.uuid.toCBUUID(), null)
                }
                CBMutableCharacteristic(
                    charConfig.uuid.toCBUUID(),
                    charConfig.properties.toDomain(),
                    null,
                    charConfig.permissions.toDomain()
                ).also { char -> char.setDescriptors(descriptors) }
            }
            CBMutableService(serviceConfig.uuid.toCBUUID(), true)
                .also { svc -> svc.setCharacteristics(characteristics) }
        }

        iosServices.forEach {
            manager.addService(it)
        }
    }

    suspend fun stopAdvertising() {
        manager.stopAdvertising()
    }

    suspend fun stopServer() {
        manager.removeAllServices()
        services = listOf()
        manager.stopAdvertising()
    }

    fun resetState() {
        _clientNames.value = emptyMap()
        _connections.value = emptyMap()
        notificationsRecords.clear()
        // _qualityReportEvents and _centralDisconnectedEvent are shared flows — they don't need clearing.
    }

    // Looks up the centralId for a device, trying two strategies:
    // 1. UUID match (peripheral.identifier == central.identifier for the same Android device, if the BLE address is stable).
    // 2. Name match via the HELLO message — reliable because Android advertises and sends HELLO with the same display name.
    fun centralIdForDevice(peripheralId: String, deviceName: String): String? =
        _connections.value.keys.firstOrNull { it.identifier.UUIDString == peripheralId }?.identifier?.UUIDString
            ?: _clientNames.value.entries.firstOrNull { (_, name) -> name == deviceName }?.key

    fun sendToSubscribers(charUuid: Uuid, data: ByteArray) = sendToSubscribers(charUuid, data, emptyList())

    fun sendToSubscribers(charUuid: Uuid, data: ByteArray, targetIds: List<String>) {
        val allCentrals = notificationsRecords.getCentrals(charUuid)
        val centrals = if (targetIds.isEmpty()) allCentrals
                       else allCentrals.filter { it.identifier.UUIDString in targetIds }
        if (centrals.isEmpty()) {
            println("[$TAG] sendToSubscribers: no matching subscribers for $charUuid")
            return
        }
        val cbChar = services
            .flatMap { svc -> svc.characteristics?.mapNotNull { it as? CBMutableCharacteristic } ?: emptyList() }
            .firstOrNull { it.UUID.toUuid() == charUuid }
            ?: run { println("[$TAG] sendToSubscribers: characteristic $charUuid not found"); return }
        @Suppress("UNCHECKED_CAST")
        manager.updateValue(data.toNSData(), cbChar, centrals as List<CBCentral>)
    }

    private fun getProfile(central: CBCentral): ServerProfile {
        return _connections.value.getOrElse(central) {
            val profile = ServerProfile(services, manager, notificationsRecords)
            _connections.value = _connections.value + (central to profile)
            profile
        }
    }

    private fun List<GattPermission>.toDomain(): CBAttributePermissions {
        return this.map {
            when (it) {
                GattPermission.READ -> CBAttributePermissionsReadable
                GattPermission.WRITE -> CBAttributePermissionsWriteable
            }
        }.reduce { acc, permission -> acc or permission }
    }

    private fun List<GattProperty>.toDomain(): CBCharacteristicProperties {
        return this.map {
            when (it) {
                GattProperty.READ -> CBCharacteristicPropertyRead
                GattProperty.WRITE -> CBCharacteristicPropertyWrite
                GattProperty.NOTIFY -> CBCharacteristicPropertyNotify
                GattProperty.INDICATE -> CBCharacteristicPropertyIndicate
            }
        }.reduce { acc, permission -> acc or permission }
    }
}
