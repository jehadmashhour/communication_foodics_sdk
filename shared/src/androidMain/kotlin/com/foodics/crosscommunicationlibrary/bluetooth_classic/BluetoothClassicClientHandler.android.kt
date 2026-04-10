package com.foodics.crosscommunicationlibrary.bluetooth_classic

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import client.WriteType
import ConnectionType
import com.foodics.crosscommunicationlibrary.AndroidAppContextProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import scanner.IoTDevice
import java.io.OutputStream

actual class BluetoothClassicClientHandler {

    companion object {
        private const val TAG = "BtClassicClient"
    }

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    private var socket: BluetoothSocket? = null
    private var output: OutputStream? = null

    // ── Scan ──────────────────────────────────────────────────────────────────

    /**
     * Emits already-paired devices immediately, then discovers nearby Classic BT devices.
     * Discovery is restarted automatically when each 12-second scan cycle finishes.
     */
    fun scan(): Flow<List<IoTDevice>> = channelFlow {
        val ctx = AndroidAppContextProvider.context
        val adapter = getAdapter() ?: run { Log.e(TAG, "Bluetooth not available"); return@channelFlow }

        val devicesMap = mutableMapOf<String, IoTDevice>()

        // Emit paired devices immediately — they are always available
        @Suppress("MissingPermission")
        runCatching {
            adapter.bondedDevices.forEach { d ->
                devicesMap[d.address] = d.toIoTDevice()
            }
        }
        trySend(devicesMap.values.toList())

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        @Suppress("MissingPermission", "DEPRECATION")
                        val device: BluetoothDevice = if (Build.VERSION.SDK_INT >= 33)
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)!!
                        else
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)!!
                        devicesMap[device.address] = device.toIoTDevice()
                        trySend(devicesMap.values.toList())
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        @Suppress("MissingPermission")
                        runCatching { adapter.startDiscovery() }
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        ctx.registerReceiver(receiver, filter)
        @Suppress("MissingPermission")
        runCatching { adapter.startDiscovery() }

        awaitClose {
            @Suppress("MissingPermission")
            runCatching { adapter.cancelDiscovery() }
            runCatching { ctx.unregisterReceiver(receiver) }
        }
    }.distinctUntilChanged()

    // ── Connect ───────────────────────────────────────────────────────────────

    suspend fun connect(device: IoTDevice): Unit = withContext(Dispatchers.IO) {
        disconnect()
        val adapter = getAdapter() ?: return@withContext
        @Suppress("MissingPermission")
        adapter.cancelDiscovery() // mandatory before connecting

        val remoteDevice = runCatching { adapter.getRemoteDevice(device.address) }.getOrNull()
            ?: run { Log.e(TAG, "Invalid address: ${device.address}"); return@withContext }

        val sock = openRfcommSocket(remoteDevice)
            ?: run { Log.e(TAG, "Could not create RFCOMM socket"); return@withContext }

        @Suppress("MissingPermission")
        val connected = runCatching { sock.connect(); true }.getOrElse {
            Log.e(TAG, "RFCOMM connect failed: ${it.message}")
            runCatching { sock.close() }
            false
        }
        if (!connected) return@withContext

        socket = sock
        output = sock.outputStream
        Log.i(TAG, "Classic BT connected to ${device.name} @ ${device.address}")

        scope.launch {
            val buf = ByteArray(4096)
            try {
                val input = sock.inputStream
                while (isActive && sock.isConnected) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    _incoming.emit(buf.copyOf(n))
                }
            } catch (e: Exception) {
                Log.d(TAG, "Remote stream ended: ${e.message}")
            } finally {
                disconnect()
            }
        }
    }

    // ── Send / Receive / Disconnect ───────────────────────────────────────────

    suspend fun sendToServer(data: ByteArray, writeType: WriteType): Unit = withContext(Dispatchers.IO) {
        val out = output ?: run { Log.w(TAG, "Not connected"); return@withContext }
        runCatching { out.write(data); out.flush() }.onFailure { Log.e(TAG, "Send error", it) }
    }

    fun receiveFromServer(): Flow<ByteArray> = _incoming.asSharedFlow()

    fun disconnect() {
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        runCatching { socket?.close() }
        socket = null; output = null
        Log.i(TAG, "Classic BT client disconnected")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Try SPP UUID first; fall back to reflection-based channel 1 for devices
     * that don't properly expose the SPP service record.
     */
    @Suppress("MissingPermission")
    private fun openRfcommSocket(device: BluetoothDevice): BluetoothSocket? {
        runCatching { return device.createRfcommSocketToServiceRecord(SPP_UUID) }
        Log.d(TAG, "SPP UUID connect failed — trying reflection fallback")
        return runCatching {
            val method = device.javaClass.getMethod("createRfcommSocket", Int::class.java)
            method.invoke(device, 1) as BluetoothSocket
        }.getOrNull()
    }
}
