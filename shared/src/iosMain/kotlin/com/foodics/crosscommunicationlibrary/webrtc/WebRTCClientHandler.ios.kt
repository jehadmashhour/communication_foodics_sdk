@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.foodics.crosscommunicationlibrary.webrtc

import client.WriteType
import ConnectionType
import cocoapods.Ably.ARTClientOptions
import cocoapods.Ably.ARTPresenceAction
import cocoapods.Ably.ARTRealtime
import cocoapods.Ably.ARTRealtimeChannel
import com.foodics.crosscommunicationlibrary.cloud.toByteArray
import com.foodics.crosscommunicationlibrary.cloud.toNSData
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import platform.Foundation.*
import scanner.IoTDevice

actual class WebRTCClientHandler : WebRTCClientDelegate {

    companion object {
        private const val API_KEY =
            "S4ZTiA.IC7hEQ:qsUR5drP3Ew6Zj3aHc2Qq93floLOcaYyzo7hisEn9s0"
        private const val DISCOVERY_CHANNEL = "webrtc_discovery"
    }

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val clientId = "webrtc_client_${NSUUID().UUIDString}"

    private var ably: ARTRealtime? = null
    private var discoveryChannel: ARTRealtimeChannel? = null
    private var signalChannel: ARTRealtimeChannel? = null

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    private val _devices = MutableStateFlow<List<IoTDevice>>(emptyList())

    // ── WebRTCClientDelegate ─────────────────────────────────────────────────

    override fun onDataReceived(data: NSData) {
        scope.launch { _incoming.emit(data.toByteArray()) }
    }

    override fun onConnectionReady() = NSLog("[WebRTCClient] DataChannel open")

    // ── Public API ───────────────────────────────────────────────────────────

    fun scan(): Flow<List<IoTDevice>> {
        if (ably != null) return _devices.asStateFlow()

        val options = ARTClientOptions(key = API_KEY).apply {
            clientId = this@WebRTCClientHandler.clientId
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
                    val raw = webrtcMessageString(msg.data) ?: return@subscribe
                    val json = webrtcParseJson(raw) ?: return@subscribe
                    val name = json.objectForKey("deviceName" as NSString) as? String ?: ""
                    val id = json.objectForKey("identifier" as NSString) as? String ?: ""
                    devicesMap[msg.clientId ?: return@subscribe] = IoTDevice(
                        id = id, name = name.ifBlank { id },
                        address = id, connectionType = ConnectionType.WEBRTC
                    )
                }
                ARTPresenceAction.ARTPresenceLeave,
                ARTPresenceAction.ARTPresenceAbsent -> devicesMap.remove(msg.clientId)
                else -> {}
            }
            _devices.value = devicesMap.values.toList()
        }
        NSLog("[WebRTCClient] Scan started")
        return _devices.asStateFlow()
    }

    suspend fun connect(serverIdentifier: String): Unit = withContext(Dispatchers.Main) {
        WebRTCBridgeProvider.clientBridge?.delegate = this@WebRTCClientHandler

        signalChannel = ably?.channels?.get("webrtc_signal_$serverIdentifier")

        // Subscribe BEFORE sending join to avoid race condition
        signalChannel?.subscribe("offer") { message ->
            val raw = webrtcMessageString(message?.data) ?: return@subscribe
            val json = webrtcParseJson(raw) ?: return@subscribe

            // Only handle offers addressed to this client
            val target = json.objectForKey("targetClientId" as NSString) as? String
            if (target != this@WebRTCClientHandler.clientId) return@subscribe

            val sdp = json.objectForKey("sdp" as NSString) as? String ?: return@subscribe
            val candidatesJson = json.objectForKey("iceCandidatesJson" as NSString) as? String
                ?: "[]"

            WebRTCBridgeProvider.clientBridge?.setOffer(
                sdp = sdp,
                iceCandidatesJson = candidatesJson
            ) { answerSdp, answerCandidatesJson ->
                val answerJson = buildWebRTCJson(
                    "sdp" to answerSdp,
                    "iceCandidatesJson" to answerCandidatesJson
                )
                this@WebRTCClientHandler.signalChannel?.publish(
                    "answer", data = answerJson
                ) { _ -> }
                NSLog("[WebRTCClient] Answer sent")
            }
        }

        // Send join request
        val joinJson = buildWebRTCJson("clientId" to clientId)
        signalChannel?.publish("join", data = joinJson) { _ -> }
        NSLog("[WebRTCClient] Join sent to: $serverIdentifier")
    }

    suspend fun sendToServer(data: ByteArray, writeType: WriteType) {
        WebRTCBridgeProvider.clientBridge?.sendData(bytes = data.toNSData())
    }

    fun receiveFromServer(): Flow<ByteArray> = _incoming.asSharedFlow()

    suspend fun disconnect(): Unit = withContext(Dispatchers.Main) {
        try {
            signalChannel?.unsubscribe()
            discoveryChannel?.unsubscribe()
            WebRTCBridgeProvider.clientBridge?.close()
            ably?.close()
            scope.cancel()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            ably = null; signalChannel = null; discoveryChannel = null
            _devices.value = emptyList()
            NSLog("[WebRTCClient] Disconnected")
        } catch (e: Exception) {
            NSLog("[WebRTCClient] Disconnect error: ${e.message}")
        }
    }
}
