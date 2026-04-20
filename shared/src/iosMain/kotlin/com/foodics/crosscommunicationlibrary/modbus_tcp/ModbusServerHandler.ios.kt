@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.foodics.crosscommunicationlibrary.modbus_tcp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import platform.posix.*
import kotlin.concurrent.Volatile

actual class ModbusServerHandler actual constructor() {

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _fromClient = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

    @Volatile private var serverFd  = -1
    @Volatile private var clientFd  = -1
    @Volatile private var udpSendFd = -1

    actual suspend fun start(deviceName: String, identifier: String) {
        stop(); delay(500)
        val (sFd, port) = modbusTcpBind()
        if (sFd < 0) { println("[ModbusServer] Bind failed"); return }
        serverFd = sFd

        val uFd = modbusUdpSenderFd()
        if (uFd < 0) { println("[ModbusServer] UDP failed"); close(sFd); serverFd = -1; return }
        udpSendFd = uFd

        println("[ModbusServer] Started: $deviceName [$identifier] port=$port")

        val beacon = modbusBeacon(deviceName, identifier, port)
        scope.launch {
            while (isActive) {
                runCatching { modbusUdpBroadcast(udpSendFd, beacon, MODBUS_DISCOVERY_PORT) }
                delay(MODBUS_BEACON_INTERVAL_MS)
            }
        }

        scope.launch { acceptLoop(sFd) }
    }

    private suspend fun acceptLoop(srvFd: Int) {
        while (scope.isActive && serverFd == srvFd) {
            val cFd = modbusAccept(srvFd)
            if (cFd < 0) continue
            if (clientFd >= 0) close(clientFd)
            clientFd = cFd
            scope.launch { handleClient(cFd) }
        }
    }

    private suspend fun handleClient(fd: Int) {
        try {
            println("[ModbusServer] Client connected")
            modbusSetTimeout(fd, 2_000)
            while (scope.isActive && clientFd == fd) {
                val frame = modbusReadFrame(fd) ?: continue
                if (frame.fc == FC_UPLOAD) _fromClient.emit(frame.data)
            }
        } catch (e: Exception) {
            println("[ModbusServer] Client disconnected: ${e.message}")
        } finally {
            if (clientFd == fd) clientFd = -1
            close(fd)
        }
    }

    actual fun sendToClient(data: ByteArray) {
        val cFd = clientFd; if (cFd < 0) { println("[ModbusServer] No client"); return }
        scope.launch {
            runCatching { modbusSendAll(cFd, buildModbusAdu(FC_PUSH, data)) }
        }
    }

    actual fun receiveFromClient(): Flow<ByteArray> = _fromClient.asSharedFlow()

    actual suspend fun stop() {
        scope.cancel(); scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val c = clientFd; clientFd = -1; if (c >= 0) close(c)
        val s = serverFd; serverFd = -1; if (s >= 0) close(s)
        val u = udpSendFd; udpSendFd = -1; if (u >= 0) close(u)
        println("[ModbusServer] Stopped")
    }
}
