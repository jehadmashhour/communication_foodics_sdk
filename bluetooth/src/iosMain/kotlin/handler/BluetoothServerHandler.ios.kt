package handler

import BleChunker
import ChunkReassembler
import BluetoothConstants.ADVERTISER_UUID
import BluetoothConstants.CHAR_FROM_CLIENT_UUID
import BluetoothConstants.CHAR_TO_CLIENT_UUID
import BluetoothConstants.SERVER_STOP_SIGNAL
import BluetoothConstants.SERVICE_UUID
import advertisement.AdvertisementSettings
import advertisement.Advertiser
import ClientQuality
import com.foodics.crosscommunicationlibrary.logger.CommunicationLogger
import com.foodics.crosscommunicationlibrary.logger.debug
import com.foodics.crosscommunicationlibrary.logger.info
import com.foodics.crosscommunicationlibrary.logger.warn
import model.BleClient
import model.BleMessage
import rssiToQuality
import rssiToSignalLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import server.*

private const val LOG_TITLE = "BLE_SERVER"

actual class BluetoothServerHandler(
    private val logger: CommunicationLogger?
) {

    private val iosServer = IOSServer(NotificationsRecords())
    private val advertiser: Advertiser = Advertiser(iosServer)
    private val server: Server = Server(iosServer)
    private var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        logger?.info(LOG_TITLE, "Starting BLE server", mapOf("device_name" to deviceName, "identifier" to identifier))

        server.startServer(listOf(createServiceConfig()), scope)

        iosServer.receivedWrites
            .filter { (_, charUuid, _) -> charUuid == CHAR_FROM_CLIENT_UUID }
            .onEach { (centralId, _, data) ->
                if (BleChunker.isChunk(data)) {
                    val reassembler = clientReassemblers.getOrPut(centralId) { ChunkReassembler() }
                    val complete = reassembler.process(data)
                    if (complete != null) {
                        val name = iosServer.clientNames.value[centralId] ?: centralId
                        logger?.debug(LOG_TITLE, "Chunk reassembled from client", mapOf("client_name" to name, "bytes" to complete.size))
                        _messageFlow.tryEmit(BleMessage(BleClient(centralId, name), complete))
                    }
                } else {
                    val name = iosServer.clientNames.value[centralId] ?: centralId
                    logger?.debug(LOG_TITLE, "Message received from client", mapOf("client_name" to name, "bytes" to data.size))
                    _messageFlow.tryEmit(BleMessage(BleClient(centralId, name), data))
                }
            }
            .launchIn(scope)

        var previousIds = emptySet<String>()
        iosServer.clientNames
            .onEach { current ->
                val currentIds = current.keys.toSet()
                (currentIds - previousIds).forEach { id ->
                    logger?.info(LOG_TITLE, "Client connected", mapOf("client_id" to id, "client_name" to (current[id] ?: id)))
                }
                (previousIds - currentIds).forEach { id ->
                    logger?.info(LOG_TITLE, "Client disconnected", mapOf("client_id" to id))
                    _clientQuality.value = _clientQuality.value - id
                    _clientMtus.value = _clientMtus.value - id
                    _clientSmoothedRssi.remove(id)
                    clientReassemblers.remove(id)
                }
                previousIds = currentIds
            }
            .launchIn(scope)

        iosServer.qualityReportEvents
            .onEach { (centralId, rawRssi, mtu) ->
                if (rawRssi != Int.MIN_VALUE) {
                    val prev = _clientSmoothedRssi[centralId]
                    _clientSmoothedRssi[centralId] = prev?.let { emaAlpha * rawRssi + (1f - emaAlpha) * it } ?: rawRssi.toFloat()
                }
                val rssi = _clientSmoothedRssi[centralId]?.toInt() ?: rawRssi
                _clientQuality.value = _clientQuality.value + (centralId to rssiToQuality(rssi))
                if (mtu != null) _clientMtus.value = _clientMtus.value + (centralId to mtu)
                val name = iosServer.clientNames.value[centralId] ?: centralId
                logger?.debug(LOG_TITLE, "Client quality updated", mapOf("client_name" to name, "rssi_dbm" to rssi, "mtu" to (mtu ?: "unknown"), "signal_level" to rssiToSignalLevel(rssi).name))
            }
            .launchIn(scope)

        advertiser.advertise(AdvertisementSettings(name = deviceName, identifier = identifier, uuid = ADVERTISER_UUID))
        logger?.info(LOG_TITLE, "BLE advertising started", mapOf("device_name" to deviceName))
    }

    fun clientsQuality(): Flow<List<ClientQuality>> = combine(
        iosServer.clientNames,
        _clientQuality
    ) { names, quality ->
        names.entries.map { (id, name) -> ClientQuality(id, name, quality[id] ?: 0f) }
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
        val maxPayload = serverWritePayloadSize()
        if (data.size <= maxPayload) {
            logger?.debug(LOG_TITLE, "Sending data to subscribers", mapOf("bytes" to data.size))
            iosServer.sendToSubscribers(CHAR_TO_CLIENT_UUID, data, targetIds)
        } else {
            val chunks = BleChunker.buildChunks(data, maxPayload, serverChunkMsgId++)
            logger?.debug(LOG_TITLE, "Sending chunked data to subscribers", mapOf("bytes" to data.size, "chunks" to chunks.size))
            chunks.forEach { chunk -> iosServer.sendToSubscribers(CHAR_TO_CLIENT_UUID, chunk, targetIds) }
        }
    }

    fun receiveFromClient(): Flow<ByteArray> = _messageFlow.map { it.data }

    fun receiveMessagesFromClient(): Flow<BleMessage> = _messageFlow.asSharedFlow()

    fun connectedClients(): Flow<List<BleClient>> = iosServer.clientNames
        .map { map -> map.entries.map { (id, name) -> BleClient(id, name) } }

    fun clientConnectionState(): Flow<Boolean> = iosServer.clientNames.map { it.isNotEmpty() }

    // Returns the centralId if the given device (identified by its peripheral UUID from scanning)
    // is already connected to this iOS peripheral manager — used for bridge-mode detection.
    fun bridgeCentralId(deviceId: String, deviceName: String): String? = iosServer.centralIdForDevice(deviceId, deviceName)

    // Flow of raw bytes from a specific client, used when iOS acts as client via the server bridge.
    // Throws when the client unsubscribes (disconnects).
    fun receiveFromClientAsServer(centralId: String): Flow<ByteArray> = merge(
        iosServer.receivedWrites
            .filter { (id, charUuid, _) -> id == centralId && charUuid == CHAR_FROM_CLIENT_UUID }
            .map { (_, _, data) -> data },
        iosServer.centralDisconnectedEvent
            .filter { it == centralId }
            .take(1)
            .onEach {
                logger?.warn(LOG_TITLE, "Bridge connection lost", mapOf("central_id" to centralId))
                throw Exception("Bridge connection lost")
            }
            .map { byteArrayOf() }
    )

    suspend fun stop() {
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
        iosServer.resetState()
        _clientQuality.value = emptyMap()
        _clientMtus.value = emptyMap()
        _clientSmoothedRssi.clear()
        clientReassemblers.clear()
        logger?.info(LOG_TITLE, "BLE server stopped")
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
