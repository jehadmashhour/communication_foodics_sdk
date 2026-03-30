import Foundation
import UIKit
import NearbyConnections
import shared   // KMP framework — NearbyClientBridgeProtocol lives here

/// Implements the Kotlin-defined NearbyClientBridgeProtocol (ObjC protocol).
/// Kotlin sets itself as `delegate` during GoogleNearbyClientHandler.init().
/// All Nearby SDK calls originate here; all events flow back via the delegate.
public class NearbyClientBridge: NSObject, NearbyClientBridgeProtocol {

    public var delegate: (any NearbyClientDelegate)?

    private let connectionManager: ConnectionManager
    private var discoverer: Discoverer?
    private var connectedEndpointID: EndpointID?

    public override init() {
        connectionManager = ConnectionManager(
            serviceID: "com.foodics.crosscommunicationlibrary",
            strategy: .star
        )
        super.init()
        connectionManager.delegate = self
    }

    // MARK: - NearbyClientBridgeProtocol

    public func startDiscovery() {
        discoverer = Discoverer(connectionManager: connectionManager)
        discoverer?.delegate = self
        discoverer?.startDiscovery()
        NSLog("[NearbyClient] Discovery started")
    }

    public func stopDiscovery() {
        discoverer?.stopDiscovery()
        discoverer = nil
        NSLog("[NearbyClient] Discovery stopped")
    }

    public func requestConnection(endpointId: String) {
        discoverer?.requestConnection(
            to: endpointId,
            using: UIDevice.current.name.data(using: .utf8) ?? Data()
        )
        NSLog("[NearbyClient] Requested connection to: \(endpointId)")
    }

    public func sendData(data: Data) {
        guard let endpointID = connectedEndpointID else {
            NSLog("[NearbyClient] sendData: no connected endpoint")
            return
        }
        _ = connectionManager.send(data, to: [endpointID])
    }

    public func disconnect() {
        if let endpointID = connectedEndpointID {
            connectionManager.disconnect(from: endpointID)
        }
        connectedEndpointID = nil
        NSLog("[NearbyClient] Disconnected")
    }
}

// MARK: - DiscovererDelegate

extension NearbyClientBridge: DiscovererDelegate {
    public func discoverer(
        _ discoverer: Discoverer,
        didFind endpointID: EndpointID,
        with context: Data
    ) {
        let endpointName = String(data: context, encoding: .utf8) ?? endpointID
        NSLog("[NearbyClient] Found: \(endpointID) name: \(endpointName)")
        delegate?.onEndpointFound(endpointId: endpointID, endpointName: endpointName)
    }

    public func discoverer(_ discoverer: Discoverer, didLose endpointID: EndpointID) {
        NSLog("[NearbyClient] Lost: \(endpointID)")
        delegate?.onEndpointLost(endpointId: endpointID)
    }
}

// MARK: - ConnectionManagerDelegate

extension NearbyClientBridge: ConnectionManagerDelegate {

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
            NSLog("[NearbyClient] Connecting to: \(endpointID)")
        case .connected:
            connectedEndpointID = endpointID
            delegate?.onConnectionResult(success: true)
            NSLog("[NearbyClient] Connected to: \(endpointID)")
        case .disconnected:
            if connectedEndpointID == endpointID {
                connectedEndpointID = nil
                delegate?.onDisconnected()
            }
            NSLog("[NearbyClient] Disconnected from: \(endpointID)")
        case .rejected:
            delegate?.onConnectionResult(success: false)
            NSLog("[NearbyClient] Connection rejected by: \(endpointID)")
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
        NSLog("[NearbyClient] Received \(data.count) bytes from \(endpointID)")
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