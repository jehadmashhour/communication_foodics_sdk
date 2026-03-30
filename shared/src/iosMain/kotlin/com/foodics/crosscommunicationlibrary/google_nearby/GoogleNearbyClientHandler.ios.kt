@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

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
import platform.Foundation.NSClassFromString
import platform.Foundation.NSSelectorFromString
import platform.Foundation.create
import platform.Foundation.setValue
import platform.darwin.NSObject
import scanner.IoTDevice
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

actual class GoogleNearbyClientHandler {

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    private var connectContinuation: ((Result<Unit>) -> Unit)? = null

    // Get bridge at runtime — no compile-time symbol dependency on NearbyClientBridge
    private val bridge: NSObject? by lazy {
        val cls = NSClassFromString("CrossCommunicationLibrary.NearbyClientBridge") ?: run {
            println("[NearbyClient] NearbyClientBridge class not found at runtime")
            return@lazy null
        }
        val sharedSel = NSSelectorFromString("shared")
        (cls as NSObject).performSelector(sharedSel) as? NSObject
    }

    init {
        bridge?.let { b ->
            b.setValue({ endpointId: String?, endpointName: String? ->
                if (endpointId != null && endpointName != null)
                    NearbyClientBridgeInterop.onEndpointFound(endpointId, endpointName)
            }, forKey = "onEndpointFound")

            b.setValue({ endpointId: String? ->
                if (endpointId != null)
                    NearbyClientBridgeInterop.onEndpointLost(endpointId)
            }, forKey = "onEndpointLost")

            b.setValue({ success: Boolean ->
                connectContinuation?.invoke(
                    if (success) Result.success(Unit)
                    else Result.failure(Exception("Nearby connection failed"))
                )
                connectContinuation = null
            }, forKey = "onConnectionResult")

            b.setValue({ nsData: NSData? ->
                nsData?.let { _incoming.tryEmit(it.toByteArray()) }
            }, forKey = "onDataReceived")
        }
    }

    fun scan(): Flow<List<IoTDevice>> = callbackFlow {
        NearbyClientBridgeInterop.startDiscovery(onUpdate = { trySend(it) })
        bridge?.performSelector(NSSelectorFromString("startDiscovery"))
        awaitClose {
            bridge?.performSelector(NSSelectorFromString("stopDiscovery"))
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
        bridge?.performSelector(
            NSSelectorFromString("requestConnection:"),
            withObject = device.address ?: ""
        )
    }

    suspend fun sendToServer(data: ByteArray, writeType: WriteType) {
        bridge?.performSelector(
            NSSelectorFromString("sendData:"),
            withObject = data.toNSData()
        )
    }

    suspend fun receiveFromServer(): Flow<ByteArray> = _incoming.asSharedFlow()

    suspend fun disconnect() {
        bridge?.performSelector(NSSelectorFromString("disconnect"))
    }
}

// ── Device interop ───────────────────────────────────────────────────────────

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
