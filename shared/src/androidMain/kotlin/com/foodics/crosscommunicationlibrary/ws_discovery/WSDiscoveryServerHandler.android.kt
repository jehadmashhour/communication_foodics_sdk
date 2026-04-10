package com.foodics.crosscommunicationlibrary.ws_discovery

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.foodics.crosscommunicationlibrary.AndroidAppContextProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.net.*

actual class WSDiscoveryServerHandler {

    companion object {
        private const val TAG = "WSDServer"
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
            val srv = ServerSocket(0)
            tcpServer = srv
            val tcpPort = srv.localPort
            val ip = wsdGetLocalIpAndroid()

            val wifiMgr = AndroidAppContextProvider.context
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lock = wifiMgr.createMulticastLock("$TAG.lock")
            lock.setReferenceCounted(true)
            lock.acquire()
            multicastLock = lock

            val mSock = MulticastSocket(WSD_PORT)
            mSock.reuseAddress = true
            mSock.soTimeout = 2_000
            mSock.joinGroup(InetAddress.getByName(WSD_MULTICAST_IP))
            udpSocket = mSock

            Log.i(TAG, "WS-Discovery server: $deviceName @ $ip:$tcpPort")

            // Send Hello on startup
            val hello = wsdHello(identifier, deviceName, ip, tcpPort).toByteArray()
            runCatching {
                mSock.send(DatagramPacket(hello, hello.size,
                    InetAddress.getByName(WSD_MULTICAST_IP), WSD_PORT))
            }
            Log.i(TAG, "Hello sent")

            // Listen for Probe requests and reply with ProbeMatches
            scope.launch {
                val buf = ByteArray(4096)
                while (isActive) {
                    val pkt = DatagramPacket(buf, buf.size)
                    val ok = runCatching { mSock.receive(pkt); true }.getOrDefault(false)
                    if (!ok) continue
                    val msg = String(pkt.data, 0, pkt.length)
                    if (msg.contains("Probe") && !msg.contains("ProbeMatches") &&
                        msg.contains(WSD_SERVICE_TYPE)
                    ) {
                        val relatesTo = extractMessageId(msg) ?: "unknown"
                        val resp = wsdProbeMatch(identifier, deviceName, ip, tcpPort, relatesTo).toByteArray()
                        runCatching {
                            mSock.send(DatagramPacket(resp, resp.size, pkt.address, pkt.port))
                        }
                        Log.i(TAG, "ProbeMatch sent to ${pkt.address.hostAddress}")
                    }
                }
            }

            // Periodic Hello (every 30 s)
            scope.launch {
                while (isActive) {
                    delay(30_000)
                    val notify = wsdHello(identifier, deviceName, ip, tcpPort).toByteArray()
                    runCatching {
                        mSock.send(DatagramPacket(notify, notify.size,
                            InetAddress.getByName(WSD_MULTICAST_IP), WSD_PORT))
                    }
                    Log.i(TAG, "Periodic Hello sent")
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
                val data = runCatching { wsdReadFramed(input) }.getOrNull() ?: break
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
            sock.getOutputStream().apply { write(wsdFramed(data)); flush() }
        }.onFailure { Log.e(TAG, "Send error", it) }
    }

    fun receiveFromClient(): Flow<ByteArray> = fromClientFlow

    suspend fun stop(): Unit = withContext(Dispatchers.IO) {
        try {
            runCatching { udpSocket?.leaveGroup(InetAddress.getByName(WSD_MULTICAST_IP)) }
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
