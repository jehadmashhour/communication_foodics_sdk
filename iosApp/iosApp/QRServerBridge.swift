import CoreImage
import UIKit
import shared   // KMP framework — QRGeneratorBridgeProtocol lives here

/// Implements the Kotlin-defined QRGeneratorBridgeProtocol (ObjC protocol).
/// Uses CoreImage's CIQRCodeGenerator to produce a PNG-encoded QR image and
/// returns it as NSData so Kotlin can store/display it.
public class QRServerBridge: NSObject, QRGeneratorBridgeProtocol {

    public func generateQRCode(content: String) -> Data? {
        guard let inputData = content.data(using: .utf8) else {
            NSLog("[QRServerBridge] Failed to encode content as UTF-8")
            return nil
        }

        guard let filter = CIFilter(name: "CIQRCodeGenerator") else {
            NSLog("[QRServerBridge] CIQRCodeGenerator unavailable")
            return nil
        }
        filter.setValue(inputData, forKey: "inputMessage")
        filter.setValue("H", forKey: "inputCorrectionLevel")  // highest error correction

        guard let rawImage = filter.outputImage else {
            NSLog("[QRServerBridge] No output image from filter")
            return nil
        }

        // Scale up from the tiny default size (e.g. 33×33 pts) to 512×512
        let scaleX = 512.0 / rawImage.extent.width
        let scaleY = 512.0 / rawImage.extent.height
        let scaledImage = rawImage.transformed(by: CGAffineTransform(scaleX: scaleX, y: scaleY))

        let context = CIContext()
        guard let cgImage = context.createCGImage(scaledImage, from: scaledImage.extent) else {
            NSLog("[QRServerBridge] CGImage creation failed")
            return nil
        }

        let uiImage = UIImage(cgImage: cgImage)
        guard let pngData = uiImage.pngData() else {
            NSLog("[QRServerBridge] PNG encoding failed")
            return nil
        }

        NSLog("[QRServerBridge] QR code generated (%d bytes)", pngData.count)
        return pngData
    }
}
