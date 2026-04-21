package com.foodics.crosscommunicationlibrary.bluetooth

import BluetoothConstants.ADVERTISER_UUID
import BluetoothConstants.CHAR_FROM_CLIENT_UUID
import BluetoothConstants.CHAR_TO_CLIENT_UUID
import BluetoothConstants.HELLO_PREFIX
import BluetoothConstants.SERVICE_UUID
import BluetoothConstants.TAG
import advertisement.AdvertisementSettings
import advertisement.Advertiser
import android.content.Context
import android.util.Log
import com.foodics.crosscommunicationlibrary.core.ClientMessage
import com.foodics.crosscommunicationlibrary.core.ConnectedClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import scanner.IoTDevice
import server.*

actual class BluetoothServerHandler(context: Context) {

    private val server: Server = Server(context)
    private val advertiser: Advertiser = Advertiser(context)
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var toClientChar: ServerCharacteristic

    private val initializedProfiles = mutableSetOf<ServerProfile>()
    private val _connectedClients = MutableStateFlow<Map<String, ConnectedClient>>(emptyMap())
    private val _messageFlow = MutableSharedFlow<ClientMessage>(extraBufferCapacity = 64)

    suspend fun start(deviceName: String, identifier: String) {
        stop()
        delay(300)

        Log.i(TAG, "Starting BLE server with name=$deviceName identifier=$identifier")

        val serviceConfig = createServiceConfig()
        server.startServer(listOf(serviceConfig), scope)

        server.connections
            .onEach { map ->
                map.entries.forEach { (device, profile) ->
                    if (initializedProfiles.add(profile)) {
                        val clientId = device.id ?: device.address
                        // Don't add to _connectedClients yet — wait for __HELLO__ to supply the display name.
                        setupProfile(clientId, profile)
                    }
                }
                val activeIds = map.keys.mapNotNull { it.id ?: it.address }.toSet()
                _connectedClients.value = _connectedClients.value.filterKeys { it in activeIds }
            }
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

    private fun setupProfile(clientId: String, profile: ServerProfile) {
        val service = profile.findService(SERVICE_UUID)
            ?: run { Log.e(TAG, "Service $SERVICE_UUID not found"); return }

        val fromClientChar = service.findCharacteristic(CHAR_FROM_CLIENT_UUID)
            ?: run { Log.e(TAG, "Characteristic FROM_CLIENT not found"); return }

        toClientChar = service.findCharacteristic(CHAR_TO_CLIENT_UUID)
            ?: run { Log.e(TAG, "Characteristic TO_CLIENT not found"); return }

        fromClientChar.value
            .onEach { data ->
                val text = data.decodeToString()
                if (text.startsWith(HELLO_PREFIX)) {
                    val name = text.removePrefix(HELLO_PREFIX).trim()
                    _connectedClients.value = _connectedClients.value + (clientId to ConnectedClient(clientId, name))
                    Log.i(TAG, "Client $clientId identified as: $name")
                } else {
                    val client = _connectedClients.value[clientId] ?: ConnectedClient(clientId, clientId)
                    _messageFlow.tryEmit(ClientMessage(client, data))
                    Log.i(TAG, "Message from ${client.name}: ${String(data)}")
                }
            }
            .launchIn(scope)
    }

    suspend fun sendToClient(data: ByteArray) {
        Log.i(TAG, "Sending to client: ${String(data)}")
        toClientChar.setValue(data)
    }

    fun receiveFromClient(): Flow<ByteArray> = _messageFlow.map { it.data }

    fun receiveMessagesFromClient(): Flow<ClientMessage> = _messageFlow.asSharedFlow()

    fun connectedClients(): Flow<List<ConnectedClient>> = _connectedClients.map { it.values.toList() }

    fun clientConnectionState(): Flow<Boolean> = _connectedClients
        .map { it.isNotEmpty() }
        .distinctUntilChanged()

    suspend fun stop() {
        try {
            advertiser.stop()
            server.stopServer()
            initializedProfiles.clear()
            _connectedClients.value = emptyMap()
            Log.i(TAG, "Stopped BLE server & advertiser")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping BLE server", e)
        }
    }

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
