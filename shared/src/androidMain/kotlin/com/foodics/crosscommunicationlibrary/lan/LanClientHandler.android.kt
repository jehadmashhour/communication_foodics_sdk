package com.foodics.crosscommunicationlibrary.lan

import android.content.Context
import android.util.Log
import client.WriteType
import com.appstractive.dnssd.DiscoveryEvent
import com.appstractive.dnssd.discoverServices
import com.appstractive.dnssd.key
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import scanner.IoTDevice
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

actual class LanClientHandler(private val context: Context) {

    companion object {
        private const val TAG = "LanClientHandler"
        private const val SERVICE_TYPE = "_foodics._tcp."
        private const val SOCKET_TIMEOUT_MS = 5000
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

    /** SCAN (mDNS) */
    fun scan(): Flow<List<IoTDevice>> =
        discoverServices(SERVICE_TYPE)
            .scan(emptyMap<String, IoTDevice>()) { acc, event ->
                val devices = acc.toMutableMap()

                when (event) {
                    is DiscoveryEvent.Discovered -> {
                        event.resolve()
                    }

                    is DiscoveryEvent.Resolved -> {
                        val service = event.service
                        val stableKey = service.key
                        val id = service.txt["id"]?.decodeToString()?.trim()?.ifBlank { null } ?: service.name

                        devices[stableKey] = IoTDevice(
                            id = id,
                            name = service.name,
                            connectionType = ConnectionType.LAN,
                            address = "${service.host}:${service.port}"
                        )

                        Log.d("devices_resolved", "$devices")
                        Log.d("id_resolved", id)
                    }

                    is DiscoveryEvent.Removed -> {
                        val stableKey = event.service.key
                        devices.remove(stableKey)
                    }
                }

                devices
            }
            .map { it.values.toList() }
            .distinctUntilChanged()


    /** CONNECT (TCP) */
    suspend fun connect(device: IoTDevice) {
        val (host, port) = device.address.split(":")

        Log.i(TAG, "Connecting to LAN server ${device.name} at $host:$port")

        socket = Socket().apply {
            connect(InetSocketAddress(host, port.toInt()), SOCKET_TIMEOUT_MS)
        }

        input = socket!!.getInputStream()
        output = socket!!.getOutputStream()

        startReadLoop()
        Log.i(TAG, "Connected to LAN server: ${device.name}")
    }

    /** SEND DATA */
    suspend fun sendToServer(data: ByteArray, writeType: WriteType) {
        output?.let {
            it.write(data)
            it.flush()
            Log.d(TAG, "Sent to server: ${String(data)}")
        } ?: error("Socket not connected")
    }

    /** RECEIVE DATA */
    suspend fun receiveFromServer(): Flow<ByteArray> = _incoming.asSharedFlow()

    private fun startReadLoop() {
        scope.launch {
            val buffer = ByteArray(4096)

            try {
                while (isActive) {
                    val count = input?.read(buffer) ?: -1
                    if (count <= 0) break

                    val data = buffer.copyOf(count)
                    _incoming.emit(data)
                    Log.d(TAG, "Received from server: ${String(data)}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Socket read error", e)
            } finally {
                disconnect()
            }
        }
    }

    /** DISCONNECT */
    suspend fun disconnect() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        input = null
        output = null
        Log.i(TAG, "Disconnected from LAN server")
    }
}
