package com.foodics.crosscommunicationlibrary.websocket

import android.util.Log
import client.WriteType
import ConnectionType
import com.appstractive.dnssd.DiscoveryEvent
import com.appstractive.dnssd.discoverServices
import com.appstractive.dnssd.key
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import scanner.IoTDevice
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

actual class WebSocketClientHandler {

    companion object {
        private const val TAG = "WebSocketClient"
        private const val CONNECT_TIMEOUT_MS = 5000
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    private var socket: Socket? = null
    private var output: OutputStream? = null

    fun scan(): Flow<List<IoTDevice>> =
        discoverServices(WS_SERVICE_TYPE)
            .scan(emptyMap<String, IoTDevice>()) { acc, event ->
                val devices = acc.toMutableMap()
                when (event) {
                    is DiscoveryEvent.Discovered -> event.resolve()
                    is DiscoveryEvent.Resolved -> {
                        val s = event.service
                        val id = s.txt["id"]?.decodeToString()?.trim()?.ifBlank { null } ?: s.name
                        devices[s.key] = IoTDevice(
                            id = id, name = s.name,
                            address = "${s.host}:${s.port}",
                            connectionType = ConnectionType.WEBSOCKET
                        )
                    }
                    is DiscoveryEvent.Removed -> devices.remove(event.service.key)
                }
                devices
            }
            .map { it.values.toList() }
            .distinctUntilChanged()

    suspend fun connect(device: IoTDevice): Unit = withContext(Dispatchers.IO) {
        disconnect()
        val (host, port) = device.address.split(":")
        val sock = Socket()
        sock.connect(InetSocketAddress(host, port.toInt()), CONNECT_TIMEOUT_MS)
        val input = sock.getInputStream()
        val out = sock.getOutputStream()

        performClientHandshake(host, port.toInt(), input, out)
        socket = sock
        output = out
        Log.i(TAG, "WebSocket connected to ${device.name} @ ${device.address}")

        scope.launch {
            while (isActive && !sock.isClosed) {
                val frame = runCatching { readWsFrame(input) }.getOrNull() ?: break
                _incoming.emit(frame)
            }
            disconnect()
            Log.i(TAG, "WebSocket disconnected from ${device.name}")
        }
    }

    suspend fun sendToServer(data: ByteArray, writeType: WriteType): Unit = withContext(Dispatchers.IO) {
        val out = output ?: run { Log.w(TAG, "Not connected"); return@withContext }
        runCatching { writeWsFrame(out, data, mask = true) }
            .onFailure { Log.e(TAG, "Send error", it) }
    }

    fun receiveFromServer(): Flow<ByteArray> = _incoming.asSharedFlow()

    fun disconnect() {
        runCatching { output?.let { sendWsClose(it) } }
        runCatching { socket?.close() }
        socket = null; output = null
        Log.i(TAG, "WebSocket client disconnected")
    }
}
