package com.foodics.crosscommunicationlibrary.ssdp

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
import java.net.*

actual class SSDPClientHandler {

    companion object {
        private const val TAG = "SSDPClient"
    }

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _devices = MutableStateFlow<List<IoTDevice>>(emptyList())
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    private var clientSocket: Socket? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun scan(): Flow<List<IoTDevice>> = channelFlow {
        val wifiMgr = AndroidAppContextProvider.context
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifiMgr.createMulticastLock("$TAG.lock")
        lock.setReferenceCounted(true)
        lock.acquire()
        multicastLock = lock

        val mSock = MulticastSocket(SSDP_PORT)
        mSock.reuseAddress = true
        mSock.soTimeout = 2_000
        mSock.joinGroup(InetAddress.getByName(SSDP_MULTICAST_IP))

        // Send initial M-SEARCH
        fun sendMSearch() {
            val mq = ssdpMSearch().toByteArray()
            runCatching {
                mSock.send(DatagramPacket(mq, mq.size,
                    InetAddress.getByName(SSDP_MULTICAST_IP), SSDP_PORT))
            }
        }
        sendMSearch()
        Log.i(TAG, "M-SEARCH sent")

        // Repeat M-SEARCH every 5 s
        launch {
            while (isActive) { delay(5_000); sendMSearch() }
        }

        // Receive responses
        launch {
            val devicesMap = mutableMapOf<String, IoTDevice>()
            val buf = ByteArray(2048)
            while (isActive) {
                val pkt = DatagramPacket(buf, buf.size)
                val ok = runCatching { mSock.receive(pkt); true }.getOrDefault(false)
                if (!ok) continue
                val info = parseSSDPMessage(String(pkt.data, 0, pkt.length)) ?: continue
                if (devicesMap.containsKey(info.id)) continue
                devicesMap[info.id] = IoTDevice(
                    id = info.id, name = info.name,
                    address = "${info.ip}:${info.port}",
                    connectionType = ConnectionType.SSDP
                )
                _devices.value = devicesMap.values.toList()
                Log.i(TAG, "Discovered: ${info.name} @ ${info.ip}:${info.port}")
            }
        }

        // Forward device list updates
        launch { _devices.collect { trySend(it) } }

        awaitClose {
            runCatching {
                mSock.leaveGroup(InetAddress.getByName(SSDP_MULTICAST_IP))
                mSock.close()
            }
            multicastLock?.release()
            multicastLock = null
        }
    }

    suspend fun connect(device: IoTDevice): Unit = withContext(Dispatchers.IO) {
        val (ip, port) = device.address.split(":").let { it[0] to it[1].toInt() }
        val sock = Socket(ip, port)
        clientSocket = sock
        Log.i(TAG, "TCP connected to $ip:$port")
        scope.launch {
            val input = sock.getInputStream()
            while (scope.isActive && !sock.isClosed) {
                val data = runCatching { ssdpReadFramed(input) }.getOrNull() ?: break
                _incoming.emit(data)
            }
            runCatching { sock.close() }
            if (clientSocket == sock) clientSocket = null
            Log.i(TAG, "TCP disconnected from $ip:$port")
        }
    }

    suspend fun sendToServer(data: ByteArray, writeType: WriteType) {
        val sock = clientSocket ?: run { Log.w(TAG, "Not connected"); return }
        runCatching {
            sock.getOutputStream().apply { write(ssdpFramed(data)); flush() }
        }.onFailure { Log.e(TAG, "Send error", it) }
    }

    fun receiveFromServer(): Flow<ByteArray> = _incoming.asSharedFlow()

    suspend fun disconnect(): Unit = withContext(Dispatchers.IO) {
        clientSocket?.close()
        clientSocket = null
        _devices.value = emptyList()
        Log.i(TAG, "Client disconnected")
    }
}
