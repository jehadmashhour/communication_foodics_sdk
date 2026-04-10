package com.foodics.crosscommunicationlibrary.tcp

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
import java.net.Socket

actual class TcpClientHandler {

    companion object {
        private const val TAG = "TcpClient"
    }

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    private var socket: Socket? = null
    @Volatile private var output: OutputStream? = null

    // ── Scan ──────────────────────────────────────────────────────────────────

    fun scan(): Flow<List<IoTDevice>> =
        discoverServices(TCP_SERVICE_TYPE)
            .scan(emptyMap<String, IoTDevice>()) { acc, event ->
                val devices = acc.toMutableMap()
                when (event) {
                    is DiscoveryEvent.Discovered -> event.resolve()
                    is DiscoveryEvent.Resolved -> {
                        val s = event.service
                        val id = s.txt["id"]?.decodeToString()?.trim()?.ifBlank { null } ?: s.name
                        devices[s.key] = IoTDevice(
                            id = id,
                            name = s.name,
                            address = "${s.host}:${s.port}",
                            connectionType = ConnectionType.TCP_SOCKET
                        )
                    }
                    is DiscoveryEvent.Removed -> devices.remove(event.service.key)
                }
                devices
            }
            .map { it.values.toList() }
            .distinctUntilChanged()

    // ── Connect ───────────────────────────────────────────────────────────────

    suspend fun connect(device: IoTDevice): Unit = withContext(Dispatchers.IO) {
        disconnect()
        val parts = device.address.split(":")
        val host = parts[0]
        val port = parts.getOrNull(1)?.toIntOrNull() ?: return@withContext

        val sock = runCatching { Socket(host, port) }.getOrElse {
            Log.e(TAG, "Connect failed: ${it.message}"); return@withContext
        }
        socket = sock
        output = sock.getOutputStream()
        Log.i(TAG, "TCP connected to ${device.name} @ ${device.address}")
        scope.launch { receiveLoop(sock) }
    }

    // ── Send ──────────────────────────────────────────────────────────────────

    suspend fun sendToServer(data: ByteArray, writeType: WriteType): Unit = withContext(Dispatchers.IO) {
        val out = output ?: run { Log.w(TAG, "Not connected"); return@withContext }
        runCatching { out.writeTcpFrame(data) }.onFailure { Log.e(TAG, "Send error", it) }
    }

    // ── Receive ───────────────────────────────────────────────────────────────

    fun receiveFromServer(): Flow<ByteArray> = _incoming.asSharedFlow()

    // ── Disconnect ────────────────────────────────────────────────────────────

    fun disconnect() {
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        runCatching { socket?.close() }
        socket = null; output = null
        Log.i(TAG, "TCP client disconnected")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private suspend fun receiveLoop(sock: Socket) = withContext(Dispatchers.IO) {
        try {
            val input = sock.getInputStream().buffered(65_536)
            while (isActive && !sock.isClosed) {
                val frame = runCatching { input.readTcpFrame() }.getOrNull() ?: break
                _incoming.emit(frame)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Receive loop ended: ${e.message}")
        }
    }
}
