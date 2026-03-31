package com.foodics.crosscommunicationlibrary.webrtc

import android.util.Log
import client.WriteType
import ConnectionType
import io.ably.lib.realtime.AblyRealtime
import io.ably.lib.realtime.Channel
import io.ably.lib.types.ClientOptions
import io.ably.lib.types.PresenceMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.*
import scanner.IoTDevice
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

actual class WebRTCClientHandler {

    companion object {
        private const val TAG = "WebRTCClient"
        private const val API_KEY =
            "S4ZTiA.IC7hEQ:qsUR5drP3Ew6Zj3aHc2Qq93floLOcaYyzo7hisEn9s0"
        private const val DISCOVERY_CHANNEL = "webrtc_discovery"
    }

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clientId = "webrtc_client_${UUID.randomUUID()}"

    private var ably: AblyRealtime? = null
    private var discoveryChannel: Channel? = null
    private var signalChannel: Channel? = null

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    private val _devices = MutableStateFlow<List<IoTDevice>>(emptyList())

    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null

    fun scan(): Flow<List<IoTDevice>> {
        if (ably != null) return _devices.asStateFlow()

        val options = ClientOptions(API_KEY).apply {
            clientId = this@WebRTCClientHandler.clientId
            autoConnect = true
        }
        ably = AblyRealtime(options)
        discoveryChannel = ably?.channels?.get(DISCOVERY_CHANNEL)

        val devicesMap = mutableMapOf<String, IoTDevice>()
        discoveryChannel?.presence?.subscribe { msg ->
            when (msg.action) {
                PresenceMessage.Action.enter,
                PresenceMessage.Action.present,
                PresenceMessage.Action.update -> {
                    val json = JSONObject(msg.data as? String ?: return@subscribe)
                    val name = json.optString("deviceName")
                    val id = json.optString("identifier")
                    devicesMap[msg.clientId] = IoTDevice(
                        id = id, name = name.ifBlank { id },
                        address = id, connectionType = ConnectionType.WEBRTC
                    )
                }
                PresenceMessage.Action.leave,
                PresenceMessage.Action.absent -> devicesMap.remove(msg.clientId)
                else -> {}
            }
            _devices.value = devicesMap.values.toList()
        }
        Log.i(TAG, "WebRTC scan started")
        return _devices.asStateFlow()
    }

    suspend fun connect(serverIdentifier: String): Unit = withContext(Dispatchers.IO) {
        WebRTCFactory.initialize()
        val factory = WebRTCFactory.create()

        signalChannel = ably?.channels?.get("webrtc_signal_$serverIdentifier")

        val offerDeferred = CompletableDeferred<JSONObject>()

        // Subscribe to offer before sending join (avoid race)
        signalChannel?.subscribe("offer") { msg ->
            val data = msg.data as? String ?: return@subscribe
            val json = JSONObject(data)
            if (json.optString("targetClientId") == clientId && !offerDeferred.isCompleted) {
                offerDeferred.complete(json)
            }
        }

        // Send join request
        signalChannel?.publish("join", JSONObject().apply { put("clientId", clientId) }.toString(), null)
        Log.i(TAG, "Join sent to server: $serverIdentifier")

        // Wait for offer (max 15 s)
        val offerJson = withTimeout(15_000) { offerDeferred.await() }

        val collectedCandidates = mutableListOf<IceCandidate>()
        val iceComplete = CompletableDeferred<Unit>()

        peerConnection = factory.createPeerConnection(
            PeerConnection.RTCConfiguration(listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
            )),
            object : PeerConnection.Observer {
                override fun onIceCandidate(c: IceCandidate?) {
                    c?.let { collectedCandidates.add(it) }
                }
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                    if (state == PeerConnection.IceGatheringState.COMPLETE && !iceComplete.isCompleted) {
                        iceComplete.complete(Unit)
                    }
                }
                override fun onDataChannel(dc: DataChannel?) {
                    dataChannel = dc
                    dc?.registerObserver(object : DataChannel.Observer {
                        override fun onBufferedAmountChange(p: Long) {}
                        override fun onStateChange() {
                            Log.i(TAG, "DataChannel state: ${dc.state()}")
                        }
                        override fun onMessage(buf: DataChannel.Buffer) {
                            val bytes = ByteArray(buf.data.remaining()).also { buf.data.get(it) }
                            scope.launch { _incoming.emit(bytes) }
                        }
                    })
                    Log.i(TAG, "DataChannel received from server")
                }
                override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {
                    Log.i(TAG, "ICE connection state: $s")
                }
                override fun onIceConnectionReceivingChange(b: Boolean) {}
                override fun onAddStream(s: MediaStream?) {}
                override fun onRemoveStream(s: MediaStream?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(r: RtpReceiver?, s: Array<out MediaStream>?) {}
            }
        ) ?: run { Log.e(TAG, "Failed to create PeerConnection"); return@withContext }

        // Set remote description (server's offer)
        val offerSdp = SessionDescription(SessionDescription.Type.OFFER, offerJson.optString("sdp"))
        suspendCoroutine<Unit> { cont ->
            peerConnection!!.setRemoteDescription(object : SdpObserver {
                override fun onCreateSuccess(p: SessionDescription?) {}
                override fun onSetSuccess() { cont.resumeWith(Result.success(Unit)) }
                override fun onCreateFailure(s: String?) {}
                override fun onSetFailure(s: String?) {
                    cont.resumeWithException(Exception("setRemoteDescription: $s"))
                }
            }, offerSdp)
        }

        // Add server ICE candidates
        val serverCandidates = offerJson.optJSONArray("iceCandidates") ?: JSONArray()
        for (i in 0 until serverCandidates.length()) {
            val c = serverCandidates.getJSONObject(i)
            peerConnection?.addIceCandidate(IceCandidate(
                c.optString("sdpMid"), c.optInt("sdpMLineIndex"), c.optString("candidate")
            ))
        }

        // Create answer
        val answer = suspendCoroutine<SessionDescription> { cont ->
            peerConnection!!.createAnswer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) {
                    sdp?.let { cont.resumeWith(Result.success(it)) }
                }
                override fun onSetSuccess() {}
                override fun onCreateFailure(s: String?) {
                    cont.resumeWithException(Exception("createAnswer: $s"))
                }
                override fun onSetFailure(s: String?) {}
            }, MediaConstraints())
        }

        // Set local description
        suspendCoroutine<Unit> { cont ->
            peerConnection!!.setLocalDescription(object : SdpObserver {
                override fun onCreateSuccess(p: SessionDescription?) {}
                override fun onSetSuccess() { cont.resumeWith(Result.success(Unit)) }
                override fun onCreateFailure(s: String?) {}
                override fun onSetFailure(s: String?) {
                    cont.resumeWithException(Exception("setLocalDescription: $s"))
                }
            }, answer)
        }

        // Wait for ICE gathering (max 5 s)
        withTimeoutOrNull(5_000) { iceComplete.await() }

        // Send answer
        val candidatesJson = JSONArray().also { arr ->
            collectedCandidates.forEach { c ->
                arr.put(JSONObject().apply {
                    put("candidate", c.sdp)
                    put("sdpMid", c.sdpMid)
                    put("sdpMLineIndex", c.sdpMLineIndex)
                })
            }
        }
        val answerPayload = JSONObject().apply {
            put("sdp", answer.description)
            put("iceCandidates", candidatesJson)
        }.toString()
        signalChannel?.publish("answer", answerPayload, null)
        Log.i(TAG, "Answer sent (${collectedCandidates.size} ICE candidates)")
    }

    suspend fun sendToServer(data: ByteArray, writeType: WriteType) {
        val dc = dataChannel ?: run { Log.w(TAG, "DataChannel not ready"); return }
        if (dc.state() != DataChannel.State.OPEN) { Log.w(TAG, "DataChannel not open"); return }
        dc.send(DataChannel.Buffer(ByteBuffer.wrap(data), true))
    }

    fun receiveFromServer(): Flow<ByteArray> = _incoming.asSharedFlow()

    suspend fun disconnect(): Unit = withContext(Dispatchers.IO) {
        try {
            signalChannel?.unsubscribe()
            discoveryChannel?.unsubscribe()
            dataChannel?.close()
            peerConnection?.close()
            ably?.close()
            scope.cancel()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            dataChannel = null; peerConnection = null; ably = null
            _devices.value = emptyList()
            Log.i(TAG, "Client disconnected")
        } catch (e: Exception) {
            Log.e(TAG, "Disconnect error", e)
        }
    }
}
