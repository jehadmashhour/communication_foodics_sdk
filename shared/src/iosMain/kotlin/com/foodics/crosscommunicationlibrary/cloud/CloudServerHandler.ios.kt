package com.foodics.crosscommunicationlibrary.cloud

import cocoapods.Ably.ARTClientOptions
import cocoapods.Ably.ARTRealtime
import cocoapods.Ably.ARTRealtimeChannel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import platform.Foundation.*
import kotlinx.cinterop.*

@OptIn(ExperimentalForeignApi::class)
actual class CloudServerHandler {

    companion object {
        private const val TAG = "CloudServerHandler"
        private const val API_KEY =
            "S4ZTiA.IC7hEQ:qsUR5drP3Ew6Zj3aHc2Qq93floLOcaYyzo7hisEn9s0"
        private const val DISCOVERY_CHANNEL = "cloud_discovery"
    }

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var ably: ARTRealtime? = null
    private var communicationChannel: ARTRealtimeChannel? = null
    private var discoveryChannel: ARTRealtimeChannel? = null
    private var serverChannelName: String? = null

    private val _fromClient = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val fromClientFlow: Flow<ByteArray> = _fromClient.asSharedFlow()

    suspend fun start(deviceName: String, identifier: String): Unit =
        withContext(Dispatchers.Main) {

            val channelName = "server_${identifier}_${NSUUID().UUIDString}"
            serverChannelName = channelName

            val options = ARTClientOptions(key = API_KEY).apply {
                clientId = "server_$identifier"
                autoConnect = true
            }

            ably = ARTRealtime(options = options)

            communicationChannel = ably?.channels?.get(channelName)
            discoveryChannel = ably?.channels?.get(DISCOVERY_CHANNEL)

            // 1️⃣ Announce in discovery channel
            val payload = NSMutableDictionary().apply {
                setObject(deviceName, forKey = "deviceName" as NSString)
                setObject(identifier, forKey = "identifier" as NSString)
                setObject(channelName, forKey = "channel" as NSString)
            }

            val jsonData = NSJSONSerialization.dataWithJSONObject(
                payload, options = 0u, error = null
            )
            val jsonString = jsonData?.let {
                NSString.create(data = it, encoding = NSUTF8StringEncoding) as String
            } ?: ""

            discoveryChannel?.presence?.enter(jsonString) { error ->
                if (error != null) {
                    NSLog("$TAG Discovery enter failed: ${error.message}")
                } else {
                    NSLog("$TAG Server announced in discovery channel")
                }
            }

            // 2️⃣ Listen for client messages
            communicationChannel?.subscribe("client_to_server") { message ->
                val data = when (val payload = message?.data) {
                    is NSData -> payload.toByteArray()
                    is NSString -> (payload as String).encodeToByteArray()
                    else -> null
                }
                data?.let { scope.launch { _fromClient.emit(it) } }
            }

            NSLog("$TAG Cloud server started on channel: $channelName")
        }

    suspend fun sendToClient(data: ByteArray) {
        val ch = communicationChannel ?: error("Server not started")

        val nsData = data.toNSData()
        ch.publish("server_to_client", data = nsData) { error ->
            if (error != null) {
                NSLog("$TAG Send to client failed: ${error.message}")
            } else {
                NSLog("$TAG Sent to client successfully")
            }
        }
    }

    fun receiveFromClient(): Flow<ByteArray> = fromClientFlow

    suspend fun stop(): Unit = withContext(Dispatchers.Main) {
        try {
            communicationChannel?.presence?.leave(null) { _ -> }
            discoveryChannel?.presence?.leave(null) { _ -> }

            communicationChannel?.unsubscribe()
            discoveryChannel?.unsubscribe()

            ably?.close()

            scope.cancel()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

            communicationChannel = null
            discoveryChannel = null
            ably = null
            serverChannelName = null

            NSLog("$TAG Server stopped")
        } catch (e: Exception) {
            NSLog("$TAG Stop error: ${e.message}")
        }
    }
}