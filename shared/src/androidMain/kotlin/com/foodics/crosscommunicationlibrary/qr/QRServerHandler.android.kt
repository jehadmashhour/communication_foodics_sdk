package com.foodics.crosscommunicationlibrary.qr

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.ByteArrayOutputStream
import java.net.*

actual class QRServerHandler {

    private companion object {
        const val TAG = "QRServerHandler"
        const val QR_SIZE = 512
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _qrCodeBytes = MutableStateFlow<ByteArray?>(null)
    val qrCodeBytes: StateFlow<ByteArray?> = _qrCodeBytes.asStateFlow()

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var acceptJob: Job? = null
    private val _fromClient = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

    suspend fun start(deviceName: String, identifier: String) {
        stop()
        val srv = ServerSocket(0)
        serverSocket = srv
        val port = srv.localPort
        val ip = getLocalIpAddress()

        val json = buildQRJson(identifier, deviceName, ip, port)
        Log.d(TAG, "QR payload: $json")
        _qrCodeBytes.value = generateQRBitmap(json)

        acceptJob = scope.launch {
            while (isActive) {
                try {
                    val client = srv.accept()
                    clientSocket?.close()
                    clientSocket = client
                    Log.i(TAG, "QR client connected from ${client.inetAddress.hostAddress}")
                    startReceiveLoop(client)
                } catch (e: Exception) {
                    if (isActive) Log.e(TAG, "Accept error", e)
                }
            }
        }
        Log.i(TAG, "QR server started on $ip:$port")
    }

    suspend fun sendToClient(data: ByteArray) {
        try {
            clientSocket?.getOutputStream()?.let { out ->
                out.write(lengthPrefix(data))
                out.write(data)
                out.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Send error", e)
        }
    }

    fun receiveFromClient(): Flow<ByteArray> = _fromClient.asSharedFlow()

    suspend fun stop() {
        acceptJob?.cancel()
        clientSocket?.close()
        serverSocket?.close()
        clientSocket = null
        serverSocket = null
        _qrCodeBytes.value = null
        Log.i(TAG, "QR server stopped")
    }

    private fun startReceiveLoop(socket: Socket) {
        scope.launch {
            val input = socket.getInputStream()
            while (isActive && !socket.isClosed) {
                try {
                    val data = readFramed(input) ?: break
                    _fromClient.emit(data)
                } catch (e: Exception) {
                    if (isActive) Log.e(TAG, "Receive error", e)
                    break
                }
            }
        }
    }

    private fun generateQRBitmap(content: String): ByteArray? = runCatching {
        val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE)
        val bitmap = Bitmap.createBitmap(QR_SIZE, QR_SIZE, Bitmap.Config.RGB_565)
        for (x in 0 until QR_SIZE) {
            for (y in 0 until QR_SIZE) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            .toByteArray()
    }.getOrElse { Log.e(TAG, "QR generation failed", it); null }

    private fun getLocalIpAddress(): String = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
            ?.hostAddress ?: "0.0.0.0"
    }.getOrDefault("0.0.0.0")
}
