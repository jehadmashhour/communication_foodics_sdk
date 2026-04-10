package com.foodics.crosscommunicationlibrary.nats

import android.util.Log
import client.WriteType
import ConnectionType
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import scanner.IoTDevice
import java.io.BufferedInputStream
import java.net.Socket
import kotlin.time.TimeSource

actual class NATSClientHandler actual constructor(private val brokerUrl: String) {

    companion object { private const val TAG = "NATSClient" }

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

    private var socket: Socket? = null
    private var writer: NatsWriter? = null
    private var serverId: String? = null

    // ── Scan ──────────────────────────────────────────────────────────────────

    fun scan(): Flow<List<IoTDevice>> = channelFlow {
        var scanSock: Socket? = null
        try {
            val (host, port) = parseNatsUrl(brokerUrl)
            val sock = withContext(Dispatchers.IO) { Socket(host, port) }
            scanSock = sock
            val input = sock.getInputStream().buffered(65_536) as BufferedInputStream
            val w = NatsWriter(sock.getOutputStream())

            input.readNatsLine() // INFO
            w.sendCmd(NATS_CONNECT_CMD)
            w.sendCmd(natsSub(NATS_DISCOVERY_SUBJECT, NATS_SID_SCAN))

            val devices = mutableMapOf<String, Pair<IoTDevice, TimeSource.Monotonic.ValueTimeMark>>()

            val evictJob = launch {
                while (isActive) {
                    delay(2_000)
                    val before = devices.size
                    devices.entries.removeIf {
                        it.value.second.elapsedNow().inWholeMilliseconds > NATS_DEVICE_TTL_MS
                    }
                    if (devices.size != before) trySend(devices.values.map { it.first })
                }
            }

            sock.soTimeout = 2_000
            while (isActive) {
                val line = try {
                    withContext(Dispatchers.IO) { input.readNatsLine() } ?: break
                } catch (_: java.net.SocketTimeoutException) { continue }
                  catch (_: Exception) { break }

                when {
                    line.startsWith("MSG") -> {
                        val (_, bytes) = parseNatsMsgLine(line) ?: continue
                        val data = withContext(Dispatchers.IO) { input.readExactNats(bytes) } ?: break
                        withContext(Dispatchers.IO) { input.readExactNats(2) } // \r\n
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
                    line == "PING" -> w.sendCmd("PONG\r\n")
                }
            }

            awaitClose {
                evictJob.cancel()
                runCatching { scanSock?.close() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Scan error", e)
            awaitClose { runCatching { scanSock?.close() } }
        }
    }.distinctUntilChanged()

    // ── Connect ───────────────────────────────────────────────────────────────

    suspend fun connect(device: IoTDevice): Unit = withContext(Dispatchers.IO) {
        disconnect()
        try {
            val parts    = device.address.split("?id=")
            val hostPort = parts[0].split(":")
            val host     = hostPort[0]
            val port     = hostPort.getOrNull(1)?.toIntOrNull() ?: 4222
            val id       = parts.getOrNull(1) ?: return@withContext
            serverId = id

            val sock = Socket(host, port); socket = sock
            val input = sock.getInputStream().buffered(65_536) as BufferedInputStream
            val w = NatsWriter(sock.getOutputStream()); writer = w

            input.readNatsLine() // INFO
            w.sendCmd(NATS_CONNECT_CMD)
            w.sendCmd(natsSub(natsSubjectOut(id), NATS_SID_DATA))

            scope.launch(Dispatchers.IO) { receiveLoop(input, w) }
            Log.i(TAG, "NATS client connected to $id @ ${device.address}")
        } catch (e: Exception) {
            Log.e(TAG, "Connect error", e)
        }
    }

    // ── Send ──────────────────────────────────────────────────────────────────

    suspend fun sendToServer(data: ByteArray, writeType: WriteType): Unit = withContext(Dispatchers.IO) {
        val id = serverId ?: run { Log.w(TAG, "Not connected"); return@withContext }
        runCatching { writer?.publish(natsSubjectIn(id), data) }
            .onFailure { Log.e(TAG, "Send error", it) }
    }

    fun receiveFromServer(): Flow<ByteArray> = _incoming.asSharedFlow()

    // ── Disconnect ────────────────────────────────────────────────────────────

    fun disconnect() {
        scope.cancel(); scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        runCatching { socket?.close() }
        socket = null; writer = null; serverId = null
        Log.i(TAG, "NATS client disconnected")
    }

    // ── Receive loop ──────────────────────────────────────────────────────────

    private suspend fun receiveLoop(input: BufferedInputStream, w: NatsWriter) {
        try {
            while (scope.isActive) {
                val line = input.readNatsLine() ?: break
                when {
                    line.startsWith("MSG") -> {
                        val (_, bytes) = parseNatsMsgLine(line) ?: continue
                        val data = input.readExactNats(bytes) ?: break
                        input.readExactNats(2) // \r\n
                        _incoming.emit(data)
                    }
                    line == "PING" -> w.sendCmd("PONG\r\n")
                    line.startsWith("-ERR") -> break
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Receive loop ended: ${e.message}")
        }
    }
}
