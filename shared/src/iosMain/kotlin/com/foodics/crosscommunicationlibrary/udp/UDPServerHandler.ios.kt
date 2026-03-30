@file:OptIn(ExperimentalForeignApi::class)

package com.foodics.crosscommunicationlibrary.udp

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import platform.darwin.getifaddrs
import platform.darwin.freeifaddrs
import platform.darwin.ifaddrs
import platform.posix.*
import kotlin.concurrent.Volatile

actual class UDPServerHandler {

    private companion object {
        const val DISCOVERY_PORT: UShort = 33333u
        const val DATA_PORT: UShort = 8080u
        const val BROADCAST_INTERVAL_MS = 1500L
        const val RECV_BUFFER_SIZE = 4096
        const val RECV_TIMEOUT_SEC = 1L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var discoveryFd = -1
    @Volatile private var dataFd = -1
    private var broadcastJob: Job? = null
    private var receiveJob: Job? = null

    @Volatile private var lastClientAddr: String? = null
    @Volatile private var lastClientPort: Int? = null

    private val _fromClient = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

    // ── Public API ────────────────────────────────────────────────────────────

    fun start(deviceName: String, identifier: String) {
        stop()
        openDiscoverySocket()
        openDataSocket()
        println("[UDPServer] Started — data port $DATA_PORT")
        startBroadcast(deviceName, identifier)
        startReceiveLoop()
    }

    fun sendToClient(data: ByteArray) {
        val addr = lastClientAddr ?: run {
            println("[UDPServer] sendToClient: no client connected yet")
            return
        }
        val port = lastClientPort ?: run {
            println("[UDPServer] sendToClient: no client port available")
            return
        }
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
        println("[UDPServer] Sent ${data.size} bytes to $addr:$port")
    }

    fun receiveFromClient(): Flow<ByteArray> = _fromClient.asSharedFlow()

    fun stop() {
        broadcastJob?.cancel()
        receiveJob?.cancel()
        val dFd = discoveryFd; discoveryFd = -1; if (dFd >= 0) close(dFd)
        val datFd = dataFd; dataFd = -1; if (datFd >= 0) close(datFd)
        lastClientAddr = null
        lastClientPort = null
        println("[UDPServer] Stopped")
    }

    // ── Socket setup ──────────────────────────────────────────────────────────

    private fun openDiscoverySocket() {
        val fd = socket(AF_INET, SOCK_DGRAM, 0)
        check(fd >= 0) { "[UDPServer] Failed to create discovery socket: errno=$errno" }
        memScoped {
            val flag = alloc<IntVar>().apply { value = 1 }
            setsockopt(fd, SOL_SOCKET, SO_BROADCAST, flag.ptr, sizeOf<IntVar>().convert())
            setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, flag.ptr, sizeOf<IntVar>().convert())

            // Bind to INADDR_ANY with ephemeral port so iOS routes packets through
            // the active network interface (WiFi) rather than loopback.
            val addr = alloc<sockaddr_in>()
            addr.sin_family = AF_INET.convert()
            addr.sin_port = udpHtons(0u)
            addr.sin_addr.s_addr = INADDR_ANY
            bind(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
        }
        discoveryFd = fd
    }

    private fun openDataSocket() {
        val fd = socket(AF_INET, SOCK_DGRAM, 0)
        check(fd >= 0) { "[UDPServer] Failed to create data socket: errno=$errno" }
        memScoped {
            val flag = alloc<IntVar>().apply { value = 1 }
            setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, flag.ptr, sizeOf<IntVar>().convert())
            setsockopt(fd, SOL_SOCKET, SO_REUSEPORT, flag.ptr, sizeOf<IntVar>().convert())

            val tv = alloc<timeval>()
            tv.tv_sec = RECV_TIMEOUT_SEC
            tv.tv_usec = 0
            setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, tv.ptr, sizeOf<timeval>().convert())

            val addr = alloc<sockaddr_in>()
            addr.sin_family = AF_INET.convert()
            addr.sin_port = udpHtons(DATA_PORT)
            addr.sin_addr.s_addr = INADDR_ANY
            val ret = bind(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
            check(ret == 0) { "[UDPServer] Failed to bind data socket: errno=$errno" }
        }
        dataFd = fd
    }

    // ── Broadcast loop ────────────────────────────────────────────────────────

    private fun startBroadcast(deviceName: String, identifier: String) {
        val message = "$identifier|$deviceName|$DATA_PORT".encodeToByteArray()
        broadcastJob = scope.launch {
            while (isActive) {
                try {
                    // Use the subnet-directed broadcast (e.g. 192.168.1.255) instead of
                    // 255.255.255.255. On iOS, the limited broadcast is not guaranteed to
                    // be routed out through the WiFi interface, so Android devices on the
                    // same network would never receive the packet.
                    val broadcastAddr = getSubnetBroadcastAddress()
                    memScoped {
                        val dest = alloc<sockaddr_in>()
                        dest.sin_family = AF_INET.convert()
                        dest.sin_port = udpHtons(DISCOVERY_PORT)
                        dest.sin_addr.s_addr = udpInetAddr(broadcastAddr)

                        message.usePinned { pinned ->
                            sendto(
                                discoveryFd, pinned.addressOf(0), message.size.convert(),
                                0, dest.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()
                            )
                        }
                    }
                    println("[UDPServer] Broadcast sent to $broadcastAddr:$DISCOVERY_PORT")
                } catch (e: Exception) {
                    if (isActive) println("[UDPServer] Broadcast error: $e")
                }
                delay(BROADCAST_INTERVAL_MS)
            }
        }
    }

    /**
     * Returns the subnet-directed broadcast address for the WiFi interface (en0).
     * Falls back to 255.255.255.255 if en0 cannot be found.
     *
     * Example: if en0 has IP 192.168.1.42 and mask 255.255.255.0,
     * this returns "192.168.1.255".
     */
    private fun getSubnetBroadcastAddress(): String {
        memScoped {
            val ifaddrPtr = alloc<CPointerVar<ifaddrs>>()
            if (getifaddrs(ifaddrPtr.ptr) != 0) return "255.255.255.255"

            var broadcastAddr = "255.255.255.255"
            var ptr: CPointer<ifaddrs>? = ifaddrPtr.value

            while (ptr != null) {
                val ifa = ptr.pointed
                val sockAddr = ifa.ifa_addr
                if (sockAddr != null && sockAddr.pointed.sa_family.toInt() == AF_INET) {
                    val ifName = ifa.ifa_name?.toKString() ?: ""
                    if (ifName == "en0") {
                        val ip = sockAddr.reinterpret<sockaddr_in>().pointed.sin_addr.s_addr
                        val mask = ifa.ifa_netmask
                            ?.reinterpret<sockaddr_in>()?.pointed?.sin_addr?.s_addr
                            ?: 0xFFFFFFFFu
                        val broadcast = (ip and mask) or mask.inv()
                        broadcastAddr = udpInetNtop(broadcast)
                        break
                    }
                }
                ptr = ifa.ifa_next
            }

            freeifaddrs(ifaddrPtr.value)
            return broadcastAddr
        }
    }

    // ── Receive loop ──────────────────────────────────────────────────────────

    private fun startReceiveLoop() {
        receiveJob = scope.launch {
            while (isActive) {
                memScoped {
                    val buf = allocArray<ByteVar>(RECV_BUFFER_SIZE)
                    val clientAddr = alloc<sockaddr_in>()
                    val addrLen = alloc<UIntVar>()
                    addrLen.value = sizeOf<sockaddr_in>().convert()

                    val received = recvfrom(
                        dataFd, buf, RECV_BUFFER_SIZE.convert(), 0,
                        clientAddr.ptr.reinterpret(), addrLen.ptr
                    ).toInt()

                    when {
                        received > 0 -> {
                            lastClientAddr = udpInetNtop(clientAddr.sin_addr.s_addr)
                            lastClientPort = udpNtohs(clientAddr.sin_port).toInt()
                            val data = ByteArray(received) { buf[it] }
                            _fromClient.tryEmit(data)
                            println("[UDPServer] Received $received bytes from $lastClientAddr:$lastClientPort")
                        }
                        received < 0 && errno != EAGAIN && errno != EWOULDBLOCK ->
                            if (isActive) println("[UDPServer] recvfrom error: errno=$errno")
                    }
                }
            }
        }
    }
}

