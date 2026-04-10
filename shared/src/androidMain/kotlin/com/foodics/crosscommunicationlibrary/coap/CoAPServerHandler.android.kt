package com.foodics.crosscommunicationlibrary.coap

import android.util.Log
import com.appstractive.dnssd.NetService
import com.appstractive.dnssd.createNetService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

actual class CoAPServerHandler {

    companion object {
        private const val TAG = "CoAPServer"
        private const val BUF_SIZE = 65_507 // max UDP payload
    }

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _fromClient = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    private var serverSocket: DatagramSocket? = null
    private var netService: NetService? = null

    @Volatile private var clientAddress: InetAddress? = null
    @Volatile private var clientPort: Int = 0

    suspend fun start(deviceName: String, identifier: String): Unit = withContext(Dispatchers.IO) {
        stop()
        val sock = DatagramSocket(0)
        sock.soTimeout = 2_000
        serverSocket = sock
        val port = sock.localPort

        val service = createNetService(
            type = COAP_SERVICE_TYPE,
            name = deviceName,
            port = port,
            txt = mapOf("id" to identifier)
        )
        service.register()
        netService = service
        Log.i(TAG, "CoAP server: $deviceName @ UDP port $port")

        scope.launch {
            val buf = ByteArray(BUF_SIZE)
            val packet = DatagramPacket(buf, buf.size)
            while (isActive) {
                try {
                    sock.receive(packet)
                } catch (_: java.net.SocketTimeoutException) {
                    continue
                } catch (e: Exception) {
                    if (isActive) Log.e(TAG, "Receive error", e)
                    break
                }
                val raw = packet.data.copyOf(packet.length)
                if (!isValidCoap(raw)) continue

                // Track the client address so we can push messages back
                clientAddress = packet.address
                clientPort = packet.port

                val payload = coapParsePayload(raw)
                if (payload.isNotEmpty()) {
                    _fromClient.emit(payload)
                    Log.d(TAG, "Received ${payload.size} bytes from ${packet.address}:${packet.port}")
                }
            }
        }
    }

    suspend fun sendToClient(data: ByteArray): Unit = withContext(Dispatchers.IO) {
        val addr = clientAddress ?: run { Log.w(TAG, "No client known yet"); return@withContext }
        val port = clientPort
        val sock = serverSocket ?: return@withContext
        val frame = coapBuildContent(data)
        runCatching { sock.send(DatagramPacket(frame, frame.size, addr, port)) }
            .onFailure { Log.e(TAG, "sendToClient error", it) }
    }

    fun receiveFromClient(): Flow<ByteArray> = _fromClient.asSharedFlow()

    suspend fun stop(): Unit = withContext(Dispatchers.IO) {
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        runCatching { netService?.unregister() }
        runCatching { serverSocket?.close() }
        netService = null; serverSocket = null
        clientAddress = null; clientPort = 0
        Log.i(TAG, "CoAP server stopped")
    }
}
