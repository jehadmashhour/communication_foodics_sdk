package com.foodics.crosscommunicationlibrary.google_nearby

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

//androidMain
actual class GoogleNearbyServerHandler(private val context: Context) {

    companion object {
        private const val TAG = "NearbyServerHandler"
        private const val SERVICE_NAME = "NearbyService"
        private val STRATEGY = Strategy.P2P_STAR
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _fromClient = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val fromClientFlow: Flow<ByteArray> = _fromClient.asSharedFlow()

    private val connectedEndpoints = mutableSetOf<String>()

    /** START ADVERTISING */
    suspend fun start(deviceName: String, identifier: String) {
        stop()

        val endpointName = "$deviceName|$identifier"

        Nearby.getConnectionsClient(context)
            .startAdvertising(
                endpointName,
                "com.foodics.crosscommunicationlibrary",
                lifecycleCallback,
                AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
            )
            .addOnSuccessListener { Log.i(TAG, "Nearby advertising started") }
            .addOnFailureListener { Log.e(TAG, "Advertising failed", it) }
    }


    /** SEND DATA */
    suspend fun sendToClient(data: ByteArray) {
        connectedEndpoints.forEach {
            Nearby.getConnectionsClient(context).sendPayload(it, Payload.fromBytes(data))
            Log.d(TAG, "Sent to client: ${String(data)}")
        }
    }

    fun receiveFromClient(): Flow<ByteArray> = fromClientFlow

    /** STOP */
    suspend fun stop() {
        connectedEndpoints.forEach {
            Nearby.getConnectionsClient(context).disconnectFromEndpoint(it)
        }
        connectedEndpoints.clear()
        Nearby.getConnectionsClient(context).stopAdvertising()
        Log.i(TAG, "Nearby server stopped")
    }

    private val lifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            Log.i(TAG, "Connection initiated by client: ${info.endpointName}")
            Nearby.getConnectionsClient(context).acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                connectedEndpoints.add(endpointId)
                Log.i(TAG, "Client connected: $endpointId")
            } else {
                Log.e(TAG, "Client connection failed: $endpointId")
            }
        }

        override fun onDisconnected(endpointId: String) {
            connectedEndpoints.remove(endpointId)
            Log.i(TAG, "Client disconnected: $endpointId")
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            payload.asBytes()?.let {
                scope.launch { _fromClient.emit(it) }
                Log.d(TAG, "Received from client: ${String(it)}")
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }
}
