package com.foodics.crosscommunicationlibrary.nfc

/**
 * Singleton that holds the Swift bridge for iOS NFC scanning.
 *
 * Populate in iOSApp.init() before any NFC channel is used:
 *   NFCBridgeProvider.shared.scannerBridge = NFCScannerBridge()
 */
object NFCBridgeProvider {
    var scannerBridge: NFCScannerBridgeProtocol? = null
}
