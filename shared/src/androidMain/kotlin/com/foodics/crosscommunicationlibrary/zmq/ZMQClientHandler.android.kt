package com.foodics.crosscommunicationlibrary.zmq

import client.WriteType
import ConnectionType
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import scanner.IoTDevice
import java.io.BufferedInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import kotlin.time.TimeSource

actual class ZMQClientHandler actual constructor() {

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    private var socket: Socket? = null

    // ── Scan ──────────────────────────────────────────────────────────────────

    actual fun scan(): Flow<List<IoTDevice>> = channelFlow {
        val udp = withContext(Dispatchers.IO) {
            DatagramSocket(ZMQ_DISCOVERY_PORT).apply { soTimeout = 2_000; broadcast = true }
        }
        try {
            val devices = mutableMapOf<String, Pair<IoTDevice, TimeSource.Monotonic.ValueTimeMark>>()
            val evictJob = launch {
                while (isActive) {
                    delay(2_000)
                    val before = devices.size
                    devices.entries.removeIf {
                        it.value.second.elapsedNow().inWholeMilliseconds > ZMQ_DEVICE_TTL_MS
                    }
                    if (devices.size != before) trySend(devices.values.map { it.first })
                }
            }

            val buf = ByteArray(512)
            while (isActive) {
                val pkt = DatagramPacket(buf, buf.size)
                try {
                    withContext(Dispatchers.IO) { udp.receive(pkt) }
                } catch (_: java.net.SocketTimeoutException) { continue }
                  catch (_: Exception) { break }

                val (name, id, port) = zmtpParseBeacon(
                    pkt.data.copyOf(pkt.length)
                ) ?: continue

                val device = IoTDevice(
                    id = id, name = name,
                    address = "${pkt.address.hostAddress}:$port",
                    connectionType = ConnectionType.ZMQ
                )
                devices[id] = device to TimeSource.Monotonic.markNow()
                trySend(devices.values.map { it.first })
            }

            awaitClose { evictJob.cancel(); runCatching { udp.close() } }
        } catch (e: Exception) {
            awaitClose { runCatching { udp.close() } }
        }
    }.distinctUntilChanged()

    // ── Connect ───────────────────────────────────────────────────────────────

    actual fun connect(device: IoTDevice) {
        disconnect()
        val parts = device.address.split(":")
        val host  = parts[0]
        val port  = parts.getOrNull(1)?.toIntOrNull() ?: return

        scope.launch(Dispatchers.IO) {
            try {
                val sock = Socket(host, port); socket = sock
                val input  = sock.getInputStream().buffered(65_536) as BufferedInputStream
                val output = sock.getOutputStream()
                if (!zmtpHandshake(input, output, asServer = false)) {
                    println("[ZMQClient] Handshake failed"); sock.close(); return@launch
                }
                println("[ZMQClient] Connected to ${device.name} @ ${device.address}")
                while (scope.isActive) {
                    val (isCmd, body) = input.readZmtpFrame() ?: break
                    if (!isCmd) _incoming.emit(body)
                }
            } catch (e: Exception) {
                println("[ZMQClient] Receive loop ended: ${e.message}")
            }
        }
    }

    // ── Send ──────────────────────────────────────────────────────────────────

    actual fun sendToServer(data: ByteArray, writeType: WriteType) {
        val sock = socket ?: run { println("[ZMQClient] Not connected"); return }
        scope.launch(Dispatchers.IO) {
            runCatching { sock.getOutputStream().zmtpSend(zmtpMessageFrame(data)) }
                .onFailure { println("[ZMQClient] Send error: ${it.message}") }
        }
    }

    actual fun receiveFromServer(): Flow<ByteArray> = _incoming.asSharedFlow()

    // ── Disconnect ────────────────────────────────────────────────────────────

    actual fun disconnect() {
        scope.cancel(); scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        runCatching { socket?.close() }; socket = null
        println("[ZMQClient] Disconnected")
    }
}
