import Foundation
import MultipeerConnectivity
import shared   // KMP framework — MCPServerBridgeProtocol / MCPServerDelegate live here

/// Implements the Kotlin-defined MCPServerBridgeProtocol (ObjC protocol).
/// Acts as the MultipeerConnectivity advertiser / host role.
///
/// Swift sets itself on MultipeerBridgeProvider.shared during app init:
///   MultipeerBridgeProvider.shared.serverBridge = MCPServerBridge()
public class MCPServerBridge: NSObject, MCPServerBridgeProtocol {

    // Service type: 1-15 chars, lowercase letters/digits/hyphens only.
    private let serviceType = "foodics-p2p"

    public var delegate: (any MCPServerDelegate)?

    private var myPeerID: MCPeerID?
    private var session: MCSession?
    private var advertiser: MCNearbyServiceAdvertiser?

    // MARK: - MCPServerBridgeProtocol

    public func startAdvertising(deviceName: String, identifier: String) {
        stopAdvertising()

        let peerID = MCPeerID(displayName: deviceName)
        myPeerID = peerID

        let session = MCSession(peer: peerID, securityIdentity: nil, encryptionPreference: .required)
        session.delegate = self
        self.session = session

        let discoveryInfo = ["identifier": identifier]
        let advertiser = MCNearbyServiceAdvertiser(peer: peerID, discoveryInfo: discoveryInfo, serviceType: serviceType)
        advertiser.delegate = self
        advertiser.startAdvertisingPeer()
        self.advertiser = advertiser

        NSLog("[MCPServer] Advertising started as: \(deviceName)")
    }

    public func sendData(data: Data) {
        guard let session = session, !session.connectedPeers.isEmpty else {
            NSLog("[MCPServer] sendData: no connected peers")
            return
        }
        do {
            try session.send(data, toPeers: session.connectedPeers, with: .reliable)
        } catch {
            NSLog("[MCPServer] sendData error: \(error)")
        }
    }

    public func stopAdvertising() {
        advertiser?.stopAdvertisingPeer()
        session?.disconnect()
        advertiser = nil
        session = nil
        myPeerID = nil
        NSLog("[MCPServer] Stopped")
    }
}

// MARK: - MCNearbyServiceAdvertiserDelegate

extension MCPServerBridge: MCNearbyServiceAdvertiserDelegate {

    public func advertiser(
        _ advertiser: MCNearbyServiceAdvertiser,
        didReceiveInvitationFromPeer peerID: MCPeerID,
        withContext context: Data?,
        invitationHandler: @escaping (Bool, MCSession?) -> Void
    ) {
        NSLog("[MCPServer] Invitation from: \(peerID.displayName) — accepting")
        invitationHandler(true, session)
    }

    public func advertiser(_ advertiser: MCNearbyServiceAdvertiser, didNotStartAdvertisingPeer error: Error) {
        NSLog("[MCPServer] Failed to start advertising: \(error)")
    }
}

// MARK: - MCSessionDelegate

extension MCPServerBridge: MCSessionDelegate {

    public func session(_ session: MCSession, peer peerID: MCPeerID, didChange state: MCSessionState) {
        switch state {
        case .connected:
            NSLog("[MCPServer] Peer connected: \(peerID.displayName)")
            delegate?.onPeerConnected(peerId: peerID.displayName)
        case .notConnected:
            NSLog("[MCPServer] Peer disconnected: \(peerID.displayName)")
            delegate?.onPeerDisconnected(peerId: peerID.displayName)
        case .connecting:
            NSLog("[MCPServer] Peer connecting: \(peerID.displayName)")
        @unknown default:
            break
        }
    }

    public func session(_ session: MCSession, didReceive data: Data, fromPeer peerID: MCPeerID) {
        NSLog("[MCPServer] Received \(data.count) bytes from \(peerID.displayName)")
        delegate?.onDataReceived(data: data)
    }

    // Required but unused
    public func session(_ session: MCSession, didReceive stream: InputStream, withName streamName: String, fromPeer peerID: MCPeerID) {}
    public func session(_ session: MCSession, didStartReceivingResourceWithName resourceName: String, fromPeer peerID: MCPeerID, with progress: Progress) {}
    public func session(_ session: MCSession, didFinishReceivingResourceWithName resourceName: String, fromPeer peerID: MCPeerID, at localURL: URL?, withError error: Error?) {}
}
