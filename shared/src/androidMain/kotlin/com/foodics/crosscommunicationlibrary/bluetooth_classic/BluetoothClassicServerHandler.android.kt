package com.foodics.crosscommunicationlibrary.bluetooth_classic

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import com.foodics.crosscommunicationlibrary.AndroidAppContextProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.OutputStream

actual class BluetoothClassicServerHandler {

    companion object {
        private const val TAG = "BtClassicServer"
        private const val SERVICE_NAME = "FoodicsComm"
    }

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _fromClient = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

    private var serverSocket: BluetoothServerSocket? = null
    private var clientSocket: BluetoothSocket? = null
    private var clientOutput: OutputStream? = null

    suspend fun start(deviceName: String, identifier: String): Unit = withContext(Dispatchers.IO) {
        stop()
        val adapter = getAdapter() ?: run { Log.e(TAG, "Bluetooth not available"); return@withContext }

        @Suppress("MissingPermission")
        val srv = runCatching {
            adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SPP_UUID)
        }.getOrNull() ?: run { Log.e(TAG, "Failed to create RFCOMM server socket"); return@withContext }
        serverSocket = srv
        Log.i(TAG, "Classic BT server listening as $deviceName")

        scope.launch {
            while (isActive) {
                val client = runCatching { srv.accept() }.getOrNull() ?: break
                clientSocket?.close()
                clientSocket = client
                clientOutput = client.outputStream
                Log.i(TAG, "Classic BT client connected: ${client.remoteDevice.address}")
                scope.launch { runReceiveLoop(client) }
            }
        }
    }

    private suspend fun runReceiveLoop(socket: BluetoothSocket) {
        val buf = ByteArray(4096)
        try {
            val input = socket.inputStream
            while (scope.isActive && socket.isConnected) {
                val n = input.read(buf)
                if (n <= 0) break
                _fromClient.emit(buf.copyOf(n))
            }
        } catch (e: Exception) {
            Log.d(TAG, "Client stream ended: ${e.message}")
        } finally {
            if (clientSocket == socket) { clientOutput = null; clientSocket = null }
            runCatching { socket.close() }
            Log.i(TAG, "Classic BT client disconnected")
        }
    }

    suspend fun sendToClient(data: ByteArray): Unit = withContext(Dispatchers.IO) {
        val out = clientOutput ?: run { Log.w(TAG, "No client connected"); return@withContext }
        runCatching { out.write(data); out.flush() }.onFailure { Log.e(TAG, "Send error", it) }
    }

    fun receiveFromClient(): Flow<ByteArray> = _fromClient.asSharedFlow()

    suspend fun stop(): Unit = withContext(Dispatchers.IO) {
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        runCatching { clientSocket?.close() }
        runCatching { serverSocket?.close() }
        clientSocket = null; clientOutput = null; serverSocket = null
        Log.i(TAG, "Classic BT server stopped")
    }
}
