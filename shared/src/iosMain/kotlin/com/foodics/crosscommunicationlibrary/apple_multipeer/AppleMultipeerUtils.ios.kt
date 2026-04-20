@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.foodics.crosscommunicationlibrary.apple_multipeer

import com.foodics.crosscommunicationlibrary.cloud.toByteArray
import platform.Foundation.*
import platform.MultipeerConnectivity.*
import platform.darwin.NSObject

/** mDNS-compatible service type: 1-15 chars, lowercase, alphanumeric + hyphens. */
internal const val MPC_SERVICE_TYPE = "foodics-mpc"

// ── Session delegate ──────────────────────────────────────────────────────────

internal class MPCSessionDelegate : NSObject(), MCSessionDelegateProtocol {

    var onData: ((ByteArray) -> Unit)? = null
    var onConnected: ((MCPeerID) -> Unit)? = null
    var onDisconnected: ((MCPeerID) -> Unit)? = null

    // Required: peer connection state changed
    override fun session(session: MCSession, peer: MCPeerID, didChangeState: MCSessionState) {
        when (didChangeState) {
            MCSessionState.MCSessionStateConnected    -> onConnected?.invoke(peer)
            MCSessionState.MCSessionStateNotConnected -> onDisconnected?.invoke(peer)
            else                                      -> Unit
        }
    }

    // Required: received a raw data message
    override fun session(session: MCSession, didReceiveData: NSData, fromPeer: MCPeerID) {
        onData?.invoke(didReceiveData.toByteArray())
    }

    // Required stubs (stream / resource APIs not used)
    override fun session(
        session: MCSession, didReceiveStream: NSInputStream,
        withName: String, fromPeer: MCPeerID
    ) {}

    override fun session(
        session: MCSession, didStartReceivingResourceWithName: String,
        fromPeer: MCPeerID, withProgress: NSProgress
    ) {}

    override fun session(
        session: MCSession, didFinishReceivingResourceWithName: String,
        fromPeer: MCPeerID, atURL: NSURL?, withError: NSError?
    ) {}
}

// ── Advertiser delegate (server) ──────────────────────────────────────────────

internal class MPCAdvertiserDelegate(
    private val session: MCSession
) : NSObject(), MCNearbyServiceAdvertiserDelegateProtocol {

    /** Auto-accept every incoming invitation. */
    override fun advertiser(
        advertiser: MCNearbyServiceAdvertiser,
        didReceiveInvitationFromPeer: MCPeerID,
        withContext: NSData?,
        invitationHandler: (Boolean, MCSession?) -> Unit
    ) {
        invitationHandler(true, session)
    }

    override fun advertiser(
        advertiser: MCNearbyServiceAdvertiser,
        didNotStartAdvertisingPeer: NSError
    ) {
        println("[MPCServer] Advertising error: ${didNotStartAdvertisingPeer.localizedDescription}")
    }
}

// ── Browser delegate (client) ─────────────────────────────────────────────────

internal class MPCBrowserDelegate : NSObject(), MCNearbyServiceBrowserDelegateProtocol {

    var onFound: ((MCPeerID, Map<Any?, *>?) -> Unit)? = null
    var onLost: ((MCPeerID) -> Unit)? = null

    override fun browser(
        browser: MCNearbyServiceBrowser,
        foundPeer: MCPeerID,
        withDiscoveryInfo: Map<Any?, *>?
    ) {
        onFound?.invoke(foundPeer, withDiscoveryInfo)
    }

    override fun browser(browser: MCNearbyServiceBrowser, lostPeer: MCPeerID) {
        onLost?.invoke(lostPeer)
    }

    override fun browser(
        browser: MCNearbyServiceBrowser,
        didNotStartBrowsingForPeers: NSError
    ) {
        println("[MPCClient] Browse error: ${didNotStartBrowsingForPeers.localizedDescription}")
    }
}
