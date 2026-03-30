package com.foodics.crosscommunicationlibrary.wifi_aware

import ConnectionType
import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareSession
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import org.json.JSONObject
import scanner.IoTDevice

object WifiAwareConstants {
    const val SERVICE_NAME = "FoodicsAwareService"
}
actual class WifiAwareClientHandler(private val context: Context) {

    private val manager: WifiAwareManager? by lazy {
        context.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
    }

    private var subscribeSession: SubscribeDiscoverySession? = null
    private val handler = Handler(Looper.getMainLooper())
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

    private val discoveredDevices = mutableMapOf<String, IoTDevice>()
    private val peers = mutableMapOf<String, PeerHandle>() // deviceId -> PeerHandle

    /**
     * Scan for nearby services. Returns a list of discovered IoTDevices with deviceName + deviceId
     */
    @SuppressLint("MissingPermission")
    fun scan(): Flow<List<IoTDevice>> = callbackFlow {
        if (manager?.isAvailable?.not() == true) {
            close(Exception("Wi-Fi Aware not available on this device"))
            return@callbackFlow
        }

        manager?.attach(object : AttachCallback() {
            override fun onAttached(session: WifiAwareSession) {
                val subscribeConfig = SubscribeConfig.Builder()
                    .setServiceName(WifiAwareConstants.SERVICE_NAME)
                    .build()

                session.subscribe(
                    subscribeConfig,
                    object : DiscoverySessionCallback() {

                        override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
                            subscribeSession = session
                        }

                        override fun onServiceDiscovered(
                            peerHandle: PeerHandle,
                            serviceSpecificInfo: ByteArray,
                            matchFilter: List<ByteArray>
                        ) {
                            val json = serviceSpecificInfo.toString(Charsets.UTF_8)
                            val obj = JSONObject(json)
                            val name = obj.optString("name", "Unknown")
                            val id = obj.optString("id", peerHandle.hashCode().toString())

                            val device = IoTDevice(
                                name = name,
                                address = id,
                                connectionType = ConnectionType.WIFI_AWARE,
                                id = id
                            )
                            discoveredDevices[id] = device
                            peers[id] = peerHandle
                            trySend(discoveredDevices.values.toList()).isSuccess
                        }

                        override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                            _incoming.tryEmit(message)
                        }
                    },
                    handler
                )
            }

            override fun onAttachFailed() {
                close(Exception("Wi-Fi Aware attach failed"))
            }
        }, handler)

        awaitClose {
            subscribeSession?.close()
            discoveredDevices.clear()
            peers.clear()
        }
    }

    suspend fun connect(device: IoTDevice) {

    }
    /**
     * Send message to a discovered device
     */
    @SuppressLint("MissingPermission")
    suspend fun sendToServer(data: ByteArray) {

    }

    fun receiveFromServer(): Flow<ByteArray> = _incoming.asSharedFlow()

    suspend fun disconnect() {
        subscribeSession?.close()
        subscribeSession = null
        discoveredDevices.clear()
        peers.clear()
    }
}