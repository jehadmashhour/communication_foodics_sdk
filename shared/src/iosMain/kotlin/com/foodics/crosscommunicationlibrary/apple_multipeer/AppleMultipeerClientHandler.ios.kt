@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.foodics.crosscommunicationlibrary.apple_multipeer

import ConnectionType
import client.WriteType
import com.foodics.crosscommunicationlibrary.cloud.toNSData
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import platform.MultipeerConnectivity.*
import scanner.IoTDevice
import kotlin.concurrent.Volatile

actual class AppleMultipeerClientHandler actual constructor() {

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    private val sessionDelegate = MPCSessionDelegate()
    private val browserDelegate = MPCBrowserDelegate()

    // Peers currently visible to the browser: displayName → MCPeerID
    private val discoveredPeers = mutableMapOf<String, MCPeerID>()
    private var scanCallback: ((List<IoTDevice>) -> Unit)? = null

    @Volatile private var peerID: MCPeerID? = null
    @Volatile private var session: MCSession? = null
    @Volatile private var browser: MCNearbyServiceBrowser? = null
    private var connectDeferred: CompletableDeferred<Unit>? = null

    init {
        sessionDelegate.onData = { _incoming.tryEmit(it) }
        sessionDelegate.onConnected = { peer ->
            println("[MPCClient] Connected to ${peer.displayName}")
            connectDeferred?.complete(Unit)
            connectDeferred = null
        }
        sessionDelegate.onDisconnected = { peer ->
            println("[MPCClient] Disconnected from ${peer.displayName}")
            connectDeferred?.completeExceptionally(Exception("Peer disconnected before session was established"))
            connectDeferred = null
        }

        browserDelegate.onFound = { peer, _ ->
            discoveredPeers[peer.displayName] = peer
            scanCallback?.invoke(buildDeviceList())
        }
        browserDelegate.onLost = { peer ->
            discoveredPeers.remove(peer.displayName)
            scanCallback?.invoke(buildDeviceList())
        }
    }

    // ── Scan ──────────────────────────────────────────────────────────────────

    fun scan(): Flow<List<IoTDevice>> = channelFlow {
        val myPeerID = MCPeerID(displayName = "mpc-scanner")
        val brw = MCNearbyServiceBrowser(peer = myPeerID, serviceType = MPC_SERVICE_TYPE)
        brw.delegate = browserDelegate
        browser = brw

        scanCallback = { trySend(it) }
        trySend(buildDeviceList())

        withContext(Dispatchers.Main) { brw.startBrowsingForPeers() }

        awaitClose {
            brw.delegate = null
            withContext(Dispatchers.Main) { brw.stopBrowsingForPeers() }
            browser = null
            scanCallback = null
            discoveredPeers.clear()
        }
    }.distinctUntilChanged()

    // ── Connect ───────────────────────────────────────────────────────────────

    suspend fun connect(device: IoTDevice) {
        disconnect()

        val targetPeerID = discoveredPeers[device.address]
            ?: run { println("[MPCClient] Peer not in discovered list: ${device.address}"); return }

        val myPeerID = MCPeerID(displayName = "mpc-client")
        peerID = myPeerID

        val mySession = MCSession(
            peer = myPeerID,
            securityIdentity = null,
            encryptionPreference = MCEncryptionOptional
        )
        mySession.delegate = sessionDelegate
        session = mySession

        val brw = MCNearbyServiceBrowser(peer = myPeerID, serviceType = MPC_SERVICE_TYPE)
        brw.delegate = browserDelegate
        browser = brw

        val deferred = CompletableDeferred<Unit>()
        connectDeferred = deferred

        withContext(Dispatchers.Main) {
            brw.startBrowsingForPeers()
            brw.invitePeer(targetPeerID, toSession = mySession, withContext = null, timeout = 10.0)
        }

        withTimeoutOrNull(12_000) { deferred.await() }
            ?: println("[MPCClient] Connect timed out")
    }

    // ── Send ──────────────────────────────────────────────────────────────────

    fun sendToServer(data: ByteArray, writeType: WriteType) {
        val sess = session ?: run { println("[MPCClient] Not connected"); return }
        val peers = sess.connectedPeers
        if (peers.isEmpty()) { println("[MPCClient] No connected peers"); return }
        @Suppress("UNCHECKED_CAST")
        runCatching {
            sess.sendData(data.toNSData(), toPeers = peers as List<MCPeerID>, with = MCSessionSendDataReliable, error = null)
        }.onFailure { println("[MPCClient] Send error: ${it.message}") }
    }

    fun receiveFromServer(): Flow<ByteArray> = _incoming.asSharedFlow()

    // ── Disconnect ────────────────────────────────────────────────────────────

    fun disconnect() {
        val brw = browser
        withContext(Dispatchers.Main) { brw?.stopBrowsingForPeers() }
        session?.disconnect()
        connectDeferred?.cancel()
        connectDeferred = null
        browser = null
        session = null
        peerID = null
        println("[MPCClient] Disconnected")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildDeviceList(): List<IoTDevice> =
        discoveredPeers.map { (displayName, _) ->
            val parts = displayName.split("|")
            IoTDevice(
                id = parts.getOrElse(1) { displayName },
                name = parts[0],
                address = displayName,   // used as key to retrieve MCPeerID on connect
                connectionType = ConnectionType.APPLE_MULTIPEER
            )
        }
}
