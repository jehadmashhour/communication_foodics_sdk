package com.foodics.crosscommunicationlibrary.coap

import android.util.Log
import client.WriteType
import ConnectionType
import com.appstractive.dnssd.DiscoveryEvent
import com.appstractive.dnssd.discoverServices
import com.appstractive.dnssd.key
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import scanner.IoTDevice
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

actual class CoAPClientHandler {

    companion object {
        private const val TAG = "CoAPClient"
        private const val BUF_SIZE = 65_507
    }

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    private var socket: DatagramSocket? = null

    @Volatile private var serverAddress: InetAddress? = null
    @Volatile private var serverPort: Int = 0

    // ── Scan ──────────────────────────────────────────────────────────────────

    fun scan(): Flow<List<IoTDevice>> =
        discoverServices(COAP_SERVICE_TYPE)
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
                            connectionType = ConnectionType.COAP
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
        serverAddress = InetAddress.getByName(parts[0])
        serverPort = parts.getOrNull(1)?.toIntOrNull() ?: 5683

        val sock = DatagramSocket(0) // random local port
        sock.soTimeout = 1_000
        socket = sock

        scope.launch { receiveLoop(sock) }
        Log.i(TAG, "CoAP client connected to ${device.name} @ ${device.address}")
    }

    // ── Send ──────────────────────────────────────────────────────────────────

    suspend fun sendToServer(data: ByteArray, writeType: WriteType): Unit = withContext(Dispatchers.IO) {
        val addr = serverAddress ?: run { Log.w(TAG, "Not connected"); return@withContext }
        val sock = socket ?: return@withContext
        val frame = coapBuildPost(data)
        runCatching { sock.send(DatagramPacket(frame, frame.size, addr, serverPort)) }
            .onFailure { Log.e(TAG, "Send error", it) }
    }

    // ── Receive ───────────────────────────────────────────────────────────────

    fun receiveFromServer(): Flow<ByteArray> = _incoming.asSharedFlow()

    // ── Disconnect ────────────────────────────────────────────────────────────

    fun disconnect() {
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        runCatching { socket?.close() }
        socket = null; serverAddress = null; serverPort = 0
        Log.i(TAG, "CoAP client disconnected")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private suspend fun receiveLoop(sock: DatagramSocket) {
        val buf = ByteArray(BUF_SIZE)
        val packet = DatagramPacket(buf, buf.size)
        while (scope.isActive) {
            try {
                sock.receive(packet)
            } catch (_: java.net.SocketTimeoutException) {
                continue
            } catch (e: Exception) {
                if (scope.isActive) Log.e(TAG, "Receive error", e)
                break
            }
            val raw = packet.data.copyOf(packet.length)
            if (!isValidCoap(raw)) continue
            val payload = coapParsePayload(raw)
            if (payload.isNotEmpty()) {
                _incoming.emit(payload)
                Log.d(TAG, "Received ${payload.size} bytes from server")
            }
        }
    }
}
