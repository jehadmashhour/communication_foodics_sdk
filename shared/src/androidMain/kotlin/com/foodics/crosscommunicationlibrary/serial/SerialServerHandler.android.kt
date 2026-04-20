package com.foodics.crosscommunicationlibrary.serial

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.FileInputStream
import java.io.FileOutputStream

actual class SerialServerHandler actual constructor(
    private val portPath: String,
    private val baudRate: Int
) {
    companion object { private const val TAG = "SerialServer" }

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _fromClient = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    @Volatile private var inputStream: FileInputStream? = null
    @Volatile private var outputStream: FileOutputStream? = null

    suspend fun start(deviceName: String, identifier: String): Unit = withContext(Dispatchers.IO) {
        stop()
        configureSerialPort(portPath, baudRate)

        val inp = runCatching { FileInputStream(portPath) }.getOrElse {
            Log.e(TAG, "Cannot open $portPath for read: ${it.message}"); return@withContext
        }
        val out = runCatching { FileOutputStream(portPath) }.getOrElse {
            runCatching { inp.close() }
            Log.e(TAG, "Cannot open $portPath for write: ${it.message}"); return@withContext
        }
        inputStream = inp
        outputStream = out
        Log.i(TAG, "Serial server: $deviceName [$identifier] on $portPath @ ${baudRate}bps")
        scope.launch { receiveLoop(inp) }
    }

    fun sendToClient(data: ByteArray) {
        val out = outputStream ?: run { Log.w(TAG, "Serial port not open"); return }
        runCatching { out.write(serialFrameEncode(data)); out.flush() }
            .onFailure { Log.e(TAG, "Send failed", it) }
    }

    fun receiveFromClient(): Flow<ByteArray> = _fromClient.asSharedFlow()

    suspend fun stop(): Unit = withContext(Dispatchers.IO) {
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        runCatching { inputStream?.close() }
        runCatching { outputStream?.close() }
        inputStream = null; outputStream = null
        Log.i(TAG, "Serial server stopped on $portPath")
    }

    private suspend fun receiveLoop(inp: FileInputStream) = withContext(Dispatchers.IO) {
        try {
            while (isActive) {
                val frame = runCatching { inp.serialReadFrame() }.getOrNull() ?: break
                _fromClient.emit(frame)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Receive loop ended: ${e.message}")
        }
    }
}
