package com.foodics.crosscommunicationlibrary.qr

import android.annotation.SuppressLint
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

/**
 * Camera preview composable that detects QR codes via ML Kit and forwards
 * each new result to [onQRScanned].
 *
 * Requires the CAMERA permission to be granted before this composable is
 * entered into the composition.
 *
 * Usage (client side):
 * ```kotlin
 * val channel = remember { QRCommunicationChannel() }
 * QRScannerView { content -> channel.clientHandler.processQRCode(content) }
 * ```
 */
@SuppressLint("UnsafeOptInUsageError")
@Composable
fun QRScannerView(
    modifier: Modifier = Modifier,
    onQRScanned: (String) -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current

    AndroidView(
        modifier = modifier,
        factory = { context ->
            val previewView = PreviewView(context)

            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val analyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(
                            ContextCompat.getMainExecutor(context),
                            QRCodeImageAnalyzer(onQRScanned)
                        )
                    }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analyzer
                )
            }, ContextCompat.getMainExecutor(context))

            previewView
        }
    )
}

@SuppressLint("UnsafeOptInUsageError")
private class QRCodeImageAnalyzer(private val onResult: (String) -> Unit) : ImageAnalysis.Analyzer {

    private val scanner = BarcodeScanning.getClient()
    private var lastValue = ""

    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image ?: run { imageProxy.close(); return }
        val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        scanner.process(inputImage)
            .addOnSuccessListener { barcodes ->
                barcodes
                    .firstOrNull { it.format == Barcode.FORMAT_QR_CODE }
                    ?.rawValue
                    ?.takeIf { it.isNotBlank() && it != lastValue }
                    ?.let { value ->
                        lastValue = value
                        onResult(value)
                    }
            }
            .addOnCompleteListener { imageProxy.close() }
    }
}
