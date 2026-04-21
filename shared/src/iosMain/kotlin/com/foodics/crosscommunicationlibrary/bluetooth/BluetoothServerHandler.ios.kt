package com.foodics.crosscommunicationlibrary.bluetooth

import BluetoothConstants.ADVERTISER_UUID
import BluetoothConstants.CHAR_FROM_CLIENT_UUID
import BluetoothConstants.CHAR_TO_CLIENT_UUID
import BluetoothConstants.SERVICE_UUID
import advertisement.AdvertisementSettings
import advertisement.Advertiser
import advertisement.IOSServer
import advertisement.IOSServerWrapper
import com.foodics.crosscommunicationlibrary.core.ClientMessage
import com.foodics.crosscommunicationlibrary.core.ConnectedClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import server.*

actual class BluetoothServerHandler() {

    private val iosServer = IOSServer(NotificationsRecords())
    private val iosServerWrapper = IOSServerWrapper(iosServer)
    private val advertiser: Advertiser = Advertiser(iosServerWrapper)
    private val server: Server = Server(iosServerWrapper)
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _messageFlow = MutableSharedFlow<ClientMessage>(extraBufferCapacity = 64)
    private var receivedWritesJob: Job? = null

    suspend fun start(deviceName: String, identifier: String) {
        stop()
        delay(300)

        val serviceConfig = createServiceConfig()
        server.startServer(listOf(serviceConfig), scope)

        receivedWritesJob = iosServer.receivedWrites
            .filter { (_, charUuid, _) -> charUuid == CHAR_FROM_CLIENT_UUID }
            .onEach { (centralId, _, data) ->
                val name = iosServer.clientNames.value[centralId] ?: centralId
                _messageFlow.tryEmit(ClientMessage(ConnectedClient(centralId, name), data))
            }
            .launchIn(scope)

        advertiser.advertise(
            AdvertisementSettings(
                name = deviceName,
                identifier = identifier,
                uuid = ADVERTISER_UUID
            )
        )
    }

    suspend fun sendToClient(data: ByteArray) {
        iosServer.sendToSubscribers(CHAR_TO_CLIENT_UUID, data)
    }

    fun receiveFromClient(): Flow<ByteArray> = _messageFlow.map { it.data }

    fun receiveMessagesFromClient(): Flow<ClientMessage> = _messageFlow.asSharedFlow()

    fun connectedClients(): Flow<List<ConnectedClient>> = iosServer.clientNames
        .map { map -> map.entries.map { (id, name) -> ConnectedClient(id, name) } }

    fun clientConnectionState(): Flow<Boolean> = iosServer.clientNames.map { it.isNotEmpty() }

    suspend fun stop() {
        receivedWritesJob?.cancel()
        receivedWritesJob = null
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
