@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.foodics.crosscommunicationlibrary.nats

import ConnectionType
import client.WriteType
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import platform.posix.*
import scanner.IoTDevice
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource

actual class NATSClientHandler actual constructor(private val brokerUrl: String) {

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

    @Volatile private var fd = -1
    @Volatile private var serverId: String? = null

    // ── Scan ──────────────────────────────────────────────────────────────────

    fun scan(): Flow<List<IoTDevice>> = channelFlow {
        val (host, port) = parseNatsUrl(brokerUrl)
        val scanFd = natsTcpConnect(host, port)
        if (scanFd < 0) { println("[NATSClient] Scan connect failed"); return@channelFlow }

        try {
            natsReadLine(scanFd)                             // INFO
            natsSendCmd(scanFd, NATS_CONNECT_CMD)
            natsSendCmd(scanFd, natsSub(NATS_DISCOVERY_SUBJECT, NATS_SID_SCAN))
            natsSetTimeout(scanFd, 2_000)

            val devices = mutableMapOf<String, Pair<IoTDevice, TimeSource.Monotonic.ValueTimeMark>>()

            val evictJob = launch {
                while (isActive) {
                    delay(2_000)
                    val before = devices.size
                    val iter = devices.iterator()
                    while (iter.hasNext()) {
                        if (iter.next().value.second.elapsedNow().inWholeMilliseconds > NATS_DEVICE_TTL_MS)
                            iter.remove()
                    }
                    if (devices.size != before) trySend(devices.values.map { it.first })
                }
            }

            while (isActive) {
                val line = natsReadLine(scanFd) ?: continue // null = timeout, retry
                when {
                    line.startsWith("MSG") -> {
                        val (_, bytes) = parseNatsMsgLine(line) ?: continue
                        val data = natsRecvExact(scanFd, bytes) ?: break
                        natsRecvExact(scanFd, 2) // \r\n
                        val json = data.decodeToString()
                        val (id, name) = parseNatsBeacon(json) ?: continue
                        val device = IoTDevice(
                            id = id, name = name,
                            address = "$host:$port?id=$id",
                            connectionType = ConnectionType.NATS
                        )
                        devices[id] = device to TimeSource.Monotonic.markNow()
                        trySend(devices.values.map { it.first })
                    }
                    line == "PING" -> natsSendCmd(scanFd, "PONG\r\n")
                }
            }

            awaitClose {
                evictJob.cancel()
                close(scanFd)
            }
        } catch (e: Exception) {
            println("[NATSClient] Scan error: ${e.message}")
            awaitClose { close(scanFd) }
        }
    }.distinctUntilChanged()

    // ── Connect ───────────────────────────────────────────────────────────────

    fun connect(device: IoTDevice) {
        disconnect()
        val parts    = device.address.split("?id=")
        val hostPort = parts[0].split(":")
        val host     = hostPort[0]
        val port     = hostPort.getOrNull(1)?.toIntOrNull() ?: 4222
        val id       = parts.getOrNull(1) ?: return

        val connFd = natsTcpConnect(host, port)
        if (connFd < 0) { println("[NATSClient] Connect failed"); return }
        fd = connFd; serverId = id

        natsReadLine(fd) // INFO
        natsSendCmd(fd, NATS_CONNECT_CMD)
        natsSendCmd(fd, natsSub(natsSubjectOut(id), NATS_SID_DATA))

        println("[NATSClient] Connected to $id @ ${device.address}")
        scope.launch { receiveLoop(connFd) }
    }

    // ── Send ──────────────────────────────────────────────────────────────────

    fun sendToServer(data: ByteArray, writeType: WriteType) {
        val id = serverId ?: run { println("[NATSClient] Not connected"); return }
        if (fd < 0) return
        runCatching { natsPublish(fd, natsSubjectIn(id), data) }
    }

    fun receiveFromServer(): Flow<ByteArray> = _incoming.asSharedFlow()

    // ── Disconnect ────────────────────────────────────────────────────────────

    fun disconnect() {
        scope.cancel(); scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val connFd = fd; fd = -1
        if (connFd >= 0) close(connFd)
        serverId = null
        println("[NATSClient] Disconnected")
    }

    // ── Receive loop ──────────────────────────────────────────────────────────

    private suspend fun receiveLoop(connFd: Int) {
        natsSetTimeout(connFd, 2_000)
        try {
            while (scope.isActive && fd == connFd) {
                val line = natsReadLine(connFd) ?: continue
                when {
                    line.startsWith("MSG") -> {
                        val (_, bytes) = parseNatsMsgLine(line) ?: continue
                        val data = natsRecvExact(connFd, bytes) ?: break
                        natsRecvExact(connFd, 2) // \r\n
                        _incoming.emit(data)
                    }
                    line == "PING" -> natsSendCmd(connFd, "PONG\r\n")
                    line.startsWith("-ERR") -> break
                }
            }
        } catch (e: Exception) {
            println("[NATSClient] Receive loop ended: ${e.message}")
        }
    }
}
