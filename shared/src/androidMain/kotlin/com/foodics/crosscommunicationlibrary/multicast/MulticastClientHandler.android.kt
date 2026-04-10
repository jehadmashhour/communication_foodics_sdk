package com.foodics.crosscommunicationlibrary.multicast

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import client.WriteType
import ConnectionType
import com.foodics.crosscommunicationlibrary.AndroidAppContextProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import scanner.IoTDevice
import java.net.DatagramPacket
import java.net.MulticastSocket
import kotlin.time.TimeSource

actual class MulticastClientHandler {

    companion object {
        private const val TAG = "MulticastClient"
        private const val DEVICE_TTL_MS = 10_000L
    }

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    private var socket: MulticastSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    // ── Scan ──────────────────────────────────────────────────────────────────

    fun scan(): Flow<List<IoTDevice>> = channelFlow {
        val wifiManager = AndroidAppContextProvider.context
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifiManager.createMulticastLock("FoodicsMulticastScan").apply {
            setReferenceCounted(false); acquire()
        }

        val sock = MulticastSocket(MULTICAST_PORT).apply {
            reuseAddress = true
            joinGroup(MULTICAST_ADDRESS)
            soTimeout = 2_000
        }

        val devices = mutableMapOf<String, Pair<IoTDevice, TimeSource.Monotonic.ValueTimeMark>>()

        // Evict stale devices
        val evictJob = launch {
            while (isActive) {
                delay(2_000)
                val before = devices.size
                devices.entries.removeIf { it.value.second.elapsedNow().inWholeMilliseconds > DEVICE_TTL_MS }
                if (devices.size != before) trySend(devices.values.map { it.first })
            }
        }

        // Receive beacons
        val buf = ByteArray(65_507)
        val packet = DatagramPacket(buf, buf.size)
        while (isActive) {
            try {
                sock.receive(packet)
            } catch (_: java.net.SocketTimeoutException) {
                continue
            } catch (e: Exception) {
                if (isActive) Log.e(TAG, "Scan receive error", e); break
            }
            val raw = packet.data.copyOf(packet.length)
            val (type, content) = parsePacket(raw) ?: continue
            if (type != PKT_BEACON || content.isEmpty()) continue

            val parts = content.decodeToString().split("|", limit = 2)
            if (parts.size < 2) continue
            val id = parts[0]; val name = parts[1]
            val device = IoTDevice(
                id = id, name = name,
                address = packet.address.hostAddress ?: continue,
                connectionType = ConnectionType.MULTICAST
            )
            devices[id] = device to TimeSource.Monotonic.markNow()
            trySend(devices.values.map { it.first })
        }

        awaitClose {
            evictJob.cancel()
            runCatching { sock.leaveGroup(MULTICAST_ADDRESS) }
            runCatching { sock.close() }
            runCatching { lock.release() }
        }
    }.distinctUntilChanged()

    // ── Connect ───────────────────────────────────────────────────────────────

    suspend fun connect(device: IoTDevice): Unit = withContext(Dispatchers.IO) {
        disconnect()
        val wifiManager = AndroidAppContextProvider.context
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifiManager.createMulticastLock("FoodicsMulticastData").apply {
            setReferenceCounted(false); acquire()
        }
        multicastLock = lock

        val sock = MulticastSocket(MULTICAST_PORT).apply {
            reuseAddress = true
            joinGroup(MULTICAST_ADDRESS)
            soTimeout = 2_000
        }
        socket = sock
        Log.i(TAG, "Multicast connected (group member for ${device.name})")
        scope.launch { receiveLoop(sock) }
    }

    // ── Send ──────────────────────────────────────────────────────────────────

    suspend fun sendToServer(data: ByteArray, writeType: WriteType): Unit = withContext(Dispatchers.IO) {
        val sock = socket ?: run { Log.w(TAG, "Not connected"); return@withContext }
        val pkt = buildData(data)
        runCatching { sock.send(DatagramPacket(pkt, pkt.size, MULTICAST_ADDRESS, MULTICAST_PORT)) }
            .onFailure { Log.e(TAG, "Send error", it) }
    }

    fun receiveFromServer(): Flow<ByteArray> = _incoming.asSharedFlow()

    // ── Disconnect ────────────────────────────────────────────────────────────

    fun disconnect() {
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        runCatching { socket?.leaveGroup(MULTICAST_ADDRESS) }
        runCatching { socket?.close() }
        runCatching { multicastLock?.release() }
        socket = null; multicastLock = null
        Log.i(TAG, "Multicast client disconnected")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private suspend fun receiveLoop(sock: MulticastSocket) {
        val buf = ByteArray(65_507)
        val packet = DatagramPacket(buf, buf.size)
        while (scope.isActive) {
            try {
                sock.receive(packet)
            } catch (_: java.net.SocketTimeoutException) {
                continue
            } catch (e: Exception) {
                if (scope.isActive) Log.e(TAG, "Receive error", e); break
            }
            val raw = packet.data.copyOf(packet.length)
            val (type, content) = parsePacket(raw) ?: continue
            if (type == PKT_DATA && content.isNotEmpty()) {
                _incoming.emit(content)
            }
        }
    }
}
