import SwiftUI
import shared   // KMP framework — NearbyBridgeProvider lives here

@main
struct iOSApp: App {

    init() {
        // Register the Swift bridge implementations with the KMP singleton
        // before any Kotlin Nearby handler is instantiated.
        NearbyBridgeProvider.shared.clientBridge = NearbyClientBridge()
        NearbyBridgeProvider.shared.serverBridge = NearbyServerBridge()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}