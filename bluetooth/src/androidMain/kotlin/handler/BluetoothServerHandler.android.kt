package handler

import BleChunker
import ChunkReassembler
import BluetoothConstants.ADVERTISER_UUID
import BluetoothConstants.CCCD_UUID
import BluetoothConstants.CHAR_FROM_CLIENT_UUID
import BluetoothConstants.CHAR_TO_CLIENT_UUID
import BluetoothConstants.CLIENT_DISCONNECT_SIGNAL
import BluetoothConstants.HELLO_PREFIX
import BluetoothConstants.QUALITY_REPORT_PREFIX
import BluetoothConstants.SERVER_STOP_SIGNAL
import BluetoothConstants.SERVICE_UUID
import BluetoothConstants.TAG
import advertisement.AdvertisementSettings
import advertisement.Advertiser
import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import ClientQuality
import com.foodics.crosscommunicationlibrary.logger.CommunicationLogger
import com.foodics.crosscommunicationlibrary.logger.debug
import com.foodics.crosscommunicationlibrary.logger.error
import com.foodics.crosscommunicationlibrary.logger.info
import com.foodics.crosscommunicationlibrary.logger.warn
import rssiToQuality
import rssiToSignalLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
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

    private val _clientQuality = MutableStateFlow<Map<String, Float>>(emptyMap())
    private val _clientMtus = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val _clientSmoothedRssi = mutableMapOf<String, Float>()
    private val emaAlpha = 0.6f
    private val clientReassemblers = mutableMapOf<String, ChunkReassembler>()
    private var serverChunkMsgId: Byte = 0

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
                    _clientQuality.value = _clientQuality.value - id
                    _clientMtus.value = _clientMtus.value - id
                    _clientSmoothedRssi.remove(id)
                    clientReassemblers.remove(id)
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
                if (BleChunker.isChunk(data)) {
                    val reassembler = clientReassemblers.getOrPut(clientId) { ChunkReassembler() }
                    val complete = reassembler.process(data)
                    if (complete != null) {
                        val client = _connectedClients.value[clientId] ?: BleClient(clientId, clientId)
                        _messageFlow.tryEmit(BleMessage(client, complete))
                        logger?.debug(LOG_TITLE, "Chunk reassembled from client", mapOf("client_name" to client.name, "bytes" to complete.size))
                    }
                } else {
                    val text = data.decodeToString()
                    when {
                        text.startsWith(HELLO_PREFIX) -> {
                            val name = text.removePrefix(HELLO_PREFIX).trim()
                            _connectedClients.value = _connectedClients.value + (clientId to BleClient(clientId, name))
                            logger?.info(LOG_TITLE, "Client connected", mapOf("client_id" to clientId, "client_name" to name))
                            Log.i(TAG, "Client $clientId identified as: $name")
                        }
                        text == CLIENT_DISCONNECT_SIGNAL -> {
                            val name = _connectedClients.value[clientId]?.name ?: clientId
                            _connectedClients.value = _connectedClients.value - clientId
                            _clientQuality.value = _clientQuality.value - clientId
                            _clientMtus.value = _clientMtus.value - clientId
                            _clientSmoothedRssi.remove(clientId)
                            clientReassemblers.remove(clientId)
                            logger?.info(LOG_TITLE, "Client disconnected gracefully", mapOf("client_id" to clientId, "client_name" to name))
                            Log.i(TAG, "Client $clientId ($name) disconnected gracefully")
                        }
                        text.startsWith(QUALITY_REPORT_PREFIX) -> {
                            val parts = text.removePrefix(QUALITY_REPORT_PREFIX).split(":")
                            val rawRssi = parts[0].toIntOrNull() ?: Int.MIN_VALUE
                            val mtu = parts.getOrNull(1)?.toIntOrNull()
                            if (rawRssi != Int.MIN_VALUE) {
                                val prev = _clientSmoothedRssi[clientId]
                                _clientSmoothedRssi[clientId] = prev?.let { emaAlpha * rawRssi + (1f - emaAlpha) * it } ?: rawRssi.toFloat()
                            }
                            val rssi = _clientSmoothedRssi[clientId]?.toInt() ?: rawRssi
                            _clientQuality.value = _clientQuality.value + (clientId to rssiToQuality(rssi))
                            if (mtu != null) _clientMtus.value = _clientMtus.value + (clientId to mtu)
                            logger?.debug(LOG_TITLE, "Client quality updated", mapOf("client_id" to clientId, "rssi_dbm" to rssi, "mtu" to (mtu ?: "unknown"), "signal_level" to rssiToSignalLevel(rssi).name))
                        }
                        else -> {
                            val client = _connectedClients.value[clientId] ?: BleClient(clientId, clientId)
                            _messageFlow.tryEmit(BleMessage(client, data))
                            logger?.debug(LOG_TITLE, "Message received from client", mapOf("client_name" to client.name, "bytes" to data.size))
                            Log.i(TAG, "Message from ${client.name}: ${String(data)}")
                        }
                    }
                }
            }
            .launchIn(scope)
    }

    fun clientsQuality(): Flow<List<ClientQuality>> = combine(
        _connectedClients,
        _clientQuality
    ) { clients, quality ->
        clients.values.map { c -> ClientQuality(c.id, c.name, quality[c.id] ?: 0f) }
    }

    private fun serverWritePayloadSize(): Int {
        val minMtu = _clientMtus.value.values.minOrNull() ?: 23
        return (minMtu - 3).coerceAtLeast(20)
    }

    fun permittedSendSize(): Flow<Int> = _clientMtus.map { mtus ->
        if (mtus.isEmpty()) 20 else (mtus.values.min() - 3).coerceAtLeast(1)
    }

    suspend fun sendToClient(data: ByteArray) = sendToClients(data, emptyList())

    suspend fun sendToClients(data: ByteArray, targetIds: List<String>) {
        val targets = if (targetIds.isEmpty()) toClientChars.values
                      else targetIds.mapNotNull { toClientChars[it] }
        val maxPayload = serverWritePayloadSize()
        if (data.size <= maxPayload) {
            logger?.debug(LOG_TITLE, "Sending data to clients", mapOf("client_count" to targets.size, "bytes" to data.size))
            Log.i(TAG, "Sending to ${targets.size} client(s): ${data.size} bytes")
            targets.forEach { it.setValue(data) }
        } else {
            val chunks = BleChunker.buildChunks(data, maxPayload, serverChunkMsgId++)
            logger?.debug(LOG_TITLE, "Sending chunked data to clients", mapOf("client_count" to targets.size, "bytes" to data.size, "chunks" to chunks.size))
            Log.i(TAG, "Sending ${chunks.size} chunks to ${targets.size} client(s)")
            chunks.forEach { chunk -> targets.forEach { it.setValue(chunk) } }
        }
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
            } catch (e: Exception) {
                logger?.warn(LOG_TITLE, "Failed to send stop signal to clients", mapOf("error" to (e.message ?: "unknown")))
            }
            scope.cancel()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            advertiser.stop()
            server.stopServer()
            initializedProfiles.clear()
            toClientChars.clear()
            _connectedClients.value = emptyMap()
            _clientQuality.value = emptyMap()
            _clientMtus.value = emptyMap()
            _clientSmoothedRssi.clear()
            clientReassemblers.clear()
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
