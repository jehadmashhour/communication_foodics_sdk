@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.foodics.crosscommunicationlibrary.stomp

import ConnectionType
import client.WriteType
import com.appstractive.dnssd.DiscoveryEvent
import com.appstractive.dnssd.discoverServices
import com.appstractive.dnssd.key
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import platform.posix.*
import scanner.IoTDevice
import kotlin.concurrent.Volatile

actual class StompClientHandler {

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

    @Volatile private var fd = -1

    // ── Scan ──────────────────────────────────────────────────────────────────

    fun scan(): Flow<List<IoTDevice>> =
        discoverServices(STOMP_SERVICE_TYPE)
            .scan(emptyMap<String, IoTDevice>()) { acc, event ->
                val devices = acc.toMutableMap()
                when (event) {
                    is DiscoveryEvent.Discovered -> event.resolve()
                    is DiscoveryEvent.Resolved -> {
                        val s = event.service
                        val id = s.txt["id"]?.decodeToString()?.trim()?.ifBlank { null } ?: s.name
                        devices[s.key] = IoTDevice(
                            id = id,
                            name = s.name,
                            address = "${s.host}:${s.port}",
                            connectionType = ConnectionType.STOMP
                        )
                    }
                    is DiscoveryEvent.Removed -> devices.remove(event.service.key)
                }
                devices
            }
            .map { it.values.toList() }
            .distinctUntilChanged()

    // ── Connect ───────────────────────────────────────────────────────────────

    fun connect(device: IoTDevice) {
        disconnect()
        val parts = device.address.split(":")
        val ip = parts[0]
        val port = parts.getOrNull(1)?.toIntOrNull() ?: 61613

        val connFd = stompConnectTcp(ip, port)
        if (connFd < 0) { println("[StompClient] TCP connect failed: errno=$errno"); return }
        fd = connFd

        // ── STOMP handshake ───────────────────────────────────────────────────
        stompSendFrame(connFd, buildStompFrame("CONNECT", mapOf(
            "accept-version" to "1.2",
            "host" to ip,
            "heart-beat" to "0,0"
        )))
        val connectedFrame = stompReadFrame(connFd)
        if (connectedFrame?.command != "CONNECTED") {
            println("[StompClient] Handshake failed: got ${connectedFrame?.command}")
            close(connFd); fd = -1; return
        }

        // Subscribe to receive MESSAGE frames from server
        stompSendFrame(connFd, buildStompFrame("SUBSCRIBE", mapOf(
            "destination" to STOMP_DESTINATION,
            "id" to "sub-0",
            "ack" to "auto"
        )))
        println("[StompClient] Connected to ${device.name} @ ${device.address}")

        scope.launch { receiveLoop(connFd) }
    }

    // ── Send ──────────────────────────────────────────────────────────────────

    fun sendToServer(data: ByteArray, writeType: WriteType) {
        val connFd = fd
        if (connFd < 0) { println("[StompClient] Not connected"); return }
        val frame = buildStompFrame("SEND", mapOf("destination" to STOMP_DESTINATION), data)
        stompSendFrame(connFd, frame)
    }

    // ── Receive ───────────────────────────────────────────────────────────────

    fun receiveFromServer(): Flow<ByteArray> = _incoming.asSharedFlow()

    // ── Disconnect ────────────────────────────────────────────────────────────

    fun disconnect() {
        val connFd = fd
        if (connFd >= 0) {
            runCatching {
                stompSendFrame(connFd, buildStompFrame("DISCONNECT", mapOf("receipt" to "r-bye")))
            }
        }
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        fd = -1
        if (connFd >= 0) close(connFd)
        println("[StompClient] Disconnected")
    }

    // ── Receive loop ──────────────────────────────────────────────────────────

    private suspend fun receiveLoop(connFd: Int) {
        while (scope.isActive && fd == connFd) {
            val frame = stompReadFrame(connFd) ?: break
            when (frame.command) {
                "MESSAGE" -> if (frame.body.isNotEmpty()) _incoming.emit(frame.body)
                "ERROR" -> { println("[StompClient] STOMP ERROR: ${frame.headers["message"]}"); break }
            }
        }
    }
}
