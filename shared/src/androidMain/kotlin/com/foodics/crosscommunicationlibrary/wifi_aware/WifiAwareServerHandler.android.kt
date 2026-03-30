package com.foodics.crosscommunicationlibrary.wifi_aware

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareSession
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONObject

actual class WifiAwareServerHandler(private val context: Context) {

    private val manager: WifiAwareManager? by lazy {
        context.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
    }

    private var publishSession: PublishDiscoverySession? = null
    private val handler = Handler(Looper.getMainLooper())
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

    // Map of discovered devices if needed
    private val peers = mutableMapOf<String, PeerHandle>()

    /**
     * Start publishing service with deviceName + deviceId
     */
    @SuppressLint("MissingPermission")
    suspend fun start(deviceName: String, deviceId: String) {
        stop() // cleanup if needed

        val supported = context.packageManager.hasSystemFeature(
            PackageManager.FEATURE_WIFI_AWARE
        )
        Log.d("WifiAware", "Supported: $supported")

        val serviceInfoBytes = JSONObject()
            .put("name", deviceName)
            .put("id", deviceId)
            .toString()
            .toByteArray(Charsets.UTF_8)

        manager?.attach(object : AttachCallback() {
            override fun onAttached(session: WifiAwareSession) {
                val publishConfig = PublishConfig.Builder()
                    .setServiceName(WifiAwareConstants.SERVICE_NAME)
                    .setServiceSpecificInfo(serviceInfoBytes) // <-- advertise device info
                    .build()

                session.publish(
                    publishConfig,
                    object : DiscoverySessionCallback() {
                        override fun onPublishStarted(session: PublishDiscoverySession) {
                            publishSession = session
                        }

                        override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                            _incoming.tryEmit(message)
                        }

                        override fun onMessageSendSucceeded(messageId: Int) {}
                        override fun onMessageSendFailed(messageId: Int) {}
                    },
                    handler
                )
            }

            override fun onAttachFailed() {
                println("Wi-Fi Aware server attach failed")
            }
        }, handler)
    }

    /**
     * Send message to a specific peer
     */
    @SuppressLint("MissingPermission")
    suspend fun sendToClient(data: ByteArray) {
    }

    fun receiveFromClient(): Flow<ByteArray> = _incoming.asSharedFlow()

    suspend fun stop() {
        publishSession?.close()
        publishSession = null
        peers.clear()
    }
}