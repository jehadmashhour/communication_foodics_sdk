package handler

import BluetoothConstants.ADVERTISER_UUID
import BluetoothConstants.CCCD_UUID
import BluetoothConstants.CHAR_FROM_CLIENT_UUID
import BluetoothConstants.CHAR_TO_CLIENT_UUID
import BluetoothConstants.HELLO_PREFIX
import BluetoothConstants.SERVER_STOP_SIGNAL
import BluetoothConstants.SERVICE_UUID
import BluetoothConstants.TAG
import advertisement.AdvertisementSettings
import advertisement.Advertiser
import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.foodics.crosscommunicationlibrary.logger.CommunicationLogger
import com.foodics.crosscommunicationlibrary.logger.debug
import com.foodics.crosscommunicationlibrary.logger.error
import com.foodics.crosscommunicationlibrary.logger.info
import com.foodics.crosscommunicationlibrary.logger.warn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import model.BleClient
import model.BleMessage
import server.*

private const val LOG_TITLE = "BLE_SERVER"

@SuppressLint("MissingPermission")
actual class BluetoothServerHandler(
    private val context: Context,
    private val logger: CommunicationLogger?
) {

    private val server: Server = Server(context)
    private val advertiser: Advertiser = Advertiser(context)
    private var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val toClientChars = mutableMapOf<String, ServerCharacteristic>()
    private val initializedProfiles = mutableSetOf<ServerProfile>()
    private val _connectedClients = MutableStateFlow<Map<String, BleClient>>(emptyMap())
    private val _messageFlow = MutableSharedFlow<BleMessage>(extraBufferCapacity = 64)

    suspend fun start(deviceName: String, identifier: String) {
        stop()
        delay(300)
        Log.i(TAG, "Starting BLE server with name=$deviceName identifier=$identifier")
        logger?.info(LOG_TITLE, "Starting BLE server", mapOf("device_name" to deviceName, "identifier" to identifier))

        server.startServer(listOf(createServiceConfig()), scope)

        server.connections
            .onEach { map ->
                map.entries.forEach { (device, profile) ->
                    if (initializedProfiles.add(profile)) {
                        setupProfile(device.id ?: device.address, profile)
                    }
                }
                val activeIds = map.keys.mapNotNull { it.id ?: it.address }.toSet()
                val droppedClients = _connectedClients.value.keys - activeIds
                droppedClients.forEach { id ->
                    val name = _connectedClients.value[id]?.name ?: id
                    logger?.info(LOG_TITLE, "Client disconnected", mapOf("client_id" to id, "client_name" to name))
                    Log.i(TAG, "Client disconnected: $name ($id)")
                }
                _connectedClients.value = _connectedClients.value.filterKeys { it in activeIds }
                toClientChars.keys.retainAll(activeIds)
            }
            .launchIn(scope)

        advertiser.advertise(AdvertisementSettings(name = deviceName, uuid = ADVERTISER_UUID, identifier = identifier))
        logger?.info(LOG_TITLE, "BLE advertising started", mapOf("device_name" to deviceName, "identifier" to identifier))
        Log.i(TAG, "BLE Advertising started with: $deviceName / $identifier")
    }

    private fun setupProfile(clientId: String, profile: ServerProfile) {
        val service = profile.findService(SERVICE_UUID)
            ?: run {
                Log.e(TAG, "Service $SERVICE_UUID not found")
                logger?.error(LOG_TITLE, "BLE service not found during profile setup", extra = mapOf("service_uuid" to SERVICE_UUID))
                return
            }
        val fromClientChar = service.findCharacteristic(CHAR_FROM_CLIENT_UUID)
            ?: run {
                Log.e(TAG, "Characteristic FROM_CLIENT not found")
                logger?.error(LOG_TITLE, "FROM_CLIENT characteristic not found")
                return
            }
        val toClientChar = service.findCharacteristic(CHAR_TO_CLIENT_UUID)
            ?: run {
                Log.e(TAG, "Characteristic TO_CLIENT not found")
                logger?.error(LOG_TITLE, "TO_CLIENT characteristic not found")
                return
            }
        toClientChars[clientId] = toClientChar

        fromClientChar.value
            .onEach { data ->
                val text = data.decodeToString()
                if (text.startsWith(HELLO_PREFIX)) {
                    val name = text.removePrefix(HELLO_PREFIX).trim()
                    _connectedClients.value = _connectedClients.value + (clientId to BleClient(clientId, name))
                    logger?.info(LOG_TITLE, "Client connected", mapOf("client_id" to clientId, "client_name" to name))
                    Log.i(TAG, "Client $clientId identified as: $name")
                } else {
                    val client = _connectedClients.value[clientId] ?: BleClient(clientId, clientId)
                    _messageFlow.tryEmit(BleMessage(client, data))
                    logger?.debug(LOG_TITLE, "Message received from client", mapOf("client_name" to client.name, "bytes" to data.size))
                    Log.i(TAG, "Message from ${client.name}: ${String(data)}")
                }
            }
            .launchIn(scope)
    }

    suspend fun sendToClient(data: ByteArray) = sendToClients(data, emptyList())

    suspend fun sendToClients(data: ByteArray, targetIds: List<String>) {
        val targets = if (targetIds.isEmpty()) toClientChars.values
                      else targetIds.mapNotNull { toClientChars[it] }
        logger?.debug(LOG_TITLE, "Sending data to clients", mapOf("client_count" to targets.size, "bytes" to data.size))
        Log.i(TAG, "Sending to ${targets.size} client(s): ${String(data)}")
        targets.forEach { it.setValue(data) }
    }

    fun receiveFromClient(): Flow<ByteArray> = _messageFlow.map { it.data }

    fun receiveMessagesFromClient(): Flow<BleMessage> = _messageFlow.asSharedFlow()

    fun connectedClients(): Flow<List<BleClient>> = _connectedClients.map { it.values.toList() }

    fun clientConnectionState(): Flow<Boolean> = _connectedClients
        .map { it.isNotEmpty() }
        .distinctUntilChanged()

    suspend fun stop() {
        try {
            try {
                sendToClients(SERVER_STOP_SIGNAL.encodeToByteArray(), emptyList())
                delay(100)
            } catch (_: Exception) { }
            scope.cancel()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            advertiser.stop()
            server.stopServer()
            initializedProfiles.clear()
            toClientChars.clear()
            _connectedClients.value = emptyMap()
            logger?.info(LOG_TITLE, "BLE server stopped")
            Log.i(TAG, "Stopped BLE server & advertiser")
        } catch (e: Exception) {
            logger?.error(LOG_TITLE, "Error stopping BLE server", e)
            Log.e(TAG, "Error stopping BLE server", e)
        }
    }

    private fun createServiceConfig(): BleServerServiceConfig = BleServerServiceConfig(
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
                descriptors = listOf(
                    BleServerDescriptorConfig(
                        uuid = CCCD_UUID,
                        permissions = listOf(GattPermission.READ, GattPermission.WRITE)
                    )
                )
            )
        )
    )
}
