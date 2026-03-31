import SwiftUI
import shared   // KMP framework — NearbyBridgeProvider lives here

@main
struct iOSApp: App {

    init() {
        // Register the Swift bridge implementations with the KMP singleton
        // before any Kotlin Nearby handler is instantiated.
        NearbyBridgeProvider.shared.clientBridge = NearbyClientBridge()
        NearbyBridgeProvider.shared.serverBridge = NearbyServerBridge()

        // Register MultipeerConnectivity bridges for WifiDirect channel.
        MultipeerBridgeProvider.shared.serverBridge = MCPServerBridge()
        MultipeerBridgeProvider.shared.clientBridge = MCPClientBridge()

        // Register QR Code bridges.
        QRBridgeProvider.shared.scannerBridge   = QRClientBridge()
        QRBridgeProvider.shared.generatorBridge = QRServerBridge()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}