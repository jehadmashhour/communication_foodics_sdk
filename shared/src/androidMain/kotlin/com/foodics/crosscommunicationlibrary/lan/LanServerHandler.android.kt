package com.foodics.crosscommunicationlibrary.lan

import android.content.Context
import android.util.Log
import com.appstractive.dnssd.NetService
import com.appstractive.dnssd.createNetService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket

actual class LanServerHandler(
    private val context: Context
) {

    companion object {
        private const val TAG = "LanServerHandler"
        private const val SERVICE_TYPE = "_foodics._tcp."
        private const val SERVICE_PORT = 8080
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null

    private var input: InputStream? = null
    private var output: OutputStream? = null

    private val _fromClient = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val fromClientFlow: Flow<ByteArray> = _fromClient.asSharedFlow()

    // Real dns-sd-kt service object
    private var netService: NetService? = null

    /**
     * Start server + mDNS advertise via dns-sd-kt
     */
    suspend fun start(deviceName: String, identifier: String) {
        stop()
        delay(300)

        // 1️⃣ Start listening on TCP
        serverSocket = ServerSocket(SERVICE_PORT)

        // 2️⃣ Create the NetService and register it
        val service = createNetService(
            type = SERVICE_TYPE,
            name = deviceName,
            port = SERVICE_PORT,
            txt = mapOf("id" to identifier)
        )

        service.register()
        netService = service

        Log.i(TAG, "LAN Server started on port $SERVICE_PORT and advertised via mDNS")

        // 3️⃣ Accept a client
        acceptClient()
    }

    private fun acceptClient() {
        scope.launch {
            try {
                clientSocket = serverSocket?.accept()
                input = clientSocket?.getInputStream()
                output = clientSocket?.getOutputStream()

                Log.i(TAG, "Client connected: ${clientSocket?.inetAddress}")

                startReadLoop()
            } catch (e: Exception) {
                Log.e(TAG, "Error accepting client", e)
            }
        }
    }

    private fun startReadLoop() {
        scope.launch {
            val buffer = ByteArray(4096)

            try {
                while (isActive) {
                    val count = input?.read(buffer) ?: -1
                    if (count <= 0) break

                    val data = buffer.copyOf(count)
                    _fromClient.emit(data)

                    Log.d(TAG, "Received from client: ${String(data)}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Client disconnected or read error", e)
            } finally {
                disconnectClient()
            }
        }
    }

    suspend fun sendToClient(data: ByteArray) {
        output?.let {
            it.write(data)
            it.flush()
            Log.d(TAG, "Sent to client: ${String(data)}")
        } ?: error("No connected client")
    }

    fun receiveFromClient(): Flow<ByteArray> = fromClientFlow

    suspend fun stop() {
        // unregister the mDNS service if present
        try {
            (netService as? AutoCloseable)?.close()
        } catch (_: Exception) {
        }

        disconnectClient()
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }

        serverSocket = null

        if (netService?.registered == true) {
            netService?.unregister()
        }

        netService = null

        Log.i(TAG, "LAN Server stopped")
    }

    private fun disconnectClient() {
        try {
            clientSocket?.close()
        } catch (_: Exception) {
        }
        clientSocket = null
        input = null
        output = null
        Log.i(TAG, "Client disconnected")
    }
}
