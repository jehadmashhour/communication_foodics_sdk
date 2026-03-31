package com.foodics.crosscommunicationlibrary.nfc

import android.util.Log
import ConnectionType
import client.WriteType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import scanner.IoTDevice
import java.net.Socket

/**
 * Android NFC bootstrapping client.
 *
 * Discovery is driven externally: call [processNfcPayload] when
 * [NFCReaderHelper] delivers a JSON string from a tapped NFC tag.
 *
 * [scan] returns a [StateFlow] of discovered devices that updates each time
 * a new NFC tap is processed. After calling [connect], data is exchanged
 * over TCP.
 */
actual class NFCClientHandler {

    companion object { private const val TAG = "NFCClient" }

    private val scope      = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _devices   = MutableStateFlow<List<IoTDevice>>(emptyList())
    private val _incoming  = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    private var clientSocket: Socket? = null

    // ── NFC tap delivery (called by NFCReaderHelper) ──────────────────────────

    /**
     * Parse the JSON payload extracted from an NFC NDEF Text record and
     * add the device to the discovered list.
     */
    fun processNfcPayload(json: String) {
        val info = parseNfcJson(json) ?: run { Log.w(TAG, "Unrecognised NFC payload: $json"); return }
        val device = IoTDevice(
            id             = info.id,
            name           = info.name,
            connectionType = ConnectionType.NFC,
            address        = "${info.ip}:${info.port}"
        )
        val current = _devices.value
        if (current.none { it.id == info.id }) {
            _devices.value = current + device
            Log.i(TAG, "NFC device found: ${info.name} @ ${info.ip}:${info.port}")
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun scan(): Flow<List<IoTDevice>> = _devices.asStateFlow()

    suspend fun connect(device: IoTDevice): Unit = withContext(Dispatchers.IO) {
        val (ip, portStr) = device.address.split(":")
        val sock = Socket(ip, portStr.toInt())
        clientSocket = sock
        Log.i(TAG, "NFC TCP connected to $ip:$portStr")
        scope.launch {
            val input = sock.getInputStream()
            while (isActive && !sock.isClosed) {
                val data = runCatching { nfcReadFramed(input) }.getOrNull() ?: break
                _incoming.emit(data)
            }
            runCatching { sock.close() }
            if (clientSocket == sock) clientSocket = null
            Log.i(TAG, "NFC TCP disconnected")
        }
    }

    suspend fun sendToServer(data: ByteArray, writeType: WriteType): Unit = withContext(Dispatchers.IO) {
        val sock = clientSocket ?: run { Log.w(TAG, "Not connected"); return@withContext }
        runCatching {
            sock.getOutputStream().apply {
                write(nfcLengthPrefix(data))
                write(data)
                flush()
            }
        }.onFailure { Log.e(TAG, "Send error", it) }
    }

    fun receiveFromServer(): Flow<ByteArray> = _incoming.asSharedFlow()

    suspend fun disconnect(): Unit = withContext(Dispatchers.IO) {
        clientSocket?.close()
        clientSocket = null
        _devices.value = emptyList()
        Log.i(TAG, "NFC client disconnected")
    }
}
