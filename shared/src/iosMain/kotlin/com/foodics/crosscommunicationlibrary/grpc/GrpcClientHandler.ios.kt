@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.foodics.crosscommunicationlibrary.grpc

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

actual class GrpcClientHandler actual constructor() {

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

    @Volatile private var fd = -1

    // ── Scan ──────────────────────────────────────────────────────────────────

    fun scan(): Flow<List<IoTDevice>> =
        discoverServices(GRPC_SERVICE_TYPE)
            .scan(emptyMap<String, IoTDevice>()) { acc, event ->
                val devices = acc.toMutableMap()
                when (event) {
                    is DiscoveryEvent.Discovered -> event.resolve()
                    is DiscoveryEvent.Resolved   -> {
                        val s  = event.service
                        val id = s.txt["id"]?.decodeToString()?.trim()?.ifBlank { null } ?: s.name
                        devices[s.key] = IoTDevice(
                            id = id,
                            name = s.name,
                            address = "${s.host}:${s.port}",
                            connectionType = ConnectionType.GRPC
                        )
                    }
                    is DiscoveryEvent.Removed    -> devices.remove(event.service.key)
                }
                devices
            }
            .map { it.values.toList() }
            .distinctUntilChanged()

    // ── Connect ───────────────────────────────────────────────────────────────

    fun connect(device: IoTDevice) {
        disconnect()
        val parts  = device.address.split(":")
        val ip     = parts[0]
        val port   = parts.getOrNull(1)?.toIntOrNull() ?: return

        val connFd = grpcConnect(ip, port)
        if (connFd < 0) { println("[GrpcClient] Connect failed: errno=$errno"); return }
        fd = connFd
        println("[GrpcClient] Connected to ${device.name} @ ${device.address}")
        scope.launch { receiveLoop(connFd) }
    }

    // ── Send ──────────────────────────────────────────────────────────────────

    fun sendToServer(data: ByteArray, writeType: WriteType) {
        val connFd = fd
        if (connFd < 0) { println("[GrpcClient] Not connected"); return }
        grpcWriteFrame(connFd, data)
    }

    fun receiveFromServer(): Flow<ByteArray> = _incoming.asSharedFlow()

    // ── Disconnect ────────────────────────────────────────────────────────────

    fun disconnect() {
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val connFd = fd; fd = -1
        if (connFd >= 0) close(connFd)
        println("[GrpcClient] Disconnected")
    }

    // ── Receive loop ──────────────────────────────────────────────────────────

    private suspend fun receiveLoop(connFd: Int) {
        while (scope.isActive && fd == connFd) {
            val frame = grpcReadFrame(connFd) ?: break
            _incoming.emit(frame)
        }
    }
}