// ── Network byte-order helpers (htons/ntohs/inet_* are macros on Darwin) ─────

internal fun udpHtons(v: UShort): UShort =
    ((v.toInt() and 0xFF shl 8) or (v.toInt() ushr 8 and 0xFF)).toUShort()

internal fun udpNtohs(v: UShort): UShort = udpHtons(v)

/**
 * Converts a dotted-decimal IP string to a network-byte-order UInt (s_addr).
 * Bytes in memory: [octet0, octet1, octet2, octet3] — equivalent to inet_addr().
 */
internal fun udpInetAddr(ip: String): UInt {
    val parts = ip.split(".")
    if (parts.size != 4) return 0u
    val b0 = parts[0].toUInt() and 0xFFu
    val b1 = parts[1].toUInt() and 0xFFu
    val b2 = parts[2].toUInt() and 0xFFu
    val b3 = parts[3].toUInt() and 0xFFu
    // On little-endian (iOS ARM), store [b0,b1,b2,b3] in memory as little-endian UInt.
    return (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
}

/**
 * Converts a network-byte-order UInt s_addr to a dotted-decimal IP string.
 * Equivalent to inet_ntop().
 */
internal fun udpInetNtop(sAddr: UInt): String {
    val b0 = sAddr and 0xFFu
    val b1 = (sAddr shr 8) and 0xFFu
    val b2 = (sAddr shr 16) and 0xFFu
    val b3 = (sAddr shr 24) and 0xFFu
    return "$b0.$b1.$b2.$b3"
}
