@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.foodics.crosscommunicationlibrary.modbus_tcp

import ConnectionType
import client.WriteType
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import platform.posix.*
import scanner.IoTDevice
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource

actual class ModbusClientHandler actual constructor() {

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

    @Volatile private var fd = -1

    // ── Scan ──────────────────────────────────────────────────────────────────

    actual fun scan(): Flow<List<IoTDevice>> = channelFlow {
        val recvFd = modbusUdpReceiverFd(MODBUS_DISCOVERY_PORT)
        if (recvFd < 0) { println("[ModbusClient] UDP receiver failed"); return@channelFlow }

        try {
            val devices = mutableMapOf<String, Pair<IoTDevice, TimeSource.Monotonic.ValueTimeMark>>()
            val evictJob = launch {
                while (isActive) {
                    delay(2_000)
                    val before = devices.size
                    val iter = devices.iterator()
                    while (iter.hasNext()) {
                        if (iter.next().value.second.elapsedNow().inWholeMilliseconds > MODBUS_DEVICE_TTL_MS)
                            iter.remove()
                    }
                    if (devices.size != before) trySend(devices.values.map { it.first })
                }
            }

            while (isActive) {
                val (ip, data) = modbusUdpReceive(recvFd) ?: continue
                val (name, id, port) = modbusParseBeacon(data) ?: continue
                val device = IoTDevice(
                    id = id, name = name,
                    address = "$ip:$port",
                    connectionType = ConnectionType.MODBUS_TCP
                )
                devices[id] = device to TimeSource.Monotonic.markNow()
                trySend(devices.values.map { it.first })
            }

            awaitClose { evictJob.cancel(); close(recvFd) }
        } catch (e: Exception) {
            println("[ModbusClient] Scan error: ${e.message}")
            awaitClose { close(recvFd) }
        }
    }.distinctUntilChanged()

    // ── Connect ───────────────────────────────────────────────────────────────

    actual fun connect(device: IoTDevice) {
        disconnect()
        val parts  = device.address.split(":")
        val host   = parts[0]
        val port   = parts.getOrNull(1)?.toIntOrNull() ?: return

        val connFd = modbusTcpConnect(host, port)
        if (connFd < 0) { println("[ModbusClient] Connect failed"); return }
        fd = connFd
        println("[ModbusClient] Connected to ${device.name} @ ${device.address}")
        scope.launch { receiveLoop(connFd) }
    }

    private suspend fun receiveLoop(connFd: Int) {
        modbusSetTimeout(connFd, 2_000)
        try {
            while (scope.isActive && fd == connFd) {
                val frame = modbusReadFrame(connFd) ?: continue
                if (frame.fc == FC_PUSH) _incoming.emit(frame.data)
            }
        } catch (e: Exception) {
            println("[ModbusClient] Receive loop ended: ${e.message}")
        }
    }

    // ── Send ──────────────────────────────────────────────────────────────────

    actual fun sendToServer(data: ByteArray, writeType: WriteType) {
        val connFd = fd; if (connFd < 0) { println("[ModbusClient] Not connected"); return }
        scope.launch {
            runCatching { modbusSendAll(connFd, buildModbusAdu(FC_UPLOAD, data)) }
        }
    }

    actual fun receiveFromServer(): Flow<ByteArray> = _incoming.asSharedFlow()

    // ── Disconnect ────────────────────────────────────────────────────────────

    actual fun disconnect() {
        scope.cancel(); scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val connFd = fd; fd = -1; if (connFd >= 0) close(connFd)
        println("[ModbusClient] Disconnected")
    }
}
