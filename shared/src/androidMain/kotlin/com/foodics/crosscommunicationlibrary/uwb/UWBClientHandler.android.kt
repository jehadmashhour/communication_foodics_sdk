package com.foodics.crosscommunicationlibrary.uwb

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.core.uwb.RangingParameters
import androidx.core.uwb.RangingResult
import androidx.core.uwb.UwbComplexChannel
import androidx.core.uwb.UwbDevice
import androidx.core.uwb.UwbManager
import client.WriteType
import ConnectionType
import com.foodics.crosscommunicationlibrary.AndroidAppContextProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import scanner.IoTDevice
import java.net.*

/**
 * Android UWB client (FiRa Controlee role).
 *
 * Lifecycle:
 *  scan()         → discovers UWB servers via UDP multicast on 239.255.255.250:1901.
 *  connect()      → creates a Controlee session, sends local UWB address to the
 *                   server over TCP, then starts FiRa ranging.
 *  disconnect()   → cancels ranging coroutine.
 *
 * Requires Android 12+ (API 31) and UWB-capable hardware.
 */
actual class UWBClientHandler {

    companion object {
        private const val TAG = "UWBClient"
    }

    private var scope        = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var multicastLock: WifiManager.MulticastLock? = null

    private val _ranging = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

    // ── Scan ──────────────────────────────────────────────────────────────────

    fun scan(): Flow<List<IoTDevice>> = channelFlow {
        val context = AndroidAppContextProvider.context
        val wifiMgr = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifiMgr.createMulticastLock("$TAG.lock").also { it.setReferenceCounted(true); it.acquire() }
        multicastLock = lock

        val mSock = MulticastSocket(UWB_OOB_PORT).also {
            it.reuseAddress = true
            it.soTimeout    = 2_000
            it.joinGroup(InetAddress.getByName(UWB_MULTICAST_IP))
        }

        val discovered = mutableMapOf<String, IoTDevice>()

        fun sendSearch() {
            val bytes = UWB_ANDROID_SEARCH.toByteArray()
            runCatching {
                mSock.send(DatagramPacket(bytes, bytes.size,
                    InetAddress.getByName(UWB_MULTICAST_IP), UWB_OOB_PORT))
            }
        }
        sendSearch()
        Log.i(TAG, "UWB M-SEARCH sent")

        // Re-send every 5 s
        launch { while (isActive) { delay(5_000); sendSearch() } }

        // Receive announce / notify responses
        launch {
            val buf = ByteArray(2048)
            while (isActive) {
                val pkt = DatagramPacket(buf, buf.size)
                val ok  = runCatching { mSock.receive(pkt); true }.getOrDefault(false)
                if (!ok) continue
                val info = parseUwbAndroidAnnounce(String(pkt.data, 0, pkt.length)) ?: continue
                if (discovered.containsKey(info.id)) continue
                discovered[info.id] = IoTDevice(
                    id             = info.id,
                    name           = info.name,
                    address        = info.address,
                    connectionType = ConnectionType.UWB
                )
                trySend(discovered.values.toList())
                Log.i(TAG, "Discovered UWB server: ${info.name} @ ${info.ip}:${info.tcpPort}")
            }
        }

        awaitClose {
            runCatching {
                mSock.leaveGroup(InetAddress.getByName(UWB_MULTICAST_IP))
                mSock.close()
            }
            multicastLock?.release()
            multicastLock = null
        }
    }

    // ── Connect ───────────────────────────────────────────────────────────────

    suspend fun connect(device: IoTDevice): Unit = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Log.w(TAG, "UWB requires Android 12 (API 31). Skipping.")
            return@withContext
        }

        val info = parseUwbAndroidAddress(device.address) ?: run {
            Log.e(TAG, "Cannot parse UWB address: ${device.address}")
            return@withContext
        }

        val uwbManager = runCatching { UwbManager.createInstance(AndroidAppContextProvider.context) }
            .getOrElse { Log.e(TAG, "UWB unavailable: ${it.message}"); return@withContext }

        scope.launch {
            val ctrleeScope = runCatching { uwbManager.controleeSessionScope() }.getOrElse {
                Log.e(TAG, "Controlee session failed: ${it.message}"); return@launch
            }

            val myAddr = ctrleeScope.localAddress.address  // 2 bytes

            // Send local UWB address to server via TCP OOB
            runCatching {
                val sock = Socket()
                sock.connect(InetSocketAddress(info.ip, info.tcpPort), 5_000)
                val line = "${myAddr[0].toInt() and 0xFF}|${myAddr[1].toInt() and 0xFF}\r\n"
                sock.getOutputStream().apply { write(line.toByteArray()); flush() }
                sock.close()
                Log.i(TAG, "UWB address sent to server")
            }.onFailure { Log.e(TAG, "TCP OOB send failed: ${it.message}"); return@launch }

            // Start FiRa ranging as Controlee
            val serverAddr = byteArrayOf(info.addrB0.toByte(), info.addrB1.toByte())
            val params = RangingParameters(
                uwbConfigType     = RangingParameters.CONFIG_UNICAST_DS_TWR,
                sessionId         = info.sessionId,
                subSessionId      = 0,
                sessionKeyInfo    = null,
                subSessionKeyInfo = null,
                complexChannel    = UwbComplexChannel(info.channel, info.preamble),
                peerDevices       = listOf(UwbDevice.createForAddress(serverAddr)),
                updateRateType    = RangingParameters.RANGING_UPDATE_RATE_AUTOMATIC
            )

            Log.i(TAG, "UWB ranging started (Controlee)")
            runCatching {
                ctrleeScope.prepareSession(params).collect { result ->
                    when (result) {
                        is RangingResult.RangingResultPosition -> {
                            val dist = result.position.distance?.value   ?: Float.NaN
                            val az   = result.position.azimuth?.value    ?: Float.NaN
                            val el   = result.position.elevation?.value  ?: Float.NaN
                            _ranging.emit(encodeRanging(dist, az, el))
                            Log.v(TAG, "Ranging: dist=${dist}m az=${az}° el=${el}°")
                        }
                        is RangingResult.RangingResultPeerDisconnected ->
                            Log.i(TAG, "UWB peer disconnected")
                    }
                }
            }.onFailure { if (isActive) Log.e(TAG, "Ranging error: ${it.message}") }
        }
    }

    // ── Receive / Disconnect ──────────────────────────────────────────────────

    fun receiveFromServer(): Flow<ByteArray> = _ranging.asSharedFlow()

    fun sendToServer(data: ByteArray, writeType: WriteType) {
        Log.w(TAG, "sendToServer is a no-op — UWB is a ranging-only channel")
    }

    suspend fun disconnect(): Unit = withContext(Dispatchers.IO) {
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        Log.i(TAG, "UWB client disconnected")
    }
}
