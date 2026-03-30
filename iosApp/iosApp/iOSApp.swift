import SwiftUI

@main
struct iOSApp: App {

    init() {
        // Force ObjC class registration before the KMP shared framework
        // initializes its lazy bridge references. Without this, Swift classes
        // in a static framework may not be registered in the ObjC runtime yet
        // when Kotlin calls NSClassFromString on a background thread.
        _ = NearbyServerBridge.shared
        _ = NearbyClientBridge.shared
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}