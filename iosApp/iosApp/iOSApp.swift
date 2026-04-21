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
        #if DEBUG
        let variant = "debug"
        #else
        let variant = "release"
        #endif

        Datadog.initialize(
            with: Datadog.Configuration(
                clientToken: "pub4f51a761a700a9caf8c15aa221bc4dd4",
                env: "dev",
                service: "CrossCommunicationLibrary-IOS"
            ),
            trackingConsent: .granted
        )
        Logs.enable()

        let ddLogger = Logger.create(with: Logger.Configuration(
            name: "CrossCommunicationLibrary",
            networkInfoEnabled: true
        ))
        ddLogger.addAttribute(forKey: "variant", value: variant)

        DatadogBridge.shared.logSink = { level, message, attributes in
            var encodable: [String: Encodable] = [:]
            for (key, value) in attributes {
                switch value {
                case let v as String:        encodable[key] = v
                case let v as NSString:      encodable[key] = String(v)
                case let v as KotlinInt:     encodable[key] = v.intValue
                case let v as KotlinLong:    encodable[key] = v.int64Value
                case let v as KotlinDouble:  encodable[key] = v.doubleValue
                case let v as KotlinFloat:   encodable[key] = v.floatValue
                case let v as KotlinBoolean: encodable[key] = v.boolValue
                case let v as NSNumber:      encodable[key] = v.doubleValue
                default: break
                }
            }
            switch level {
            case "DEBUG": ddLogger.debug(message, attributes: encodable)
            case "INFO":  ddLogger.info(message,  attributes: encodable)
            case "WARN":  ddLogger.warn(message,  attributes: encodable)
            default:      ddLogger.error(message, attributes: encodable)
            }
        }
    }
}