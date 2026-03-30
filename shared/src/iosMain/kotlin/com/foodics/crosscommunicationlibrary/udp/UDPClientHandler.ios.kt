@file:OptIn(ExperimentalForeignApi::class)

package com.foodics.crosscommunicationlibrary.udp

import ConnectionType
import client.WriteType
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.posix.*
import scanner.IoTDevice
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource

actual class UDPClientHandler {

    private companion object {
        const val DISCOVERY_PORT: UShort = 33333u
        const val DEVICE_TIMEOUT_MS = 15_000L
        const val RECV_BUFFER_SIZE = 4096
        const val SCAN_RECV_TIMEOUT_SEC = 2L
        const val DATA_RECV_TIMEOUT_SEC = 1L
    }

    @Volatile private var dataFd = -1
    @Volatile private var serverAddr: String? = null
    @Volatile private var serverPort: Int? = null

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    private val receiveScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var receiveJob: Job? = null

    // ── Scan ──────────────────────────────────────────────────────────────────

    fun scan(): Flow<List<IoTDevice>> = channelFlow {
        val devices = mutableMapOf<String, Pair<IoTDevice, TimeSource.Monotonic.ValueTimeMark>>()
        val mutex = Mutex()

        val fd = openScanSocket()

        // Receives broadcast packets from servers
        launch {
            while (isActive) {
                memScoped {
                    val buf = allocArray<ByteVar>(RECV_BUFFER_SIZE)
                    val senderAddr = alloc<sockaddr_in>()
                    val addrLen = alloc<UIntVar>()
                    addrLen.value = sizeOf<sockaddr_in>().convert()

                    val received = recvfrom(
                        fd, buf, RECV_BUFFER_SIZE.convert(), 0,
                        senderAddr.ptr.reinterpret(), addrLen.ptr
                    ).toInt()

                    if (received > 0) {
                        val msg = ByteArray(received) { buf[it] }.decodeToString()
                        val parts = msg.split("|")
                        if (parts.size == 3) {
                            val id = parts[0]
                            val name = parts[1]
                            val port = parts[2].toIntOrNull() ?: return@memScoped
                            val host = udpInetNtop(senderAddr.sin_addr.s_addr)

                            val device = IoTDevice(
                                id = id,
                                name = name,
                                connectionType = ConnectionType.UDP,
                                address = "$host:$port"
                            )
                            mutex.withLock {
                                devices[id] = device to TimeSource.Monotonic.markNow()
                                trySend(devices.values.map { it.first }.sortedBy { it.id })
                            }
                        }
                    }
                    // else: timeout (EAGAIN) — just loop
                }
            }
        }

        // Evicts devices not seen within DEVICE_TIMEOUT_MS
        launch {
            while (isActive) {
                delay(1_000)
                mutex.withLock {
                    val iter = devices.entries.iterator()
                    var removed = false
                    while (iter.hasNext()) {
                        val entry = iter.next()
                        if (entry.value.second.elapsedNow().inWholeMilliseconds > DEVICE_TIMEOUT_MS) {
                            iter.remove()
                            removed = true
                        }
                    }
                    if (removed) trySend(devices.values.map { it.first }.sortedBy { it.id })
                }
            }
        }

        awaitClose { close(fd) }
    }

    // ── Connect / Send / Receive ──────────────────────────────────────────────

    fun connect(device: IoTDevice) {
        val (host, portStr) = device.address.split(":")
        serverAddr = host
        serverPort = portStr.toInt()

        val fd = socket(AF_INET, SOCK_DGRAM, 0)
        check(fd >= 0) { "[UDPClient] Failed to create data socket: errno=$errno" }
        memScoped {
            val tv = alloc<timeval>()
            tv.tv_sec = DATA_RECV_TIMEOUT_SEC
            tv.tv_usec = 0
            setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, tv.ptr, sizeOf<timeval>().convert())
        }
        dataFd = fd

        startReceiveLoop()
        println("[UDPClient] Connected to ${device.address}")
    }

    fun sendToServer(data: ByteArray, writeType: WriteType) {
        val addr = serverAddr ?: return
        val port = serverPort ?: return
        memScoped {
            val dest = alloc<sockaddr_in>()
            dest.sin_family = AF_INET.convert()
            dest.sin_port = udpHtons(port.toUShort())
            dest.sin_addr.s_addr = udpInetAddr(addr)

            data.usePinned { pinned ->
                sendto(
                    dataFd, pinned.addressOf(0), data.size.convert(),
                    0, dest.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()
                )
            }
        }
        println("[UDPClient] Sent ${data.size} bytes to $addr:$port")
    }

    fun receiveFromServer(): Flow<ByteArray> = _incoming.asSharedFlow()

    fun disconnect() {
        receiveJob?.cancel()
        val fd = dataFd
        dataFd = -1
        if (fd >= 0) close(fd)
        serverAddr = null
        serverPort = null
        println("[UDPClient] Disconnected")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun openScanSocket(): Int {
        val fd = socket(AF_INET, SOCK_DGRAM, 0)
        check(fd >= 0) { "[UDPClient] Failed to create scan socket: errno=$errno" }
        memScoped {
            val flag = alloc<IntVar>().apply { value = 1 }
            setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, flag.ptr, sizeOf<IntVar>().convert())
            setsockopt(fd, SOL_SOCKET, SO_REUSEPORT, flag.ptr, sizeOf<IntVar>().convert())

            val tv = alloc<timeval>()
            tv.tv_sec = SCAN_RECV_TIMEOUT_SEC
            tv.tv_usec = 0
            setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, tv.ptr, sizeOf<timeval>().convert())

            val addr = alloc<sockaddr_in>()
            addr.sin_family = AF_INET.convert()
            addr.sin_port = udpHtons(DISCOVERY_PORT)
            addr.sin_addr.s_addr = INADDR_ANY
            bind(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
        }
        return fd
    }

    private fun startReceiveLoop() {
        receiveJob?.cancel()
        receiveJob = receiveScope.launch {
            while (isActive) {
                val fd = dataFd
                if (fd < 0) break
                memScoped {
                    val buf = allocArray<ByteVar>(RECV_BUFFER_SIZE)
                    val ignored = alloc<sockaddr_in>()
                    val addrLen = alloc<UIntVar>()
                    addrLen.value = sizeOf<sockaddr_in>().convert()

                    val received = recvfrom(
                        fd, buf, RECV_BUFFER_SIZE.convert(), 0,
                        ignored.ptr.reinterpret(), addrLen.ptr
                    ).toInt()

                    when {
                        received > 0 -> {
                            val data = ByteArray(received) { buf[it] }
                            _incoming.tryEmit(data)
                        }
                        received < 0 && errno != EAGAIN && errno != EWOULDBLOCK ->
                            if (isActive) println("[UDPClient] recvfrom error: errno=$errno")
                    }
                }
            }
        }
    }
}
