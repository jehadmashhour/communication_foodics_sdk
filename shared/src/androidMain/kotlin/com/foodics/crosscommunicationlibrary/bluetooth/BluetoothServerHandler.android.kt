package com.foodics.crosscommunicationlibrary.bluetooth

import BluetoothConstants.ADVERTISER_UUID
import BluetoothConstants.CHAR_FROM_CLIENT_UUID
import BluetoothConstants.CHAR_TO_CLIENT_UUID
import BluetoothConstants.SERVICE_UUID
import BluetoothConstants.TAG
import advertisement.AdvertisementSettings
import advertisement.Advertiser
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import server.*

actual class BluetoothServerHandler(context: Context) {

    private val server: Server = Server(context)
    private val advertiser: Advertiser = Advertiser(context)

    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var toClientChar: ServerCharacteristic
    private lateinit var fromClientChar: ServerCharacteristic

    private val _fromClientFlow = MutableStateFlow<ByteArray?>(null)
    val fromClientFlow: Flow<ByteArray> = _fromClientFlow.filterNotNull()

    /**
     * Start the BLE server + advertiser
     */
    suspend fun start(deviceName: String, identifier: String) {
        stop()
        delay(300) // small delay to allow BLE stack reset

        Log.i(TAG, "Starting BLE server with name=$deviceName identifier=$identifier")

        val serviceConfig = createServiceConfig()
        server.startServer(listOf(serviceConfig), scope)

        server.connections
            .onEach { map -> map.values.forEach(::setupProfile) }
            .launchIn(scope)

        advertiser.advertise(
            AdvertisementSettings(
                name = deviceName,
                uuid = ADVERTISER_UUID,
                identifier = identifier
            )
        )

        Log.i(TAG, "BLE Advertising started with: $deviceName / $identifier")
    }

    /**
     * Initialize BLE characteristics for a connected client
     */
    private fun setupProfile(profile: ServerProfile) {
        val service = profile.findService(SERVICE_UUID)
            ?: throw Exception("Service $SERVICE_UUID not found in server profile")

        fromClientChar = service.findCharacteristic(CHAR_FROM_CLIENT_UUID)
            ?: throw Exception("Characteristic FROM_CLIENT not found")

        toClientChar = service.findCharacteristic(CHAR_TO_CLIENT_UUID)
            ?: throw Exception("Characteristic TO_CLIENT not found")

        Log.i(TAG, "Server characteristics initialized successfully")

        fromClientChar.value
            .onEach { data ->
                _fromClientFlow.value = data
                Log.i(TAG, "Received from client: ${String(data)}")
            }
            .launchIn(scope)

        toClientChar.value
            .onEach { data ->
                Log.i(TAG, "Notifying client: ${String(data)}")
            }
            .launchIn(scope)
    }

    /**
     * Send data to connected client
     */
    suspend fun sendToClient(data: ByteArray) {
        Log.i(TAG, "Sending to client: ${String(data)}")
        toClientChar.setValue(data)
    }

    /**
     * Public receive flow for SDK
     */
    fun receiveFromClient(): Flow<ByteArray> = fromClientFlow

    /**
     * Stop server & advertiser
     */
    suspend fun stop() {
        try {
            advertiser.stop()
            server.stopServer()
            Log.i(TAG, "Stopped BLE server & advertiser")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping BLE server", e)
        }
    }

    /**
     * Defines the services & characteristics exposed by your BLE server
     */
    private fun createServiceConfig(): BleServerServiceConfig =
        BleServerServiceConfig(
            uuid = SERVICE_UUID,
            characteristics = listOf(
                BleServerCharacteristicConfig(
                    uuid = CHAR_FROM_CLIENT_UUID,
                    properties = listOf(GattProperty.READ, GattProperty.WRITE),
                    permissions = listOf(GattPermission.READ, GattPermission.WRITE),
                    descriptors = emptyList()
                ),
                BleServerCharacteristicConfig(
                    uuid = CHAR_TO_CLIENT_UUID,
                    properties = listOf(GattProperty.READ, GattProperty.NOTIFY),
                    permissions = listOf(GattPermission.READ, GattPermission.WRITE),
                    descriptors = emptyList()
                )
            )
        )
}
