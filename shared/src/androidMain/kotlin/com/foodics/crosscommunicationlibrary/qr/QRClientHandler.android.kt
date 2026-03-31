package com.foodics.crosscommunicationlibrary.qr

import android.util.Log
import ConnectionType
import client.WriteType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import scanner.IoTDevice
import java.net.Socket

actual class QRClientHandler {

    private companion object {
        const val TAG = "QRClientHandler"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _devices = MutableStateFlow<List<IoTDevice>>(emptyList())
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    private var clientSocket: Socket? = null

    fun scan(): Flow<List<IoTDevice>> = _devices.asStateFlow()

    /**
     * Called by [QRScannerView] when a QR code is detected.
     * Parses the JSON payload and adds the device to the discovered list.
     */
    fun processQRCode(content: String) {
        val info = parseQRJson(content) ?: run {
            Log.w(TAG, "Unrecognised QR: $content")
            return
        }
        val device = IoTDevice(
            id = info.id,
            name = info.name,
            connectionType = ConnectionType.QR,
            address = "${info.ip}:${info.port}"
        )
        val current = _devices.value
        if (current.none { it.id == info.id }) {
            _devices.value = current + device
            Log.i(TAG, "QR device found: ${info.name} @ ${info.ip}:${info.port}")
        }
    }

    suspend fun connect(device: IoTDevice) {
        val (ip, portStr) = device.address.split(":")
        val socket = Socket(ip, portStr.toInt())
        clientSocket = socket
        startReceiveLoop(socket)
        Log.i(TAG, "Connected via QR to $ip:$portStr")
    }

    suspend fun sendToServer(data: ByteArray, writeType: WriteType) {
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

    fun receiveFromServer(): Flow<ByteArray> = _incoming.asSharedFlow()

    suspend fun disconnect() {
        clientSocket?.close()
        clientSocket = null
        _devices.value = emptyList()
        Log.i(TAG, "QR client disconnected")
    }

    private fun startReceiveLoop(socket: Socket) {
        scope.launch {
            val input = socket.getInputStream()
            while (isActive && !socket.isClosed) {
                try {
                    val data = readFramed(input) ?: break
                    _incoming.emit(data)
                } catch (e: Exception) {
                    if (isActive) Log.e(TAG, "Receive error", e)
                    break
                }
            }
        }
    }
}
