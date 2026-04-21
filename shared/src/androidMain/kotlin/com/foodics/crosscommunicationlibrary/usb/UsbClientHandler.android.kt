package com.foodics.crosscommunicationlibrary.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import android.os.Build
import android.util.Log
import client.WriteType
import ConnectionType
import com.foodics.crosscommunicationlibrary.AppContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import scanner.IoTDevice
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

actual class UsbClientHandler {

    companion object {
        private const val TAG = "UsbClient"
        private const val ACTION_USB_PERMISSION = "com.foodics.crosscommunicationlibrary.USB_PERMISSION"
        private const val TRANSFER_TIMEOUT_MS = 2_000
        private const val RECEIVE_TIMEOUT_MS = 2_000
    }

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

    private var usbConnection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var outEndpoint: UsbEndpoint? = null
    private var inEndpoint: UsbEndpoint? = null

    // ── Scan ──────────────────────────────────────────────────────────────────

    /**
     * Returns a live [Flow] of currently connected USB devices.
     * Emits immediately with the current list, then updates on plug / unplug events.
     */
    fun scan(): Flow<List<IoTDevice>> = channelFlow {
        val ctx = AppContext.get()
        val usbManager = ctx.getSystemService(Context.USB_SERVICE) as UsbManager

        fun snapshot(): List<IoTDevice> = usbManager.deviceList.values.map { d ->
            val name = buildString {
                d.manufacturerName?.takeIf { it.isNotBlank() }?.let { append(it); append(" ") }
                d.productName?.takeIf { it.isNotBlank() }?.let { append(it) }
                if (isEmpty()) append("USB ${d.vendorId.toString(16).uppercase()}:${d.productId.toString(16).uppercase()}")
            }.trim()
            IoTDevice(
                name = name,
                id = d.deviceName,
                address = d.deviceName,
                connectionType = ConnectionType.USB
            )
        }

        trySend(snapshot())

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                trySend(snapshot())
            }
        }
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ctx.registerReceiver(receiver, filter)
        awaitClose { runCatching { ctx.unregisterReceiver(receiver) } }
    }.distinctUntilChanged()

    // ── Connect ───────────────────────────────────────────────────────────────

    suspend fun connect(device: IoTDevice): Unit = withContext(Dispatchers.IO) {
        disconnect()
        val ctx = AppContext.get()
        val usbManager = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val usbDevice = usbManager.deviceList[device.address]
            ?: run { Log.w(TAG, "USB device not found: ${device.address}"); return@withContext }

        // Request permission if not already granted
        if (!usbManager.hasPermission(usbDevice)) {
            Log.i(TAG, "Requesting USB permission for ${usbDevice.productName}")
            val granted = suspendCancellableCoroutine { cont ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        runCatching { ctx.unregisterReceiver(this) }
                        val ok = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        if (cont.isActive) cont.resume(ok)
                    }
                }
                ctx.registerReceiver(receiver, IntentFilter(ACTION_USB_PERMISSION))
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val pi = PendingIntent.getBroadcast(ctx, 0, Intent(ACTION_USB_PERMISSION), flags)
                usbManager.requestPermission(usbDevice, pi)
                cont.invokeOnCancellation { runCatching { ctx.unregisterReceiver(receiver) } }
            }
            if (!granted) { Log.w(TAG, "USB permission denied for ${usbDevice.productName}"); return@withContext }
        }

        val connection = usbManager.openDevice(usbDevice)
            ?: run { Log.e(TAG, "Failed to open USB device"); return@withContext }

        // Find the first interface with bulk endpoints
        for (i in 0 until usbDevice.interfaceCount) {
            val intf = usbDevice.getInterface(i)
            if (!connection.claimInterface(intf, true)) continue

            var epOut: UsbEndpoint? = null
            var epIn: UsbEndpoint? = null
            for (j in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(j)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_OUT) epOut = ep
                    else epIn = ep
                }
            }

            if (epOut != null || epIn != null) {
                usbConnection = connection
                usbInterface = intf
                outEndpoint = epOut
                inEndpoint = epIn
                Log.i(TAG, "USB connected: ${usbDevice.productName} — OUT=$epOut IN=$epIn")
                epIn?.let { startReceiveLoop(connection, it) }
                return@withContext
            }
            connection.releaseInterface(intf)
        }

        Log.w(TAG, "No bulk endpoints found — device may use a different transfer type")
        connection.close()
    }

    // ── Send ──────────────────────────────────────────────────────────────────

    suspend fun sendToServer(data: ByteArray, writeType: WriteType): Unit = withContext(Dispatchers.IO) {
        val conn = usbConnection ?: run { Log.w(TAG, "Not connected"); return@withContext }
        val ep = outEndpoint ?: run { Log.w(TAG, "No OUT endpoint"); return@withContext }
        var offset = 0
        while (offset < data.size) {
            val chunk = data.copyOfRange(offset, minOf(offset + ep.maxPacketSize, data.size))
            val n = conn.bulkTransfer(ep, chunk, chunk.size, TRANSFER_TIMEOUT_MS)
            if (n < 0) { Log.e(TAG, "Bulk OUT transfer failed at offset $offset"); break }
            offset += n
        }
    }

    fun receiveFromServer(): Flow<ByteArray> = _incoming.asSharedFlow()

    // ── Disconnect ────────────────────────────────────────────────────────────

    suspend fun disconnect(): Unit = withContext(Dispatchers.IO) {
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        usbInterface?.let { runCatching { usbConnection?.releaseInterface(it) } }
        runCatching { usbConnection?.close() }
        usbConnection = null; usbInterface = null; outEndpoint = null; inEndpoint = null
        Log.i(TAG, "USB disconnected")
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun startReceiveLoop(connection: UsbDeviceConnection, endpoint: UsbEndpoint) {
        val bufSize = endpoint.maxPacketSize.coerceAtLeast(512)
        scope.launch {
            val buf = ByteArray(bufSize)
            while (isActive) {
                val n = connection.bulkTransfer(endpoint, buf, buf.size, RECEIVE_TIMEOUT_MS)
                if (n > 0) _incoming.emit(buf.copyOf(n))
                // n == 0 → timeout with no data (normal); n < 0 → error / disconnected
                else if (n < 0 && !isActive) break
            }
        }
    }
}
