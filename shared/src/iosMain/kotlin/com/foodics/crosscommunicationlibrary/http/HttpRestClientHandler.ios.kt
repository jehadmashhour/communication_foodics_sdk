@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.foodics.crosscommunicationlibrary.http

import ConnectionType
import client.WriteType
import com.appstractive.dnssd.DiscoveryEvent
import com.appstractive.dnssd.discoverServices
import com.appstractive.dnssd.key
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import platform.posix.*
import scanner.IoTDevice
import kotlin.concurrent.Volatile

actual class HttpRestClientHandler {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    @Volatile private var serverIp: String? = null
    @Volatile private var serverPort: Int = 0

    // ── Scan ──────────────────────────────────────────────────────────────────

    fun scan(): Flow<List<IoTDevice>> =
        discoverServices(HTTP_SERVICE_TYPE)
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
                            connectionType = ConnectionType.HTTP_REST
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
        val parts = device.address.split(":")
        serverIp = parts[0]
        serverPort = parts.getOrNull(1)?.toIntOrNull() ?: 80
        println("[HttpRestClient] Connected to ${device.name} @ ${device.address}")
    }

    // ── Send / Receive / Disconnect ───────────────────────────────────────────

    suspend fun sendToServer(data: ByteArray, writeType: WriteType) {
        val ip = serverIp ?: run { println("[HttpRestClient] Not connected"); return }
        val responseBody = httpSendPost(ip, serverPort, data)
            ?: run { println("[HttpRestClient] POST failed"); return }
        if (responseBody.isNotEmpty()) _incoming.emit(responseBody)
    }

    fun receiveFromServer(): Flow<ByteArray> = _incoming.asSharedFlow()

    fun disconnect() {
        serverIp = null
        serverPort = 0
        println("[HttpRestClient] Disconnected")
    }
}
