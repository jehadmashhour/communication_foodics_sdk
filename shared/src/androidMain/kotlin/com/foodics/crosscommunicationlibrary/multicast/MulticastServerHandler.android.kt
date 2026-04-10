package com.foodics.crosscommunicationlibrary.multicast

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.foodics.crosscommunicationlibrary.AndroidAppContextProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.net.DatagramPacket
import java.net.MulticastSocket

actual class MulticastServerHandler {

    companion object {
        private const val TAG = "MulticastServer"
        private const val BEACON_INTERVAL_MS = 2_000L
    }

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _fromClient = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    private var socket: MulticastSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    suspend fun start(deviceName: String, identifier: String): Unit = withContext(Dispatchers.IO) {
        stop()

        // Android WiFi driver filters multicast by default; we need a lock to receive it.
        val wifiManager = AndroidAppContextProvider.context
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifiManager.createMulticastLock("FoodicsMulticast").apply {
            setReferenceCounted(false)
            acquire()
        }
        multicastLock = lock

        val sock = MulticastSocket(MULTICAST_PORT).apply {
            reuseAddress = true
            timeToLive = 4          // don't cross WAN routers
            joinGroup(MULTICAST_ADDRESS)
            soTimeout = 2_000
        }
        socket = sock
        Log.i(TAG, "Multicast server: $deviceName @ $MULTICAST_GROUP:$MULTICAST_PORT")

        // Periodic BEACON broadcasts
        scope.launch {
            val beacon = buildBeacon(identifier, deviceName)
            while (isActive) {
                runCatching { sock.send(DatagramPacket(beacon, beacon.size, MULTICAST_ADDRESS, MULTICAST_PORT)) }
                delay(BEACON_INTERVAL_MS)
            }
        }

        // Receive loop — data packets from clients
        scope.launch {
            val buf = ByteArray(65_507)
            val packet = DatagramPacket(buf, buf.size)
            while (isActive) {
                try {
                    sock.receive(packet)
                } catch (_: java.net.SocketTimeoutException) {
                    continue
                } catch (e: Exception) {
                    if (isActive) Log.e(TAG, "Receive error", e); break
                }
                val raw = packet.data.copyOf(packet.length)
                val (type, content) = parsePacket(raw) ?: continue
                if (type == PKT_DATA && content.isNotEmpty()) {
                    _fromClient.emit(content)
                    Log.d(TAG, "Received ${content.size} bytes from ${packet.address}")
                }
            }
        }
    }

    suspend fun sendToClient(data: ByteArray): Unit = withContext(Dispatchers.IO) {
        val sock = socket ?: return@withContext
        val pkt = buildData(data)
        runCatching { sock.send(DatagramPacket(pkt, pkt.size, MULTICAST_ADDRESS, MULTICAST_PORT)) }
            .onFailure { Log.e(TAG, "Send error", it) }
    }

    fun receiveFromClient(): Flow<ByteArray> = _fromClient.asSharedFlow()

    suspend fun stop(): Unit = withContext(Dispatchers.IO) {
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        runCatching { socket?.leaveGroup(MULTICAST_ADDRESS) }
        runCatching { socket?.close() }
        runCatching { multicastLock?.release() }
        socket = null; multicastLock = null
        Log.i(TAG, "Multicast server stopped")
    }
}
