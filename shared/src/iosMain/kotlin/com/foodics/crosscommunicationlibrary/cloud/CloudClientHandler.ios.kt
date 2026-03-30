package com.foodics.crosscommunicationlibrary.cloud

import client.WriteType
import ConnectionType
import cocoapods.Ably.ARTClientOptions
import cocoapods.Ably.ARTPresenceAction
import cocoapods.Ably.ARTRealtime
import cocoapods.Ably.ARTRealtimeChannel
import kotlinx.cinterop.ExperimentalForeignApi
import scanner.IoTDevice
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import platform.Foundation.*


@OptIn(ExperimentalForeignApi::class)
actual class CloudClientHandler {

    companion object {
        private const val TAG = "CloudClientHandler"
        private const val API_KEY =
            "S4ZTiA.IC7hEQ:qsUR5drP3Ew6Zj3aHc2Qq93floLOcaYyzo7hisEn9s0"
        private const val DISCOVERY_CHANNEL = "cloud_discovery"
    }

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var ably: ARTRealtime? = null
    private var discoveryChannel: ARTRealtimeChannel? = null
    private var communicationChannel: ARTRealtimeChannel? = null

    private val clientId = "client_${NSUUID().UUIDString}"

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    private val _onlineDevices = MutableStateFlow<List<IoTDevice>>(emptyList())

    fun scan(): Flow<List<IoTDevice>> {

        if (ably != null) return _onlineDevices.asStateFlow()

        val options = ARTClientOptions(key = API_KEY).apply {
            clientId = this@CloudClientHandler.clientId
            autoConnect = true
        }

        ably = ARTRealtime(options = options)

        discoveryChannel = ably?.channels?.get(DISCOVERY_CHANNEL)

        val devicesMap = mutableMapOf<String, IoTDevice>()

        discoveryChannel?.presence?.subscribe { msg ->
            msg ?: return@subscribe

            when (msg.action) {
                ARTPresenceAction.ARTPresenceEnter,
                ARTPresenceAction.ARTPresencePresent,
                ARTPresenceAction.ARTPresenceUpdate -> {

                    val jsonString = msg.data as? String ?: return@subscribe
                    val jsonData = (jsonString as NSString)
                        .dataUsingEncoding(NSUTF8StringEncoding) ?: return@subscribe
                    val metadata = NSJSONSerialization
                        .JSONObjectWithData(jsonData, options = 0u, error = null)
                            as? NSDictionary ?: return@subscribe

                    val name = metadata.objectForKey("deviceName" as NSString) as? String ?: ""
                    val identifier = metadata.objectForKey("identifier" as NSString) as? String ?: ""
                    val channel = metadata.objectForKey("channel" as NSString) as? String ?: ""

                    if (channel.isBlank()) return@subscribe

                    val device = IoTDevice(
                        id = identifier,
                        name = name.ifBlank { identifier },
                        address = channel,
                        connectionType = ConnectionType.CLOUD
                    )

                    devicesMap[msg.clientId ?: return@subscribe] = device
                }

                ARTPresenceAction.ARTPresenceLeave,
                ARTPresenceAction.ARTPresenceAbsent -> {
                    devicesMap.remove(msg.clientId)
                }

                else -> {}
            }

            _onlineDevices.value = devicesMap.values.toList()
        }

        NSLog("$TAG Cloud scan started")
        return _onlineDevices.asStateFlow()
    }

    suspend fun connect(serverChannelName: String): Unit =
        withContext(Dispatchers.Main) {

            val instance = ably ?: error("Call scan() first")

            communicationChannel = instance.channels.get(serverChannelName)

            communicationChannel?.subscribe("server_to_client") { message ->
                val data = when (val payload = message?.data) {
                    is NSData -> payload.toByteArray()
                    is NSString -> (payload as String).encodeToByteArray()
                    else -> null
                }
                data?.let { scope.launch { _incoming.emit(it) } }
            }

            NSLog("$TAG Connected to server channel: $serverChannelName")
        }

    suspend fun sendToServer(data: ByteArray, writeType: WriteType) {
        val ch = communicationChannel ?: error("Not connected to server")

        ch.publish("client_to_server", data = data.toNSData()) { error ->
            if (error != null) {
                NSLog("$TAG Send failed: ${error.message}")
            } else {
                NSLog("$TAG Sent to server successfully")
            }
        }
    }

    fun receiveFromServer(): Flow<ByteArray> = _incoming.asSharedFlow()

    suspend fun disconnect(): Unit = withContext(Dispatchers.Main) {
        try {
            communicationChannel?.unsubscribe()
            discoveryChannel?.unsubscribe()

            ably?.close()

            scope.cancel()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

            communicationChannel = null
            discoveryChannel = null
            ably = null

            _onlineDevices.value = emptyList()

            NSLog("$TAG Client disconnected")
        } catch (e: Exception) {
            NSLog("$TAG Disconnect error: ${e.message}")
        }
    }
}