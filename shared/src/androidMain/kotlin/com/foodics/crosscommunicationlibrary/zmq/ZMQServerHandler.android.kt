package com.foodics.crosscommunicationlibrary.zmq

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.BufferedInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

actual class ZMQServerHandler actual constructor() {

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _fromClient = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var udpSocket: DatagramSocket? = null

    actual suspend fun start(deviceName: String, identifier: String) = withContext(Dispatchers.IO) {
        stop()
        try {
            val ss = ServerSocket(0); serverSocket = ss   // ephemeral port
            val zmqPort = ss.localPort

            // UDP beacon
            val udp = DatagramSocket().apply { broadcast = true }; udpSocket = udp
            val beacon = zmtpBeacon(deviceName, identifier, zmqPort)
            scope.launch(Dispatchers.IO) {
                val bcastAddr = InetAddress.getByName("255.255.255.255")
                while (isActive) {
                    runCatching {
                        udp.send(DatagramPacket(beacon, beacon.size, bcastAddr, ZMQ_DISCOVERY_PORT))
                    }
                    delay(ZMQ_BEACON_INTERVAL_MS)
                }
            }

            // Accept loop
            scope.launch(Dispatchers.IO) {
                while (scope.isActive) {
                    val client = runCatching { ss.accept() }.getOrNull() ?: break
                    clientSocket?.close()
                    clientSocket = client
                    launch(Dispatchers.IO) { handleClient(client) }
                }
            }

            println("[ZMQServer] Started: $deviceName [$identifier] port=$zmqPort")
        } catch (e: Exception) {
            println("[ZMQServer] Start failed: ${e.message}")
        }
    }

    private suspend fun handleClient(socket: Socket) {
        try {
            val input  = socket.getInputStream().buffered(65_536) as BufferedInputStream
            val output = socket.getOutputStream()
            if (!zmtpHandshake(input, output, asServer = true)) {
                println("[ZMQServer] Handshake failed"); return
            }
            println("[ZMQServer] Client connected")
            while (scope.isActive) {
                val (isCmd, body) = input.readZmtpFrame() ?: break
                if (!isCmd) _fromClient.emit(body)
            }
        } catch (e: Exception) {
            println("[ZMQServer] Client disconnected: ${e.message}")
        } finally {
            if (clientSocket == socket) clientSocket = null
            runCatching { socket.close() }
        }
    }

    actual fun sendToClient(data: ByteArray) {
        val sock = clientSocket ?: run { println("[ZMQServer] No client"); return }
        scope.launch(Dispatchers.IO) {
            runCatching { sock.getOutputStream().zmtpSend(zmtpMessageFrame(data)) }
                .onFailure { println("[ZMQServer] Send error: ${it.message}") }
        }
    }

    actual fun receiveFromClient(): Flow<ByteArray> = _fromClient.asSharedFlow()

    actual suspend fun stop() {
        scope.cancel(); scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        withContext(Dispatchers.IO) {
            runCatching { clientSocket?.close() }; clientSocket = null
            runCatching { serverSocket?.close() }; serverSocket = null
            runCatching { udpSocket?.close() };    udpSocket    = null
        }
        println("[ZMQServer] Stopped")
    }
}
