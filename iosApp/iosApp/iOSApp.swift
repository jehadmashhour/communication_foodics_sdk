import SwiftUI
import shared
import DatadogCore
import DatadogLogs

@main
struct iOSApp: App {

    init() {
        NearbyBridgeProvider.shared.clientBridge = NearbyClientBridge()
        NearbyBridgeProvider.shared.serverBridge = NearbyServerBridge()
        MultipeerBridgeProvider.shared.serverBridge = MCPServerBridge()
        MultipeerBridgeProvider.shared.clientBridge = MCPClientBridge()
        QRBridgeProvider.shared.scannerBridge   = QRClientBridge()
        QRBridgeProvider.shared.generatorBridge = QRServerBridge()
        WebRTCBridgeProvider.shared.serverBridge = WebRTCServerBridge()
        WebRTCBridgeProvider.shared.clientBridge = WebRTCClientBridge()

        setupDatadogLogging()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }

    private func setupDatadogLogging() {
        let env = "dev"
        #if DEBUG
        let variant = "debug"
        #else
        let variant = "release"
        #endif

        Datadog.initialize(
            with: Datadog.Configuration(
                clientToken: "pub4f51a761a700a9caf8c15aa221bc4dd4",
                env: env,
                service: "CrossCommunicationLibrary-IOS",
                variant: variant
            ),
            trackingConsent: .granted
        )
        Logs.enable()

        let ddLogger = Logger.create(with: Logger.Configuration(
            name: "CrossCommunicationLibrary",
            networkInfoEnabled: true
        ))

        DatadogBridge.shared.logSink = { level, message, attributes in
            let encodable = attributes.compactMapValues { $0 as? Encodable }
            switch level {
            case "DEBUG": ddLogger.debug(message, attributes: encodable)
            case "INFO":  ddLogger.info(message,  attributes: encodable)
            case "WARN":  ddLogger.warn(message,  attributes: encodable)
            default:      ddLogger.error(message, attributes: encodable)
            }
        }
    }
}