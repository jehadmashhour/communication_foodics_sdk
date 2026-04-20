@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.foodics.crosscommunicationlibrary.zmq

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import platform.posix.*
import kotlin.concurrent.Volatile

actual class ZMQServerHandler actual constructor() {

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _fromClient = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

    @Volatile private var serverFd  = -1
    @Volatile private var clientFd  = -1
    @Volatile private var udpSendFd = -1

    actual suspend fun start(deviceName: String, identifier: String) {
        stop(); delay(500)
        val (sFd, zmqPort) = zmqTcpBind()
        if (sFd < 0) { println("[ZMQServer] Bind failed"); return }
        serverFd = sFd

        val uFd = zmqUdpSenderFd()
        if (uFd < 0) { println("[ZMQServer] UDP sender failed"); close(sFd); serverFd = -1; return }
        udpSendFd = uFd

        println("[ZMQServer] Started: $deviceName [$identifier] port=$zmqPort")

        val beacon = zmtpBeacon(deviceName, identifier, zmqPort)
        scope.launch {
            while (isActive) {
                runCatching { zmqUdpBroadcast(udpSendFd, beacon, ZMQ_DISCOVERY_PORT) }
                delay(ZMQ_BEACON_INTERVAL_MS)
            }
        }

        scope.launch { acceptLoop(sFd) }
    }

    private suspend fun acceptLoop(srvFd: Int) {
        while (scope.isActive && serverFd == srvFd) {
            val cFd = zmqAccept(srvFd)
            if (cFd < 0) continue
            if (clientFd >= 0) close(clientFd)
            clientFd = cFd
            scope.launch { handleClient(cFd) }
        }
    }

    private suspend fun handleClient(fd: Int) {
        try {
            if (!zmqHandshake(fd, asServer = true)) {
                println("[ZMQServer] Handshake failed"); close(fd)
                if (clientFd == fd) clientFd = -1; return
            }
            println("[ZMQServer] Client connected")
            zmqSetTimeout(fd, 2_000)
            while (scope.isActive && clientFd == fd) {
                val (isCmd, body) = zmqReadFrame(fd) ?: break
                if (!isCmd) _fromClient.emit(body)
            }
        } catch (e: Exception) {
            println("[ZMQServer] Client disconnected: ${e.message}")
        } finally {
            if (clientFd == fd) clientFd = -1
            close(fd)
        }
    }

    actual fun sendToClient(data: ByteArray) {
        val cFd = clientFd; if (cFd < 0) { println("[ZMQServer] No client"); return }
        scope.launch {
            runCatching { zmqSendAll(cFd, zmtpMessageFrame(data)) }
        }
    }

    actual fun receiveFromClient(): Flow<ByteArray> = _fromClient.asSharedFlow()

    actual suspend fun stop() {
        scope.cancel(); scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val c = clientFd; clientFd = -1; if (c >= 0) close(c)
        val s = serverFd; serverFd = -1; if (s >= 0) close(s)
        val u = udpSendFd; udpSendFd = -1; if (u >= 0) close(u)
        println("[ZMQServer] Stopped")
    }
}
