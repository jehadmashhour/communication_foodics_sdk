package com.foodics.crosscommunicationlibrary.webrtc

import android.util.Log
import io.ably.lib.realtime.AblyRealtime
import io.ably.lib.realtime.Channel
import io.ably.lib.realtime.CompletionListener
import io.ably.lib.types.ClientOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.*
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

actual class WebRTCServerHandler {

    companion object {
        private const val TAG = "WebRTCServer"
        private const val API_KEY =
            "S4ZTiA.IC7hEQ:qsUR5drP3Ew6Zj3aHc2Qq93floLOcaYyzo7hisEn9s0"
        private const val DISCOVERY_CHANNEL = "webrtc_discovery"
    }

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var ably: AblyRealtime? = null
    private var discoveryChannel: Channel? = null
    private var signalChannel: Channel? = null

    private val _fromClient = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val fromClientFlow: Flow<ByteArray> = _fromClient.asSharedFlow()

    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null

    suspend fun start(deviceName: String, identifier: String): Unit =
        withContext(Dispatchers.IO) {
            WebRTCFactory.initialize()

            val options = ClientOptions(API_KEY).apply {
                clientId = "webrtc_server_$identifier"
                autoConnect = true
            }
            ably = AblyRealtime(options)
            discoveryChannel = ably?.channels?.get(DISCOVERY_CHANNEL)
            signalChannel = ably?.channels?.get("webrtc_signal_$identifier")

            // Announce presence
            val payload = JSONObject().apply {
                put("deviceName", deviceName)
                put("identifier", identifier)
            }.toString()
            discoveryChannel?.presence?.enter(payload, null)

            // Listen for client join
            signalChannel?.subscribe("join") { msg ->
                val data = msg.data as? String ?: return@subscribe
                val json = JSONObject(data)
                val clientId = json.optString("clientId")
                scope.launch { handleClientJoin(clientId) }
            }

            // Listen for answer from client
            signalChannel?.subscribe("answer") { msg ->
                val data = msg.data as? String ?: return@subscribe
                val json = JSONObject(data)
                val sdp = json.optString("sdp")
                val candidates = json.optJSONArray("iceCandidates") ?: JSONArray()

                peerConnection?.setRemoteDescription(noopSdpObserver(), SessionDescription(
                    SessionDescription.Type.ANSWER, sdp
                ))
                for (i in 0 until candidates.length()) {
                    val c = candidates.getJSONObject(i)
                    peerConnection?.addIceCandidate(IceCandidate(
                        c.optString("sdpMid"),
                        c.optInt("sdpMLineIndex"),
                        c.optString("candidate")
                    ))
                }
                Log.i(TAG, "Answer applied from client")
            }

            Log.i(TAG, "WebRTC server started for: $identifier")
        }

    private suspend fun handleClientJoin(clientId: String) {
        val factory = WebRTCFactory.create()
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
                override fun onDataChannel(dc: DataChannel?) { /* not used server-side */ }
                override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {
                    Log.i(TAG, "ICE connection state: $s")
                }
                override fun onIceConnectionReceivingChange(b: Boolean) {}
                override fun onAddStream(s: MediaStream?) {}
                override fun onRemoveStream(s: MediaStream?) {}
                override fun onRenegotiationNeeded() {}
                override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
                override fun onAddTrack(r: RtpReceiver?, s: Array<out MediaStream>?) {}
            }
        ) ?: run {
            Log.e(TAG, "Failed to create PeerConnection"); return
        }

        // Create DataChannel (server creates it)
        val dcInit = DataChannel.Init().apply { ordered = true }
        dataChannel = peerConnection!!.createDataChannel("data", dcInit)
        dataChannel?.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(p: Long) {}
            override fun onStateChange() {
                Log.i(TAG, "DataChannel state: ${dataChannel?.state()}")
            }
            override fun onMessage(buf: DataChannel.Buffer) {
                val bytes = ByteArray(buf.data.remaining()).also { buf.data.get(it) }
                scope.launch { _fromClient.emit(bytes) }
            }
        })

        // Create offer
        val offer = suspendCoroutine<SessionDescription> { cont ->
            peerConnection!!.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) {
                    sdp?.let { cont.resumeWith(Result.success(it)) }
                }
                override fun onSetSuccess() {}
                override fun onCreateFailure(s: String?) {
                    cont.resumeWithException(Exception("createOffer: $s"))
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
            }, offer)
        }

        // Wait for ICE gathering (max 5 s)
        withTimeoutOrNull(5_000) { iceComplete.await() }

        // Send offer to client
        val candidatesJson = JSONArray().also { arr ->
            collectedCandidates.forEach { c ->
                arr.put(JSONObject().apply {
                    put("candidate", c.sdp)
                    put("sdpMid", c.sdpMid)
                    put("sdpMLineIndex", c.sdpMLineIndex)
                })
            }
        }
        val offerPayload = JSONObject().apply {
            put("sdp", offer.description)
            put("targetClientId", clientId)
            put("iceCandidates", candidatesJson)
        }.toString()
        signalChannel?.publish("offer", offerPayload, null as CompletionListener?)
        Log.i(TAG, "Offer sent to client: $clientId (${collectedCandidates.size} ICE candidates)")
    }

    suspend fun sendToClient(data: ByteArray) {
        val dc = dataChannel ?: run { Log.w(TAG, "DataChannel not ready"); return }
        if (dc.state() != DataChannel.State.OPEN) { Log.w(TAG, "DataChannel not open"); return }
        dc.send(DataChannel.Buffer(ByteBuffer.wrap(data), true))
    }

    fun receiveFromClient(): Flow<ByteArray> = fromClientFlow

    suspend fun stop(): Unit = withContext(Dispatchers.IO) {
        try {
            discoveryChannel?.presence?.leave(null)
            signalChannel?.unsubscribe()
            discoveryChannel?.unsubscribe()
            dataChannel?.close()
            peerConnection?.close()
            ably?.close()
            scope.cancel()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            dataChannel = null; peerConnection = null; ably = null
            Log.i(TAG, "Server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Stop error", e)
        }
    }

    private fun noopSdpObserver() = object : SdpObserver {
        override fun onCreateSuccess(p: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(p: String?) {}
        override fun onSetFailure(p: String?) { Log.e(TAG, "SDP set failure: $p") }
    }
}
