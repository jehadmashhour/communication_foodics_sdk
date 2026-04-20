@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.foodics.crosscommunicationlibrary.apple_multipeer

import com.foodics.crosscommunicationlibrary.cloud.toNSData
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import platform.MultipeerConnectivity.*
import kotlin.concurrent.Volatile

actual class AppleMultipeerServerHandler actual constructor() {

    private val _fromClient = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    private val sessionDelegate = MPCSessionDelegate()
    private val connectedPeers = mutableListOf<MCPeerID>()

    @Volatile private var peerID: MCPeerID? = null
    @Volatile private var session: MCSession? = null
    @Volatile private var advertiser: MCNearbyServiceAdvertiser? = null
    private var advertiserDelegate: MPCAdvertiserDelegate? = null

    init {
        sessionDelegate.onData        = { _fromClient.tryEmit(it) }
        sessionDelegate.onConnected   = { connectedPeers.add(it); println("[MPCServer] Peer connected: ${it.displayName}") }
        sessionDelegate.onDisconnected = { connectedPeers.remove(it); println("[MPCServer] Peer disconnected: ${it.displayName}") }
    }

    // ── Start ─────────────────────────────────────────────────────────────────

    suspend fun start(deviceName: String, identifier: String) {
        stop()

        // Encode identity in displayName: "deviceName|identifier"
        val myPeerID = MCPeerID(displayName = "$deviceName|$identifier")
        peerID = myPeerID

        val mySession = MCSession(
            peer = myPeerID,
            securityIdentity = null,
            encryptionPreference = MCEncryptionOptional
        )
        mySession.delegate = sessionDelegate
        session = mySession

        val adDelegate = MPCAdvertiserDelegate(mySession)
        advertiserDelegate = adDelegate

        val adv = MCNearbyServiceAdvertiser(
            peer = myPeerID,
            discoveryInfo = null,
            serviceType = MPC_SERVICE_TYPE
        )
        adv.delegate = adDelegate
        advertiser = adv

        withContext(Dispatchers.Main) { adv.startAdvertisingPeer() }
        println("[MPCServer] Started: $deviceName [$identifier]")
    }

    // ── Send ──────────────────────────────────────────────────────────────────

    fun sendToClient(data: ByteArray) {
        val sess = session ?: run { println("[MPCServer] No active session"); return }
        val peers = connectedPeers.toList()
        if (peers.isEmpty()) { println("[MPCServer] No connected peers"); return }
        runCatching {
            sess.sendData(data.toNSData(), toPeers = peers, with = MCSessionSendDataReliable, error = null)
        }.onFailure { println("[MPCServer] Send error: ${it.message}") }
    }

    fun receiveFromClient(): Flow<ByteArray> = _fromClient.asSharedFlow()

    // ── Stop ──────────────────────────────────────────────────────────────────

    suspend fun stop() {
        val adv = advertiser
        withContext(Dispatchers.Main) { adv?.stopAdvertisingPeer() }
        session?.disconnect()
        connectedPeers.clear()
        advertiser = null
        advertiserDelegate = null
        session = null
        peerID = null
        println("[MPCServer] Stopped")
    }
}
