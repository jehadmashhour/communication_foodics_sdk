import Foundation
import UIKit
import NearbyConnections

@objc(NearbyClientBridge)          // ← pins ObjC runtime name, no module prefix
public class NearbyClientBridge: NSObject {

    @objc public static let shared = NearbyClientBridge()

    // Closures set by Kotlin iosMain instead of direct class references
    @objc public var onEndpointFound: ((String, String) -> Void)?
    @objc public var onEndpointLost: ((String) -> Void)?
    @objc public var onConnectionResult: ((Bool) -> Void)?
    @objc public var onDataReceived: ((Data) -> Void)?

    private let connectionManager: ConnectionManager
    private var discoverer: Discoverer?
    private var connectedEndpointID: EndpointID?

    private override init() {
        connectionManager = ConnectionManager(
            serviceID: "com.foodics.crosscommunicationlibrary",
            strategy: .star
        )
        super.init()
        connectionManager.delegate = self
    }

    // MARK: - Called from Kotlin iosMain

    @objc public func startDiscovery() {
        discoverer = Discoverer(connectionManager: connectionManager)
        discoverer?.delegate = self
        discoverer?.startDiscovery()
        NSLog("[NearbyClient] Discovery started")
    }

    @objc public func stopDiscovery() {
        discoverer?.stopDiscovery()
        discoverer = nil
        NSLog("[NearbyClient] Discovery stopped")
    }

    @objc public func requestConnection(_ endpointID: String) {
        discoverer?.requestConnection(
            to: endpointID,
            using: UIDevice.current.name.data(using: .utf8) ?? Data()
        )
        NSLog("[NearbyClient] Requested connection to: \(endpointID)")
    }

    @objc public func sendData(_ data: Data) {
        guard let endpointID = connectedEndpointID else {
            NSLog("[NearbyClient] sendData: no connected endpoint")
            return
        }
        _ = connectionManager.send(data, to: [endpointID])
    }

    @objc public func disconnect() {
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
        onEndpointFound?(endpointID, endpointName)
    }

    public func discoverer(_ discoverer: Discoverer, didLose endpointID: EndpointID) {
        NSLog("[NearbyClient] Lost: \(endpointID)")
        onEndpointLost?(endpointID)
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
        verificationHandler(true) // auto-accept
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
            onConnectionResult?(true)
            NSLog("[NearbyClient] Connected to: \(endpointID)")
        case .disconnected:
            if connectedEndpointID == endpointID { connectedEndpointID = nil }
            NSLog("[NearbyClient] Disconnected from: \(endpointID)")
        case .rejected:
            onConnectionResult?(false)
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
        onDataReceived?(data)
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