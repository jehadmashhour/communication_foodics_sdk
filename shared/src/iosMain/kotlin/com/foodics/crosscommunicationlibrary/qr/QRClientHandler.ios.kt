@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.foodics.crosscommunicationlibrary.qr

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
 * iOS actual for QRClientHandler.
 *
 * Implements [QRScannerDelegate] so Swift's QRClientBridge can push scanned
 * content back into Kotlin via a typed ObjC protocol.
 */
actual class QRClientHandler : QRScannerDelegate {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _devices = MutableStateFlow<List<IoTDevice>>(emptyList())
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

    @Volatile private var clientFd = -1

    init {
        QRBridgeProvider.scannerBridge?.delegate = this
    }

    // ── QRScannerDelegate (called by Swift) ──────────────────────────────────

    override fun onQRCodeScanned(content: String) {
        val info = parseQRJsonIos(content) ?: run {
            println("[QRClient] Unrecognised QR: $content")
            return
        }
        val device = IoTDevice(
            id = info.id,
            name = info.name,
            connectionType = ConnectionType.QR,
            address = "${info.ip}:${info.port}"
        )
        val current = _devices.value
        if (current.none { it.id == info.id }) {
            _devices.value = current + device
            println("[QRClient] Device found: ${info.name} @ ${info.ip}:${info.port}")
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun scan(): Flow<List<IoTDevice>> = channelFlow {
        QRBridgeProvider.scannerBridge?.delegate = this@QRClientHandler
        QRBridgeProvider.scannerBridge?.startScanning()

        launch { _devices.collect { trySend(it) } }

        awaitClose {
            QRBridgeProvider.scannerBridge?.stopScanning()
        }
    }

    suspend fun connect(device: IoTDevice) {
        val parts = device.address.split(":")
        val ip = parts[0]
        val port = parts[1].toInt()

        val fd = socket(AF_INET, SOCK_STREAM, 0)
        check(fd >= 0) { "[QRClient] socket() failed errno=$errno" }

        memScoped {
            val addr = alloc<sockaddr_in>()
            addr.sin_family = AF_INET.convert()
            addr.sin_port = qrHtons(port.toUShort())
            addr.sin_addr.s_addr = qrInetAddr(ip)
            check(
                platform.posix.connect(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) == 0
            ) { "[QRClient] connect() failed errno=$errno" }
        }

        clientFd = fd
        startReceiveLoop()
        println("[QRClient] Connected to $ip:$port via QR")
    }

    suspend fun sendToServer(data: ByteArray, writeType: WriteType) {
        val fd = clientFd
        if (fd < 0) { println("[QRClient] Not connected"); return }
        tcpSendFramed(fd, data)
    }

    fun receiveFromServer(): Flow<ByteArray> = _incoming.asSharedFlow()

    suspend fun disconnect() {
        QRBridgeProvider.scannerBridge?.stopScanning()
        val fd = clientFd; clientFd = -1; if (fd >= 0) close(fd)
        _devices.value = emptyList()
        println("[QRClient] Disconnected")
    }

    private fun startReceiveLoop() {
        scope.launch {
            val fd = clientFd
            while (isActive && fd >= 0) {
                val data = tcpRecvFramed(fd) ?: break
                _incoming.tryEmit(data)
            }
        }
    }
}
