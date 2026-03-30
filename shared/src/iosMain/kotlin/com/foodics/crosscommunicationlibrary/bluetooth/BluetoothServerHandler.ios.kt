package com.foodics.crosscommunicationlibrary.bluetooth

import BluetoothConstants.CHAR_FROM_CLIENT_UUID
import BluetoothConstants.CHAR_TO_CLIENT_UUID
import BluetoothConstants.SERVICE_UUID
import BluetoothConstants.ADVERTISER_UUID
import advertisement.AdvertisementSettings
import advertisement.Advertiser
import advertisement.IOSServer
import advertisement.IOSServerWrapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import server.*

actual class BluetoothServerHandler() {

    private val iosServerWrapper = IOSServerWrapper(IOSServer(NotificationsRecords()))
    private val server: Server = Server(iosServerWrapper)
    private val advertiser: Advertiser = Advertiser(iosServerWrapper)
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var toClientChar: ServerCharacteristic
    private lateinit var fromClientChar: ServerCharacteristic

    private val _fromClientFlow = MutableStateFlow<ByteArray?>(null)
    val fromClientFlow: Flow<ByteArray> = _fromClientFlow.filterNotNull()

    suspend fun start(deviceName: String, identifier: String) {
        stop()
        delay(300)

        val serviceConfig = createServiceConfig()
        server.startServer(listOf(serviceConfig), scope)

        server.connections
            .onEach { map -> map.values.forEach(::setupProfile) }
            .launchIn(scope)

        advertiser.advertise(
            AdvertisementSettings(
                name = deviceName,
                identifier = identifier,
                uuid = ADVERTISER_UUID
            )
        )
    }

    private fun setupProfile(profile: ServerProfile) {
        val service = profile.findService(SERVICE_UUID)
            ?: throw Exception("Bluetooth service with UUID $SERVICE_UUID not found in server profile")

        fromClientChar = service.findCharacteristic(CHAR_FROM_CLIENT_UUID)
            ?: throw Exception(
                "Required 'from-client' characteristic ($CHAR_FROM_CLIENT_UUID) not found in service $SERVICE_UUID"
            )

        toClientChar = service.findCharacteristic(CHAR_TO_CLIENT_UUID)
            ?: throw Exception(
                "Required 'to-client' characteristic ($CHAR_TO_CLIENT_UUID) not found in service $SERVICE_UUID"
            )

//        Log.i(
//            com.foodics.crosscommunicationlibrary.channel.bluetooth.BluetoothConstants.TAG,
//            "Server characteristics initialized successfully"
//        )

        fromClientChar.value
            .onEach { _fromClientFlow.value = it }
            .launchIn(scope)

        toClientChar.value.onEach {
//            Log.i(
//                com.foodics.crosscommunicationlibrary.channel.bluetooth.BluetoothConstants.TAG,
//                "Sending to client: ${String(it)}"
//            )
        }.launchIn(scope)
    }

    suspend fun sendToClient(data: ByteArray) = toClientChar.setValue(data)

    fun receiveFromClient(): Flow<ByteArray> {
        return fromClientFlow
    }

    suspend fun stop() {
        advertiser.stop()
        server.stopServer()
    }

    private fun createServiceConfig(): BleServerServiceConfig = BleServerServiceConfig(
        SERVICE_UUID,
        listOf(
            BleServerCharacteristicConfig(
                CHAR_FROM_CLIENT_UUID,
                listOf(GattProperty.READ, GattProperty.WRITE),
                listOf(GattPermission.READ, GattPermission.WRITE),
                emptyList()
            ),
            BleServerCharacteristicConfig(
                CHAR_TO_CLIENT_UUID,
                listOf(GattProperty.READ, GattProperty.NOTIFY),
                listOf(GattPermission.READ, GattPermission.WRITE),
                emptyList()
            )
        )
    )
}
