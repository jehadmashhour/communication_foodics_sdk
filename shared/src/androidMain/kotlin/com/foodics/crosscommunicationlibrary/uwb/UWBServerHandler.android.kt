@file:OptIn(androidx.core.uwb.ExperimentalUwbApi::class)

package com.foodics.crosscommunicationlibrary.uwb

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.core.uwb.RangingParameters
import androidx.core.uwb.RangingResult
import androidx.core.uwb.UwbAddress
import androidx.core.uwb.UwbDevice
import androidx.core.uwb.UwbManager
import com.foodics.crosscommunicationlibrary.AndroidAppContextProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.net.*

/**
 * Android UWB server (FiRa Controller role).
 *
 * Lifecycle:
 *  1. start()  → creates UwbManager controller session, begins UDP OOB advertising
 *               on 239.255.255.250:1901, opens a TCP server for client address
 *               exchange, then starts ranging once a client connects.
 *  2. stop()   → cancels all coroutines, releases sockets and multicast lock.
 *
 * Ranging results are emitted through [receiveFromClient] as 12-byte ByteArrays.
 * Requires Android 12+ (API 31) and UWB-capable hardware.
 */
actual class UWBServerHandler {

    companion object {
        private const val TAG = "UWBServer"
    }

    private var scope        = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var udpSocket    : MulticastSocket? = null
    private var tcpServer    : ServerSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private val _ranging = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

    // ── Public API ────────────────────────────────────────────────────────────

    suspend fun start(deviceName: String, identifier: String): Unit = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Log.w(TAG, "UWB requires Android 12 (API 31). Skipping.")
            return@withContext
        }

        val context = AndroidAppContextProvider.context
        val ip      = uwbGetLocalIpAndroid()

        // Acquire multicast lock so UDP multicast works on Android Wi-Fi
        val wifiMgr = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifiMgr.createMulticastLock("$TAG.lock").also { it.setReferenceCounted(true); it.acquire() }
        multicastLock = lock

        // TCP server for OOB client-address exchange
        val srv = ServerSocket(0).also { tcpServer = it }
        val tcpPort = srv.localPort

        // UDP multicast socket
        val mSock = MulticastSocket(UWB_OOB_PORT).also {
            it.reuseAddress = true
            it.soTimeout    = 2_000
            it.joinGroup(InetAddress.getByName(UWB_MULTICAST_IP))
            udpSocket = it
        }

        Log.i(TAG, "UWB server OOB ready: $deviceName @ $ip:$tcpPort")

        scope.launch {
            val uwbManager = runCatching { UwbManager.createInstance(context) }.getOrElse {
                Log.e(TAG, "UWB unavailable: ${it.message}"); return@launch
            }
            val ctrlScope = runCatching { uwbManager.controllerSessionScope() }.getOrElse {
                Log.e(TAG, "Controller session failed: ${it.message}"); return@launch
            }

            val myAddr  = ctrlScope.localAddress.address          // 2 bytes
            val channel = ctrlScope.uwbComplexChannel
            val sid     = (System.currentTimeMillis() and 0x7FFF_FFFFL).toInt()

            Log.i(TAG, "UWB addr=${myAddr[0].toUInt()}.${myAddr[1].toUInt()} " +
                       "ch=${channel.channel} pre=${channel.preambleIndex}")

            // Periodic NOTIFY alive
            launch {
                while (isActive) {
                    val msg = uwbAndroidAnnounce(deviceName, identifier, ip, tcpPort,
                                                 myAddr, channel.channel, channel.preambleIndex, sid)
                    runCatching {
                        mSock.send(DatagramPacket(msg.toByteArray(), msg.length,
                            InetAddress.getByName(UWB_MULTICAST_IP), UWB_OOB_PORT))
                    }
                    delay(5_000)
                }
            }

            // Reply to M-SEARCH
            launch {
                val buf = ByteArray(2048)
                while (isActive) {
                    val pkt = DatagramPacket(buf, buf.size)
                    val ok  = runCatching { mSock.receive(pkt); true }.getOrDefault(false)
                    if (!ok) continue
                    val text = String(pkt.data, 0, pkt.length)
                    if (text.startsWith(UWB_ANDROID_SEARCH)) {
                        val resp = uwbAndroidAnnounce(deviceName, identifier, ip, tcpPort,
                                                      myAddr, channel.channel, channel.preambleIndex, sid)
                        runCatching {
                            mSock.send(DatagramPacket(resp.toByteArray(), resp.length,
                                pkt.address, pkt.port))
                        }
                        Log.i(TAG, "Replied M-SEARCH from ${pkt.address.hostAddress}")
                    }
                }
            }

            // Accept one TCP client for address exchange
            val clientLine = runCatching {
                val sock = srv.accept()
                val line = sock.getInputStream().bufferedReader().readLine()
                sock.close()
                line
            }.getOrElse { Log.w(TAG, "TCP accept error: ${it.message}"); return@launch }

            val parts = clientLine?.split("|")
            if (parts == null || parts.size < 2) {
                Log.w(TAG, "Bad client address: $clientLine"); return@launch
            }
            val clientAddrBytes = byteArrayOf(
                (parts[0].toIntOrNull() ?: 0).toByte(),
                (parts[1].toIntOrNull() ?: 0).toByte()
            )
            Log.i(TAG, "Client UWB address received: ${parts[0]}|${parts[1]}")

            // Start FiRa ranging
            val params = RangingParameters(
                uwbConfigType    = RangingParameters.CONFIG_UNICAST_DS_TWR,
                sessionId        = sid,
                subSessionId     = 0,
                sessionKeyInfo   = null,
                complexChannel   = channel,
                peerDevices      = listOf(UwbDevice.createForAddress(UwbAddress(clientAddrBytes))),
                updateRateType   = RangingParameters.RANGING_UPDATE_RATE_AUTOMATIC
            )

            Log.i(TAG, "UWB ranging started (Controller)")
            runCatching {
                ctrlScope.prepareSession(params).collect { result ->
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

    fun receiveFromClient(): Flow<ByteArray> = _ranging.asSharedFlow()

    suspend fun stop(): Unit = withContext(Dispatchers.IO) {
        runCatching { udpSocket?.leaveGroup(InetAddress.getByName(UWB_MULTICAST_IP)) }
        runCatching { udpSocket?.close() }
        runCatching { tcpServer?.close() }
        runCatching { multicastLock?.release() }
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        udpSocket = null; tcpServer = null; multicastLock = null
        Log.i(TAG, "UWB server stopped")
    }
}
