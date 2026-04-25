package com.foodics.crosscommunicationlibrary.bluetooth

import android.content.Context
import client.WriteType
import com.foodics.crosscommunicationlibrary.AppContext
import ClientQuality
import com.foodics.crosscommunicationlibrary.core.ClientMessage
import com.foodics.crosscommunicationlibrary.core.CommunicationChannel
import com.foodics.crosscommunicationlibrary.core.ConnectedClient
import com.foodics.crosscommunicationlibrary.logger.CommunicationLogger
import BluetoothConstants.BRIDGE_C2S_PREFIX
import BluetoothConstants.BRIDGE_CLIENT_ID
import BluetoothConstants.BRIDGE_DISCONNECT_PREFIX
import BluetoothConstants.BRIDGE_INIT_PREFIX
import BluetoothConstants.BRIDGE_S2C_PREFIX
import BluetoothConstants.HELLO_PREFIX
import ConnectionQuality
import ConnectionType
import handler.BluetoothClientHandler
import handler.BluetoothServerHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import model.BleClient
import model.BleMessage
import scanner.IoTDevice

actual class BluetoothCommunicationChannel(
    private val context: Context,
    private val logger: CommunicationLogger?
) : CommunicationChannel {

    actual constructor(logger: CommunicationLogger?) : this(AppContext.get(), logger)

    private val serverHandler = BluetoothServerHandler(context, logger)
    private val clientHandler = BluetoothClientHandler(context, logger)

    // Populated when iOS sends __BINIT__: over the iOS-server notification channel.
    private val _bridgeClient = MutableStateFlow<BleClient?>(null)
    private val _bridgeMessages = MutableSharedFlow<BleMessage>(extraBufferCapacity = 64)
    private var bridgeMonitorScope: CoroutineScope? = null

    actual override val connectionType: ConnectionType = ConnectionType.BLUETOOTH

    actual override suspend fun startServer(deviceName: String, identifier: String) =
        serverHandler.start(deviceName, identifier)

    actual override fun scan(): Flow<List<IoTDevice>> = clientHandler.scan()

    actual override suspend fun connectToServer(device: IoTDevice) {
        clientHandler.connect(device)
        startBridgeMonitor()
    }

    private fun startBridgeMonitor() {
        bridgeMonitorScope?.cancel()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        bridgeMonitorScope = scope
        scope.launch {
            clientHandler.rawNotifications().collect { data ->
                val text = data.decodeToString()
                when {
                    text.startsWith(BRIDGE_INIT_PREFIX) -> {
                        val name = text.removePrefix(BRIDGE_INIT_PREFIX)
                        _bridgeClient.value = BleClient(BRIDGE_CLIENT_ID, name)
                    }
                    text.startsWith(BRIDGE_DISCONNECT_PREFIX) -> {
                        _bridgeClient.value = null
                    }
                    text.startsWith(BRIDGE_C2S_PREFIX) -> {
                        val stripped = data.copyOfRange(BRIDGE_C2S_PREFIX.length, data.size)
                        val strippedText = stripped.decodeToString()
                        if (strippedText.startsWith(HELLO_PREFIX)) {
                            val name = strippedText.removePrefix(HELLO_PREFIX).trim()
                            _bridgeClient.value = BleClient(BRIDGE_CLIENT_ID, name)
                        } else {
                            val client = _bridgeClient.value ?: BleClient(BRIDGE_CLIENT_ID, BRIDGE_CLIENT_ID)
                            _bridgeMessages.tryEmit(BleMessage(client, stripped))
                        }
                    }
                }
            }
        }
    }

    actual override suspend fun sendDataToServer(data: ByteArray, writeType: WriteType) =
        clientHandler.sendToServer(data, writeType)

    actual override suspend fun receiveDataFromServer(): Flow<ByteArray> =
        clientHandler.receiveFromServer().filter { data ->
            val text = data.decodeToString()
            !text.startsWith(BRIDGE_C2S_PREFIX) &&
            !text.startsWith(BRIDGE_INIT_PREFIX) &&
            !text.startsWith(BRIDGE_DISCONNECT_PREFIX)
        }

    actual override suspend fun sendDataToClient(data: ByteArray) {
        val bridge = _bridgeClient.value
        if (bridge != null) {
            val tagged = BRIDGE_S2C_PREFIX.encodeToByteArray() + data
            clientHandler.sendToServer(tagged, WriteType.DEFAULT)
        }
        serverHandler.sendToClient(data)
    }

    actual override suspend fun sendDataToClients(data: ByteArray, clientIds: List<String>) {
        val bridge = _bridgeClient.value
        val targetsBridge = bridge != null && (clientIds.isEmpty() || BRIDGE_CLIENT_ID in clientIds)
        if (targetsBridge) {
            val tagged = BRIDGE_S2C_PREFIX.encodeToByteArray() + data
            clientHandler.sendToServer(tagged, WriteType.DEFAULT)
        }
        val serverIds = clientIds.filter { it != BRIDGE_CLIENT_ID }
        if (serverIds.isNotEmpty() || clientIds.isEmpty()) {
            serverHandler.sendToClients(data, serverIds)
        }
    }

    actual override suspend fun receiveDataFromClient(): Flow<ByteArray> = merge(
        serverHandler.receiveFromClient(),
        _bridgeMessages.map { it.data }
    )

    actual override suspend fun stopServer() = serverHandler.stop()

    actual override suspend fun disconnectClient() {
        bridgeMonitorScope?.cancel()
        bridgeMonitorScope = null
        _bridgeClient.value = null
        clientHandler.disconnect()
    }

    actual override fun clientConnectionState(): Flow<Boolean> = combine(
        serverHandler.clientConnectionState(),
        _bridgeClient
    ) { serverHasClients, bridge ->
        serverHasClients || bridge != null
    }.distinctUntilChanged()

    actual override fun connectedClients(): Flow<List<ConnectedClient>> = combine(
        serverHandler.connectedClients().map { list ->
            list.map { ConnectedClient(it.id, it.name) }
        },
        _bridgeClient
    ) { serverClients, bridge ->
        if (bridge != null) serverClients + ConnectedClient(bridge.id, bridge.name)
        else serverClients
    }

    actual override suspend fun receiveMessagesFromClient(): Flow<ClientMessage> = merge(
        serverHandler.receiveMessagesFromClient().map { msg ->
            ClientMessage(ConnectedClient(msg.client.id, msg.client.name), msg.data)
        },
        _bridgeMessages.map { msg ->
            ClientMessage(ConnectedClient(msg.client.id, msg.client.name), msg.data)
        }
    )

    actual override fun connectionQuality(): Flow<ConnectionQuality> = clientHandler.connectionQuality()

    actual override fun serverClientsQuality(): Flow<List<ClientQuality>> = combine(
        serverHandler.clientsQuality(),
        _bridgeClient
    ) { serverList, bridge ->
        if (bridge != null) serverList + ClientQuality(bridge.id, bridge.name, 0f)
        else serverList
    }
}
