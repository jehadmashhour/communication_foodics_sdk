package com.foodics.crosscommunicationlibrary.ssdp

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.foodics.crosscommunicationlibrary.AppContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.net.*

actual class SSDPServerHandler {

    companion object {
        private const val TAG = "SSDPServer"
    }

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _fromClient = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val fromClientFlow: Flow<ByteArray> = _fromClient.asSharedFlow()

    private var tcpServer: ServerSocket? = null
    private var udpSocket: MulticastSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var clientSocket: Socket? = null

    suspend fun start(deviceName: String, identifier: String): Unit =
        withContext(Dispatchers.IO) {
            // Open TCP server on a random port
            val srv = ServerSocket(0)
            tcpServer = srv
            val tcpPort = srv.localPort
            val ip = getLocalIpAndroid()

            // Acquire Wi-Fi multicast lock (required on Android)
            val wifiMgr = AppContext.get()
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lock = wifiMgr.createMulticastLock("$TAG.lock")
            lock.setReferenceCounted(true)
            lock.acquire()
            multicastLock = lock

            // Create multicast UDP socket
            val mSock = MulticastSocket(SSDP_PORT)
            mSock.reuseAddress = true
            mSock.soTimeout = 2_000        // 2 s receive timeout so loop can check isActive
            mSock.joinGroup(InetAddress.getByName(SSDP_MULTICAST_IP))
            udpSocket = mSock

            Log.i(TAG, "SSDP server: $deviceName @ $ip:$tcpPort")

            // Listen for M-SEARCH requests
            scope.launch {
                val buf = ByteArray(2048)
                while (isActive) {
                    val pkt = DatagramPacket(buf, buf.size)
                    val ok = runCatching { mSock.receive(pkt); true }.getOrDefault(false)
                    if (!ok) continue
                    val msg = String(pkt.data, 0, pkt.length)
                    if (msg.contains("M-SEARCH") && msg.contains(SSDP_SERVICE_TYPE)) {
                        val resp = ssdpOkResponse(identifier, deviceName, ip, tcpPort).toByteArray()
                        runCatching {
                            mSock.send(DatagramPacket(resp, resp.size, pkt.address, pkt.port))
                        }
                        Log.i(TAG, "Replied to M-SEARCH from ${pkt.address.hostAddress}")
                    }
                }
            }

            // Periodic NOTIFY alive (every 30 s)
            scope.launch {
                while (isActive) {
                    val notify = ssdpNotifyAlive(identifier, deviceName, ip, tcpPort).toByteArray()
                    runCatching {
                        mSock.send(DatagramPacket(notify, notify.size,
                            InetAddress.getByName(SSDP_MULTICAST_IP), SSDP_PORT))
                    }
                    Log.i(TAG, "NOTIFY alive sent")
                    delay(30_000)
                }
            }

            // Accept TCP clients
            scope.launch {
                while (isActive) {
                    val sock = runCatching { srv.accept() }.getOrNull() ?: break
                    clientSocket?.close()
                    clientSocket = sock
                    Log.i(TAG, "TCP client connected: ${sock.inetAddress.hostAddress}")
                    scope.launch { runReceiveLoop(sock) }
                }
            }
        }

    private fun runReceiveLoop(socket: Socket) {
        scope.launch {
            val input = socket.getInputStream()
            while (isActive && !socket.isClosed) {
                val data = runCatching { ssdpReadFramed(input) }.getOrNull() ?: break
                _fromClient.emit(data)
            }
            runCatching { socket.close() }
            if (clientSocket == socket) clientSocket = null
            Log.i(TAG, "TCP client disconnected")
        }
    }

    suspend fun sendToClient(data: ByteArray) {
        val sock = clientSocket ?: run { Log.w(TAG, "No client connected"); return }
        runCatching {
            sock.getOutputStream().apply { write(ssdpFramed(data)); flush() }
        }.onFailure { Log.e(TAG, "Send error", it) }
    }

    fun receiveFromClient(): Flow<ByteArray> = fromClientFlow

    suspend fun stop(): Unit = withContext(Dispatchers.IO) {
        try {
            runCatching { udpSocket?.leaveGroup(InetAddress.getByName(SSDP_MULTICAST_IP)) }
            udpSocket?.close()
            clientSocket?.close()
            tcpServer?.close()
            multicastLock?.release()
            scope.cancel()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            udpSocket = null; tcpServer = null; clientSocket = null; multicastLock = null
            Log.i(TAG, "Server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Stop error", e)
        }
    }
}
