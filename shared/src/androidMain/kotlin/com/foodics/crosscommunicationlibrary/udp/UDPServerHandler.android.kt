package com.foodics.crosscommunicationlibrary.udp

import android.util.Log
import ConnectionType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.net.*

actual class UDPServerHandler {

    companion object {
        private const val TAG = "UDPServerHandler"
        private const val DISCOVERY_PORT = 33333
        private const val DATA_PORT = 8080
        private const val BROADCAST_INTERVAL = 1500L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var discoverySocket: DatagramSocket? = null
    private var dataSocket: DatagramSocket? = null
    private var broadcastJob: Job? = null

    private var lastClientAddress: InetAddress? = null
    private var lastClientPort: Int? = null

    private val _fromClient = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

    suspend fun start(deviceName: String, identifier: String) {
        stop()

        discoverySocket = DatagramSocket().apply { broadcast = true }
        dataSocket = DatagramSocket(DATA_PORT)

        startBroadcast(deviceName, identifier)
        startReceiveLoop()

        Log.i(TAG, "UDP Server started")
    }

    private fun startBroadcast(deviceName: String, identifier: String) {
        broadcastJob = scope.launch {
            val message = "$identifier|$deviceName|$DATA_PORT".toByteArray()
            val address = InetAddress.getByName("255.255.255.255")

            while (isActive) {
                try {
                    val packet = DatagramPacket(message, message.size, address, DISCOVERY_PORT)
                    discoverySocket?.send(packet)
                    delay(BROADCAST_INTERVAL)
                } catch (e: Exception) {
                    Log.e(TAG, "Broadcast error", e)
                }
            }
        }
    }

    private fun startReceiveLoop() {
        scope.launch {
            val buffer = ByteArray(4096)
            while (isActive) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    dataSocket?.receive(packet)

                    lastClientAddress = packet.address
                    lastClientPort = packet.port

                    val data = packet.data.copyOf(packet.length)
                    _fromClient.emit(data)

                    Log.d(TAG, "Received from client: ${String(data)}")
                } catch (e: Exception) {
                    if (isActive) Log.e(TAG, "Receive error", e)
                }
            }
        }
    }

    suspend fun sendToClient(data: ByteArray) {
        val address = lastClientAddress ?: run {
            Log.w(TAG, "No client connected yet")
            return
        }

        val port = lastClientPort ?: run {
            Log.w(TAG, "No client port available")
            return
        }

        val packet = DatagramPacket(data, data.size, address, port)
        dataSocket?.send(packet)
        Log.d(TAG, "Sent to client: ${String(data)}")
    }

    fun receiveFromClient(): Flow<ByteArray> = _fromClient.asSharedFlow()

    suspend fun stop() {
        broadcastJob?.cancel()
        discoverySocket?.close()
        dataSocket?.close()
        discoverySocket = null
        dataSocket = null
        lastClientAddress = null
        lastClientPort = null

        Log.i(TAG, "UDP Server stopped")
    }
}