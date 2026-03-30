@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.foodics.crosscommunicationlibrary.google_nearby

import client.WriteType
import com.foodics.crosscommunicationlibrary.cloud.toByteArray
import com.foodics.crosscommunicationlibrary.cloud.toNSData
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import scanner.IoTDevice
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * iOS actual for GoogleNearbyClientHandler.
 *
 * Implements NearbyClientDelegate so the Swift bridge can call back into
 * Kotlin via typed protocol methods — no ObjC runtime reflection needed.
 *
 * The bridge is accessed through NearbyBridgeProvider to avoid a retain
 * cycle (bridge holds this handler via `delegate`; this handler does NOT
 * hold a strong reference back to the bridge).
 */
actual class GoogleNearbyClientHandler : NearbyClientDelegate {

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    private var connectContinuation: ((Result<Unit>) -> Unit)? = null

    // Register ourselves as the delegate on the bridge immediately.
    init {
        NearbyBridgeProvider.clientBridge?.delegate = this
    }

    // Always go through the provider — avoids a strong-reference cycle.
    private val bridge get() = NearbyBridgeProvider.clientBridge

    // ── NearbyClientDelegate (called by Swift) ────────────────────────────────

    override fun onEndpointFound(endpointId: String, endpointName: String) {
        NearbyClientBridgeInterop.onEndpointFound(endpointId, endpointName)
    }

    override fun onEndpointLost(endpointId: String) {
        NearbyClientBridgeInterop.onEndpointLost(endpointId)
    }

    override fun onConnectionResult(success: Boolean) {
        val cb = connectContinuation
        connectContinuation = null
        cb?.invoke(
            if (success) Result.success(Unit)
            else Result.failure(Exception("Nearby connection failed"))
        )
    }

    override fun onDisconnected() {
        // If a connect() coroutine is still suspended waiting for a result,
        // resume it with failure so it doesn't leak forever.
        val cb = connectContinuation
        connectContinuation = null
        cb?.invoke(Result.failure(Exception("Nearby connection dropped")))
    }

    override fun onDataReceived(data: NSData) {
        _incoming.tryEmit(data.toByteArray())
    }

    // ── Public API (called by GoogleNearbyCommunicationChannel) ───────────────

    fun scan(): Flow<List<IoTDevice>> = callbackFlow {
        NearbyClientBridgeInterop.startDiscovery(onUpdate = { trySend(it) })
        bridge?.startDiscovery()
        awaitClose {
            bridge?.stopDiscovery()
            NearbyClientBridgeInterop.stopDiscovery()
        }
    }

    suspend fun connect(device: IoTDevice) = suspendCancellableCoroutine<Unit> { cont ->
        connectContinuation = { result ->
            if (cont.isActive) result.fold(
                onSuccess = { cont.resume(Unit) },
                onFailure = { cont.resumeWithException(it) }
            )
        }
        bridge?.requestConnection(device.address ?: "")
    }

    suspend fun sendToServer(data: ByteArray, writeType: WriteType) {
        bridge?.sendData(data.toNSData())
    }

    fun receiveFromServer(): Flow<ByteArray> = _incoming.asSharedFlow()

    suspend fun disconnect() {
        bridge?.disconnect()
    }
}

// ── Device list management ────────────────────────────────────────────────────

internal object NearbyClientBridgeInterop {
    private val discoveredDevices = mutableMapOf<String, IoTDevice>()
    private var onUpdateCallback: ((List<IoTDevice>) -> Unit)? = null

    fun startDiscovery(onUpdate: (List<IoTDevice>) -> Unit) {
        onUpdateCallback = onUpdate
    }

    fun stopDiscovery() {
        discoveredDevices.clear()
        onUpdateCallback = null
    }

    fun onEndpointFound(endpointId: String, endpointName: String) {
        val parts = endpointName.split("|")
        val device = IoTDevice(
            id = parts.getOrNull(1),
            name = parts.getOrNull(0) ?: endpointName,
            connectionType = ConnectionType.GOOGLE_NEARBY,
            address = endpointId
        )
        discoveredDevices[endpointId] = device
        onUpdateCallback?.invoke(discoveredDevices.values.toList())
    }

    fun onEndpointLost(endpointId: String) {
        discoveredDevices.remove(endpointId)
        onUpdateCallback?.invoke(discoveredDevices.values.toList())
    }
}