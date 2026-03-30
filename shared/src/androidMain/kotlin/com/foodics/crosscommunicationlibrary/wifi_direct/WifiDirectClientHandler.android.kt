package com.foodics.crosscommunicationlibrary.wifi_direct

import ConnectionType
import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.*
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import scanner.IoTDevice
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException


actual class WifiDirectClientHandler(
    private val context: Context
) {

    private val manager by lazy { context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager }
    private lateinit var wifiManagerChannel: WifiP2pManager.Channel
    private var serviceRequest: WifiP2pDnsSdServiceRequest? = null

    // Internal wrapper to track lastSeen for stale device removal
    private data class DiscoveredDevice(
        val device: IoTDevice,
        var lastSeen: Long
    )

    private val discoveredDevices = mutableMapOf<String, DiscoveredDevice>()

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

    private val discoveryHandler = Handler(Looper.getMainLooper())

    // -----------------------------
    // Public API: scan for devices
    // -----------------------------
    @SuppressLint("MissingPermission")
    fun scan(): Flow<List<IoTDevice>> = callbackFlow {
        wifiManagerChannel = manager.initialize(context, context.mainLooper, null)

        // DNS-SD service response listener
        val serviceResponseListener =
            WifiP2pManager.DnsSdServiceResponseListener { instanceName, _, device ->
                if (instanceName == WifiDirectConstants.SERVICE_INSTANCE) {
                    val now = System.currentTimeMillis()
                    val ioTDevice = IoTDevice(
                        name = device.deviceName ?: device.deviceAddress,
                        address = device.deviceAddress,
                        connectionType = ConnectionType.WIFI_DIRECT,
                        id = device.deviceAddress
                    )
                    discoveredDevices[device.deviceAddress] = DiscoveredDevice(ioTDevice, now)
                    trySend(discoveredDevices.values.map { it.device }).isSuccess
                }
            }

        // DNS-SD TXT record listener
        val txtRecordListener = WifiP2pManager.DnsSdTxtRecordListener { _, record, device ->
            val now = System.currentTimeMillis()
            val deviceName = record["deviceName"] ?: device.deviceName ?: device.deviceAddress
            val deviceId = record["deviceId"]

            val ioTDevice = IoTDevice(
                name = deviceName,
                address = device.deviceAddress,
                connectionType = ConnectionType.WIFI_DIRECT,
                id = deviceId
            )
            discoveredDevices[device.deviceAddress] = DiscoveredDevice(ioTDevice, now)
            trySend(discoveredDevices.values.map { it.device }).isSuccess
        }

        // Register listeners
        manager.setDnsSdResponseListeners(wifiManagerChannel, serviceResponseListener, txtRecordListener)

        // Runnable for stale device cleanup
        val discoveryRunnable = object : Runnable {
            override fun run() {
                expireStaleDevices()
                trySend(discoveredDevices.values.map { it.device }).isSuccess
                runDiscoveryCycle()
                discoveryHandler.postDelayed(this, 5_000)
            }
        }

        // Start the discovery loop
        discoveryHandler.post(discoveryRunnable)

        // Clean up when flow is closed
        awaitClose {
            discoveryHandler.removeCallbacks(discoveryRunnable)
            manager.clearServiceRequests(wifiManagerChannel, null)
        }
    }

    // -----------------------------
    // Discovery loop
    // -----------------------------
    @SuppressLint("MissingPermission")
    private fun runDiscoveryCycle() {
        manager.clearServiceRequests(wifiManagerChannel, null)

        serviceRequest = WifiP2pDnsSdServiceRequest.newInstance(
            WifiDirectConstants.SERVICE_INSTANCE,
            WifiDirectConstants.SERVICE_TYPE
        )

        manager.addServiceRequest(wifiManagerChannel, serviceRequest, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                manager.discoverServices(wifiManagerChannel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        println("Discovery cycle started")
                    }

                    override fun onFailure(reason: Int) {
                        println("Discovery failed: $reason")
                    }
                })
            }

            override fun onFailure(reason: Int) {
                println("Add service request failed: $reason")
            }
        })
    }

    // -----------------------------
    // Remove stale devices
    // -----------------------------
    private fun expireStaleDevices() {
        val now = System.currentTimeMillis()
        val iterator = discoveredDevices.entries.iterator()
        while (iterator.hasNext()) {
            val (_, dev) = iterator.next()
            if (now - dev.lastSeen > 15_000) iterator.remove()
        }
    }

    // -----------------------------
    // Connect to a discovered device
    // -----------------------------
    @SuppressLint("MissingPermission")
    suspend fun connect(device: IoTDevice) {
        val config = WifiP2pConfig().apply {
            deviceAddress = device.address
            wps.setup = WpsInfo.PBC
        }

        suspendCancellableCoroutine<Unit> { cont ->
            manager.connect(wifiManagerChannel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { cont.resume(Unit) }
                override fun onFailure(reason: Int) { cont.resumeWithException(IllegalStateException("Connect failed: $reason")) }
            })
        }

        suspendCancellableCoroutine<Unit> { cont ->
            manager.requestConnectionInfo(wifiManagerChannel) { info ->
                if (info.groupFormed) {
                    val host = info.groupOwnerAddress.hostAddress
                    openSocket(host)
                    cont.resume(Unit)
                }
            }
        }
    }


    private fun openSocket(host: String) {
    }

    suspend fun sendToServer(data: ByteArray) {

    }

    fun receiveFromServer(): Flow<ByteArray> = _incoming.asSharedFlow()

    suspend fun disconnect() {

    }
}
