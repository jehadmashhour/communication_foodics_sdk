package com.foodics.crosscommunicationlibrary.serial

import android.util.Log
import ConnectionType
import client.WriteType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import scanner.IoTDevice
import java.io.FileInputStream
import java.io.FileOutputStream

actual class SerialClientHandler actual constructor(private val baudRate: Int) {

    companion object { private const val TAG = "SerialClient" }

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    @Volatile private var inputStream: FileInputStream? = null
    @Volatile private var outputStream: FileOutputStream? = null

    // ── Scan ──────────────────────────────────────────────────────────────────
    // Enumerate local serial ports — the physical cable is the "connection".

    fun scan(): Flow<List<IoTDevice>> = flow {
        val devices = enumerateSerialPorts().mapIndexed { idx, path ->
            IoTDevice(
                id = "serial_$idx",
                name = path,
                address = path,
                connectionType = ConnectionType.SERIAL
            )
        }
        Log.d(TAG, "Found ${devices.size} serial port(s): ${devices.map { it.address }}")
        emit(devices)
    }

    // ── Connect ───────────────────────────────────────────────────────────────

    suspend fun connect(device: IoTDevice): Unit = withContext(Dispatchers.IO) {
        disconnect()
        val portPath = device.address
        configureSerialPort(portPath, baudRate)

        val inp = runCatching { FileInputStream(portPath) }.getOrElse {
            Log.e(TAG, "Cannot open $portPath: ${it.message}"); return@withContext
        }
        val out = runCatching { FileOutputStream(portPath) }.getOrElse {
            runCatching { inp.close() }
            Log.e(TAG, "Cannot open $portPath for write: ${it.message}"); return@withContext
        }
        inputStream = inp; outputStream = out
        Log.i(TAG, "Serial connected to $portPath @ ${baudRate}bps")
        scope.launch { receiveLoop(inp) }
    }

    // ── Send ──────────────────────────────────────────────────────────────────

    fun sendToServer(data: ByteArray, writeType: WriteType) {
        val out = outputStream ?: run { Log.w(TAG, "Not connected"); return }
        runCatching { out.write(serialFrameEncode(data)); out.flush() }
            .onFailure { Log.e(TAG, "Send failed", it) }
    }

    fun receiveFromServer(): Flow<ByteArray> = _incoming.asSharedFlow()

    // ── Disconnect ────────────────────────────────────────────────────────────

    fun disconnect() {
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        runCatching { inputStream?.close() }
        runCatching { outputStream?.close() }
        inputStream = null; outputStream = null
        Log.i(TAG, "Serial disconnected")
    }

    // ── Receive loop ──────────────────────────────────────────────────────────

    private suspend fun receiveLoop(inp: FileInputStream) = withContext(Dispatchers.IO) {
        try {
            while (isActive) {
                val frame = runCatching { inp.serialReadFrame() }.getOrNull() ?: break
                _incoming.emit(frame)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Receive loop ended: ${e.message}")
        }
    }
}
