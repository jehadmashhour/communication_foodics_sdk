package com.foodics.crosscommunicationlibrary.modbus_tcp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.BufferedInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

actual class ModbusServerHandler actual constructor() {

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _fromClient = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var udpSocket: DatagramSocket? = null

    actual suspend fun start(deviceName: String, identifier: String) = withContext(Dispatchers.IO) {
        stop()
        try {
            val ss = ServerSocket(0); serverSocket = ss
            val port = ss.localPort

            val udp = DatagramSocket().apply { broadcast = true }; udpSocket = udp
            val beacon = modbusBeacon(deviceName, identifier, port)
            scope.launch(Dispatchers.IO) {
                val bcast = InetAddress.getByName("255.255.255.255")
                while (isActive) {
                    runCatching { udp.send(DatagramPacket(beacon, beacon.size, bcast, MODBUS_DISCOVERY_PORT)) }
                    delay(MODBUS_BEACON_INTERVAL_MS)
                }
            }

            scope.launch(Dispatchers.IO) {
                while (scope.isActive) {
                    val client = runCatching { ss.accept() }.getOrNull() ?: break
                    clientSocket?.close(); clientSocket = client
                    launch(Dispatchers.IO) { handleClient(client) }
                }
            }

            println("[ModbusServer] Started: $deviceName [$identifier] port=$port")
        } catch (e: Exception) {
            println("[ModbusServer] Start failed: ${e.message}")
        }
    }

    private suspend fun handleClient(socket: Socket) {
        try {
            val input = socket.getInputStream().buffered(65_536) as BufferedInputStream
            println("[ModbusServer] Client connected")
            while (scope.isActive) {
                val frame = input.readModbusFrame() ?: break
                if (frame.fc == FC_UPLOAD) _fromClient.emit(frame.data)
            }
        } catch (e: Exception) {
            println("[ModbusServer] Client disconnected: ${e.message}")
        } finally {
            if (clientSocket == socket) clientSocket = null
            runCatching { socket.close() }
        }
    }

    actual fun sendToClient(data: ByteArray) {
        val sock = clientSocket ?: run { println("[ModbusServer] No client"); return }
        scope.launch(Dispatchers.IO) {
            runCatching { sock.getOutputStream().modbusWrite(buildModbusAdu(FC_PUSH, data)) }
                .onFailure { println("[ModbusServer] Send error: ${it.message}") }
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
        println("[ModbusServer] Stopped")
    }
}
