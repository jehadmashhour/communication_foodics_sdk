import AVFoundation
import UIKit
import shared   // KMP framework — QRScannerBridgeProtocol / QRScannerDelegate live here

/// Implements the Kotlin-defined QRScannerBridgeProtocol (ObjC protocol).
/// On startScanning() it presents a full-screen camera overlay that uses
/// AVFoundation's metadata output to detect QR codes.
/// The first decoded QR string is forwarded to the Kotlin delegate and the
/// scanner is automatically dismissed.
public class QRClientBridge: NSObject, QRScannerBridgeProtocol {

    public var delegate: (any QRScannerDelegate)?

    private var captureSession: AVCaptureSession?
    private var scannerVC: UIViewController?
    private var previewLayer: AVCaptureVideoPreviewLayer?

    // MARK: - QRScannerBridgeProtocol

    public func startScanning() {
        DispatchQueue.main.async { [weak self] in
            self?.presentScanner()
        }
    }

    public func stopScanning() {
        DispatchQueue.main.async { [weak self] in
            self?.dismissScanner()
        }
    }

    // MARK: - Private

    private func presentScanner() {
        guard let rootVC = UIApplication.shared.windows.first?.rootViewController else {
            NSLog("[QRClientBridge] No root view controller found")
            return
        }

        let session = AVCaptureSession()
        captureSession = session

        guard let device = AVCaptureDevice.default(for: .video) else {
            NSLog("[QRClientBridge] No camera device available")
            return
        }
        guard let input = try? AVCaptureDeviceInput(device: device) else {
            NSLog("[QRClientBridge] Cannot create camera input")
            return
        }
        guard session.canAddInput(input) else { return }
        session.addInput(input)

        let output = AVCaptureMetadataOutput()
        guard session.canAddOutput(output) else { return }
        session.addOutput(output)
        output.setMetadataObjectsDelegate(self, queue: .main)
        output.metadataObjectTypes = [.qr]

        // Build scanner VC
        let vc = UIViewController()
        vc.view.backgroundColor = .black
        vc.modalPresentationStyle = .fullScreen

        let preview = AVCaptureVideoPreviewLayer(session: session)
        preview.frame = UIScreen.main.bounds
        preview.videoGravity = .resizeAspectFill
        vc.view.layer.insertSublayer(preview, at: 0)
        previewLayer = preview

        // Semi-transparent overlay with a square viewfinder
        let overlay = buildOverlay(in: vc.view.bounds)
        vc.view.addSubview(overlay)

        // Cancel button
        let cancelBtn = UIButton(type: .system)
        cancelBtn.setTitle("Cancel", for: .normal)
        cancelBtn.titleLabel?.font = UIFont.systemFont(ofSize: 18, weight: .medium)
        cancelBtn.tintColor = .white
        cancelBtn.frame = CGRect(x: 20, y: 56, width: 80, height: 44)
        cancelBtn.addTarget(self, action: #selector(cancelTapped), for: .touchUpInside)
        vc.view.addSubview(cancelBtn)

        scannerVC = vc

        DispatchQueue.global(qos: .userInitiated).async {
            session.startRunning()
        }

        rootVC.present(vc, animated: true) {
            NSLog("[QRClientBridge] Scanner presented")
        }
    }

    private func dismissScanner() {
        captureSession?.stopRunning()
        captureSession = nil
        previewLayer = nil
        scannerVC?.dismiss(animated: true)
        scannerVC = nil
        NSLog("[QRClientBridge] Scanner dismissed")
    }

    @objc private func cancelTapped() {
        dismissScanner()
    }

    /// Builds a simple viewfinder overlay: darkened edges + clear square in the centre.
    private func buildOverlay(in bounds: CGRect) -> UIView {
        let overlay = UIView(frame: bounds)
        overlay.backgroundColor = .clear

        let side = min(bounds.width, bounds.height) * 0.65
        let viewfinderRect = CGRect(
            x: (bounds.width  - side) / 2,
            y: (bounds.height - side) / 2,
            width: side,
            height: side
        )

        // Dark mask with a clear hole
        let maskLayer = CAShapeLayer()
        let fullPath = UIBezierPath(rect: bounds)
        let holePath = UIBezierPath(rect: viewfinderRect)
        fullPath.append(holePath)
        maskLayer.fillRule = .evenOdd
        maskLayer.path = fullPath.cgPath
        maskLayer.fillColor = UIColor.black.withAlphaComponent(0.55).cgColor
        overlay.layer.addSublayer(maskLayer)

        // Corner bracket decorations
        let bracketColor = UIColor.white.cgColor
        let bracketLen: CGFloat = 24
        let bracketWidth: CGFloat = 3
        let corners: [(CGPoint, CGPoint, CGPoint)] = [
            (CGPoint(x: viewfinderRect.minX, y: viewfinderRect.minY + bracketLen),
             CGPoint(x: viewfinderRect.minX, y: viewfinderRect.minY),
             CGPoint(x: viewfinderRect.minX + bracketLen, y: viewfinderRect.minY)),
            (CGPoint(x: viewfinderRect.maxX - bracketLen, y: viewfinderRect.minY),
             CGPoint(x: viewfinderRect.maxX, y: viewfinderRect.minY),
             CGPoint(x: viewfinderRect.maxX, y: viewfinderRect.minY + bracketLen)),
            (CGPoint(x: viewfinderRect.minX, y: viewfinderRect.maxY - bracketLen),
             CGPoint(x: viewfinderRect.minX, y: viewfinderRect.maxY),
             CGPoint(x: viewfinderRect.minX + bracketLen, y: viewfinderRect.maxY)),
            (CGPoint(x: viewfinderRect.maxX - bracketLen, y: viewfinderRect.maxY),
             CGPoint(x: viewfinderRect.maxX, y: viewfinderRect.maxY),
             CGPoint(x: viewfinderRect.maxX, y: viewfinderRect.maxY - bracketLen)),
        ]
        for (a, corner, b) in corners {
            let path = UIBezierPath()
            path.move(to: a)
            path.addLine(to: corner)
            path.addLine(to: b)
            let shape = CAShapeLayer()
            shape.path = path.cgPath
            shape.strokeColor = bracketColor
            shape.lineWidth = bracketWidth
            shape.fillColor = UIColor.clear.cgColor
            shape.lineCap = .round
            overlay.layer.addSublayer(shape)
        }

        // Instruction label
        let label = UILabel()
        label.text = "Align QR code within the frame"
        label.textColor = .white
        label.font = UIFont.systemFont(ofSize: 14)
        label.textAlignment = .center
        label.frame = CGRect(
            x: 0,
            y: viewfinderRect.maxY + 20,
            width: bounds.width,
            height: 24
        )
        overlay.addSubview(label)

        return overlay
    }
}

// MARK: - AVCaptureMetadataOutputObjectsDelegate

extension QRClientBridge: AVCaptureMetadataOutputObjectsDelegate {
    public func metadataOutput(
        _ output: AVCaptureMetadataOutput,
        didOutput metadataObjects: [AVMetadataObject],
        from connection: AVCaptureConnection
    ) {
        guard
            let obj = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
            let value = obj.stringValue,
            !value.isEmpty
        else { return }

        NSLog("[QRClientBridge] QR scanned: %@", value)
        delegate?.onQRCodeScanned(content: value)
        dismissScanner()
    }
}
