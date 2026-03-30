package com.foodics.crosscommunicationlibrary.wifi_direct

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import com.foodics.crosscommunicationlibrary.wifi_direct.WifiDirectConstants.SERVICE_INSTANCE
import com.foodics.crosscommunicationlibrary.wifi_direct.WifiDirectConstants.SERVICE_TYPE
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object WifiDirectConstants {
    const val SERVICE_TYPE = "_foodics._tcp."
    const val SERVICE_INSTANCE = "FoodicsService"
}

actual class WifiDirectServerHandler(
    private val context: Context
) {
    private val manager by lazy { context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager }
    private lateinit var channel: WifiP2pManager.Channel
    private var localService: WifiP2pDnsSdServiceInfo? = null
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

    /** Public startServer() API stays the same */
    @SuppressLint("MissingPermission")
    suspend fun start(deviceName: String, deviceId: String) {
        stop()
        delay(300)

        channel = manager.initialize(context, context.mainLooper, null)

        val record = hashMapOf("deviceName" to deviceName, "deviceId" to deviceId)
        localService = WifiP2pDnsSdServiceInfo.newInstance(SERVICE_INSTANCE, SERVICE_TYPE, record)

        manager.addLocalService(channel, localService, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                println("Service advertised")
            }

            override fun onFailure(reason: Int) {
                println("Failed to advertise service: $reason")
            }
        })
    }

    suspend fun sendToClient(data: ByteArray) { /* write to socket */
    }

    fun receiveFromClient(): Flow<ByteArray> = _incoming.asSharedFlow()
    suspend fun stop() {
        if (::channel.isInitialized) {
            manager.clearLocalServices(channel, null)
        }
    }

}
