import Foundation
import UIKit
import NearbyConnections
import shared   // KMP framework — NearbyServerBridgeProtocol lives here

/// Implements the Kotlin-defined NearbyServerBridgeProtocol (ObjC protocol).
/// Swift sets itself as `serverBridge` on NearbyBridgeProvider during app init.
/// All Nearby SDK calls originate here; all events flow back via the delegate.
public class NearbyServerBridge: NSObject, NearbyServerBridgeProtocol {

    public var delegate: (any NearbyServerDelegate)?

    private let connectionManager: ConnectionManager
    private var advertiser: Advertiser?
    private var connectedEndpoints: [EndpointID] = []

    public override init() {
        connectionManager = ConnectionManager(
            serviceID: "com.foodics.crosscommunicationlibrary",
            strategy: .star
        )
        super.init()
        connectionManager.delegate = self
    }

    // MARK: - NearbyServerBridgeProtocol

    public func startAdvertising(endpointName: String) {
        advertiser = Advertiser(connectionManager: connectionManager)
        advertiser?.delegate = self
        advertiser?.startAdvertising(using: endpointName.data(using: .utf8) ?? Data())
        NSLog("[NearbyServer] Advertising started as: \(endpointName)")
    }

    public func sendData(data: Data) {
        guard !connectedEndpoints.isEmpty else {
            NSLog("[NearbyServer] sendData: no connected endpoints")
            return
        }
        _ = connectionManager.send(data, to: connectedEndpoints)
    }

    public func stopAdvertising() {
        advertiser?.stopAdvertising()
        advertiser = nil
        connectedEndpoints.forEach { connectionManager.disconnect(from: $0) }
        connectedEndpoints.removeAll()
        NSLog("[NearbyServer] Stopped advertising")
    }
}

// MARK: - AdvertiserDelegate

extension NearbyServerBridge: AdvertiserDelegate {
    public func advertiser(
        _ advertiser: Advertiser,
        didReceiveConnectionRequestFrom endpointID: EndpointID,
        with context: Data,
        connectionRequestHandler: @escaping (Bool) -> Void
    ) {
        NSLog("[NearbyServer] Connection request from: \(endpointID)")
        connectionRequestHandler(true)
    }
}

// MARK: - ConnectionManagerDelegate

extension NearbyServerBridge: ConnectionManagerDelegate {

    public func connectionManager(
        _ connectionManager: ConnectionManager,
        didReceive verificationCode: String,
        from endpointID: EndpointID,
        verificationHandler: @escaping (Bool) -> Void
    ) {
        verificationHandler(true)
    }

    public func connectionManager(
        _ connectionManager: ConnectionManager,
        didChangeTo state: ConnectionState,
        for endpointID: EndpointID
    ) {
        switch state {
        case .connecting:
            NSLog("[NearbyServer] Client connecting: \(endpointID)")
        case .connected:
            connectedEndpoints.append(endpointID)
            delegate?.onClientConnected(endpointId: endpointID)
            NSLog("[NearbyServer] Client connected: \(endpointID)")
        case .disconnected:
            connectedEndpoints.removeAll { $0 == endpointID }
            delegate?.onClientDisconnected(endpointId: endpointID)
            NSLog("[NearbyServer] Client disconnected: \(endpointID)")
        @unknown default:
            break
        }
    }

    public func connectionManager(
        _ connectionManager: ConnectionManager,
        didReceive data: Data,
        withID payloadID: PayloadID,
        from endpointID: EndpointID
    ) {
        NSLog("[NearbyServer] Received \(data.count) bytes from \(endpointID)")
        delegate?.onDataReceived(data: data)
    }

    public func connectionManager(
        _ connectionManager: ConnectionManager,
        didReceive stream: InputStream,
        withID payloadID: PayloadID,
        from endpointID: EndpointID,
        cancellationToken token: CancellationToken
    ) {}

    public func connectionManager(
        _ connectionManager: ConnectionManager,
        didStartReceivingResourceWithID payloadID: PayloadID,
        from endpointID: EndpointID,
        at localURL: URL,
        withName name: String,
        cancellationToken token: CancellationToken
    ) {}

    public func connectionManager(
        _ connectionManager: ConnectionManager,
        didReceiveTransferUpdate update: TransferUpdate,
        from endpointID: EndpointID,
        forPayload payloadID: PayloadID
    ) {}
}