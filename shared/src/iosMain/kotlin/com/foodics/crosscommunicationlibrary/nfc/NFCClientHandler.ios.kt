@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.foodics.crosscommunicationlibrary.nfc

import ConnectionType
import client.WriteType
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import platform.posix.*
import scanner.IoTDevice
import kotlin.concurrent.Volatile

/**
 * iOS NFC bootstrapping client.
 *
 * Implements [NFCScannerDelegate] so Swift's NFCScannerBridge can deliver
 * scanned NFC payloads via the typed protocol.
 *
 * Lifecycle:
 *  scan()      → starts the CoreNFC sheet (via bridge), emits discovered devices.
 *  connect()   → TCP-connects to the server address decoded from the NFC tap.
 *  disconnect()→ closes TCP socket and clears the device list.
 */
actual class NFCClientHandler : NFCScannerDelegate {

    private val scope     = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _devices  = MutableStateFlow<List<IoTDevice>>(emptyList())
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

    @Volatile private var clientFd = -1

    init { NFCBridgeProvider.scannerBridge?.delegate = this }
    private val bridge get() = NFCBridgeProvider.scannerBridge

    // ── NFCScannerDelegate (called by Swift) ──────────────────────────────────

    override fun onNfcPayload(json: String) {
        val info = parseNfcJsonIos(json) ?: run {
            println("[NFCClient] Unrecognised NFC payload: $json"); return
        }
        val device = IoTDevice(
            id             = info.id,
            name           = info.name,
            connectionType = ConnectionType.NFC,
            address        = "${info.ip}:${info.port}"
        )
        val current = _devices.value
        if (current.none { it.id == info.id }) {
            _devices.value = current + device
            println("[NFCClient] NFC device found: ${info.name} @ ${info.ip}:${info.port}")
        }
    }

    override fun onNfcScanCancelled() {
        println("[NFCClient] NFC scan cancelled")
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun scan(): Flow<List<IoTDevice>> = channelFlow {
        bridge?.delegate = this@NFCClientHandler
        bridge?.startScanning("Tap an NFC-enabled device to connect")

        val job = launch { _devices.collect { trySend(it) } }

        awaitClose {
            bridge?.stopScanning()
            job.cancel()
        }
    }

    fun connect(device: IoTDevice) {
        val parts = device.address.split(":")
        val ip    = parts.getOrNull(0) ?: return
        val port  = parts.getOrNull(1)?.toIntOrNull() ?: return

        val fd = socket(AF_INET, SOCK_STREAM, 0)
        if (fd < 0) { println("[NFCClient] socket() failed: errno=$errno"); return }

        val connected = memScoped {
            val addr = alloc<sockaddr_in>()
            addr.sin_family      = AF_INET.convert()
            addr.sin_port        = nfcHtons(port.toUShort())
            addr.sin_addr.s_addr = nfcInetAddr(ip)
            platform.posix.connect(fd, addr.ptr.reinterpret(),
                sizeOf<sockaddr_in>().convert()) == 0
        }

        if (!connected) {
            println("[NFCClient] TCP connect failed: errno=$errno")
            close(fd)
            return
        }

        clientFd = fd
        println("[NFCClient] TCP connected to $ip:$port")

        scope.launch {
            while (isActive && clientFd >= 0) {
                val data = nfcTcpRecvFramed(fd) ?: break
                _incoming.tryEmit(data)
            }
            println("[NFCClient] TCP receive loop ended")
        }
    }

    fun sendToServer(data: ByteArray, writeType: WriteType) {
        val fd = clientFd
        if (fd < 0) { println("[NFCClient] Not connected"); return }
        nfcTcpSendFramed(fd, data)
    }

    fun receiveFromServer(): Flow<ByteArray> = _incoming.asSharedFlow()

    fun disconnect() {
        bridge?.stopScanning()
        val fd = clientFd; clientFd = -1
        if (fd >= 0) close(fd)
        _devices.value = emptyList()
        println("[NFCClient] Disconnected")
    }
}
