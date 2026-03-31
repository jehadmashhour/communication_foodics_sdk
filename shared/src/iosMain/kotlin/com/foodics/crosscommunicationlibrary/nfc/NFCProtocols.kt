package com.foodics.crosscommunicationlibrary.nfc

/**
 * Implemented by [NFCClientHandler] (Kotlin).
 * Swift's NFCScannerBridge calls [onNfcPayload] when a tag is successfully read.
 */
interface NFCScannerDelegate {
    /** Called with the JSON string extracted from an NDEF Text record. */
    fun onNfcPayload(json: String)
    /** Called when the NFC session ends without reading a tag. */
    fun onNfcScanCancelled()
}

/**
 * Implemented by Swift's NFCScannerBridge.
 *
 * Populate before the KMP framework is used:
 *   NFCBridgeProvider.shared.scannerBridge = NFCScannerBridge()
 *
 * iOS uses NFCNDEFReaderSession (CoreNFC) to read NFC tags.
 * Server role is not supported on iOS (no HCE).
 * Requires NSNFCReaderUsageDescription in Info.plist and the Near Field
 * Communication Tag Reading capability in the entitlements.
 */
interface NFCScannerBridgeProtocol {
    var delegate: NFCScannerDelegate?
    /** Present the NFC scan sheet and read the first detected NDEF tag. */
    fun startScanning(alertMessage: String)
    /** Invalidate the current session (no-op if not scanning). */
    fun stopScanning()
}
