package com.foodics.crosscommunicationlibrary.wifi_direct

import ConnectionType
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

actual class WifiDirectClientHandler : MCPClientDelegate {

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    private var connectContinuation: ((Result<Unit>) -> Unit)? = null
    private val discoveredPeers = mutableMapOf<String, IoTDevice>()
    private var onUpdateCallback: ((List<IoTDevice>) -> Unit)? = null

    init {
        MultipeerBridgeProvider.clientBridge?.delegate = this
    }

    private val bridge get() = MultipeerBridgeProvider.clientBridge

    // ── MCPClientDelegate (called by Swift) ──────────────────────────────────

    override fun onPeerFound(peerId: String, deviceName: String, identifier: String) {
        val device = IoTDevice(
            id = identifier.ifBlank { peerId },
            name = deviceName,
            connectionType = ConnectionType.WIFI_DIRECT,
            address = peerId
        )
        discoveredPeers[peerId] = device
        onUpdateCallback?.invoke(discoveredPeers.values.sortedBy { it.id })
        println("[MCPClient] Peer found: $deviceName ($peerId)")
    }

    override fun onPeerLost(peerId: String) {
        discoveredPeers.remove(peerId)
        onUpdateCallback?.invoke(discoveredPeers.values.sortedBy { it.id })
        println("[MCPClient] Peer lost: $peerId")
    }

    override fun onConnectionResult(success: Boolean) {
        val cb = connectContinuation
        connectContinuation = null
        cb?.invoke(
            if (success) Result.success(Unit)
            else Result.failure(Exception("MPC connection failed"))
        )
    }

    override fun onDisconnected() {
        val cb = connectContinuation
        connectContinuation = null
        cb?.invoke(Result.failure(Exception("MPC connection dropped before completing")))
    }

    override fun onDataReceived(data: NSData) {
        _incoming.tryEmit(data.toByteArray())
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun scan(): Flow<List<IoTDevice>> = callbackFlow {
        bridge?.delegate = this@WifiDirectClientHandler
        onUpdateCallback = { trySend(it) }
        bridge?.startBrowsing()

        awaitClose {
            bridge?.stopBrowsing()
            discoveredPeers.clear()
            onUpdateCallback = null
        }
    }

    suspend fun connect(device: IoTDevice) = suspendCancellableCoroutine<Unit> { cont ->
        connectContinuation = { result ->
            if (cont.isActive) result.fold(
                onSuccess = { cont.resume(Unit) },
                onFailure = { cont.resumeWithException(it) }
            )
        }
        bridge?.invitePeer(device.address ?: "")
    }

    fun sendToServer(data: ByteArray, writeType: WriteType) {
        bridge?.sendData(data.toNSData())
    }

    fun receiveFromServer(): Flow<ByteArray> = _incoming.asSharedFlow()

    fun disconnect() {
        bridge?.disconnect()
        connectContinuation = null
        println("[MCPClient] Disconnected")
    }
}
