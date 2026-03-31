@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.foodics.crosscommunicationlibrary.webrtc

import cocoapods.Ably.ARTClientOptions
import cocoapods.Ably.ARTRealtime
import cocoapods.Ably.ARTRealtimeChannel
import com.foodics.crosscommunicationlibrary.cloud.toByteArray
import com.foodics.crosscommunicationlibrary.cloud.toNSData
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import platform.Foundation.*
import kotlinx.cinterop.ExperimentalForeignApi

actual class WebRTCServerHandler : WebRTCServerDelegate {

    companion object {
        private const val API_KEY =
            "S4ZTiA.IC7hEQ:qsUR5drP3Ew6Zj3aHc2Qq93floLOcaYyzo7hisEn9s0"
        private const val DISCOVERY_CHANNEL = "webrtc_discovery"
    }

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ably: ARTRealtime? = null
    private var discoveryChannel: ARTRealtimeChannel? = null
    private var signalChannel: ARTRealtimeChannel? = null

    private val _fromClient = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val fromClientFlow: Flow<ByteArray> = _fromClient.asSharedFlow()

    // ── WebRTCServerDelegate ─────────────────────────────────────────────────

    override fun onDataReceived(data: NSData) {
        scope.launch { _fromClient.emit(data.toByteArray()) }
    }

    override fun onConnectionReady() = NSLog("[WebRTCServer] DataChannel open")

    // ── Public API ───────────────────────────────────────────────────────────

    suspend fun start(deviceName: String, identifier: String): Unit =
        withContext(Dispatchers.Main) {
            WebRTCBridgeProvider.serverBridge?.delegate = this@WebRTCServerHandler

            val options = ARTClientOptions(key = API_KEY).apply {
                clientId = "webrtc_server_$identifier"
                autoConnect = true
            }
            ably = ARTRealtime(options = options)
            discoveryChannel = ably?.channels?.get(DISCOVERY_CHANNEL)
            signalChannel = ably?.channels?.get("webrtc_signal_$identifier")

            // Announce in discovery presence
            val presencePayload = buildWebRTCJson(
                "deviceName" to deviceName,
                "identifier" to identifier
            )
            discoveryChannel?.presence?.enter(presencePayload) { _ -> }

            // Handle join requests from clients
            signalChannel?.subscribe("join") { message ->
                val raw = webrtcMessageString(message?.data) ?: return@subscribe
                val json = webrtcParseJson(raw) ?: return@subscribe
                val clientId = json.objectForKey("clientId" as NSString) as? String
                    ?: return@subscribe

                WebRTCBridgeProvider.serverBridge?.createOffer { sdp, candidatesJson ->
                    val offerJson = buildWebRTCJson(
                        "sdp" to sdp,
                        "iceCandidatesJson" to candidatesJson,
                        "targetClientId" to clientId
                    )
                    this@WebRTCServerHandler.signalChannel?.publish(
                        "offer", data = offerJson
                    ) { _ -> }
                    NSLog("[WebRTCServer] Offer sent to client: $clientId")
                }
            }

            // Handle client answer
            signalChannel?.subscribe("answer") { message ->
                val raw = webrtcMessageString(message?.data) ?: return@subscribe
                val json = webrtcParseJson(raw) ?: return@subscribe
                val sdp = json.objectForKey("sdp" as NSString) as? String ?: return@subscribe
                val candidatesJson = json.objectForKey("iceCandidatesJson" as NSString) as? String
                    ?: "[]"
                WebRTCBridgeProvider.serverBridge?.handleAnswer(
                    sdp = sdp, iceCandidatesJson = candidatesJson
                )
                NSLog("[WebRTCServer] Answer received, applying")
            }

            NSLog("[WebRTCServer] Started for: $identifier")
        }

    suspend fun sendToClient(data: ByteArray) {
        WebRTCBridgeProvider.serverBridge?.sendData(bytes = data.toNSData())
    }

    fun receiveFromClient(): Flow<ByteArray> = fromClientFlow

    suspend fun stop(): Unit = withContext(Dispatchers.Main) {
        try {
            discoveryChannel?.presence?.leave(null) { _ -> }
            signalChannel?.unsubscribe()
            discoveryChannel?.unsubscribe()
            WebRTCBridgeProvider.serverBridge?.close()
            ably?.close()
            scope.cancel()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            ably = null; signalChannel = null; discoveryChannel = null
            NSLog("[WebRTCServer] Stopped")
        } catch (e: Exception) {
            NSLog("[WebRTCServer] Stop error: ${e.message}")
        }
    }
}

// ── Helpers ──────────────────────────────────────────────────────────────────

internal fun buildWebRTCJson(vararg pairs: Pair<String, String>): String {
    val dict = NSMutableDictionary()
    pairs.forEach { (k, v) -> dict.setObject(v, forKey = k as NSString) }
    val data = NSJSONSerialization.dataWithJSONObject(dict, options = 0u, error = null)
    return data?.let { NSString.create(data = it, encoding = NSUTF8StringEncoding) as? String }
        ?: "{}"
}

internal fun webrtcMessageString(payload: Any?): String? = when (payload) {
    is NSString -> payload as String
    is NSData -> NSString.create(data = payload, encoding = NSUTF8StringEncoding) as? String
    else -> null
}

internal fun webrtcParseJson(raw: String): NSDictionary? {
    val data = (raw as NSString).dataUsingEncoding(NSUTF8StringEncoding) ?: return null
    return NSJSONSerialization.JSONObjectWithData(data, options = 0u, error = null) as? NSDictionary
}
