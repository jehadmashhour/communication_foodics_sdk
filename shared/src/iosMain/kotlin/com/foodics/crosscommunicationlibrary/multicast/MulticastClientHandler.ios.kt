@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.foodics.crosscommunicationlibrary.multicast

import ConnectionType
import client.WriteType
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import platform.posix.*
import scanner.IoTDevice
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource

actual class MulticastClientHandler {

    companion object {
        private const val DEVICE_TTL_MS = 10_000L
    }

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    @Volatile private var fd = -1

    // ── Scan ──────────────────────────────────────────────────────────────────

    fun scan(): Flow<List<IoTDevice>> = channelFlow {
        val scanFd = mcOpenSocket(recvTimeoutSec = 2L)
        if (scanFd < 0) { println("[MulticastClient] scan: socket failed"); return@channelFlow }

        val devices = mutableMapOf<String, Pair<IoTDevice, TimeSource.Monotonic.ValueTimeMark>>()

        val evictJob = launch {
            while (isActive) {
                delay(2_000)
                val before = devices.size
                val iter = devices.iterator()
                while (iter.hasNext()) {
                    if (iter.next().value.second.elapsedNow().inWholeMilliseconds > DEVICE_TTL_MS) iter.remove()
                }
                if (devices.size != before) trySend(devices.values.map { it.first })
            }
        }

        while (isActive) {
            val (data, senderIp) = mcRecvFrom(scanFd) ?: continue
            val (type, content) = parsePacket(data) ?: continue
            if (type != PKT_BEACON || content.isEmpty()) continue
            val parts = content.decodeToString().split("|", limit = 2)
            if (parts.size < 2) continue
            val id = parts[0]; val name = parts[1]
            val device = IoTDevice(
                id = id, name = name,
                address = senderIp,
                connectionType = ConnectionType.MULTICAST
            )
            devices[id] = device to TimeSource.Monotonic.markNow()
            trySend(devices.values.map { it.first })
        }

        awaitClose {
            evictJob.cancel()
            close(scanFd)
        }
    }.distinctUntilChanged()

    // ── Connect ───────────────────────────────────────────────────────────────

    fun connect(device: IoTDevice) {
        disconnect()
        val connFd = mcOpenSocket(recvTimeoutSec = 1L)
        if (connFd < 0) { println("[MulticastClient] connect: socket failed: errno=$errno"); return }
        fd = connFd
        println("[MulticastClient] Joined group for ${device.name}")
        scope.launch { receiveLoop(connFd) }
    }

    // ── Send ──────────────────────────────────────────────────────────────────

    fun sendToServer(data: ByteArray, writeType: WriteType) {
        val connFd = fd
        if (connFd < 0) { println("[MulticastClient] Not connected"); return }
        mcSendTo(connFd, buildData(data))
    }

    fun receiveFromServer(): Flow<ByteArray> = _incoming.asSharedFlow()

    // ── Disconnect ────────────────────────────────────────────────────────────

    fun disconnect() {
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val connFd = fd; fd = -1
        if (connFd >= 0) close(connFd)
        println("[MulticastClient] Disconnected")
    }

    // ── Receive loop ──────────────────────────────────────────────────────────

    private suspend fun receiveLoop(connFd: Int) {
        while (scope.isActive && fd == connFd) {
            val (data, _) = mcRecvFrom(connFd) ?: continue
            val (type, content) = parsePacket(data) ?: continue
            if (type == PKT_DATA && content.isNotEmpty()) _incoming.emit(content)
        }
    }
}
