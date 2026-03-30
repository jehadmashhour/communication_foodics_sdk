package com.foodics.crosscommunicationlibrary.google_nearby

import android.content.Context
import android.util.Log
import client.WriteType
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import scanner.IoTDevice

//androidMain
actual class GoogleNearbyClientHandler(private val context: Context) {

    companion object {
        private const val TAG = "NearbyClientHandler"
        private val STRATEGY = Strategy.P2P_STAR
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var endpointId: String? = null
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

    /** SCAN (Discovery) */
    fun scan(): Flow<List<IoTDevice>> = callbackFlow {
        val discoveredDevices = mutableMapOf<String, IoTDevice>()

        val discoveryCallback = object : EndpointDiscoveryCallback() {
            override fun onEndpointFound(id: String, info: DiscoveredEndpointInfo) {
                val parts = info.endpointName.split("|")
                val deviceName = parts.getOrNull(0) ?: info.endpointName
                val identifier = parts.getOrNull(1)

                val device = IoTDevice(
                    id = identifier,
                    name = deviceName,
                    connectionType = ConnectionType.GOOGLE_NEARBY,
                    address = id
                )

                discoveredDevices[id] = device
                trySend(discoveredDevices.values.toList())
                Log.d(TAG, "Found device=$deviceName identifier=$identifier")
            }

            override fun onEndpointLost(id: String) {
                discoveredDevices.remove(id)
                trySend(discoveredDevices.values.toList())
                Log.d(TAG, "Endpoint lost: $id")
            }
        }

        Nearby.getConnectionsClient(context)
            .startDiscovery(
                "com.foodics.crosscommunicationlibrary", discoveryCallback,
                DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
            )
            .addOnFailureListener { Log.e(TAG, "Discovery failed", it) }

        awaitClose {
            Nearby.getConnectionsClient(context).stopDiscovery()
            discoveredDevices.clear()
        }
    }

    /** CONNECT */
    suspend fun connect(device: IoTDevice) = suspendCancellableCoroutine<Unit> { cont ->
        val client = Nearby.getConnectionsClient(context)
        client.requestConnection(
            "ClientDevice",
            device.id ?: "",
            object : ConnectionLifecycleCallback() {
                override fun onConnectionInitiated(endpointId_: String, info: ConnectionInfo) {
                    Log.i(TAG, "Connection initiated with ${info.endpointName}")
                    client.acceptConnection(endpointId_, payloadCallback)
                    endpointId = endpointId_
                }

                override fun onConnectionResult(endpointId_: String, result: ConnectionResolution) {
                    if (result.status.isSuccess) {
                        Log.i(TAG, "Connected to Nearby server: ${device.name}")
                        if (cont.isActive) cont.resume(Unit) {}
                    } else {
                        Log.e(TAG, "Connection failed")
                        if (cont.isActive) cont.resumeWith(Result.failure(Exception("Connection failed")))
                    }
                }

                override fun onDisconnected(endpointId_: String) {
                    Log.i(TAG, "Disconnected from Nearby server")
                    endpointId = null
                }
            })
    }

    /** SEND DATA */
    suspend fun sendToServer(data: ByteArray, writeType: WriteType) {
        endpointId?.let {
            Nearby.getConnectionsClient(context).sendPayload(it, Payload.fromBytes(data))
            Log.d(TAG, "Sent to server: ${String(data)}")
        } ?: error("No connected server")
    }

    /** RECEIVE DATA */
    suspend fun receiveFromServer(): Flow<ByteArray> = _incoming.asSharedFlow()

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId_: String, payload: Payload) {
            payload.asBytes()?.let {
                scope.launch { _incoming.emit(it) }
                Log.d(TAG, "Received from server: ${String(it)}")
            }
        }

        override fun onPayloadTransferUpdate(endpointId_: String, update: PayloadTransferUpdate) {}
    }

    /** DISCONNECT */
    suspend fun disconnect() {
        endpointId?.let {
            Nearby.getConnectionsClient(context).disconnectFromEndpoint(it)
            endpointId = null
        }
        Log.i(TAG, "Disconnected from Nearby server")
    }
}
