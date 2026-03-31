package com.foodics.crosscommunicationlibrary.qr

/**
 * Singleton holding the Swift bridge implementations.
 *
 * Register before any QR handler is instantiated (inside iOSApp.init()):
 *
 *   QRBridgeProvider.shared.scannerBridge   = QRClientBridge()
 *   QRBridgeProvider.shared.generatorBridge = QRServerBridge()
 */
object QRBridgeProvider {
    var scannerBridge:   QRScannerBridgeProtocol?   = null
    var generatorBridge: QRGeneratorBridgeProtocol? = null
}
