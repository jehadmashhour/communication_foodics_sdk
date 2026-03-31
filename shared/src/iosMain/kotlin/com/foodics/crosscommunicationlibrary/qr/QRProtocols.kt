package com.foodics.crosscommunicationlibrary.qr

import platform.Foundation.NSData

/**
 * Callback interface implemented by [QRClientHandler] (Kotlin).
 * Becomes an ObjC protocol in the generated framework header.
 * Swift's QRClientBridge calls [onQRCodeScanned] when a QR code is detected.
 */
interface QRScannerDelegate {
    fun onQRCodeScanned(content: String)
}

/**
 * Bridge interface implemented by Swift's QRClientBridge.
 * Kotlin calls [startScanning] / [stopScanning] to drive the camera.
 */
interface QRScannerBridgeProtocol {
    var delegate: QRScannerDelegate?
    fun startScanning()
    fun stopScanning()
}

/**
 * Bridge interface implemented by Swift's QRServerBridge.
 * Kotlin calls [generateQRCode] to obtain PNG bytes of the QR image.
 */
interface QRGeneratorBridgeProtocol {
    fun generateQRCode(content: String): NSData?
}
