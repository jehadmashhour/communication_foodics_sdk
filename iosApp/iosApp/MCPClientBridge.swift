import Foundation
import MultipeerConnectivity
import UIKit
import shared   // KMP framework — MCPClientBridgeProtocol / MCPClientDelegate live here

/// Implements the Kotlin-defined MCPClientBridgeProtocol (ObjC protocol).
/// Acts as the MultipeerConnectivity browser / client role.
///
/// Swift sets itself on MultipeerBridgeProvider.shared during app init:
///   MultipeerBridgeProvider.shared.clientBridge = MCPClientBridge()
public class MCPClientBridge: NSObject, MCPClientBridgeProtocol {

    private let serviceType = "foodics-p2p"

    public var delegate: (any MCPClientDelegate)?

    // This device's peer ID — created once, reused across sessions.
    private let myPeerID = MCPeerID(displayName: UIDevice.current.name)

    private var session: MCSession?
    private var browser: MCNearbyServiceBrowser?

    // Maps MCPeerID.displayName → MCPeerID so invitePeer(peerId:) can look up the object.
    private var discoveredPeers: [String: MCPeerID] = [:]
    private var connectedServerPeer: MCPeerID?

    // MARK: - MCPClientBridgeProtocol

    public func startBrowsing() {
        stopBrowsing()

        let session = MCSession(peer: myPeerID, securityIdentity: nil, encryptionPreference: .required)
        session.delegate = self
        self.session = session

        let browser = MCNearbyServiceBrowser(peer: myPeerID, serviceType: serviceType)
        browser.delegate = self
        browser.startBrowsingForPeers()
        self.browser = browser

        NSLog("[MCPClient] Browsing started")
    }

    public func stopBrowsing() {
        browser?.stopBrowsingForPeers()
        session?.disconnect()
        browser = nil
        session = nil
        discoveredPeers.removeAll()
        connectedServerPeer = nil
        NSLog("[MCPClient] Browsing stopped")
    }

    public func invitePeer(peerId: String) {
        guard let peerID = discoveredPeers[peerId],
              let session = session,
              let browser = browser else {
            NSLog("[MCPClient] invitePeer: peer '\(peerId)' not found or session missing")
            return
        }
        connectedServerPeer = peerID
        browser.invitePeer(peerID, to: session, withContext: nil, timeout: 30)
        NSLog("[MCPClient] Invited peer: \(peerId)")
    }

    public func sendData(data: Data) {
        guard let session = session, let serverPeer = connectedServerPeer else {
            NSLog("[MCPClient] sendData: not connected to any peer")
            return
        }
        do {
            try session.send(data, toPeers: [serverPeer], with: .reliable)
        } catch {
            NSLog("[MCPClient] sendData error: \(error)")
        }
    }

    public func disconnect() {
        session?.disconnect()
        connectedServerPeer = nil
        NSLog("[MCPClient] Disconnected")
    }
}

// MARK: - MCNearbyServiceBrowserDelegate

extension MCPClientBridge: MCNearbyServiceBrowserDelegate {

    public func browser(
        _ browser: MCNearbyServiceBrowser,
        foundPeer peerID: MCPeerID,
        withDiscoveryInfo info: [String: String]?
    ) {
        discoveredPeers[peerID.displayName] = peerID
        let identifier = info?["identifier"] ?? ""
        NSLog("[MCPClient] Found peer: \(peerID.displayName), identifier: \(identifier)")
        delegate?.onPeerFound(peerId: peerID.displayName, deviceName: peerID.displayName, identifier: identifier)
    }

    public func browser(_ browser: MCNearbyServiceBrowser, lostPeer peerID: MCPeerID) {
        discoveredPeers.removeValue(forKey: peerID.displayName)
        NSLog("[MCPClient] Lost peer: \(peerID.displayName)")
        delegate?.onPeerLost(peerId: peerID.displayName)
    }

    public func browser(_ browser: MCNearbyServiceBrowser, didNotStartBrowsingForPeers error: Error) {
        NSLog("[MCPClient] Failed to start browsing: \(error)")
    }
}

// MARK: - MCSessionDelegate

extension MCPClientBridge: MCSessionDelegate {

    public func session(_ session: MCSession, peer peerID: MCPeerID, didChange state: MCSessionState) {
        switch state {
        case .connected:
            NSLog("[MCPClient] Connected to: \(peerID.displayName)")
            delegate?.onConnectionResult(success: true)
        case .notConnected:
            NSLog("[MCPClient] Disconnected from: \(peerID.displayName)")
            if peerID == connectedServerPeer {
                connectedServerPeer = nil
                delegate?.onDisconnected()
            }
        case .connecting:
            NSLog("[MCPClient] Connecting to: \(peerID.displayName)")
        @unknown default:
            break
        }
    }

    public func session(_ session: MCSession, didReceive data: Data, fromPeer peerID: MCPeerID) {
        NSLog("[MCPClient] Received \(data.count) bytes from \(peerID.displayName)")
        delegate?.onDataReceived(data: data)
    }

    // Required but unused
    public func session(_ session: MCSession, didReceive stream: InputStream, withName streamName: String, fromPeer peerID: MCPeerID) {}
    public func session(_ session: MCSession, didStartReceivingResourceWithName resourceName: String, fromPeer peerID: MCPeerID, with progress: Progress) {}
    public func session(_ session: MCSession, didFinishReceivingResourceWithName resourceName: String, fromPeer peerID: MCPeerID, at localURL: URL?, withError error: Error?) {}
}
