package com.foodics.crosscommunicationlibrary.udp

import android.util.Log
import ConnectionType
import client.WriteType
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import scanner.IoTDevice
import java.net.*

actual class UDPClientHandler {

    companion object {
        private const val TAG = "UDPClientHandler"
        private const val DISCOVERY_PORT = 33333
    }

    private var socket: DatagramSocket? = null
    private var serverAddress: InetAddress? = null
    private var serverPort: Int? = null

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

    fun scan(): Flow<List<IoTDevice>> = channelFlow {
        val devices = mutableMapOf<String, Pair<IoTDevice, Long>>()
        val mutex = Mutex() // Protect devices map

        socket = DatagramSocket(DISCOVERY_PORT).apply { broadcast = true }

        // Listener coroutine (bound to channelFlow scope)
        launch {
            val buffer = ByteArray(1024)
            while (isActive) {
                val packet = DatagramPacket(buffer, buffer.size)
                socket?.receive(packet)
                val msg = String(packet.data, 0, packet.length)
                val parts = msg.split("|")
                if (parts.size == 3) {
                    val id = parts[0]
                    val name = parts[1]
                    val port = parts[2].toInt()

                    val device = IoTDevice(
                        id = id,
                        name = name,
                        connectionType = ConnectionType.UDP,
                        address = "${packet.address.hostAddress}:$port"
                    )

                    mutex.withLock {
                        devices[id] = device to System.currentTimeMillis()
                        val list = devices.values.map { it.first }.sortedBy { it.id }
                        trySend(list)
                    }
                }
            }
        }

        // Cleanup coroutine (removes timed-out devices)
        launch {
            val timeout = 15000L // 15 seconds
            while (isActive) {
                delay(1000)
                val now = System.currentTimeMillis()
                var removed = false
                mutex.withLock {
                    removed = devices.entries.removeIf { now - it.value.second > timeout }
                    if (removed) {
                        val list = devices.values.map { it.first }.sortedBy { it.id }
                        trySend(list)
                    }
                }
            }
        }

        awaitClose { socket?.close() }
    }

    suspend fun connect(device: IoTDevice) {
        val (host, port) = device.address.split(":")
        serverAddress = InetAddress.getByName(host)
        serverPort = port.toInt()
        socket = DatagramSocket()
        startReadLoop()
    }

    suspend fun sendToServer(data: ByteArray, writeType: WriteType) {
        val packet = DatagramPacket(data, data.size, serverAddress, serverPort!!)
        socket?.send(packet)
        Log.d(TAG, "Sent to server: ${String(data)}")
    }

    fun receiveFromServer(): Flow<ByteArray> = _incoming.asSharedFlow()

    private fun startReadLoop() {
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            val buffer = ByteArray(4096)
            while (isActive) {
                val packet = DatagramPacket(buffer, buffer.size)
                socket?.receive(packet)
                val data = packet.data.copyOf(packet.length)
                _incoming.emit(data)
            }
        }
    }

    suspend fun disconnect() {
        socket?.close()
        socket = null
        Log.i(TAG, "UDP Client disconnected")
    }
}