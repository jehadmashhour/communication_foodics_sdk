@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.foodics.crosscommunicationlibrary.ssdp

import kotlinx.cinterop.*
import platform.posix.*

internal const val SSDP_MULTICAST_IP  = "239.255.255.250"
internal const val SSDP_PORT_US: UShort = 1900u
internal const val SSDP_SERVICE_TYPE  = "urn:foodics:service:crosscomm:1"

// ── SSDP message builders ─────────────────────────────────────────────────────

internal fun ssdpMSearchIos() = buildString {
    append("M-SEARCH * HTTP/1.1\r\n")
    append("HOST: $SSDP_MULTICAST_IP:1900\r\n")
    append("MAN: \"ssdp:discover\"\r\n")
    append("MX: 3\r\n")
    append("ST: $SSDP_SERVICE_TYPE\r\n")
    append("\r\n")
}

internal fun ssdpOkResponseIos(id: String, name: String, ip: String, tcpPort: Int) = buildString {
    append("HTTP/1.1 200 OK\r\n")
    append("CACHE-CONTROL: max-age=1800\r\n")
    append("LOCATION: tcp://$ip:$tcpPort\r\n")
    append("ST: $SSDP_SERVICE_TYPE\r\n")
    append("USN: uuid:$id\r\n")
    append("X-DEVICE-NAME: $name\r\n")
    append("X-DEVICE-ID: $id\r\n")
    append("\r\n")
}

internal fun ssdpNotifyAliveIos(id: String, name: String, ip: String, tcpPort: Int) = buildString {
    append("NOTIFY * HTTP/1.1\r\n")
    append("HOST: $SSDP_MULTICAST_IP:1900\r\n")
    append("CACHE-CONTROL: max-age=1800\r\n")
    append("LOCATION: tcp://$ip:$tcpPort\r\n")
    append("NT: $SSDP_SERVICE_TYPE\r\n")
    append("NTS: ssdp:alive\r\n")
    append("USN: uuid:$id\r\n")
    append("X-DEVICE-NAME: $name\r\n")
    append("X-DEVICE-ID: $id\r\n")
    append("\r\n")
}

// ── Response parser ───────────────────────────────────────────────────────────

internal data class SSDPDeviceInfoIos(val id: String, val name: String, val ip: String, val port: Int)

internal fun parseSSDPMessageIos(message: String): SSDPDeviceInfoIos? {
    if (!message.startsWith("HTTP/1.1 200") && !message.startsWith("NOTIFY")) return null
    val headers = mutableMapOf<String, String>()
    message.lines().drop(1).forEach { line ->
        val idx = line.indexOf(':')
        if (idx > 0) headers[line.substring(0, idx).trim().uppercase()] = line.substring(idx + 1).trim()
    }
    val location = headers["LOCATION"] ?: return null
    val id       = headers["X-DEVICE-ID"] ?: return null
    val name     = headers["X-DEVICE-NAME"] ?: id
    val match    = Regex("""tcp://(.+?):(\d+)""").find(location) ?: return null
    val port     = match.groupValues[2].toIntOrNull() ?: return null
    return SSDPDeviceInfoIos(id = id, name = name, ip = match.groupValues[1], port = port)
}

// ── Network byte-order helpers ────────────────────────────────────────────────

/** Host → network byte order for UShort. */
internal fun ssdpHtons(v: UShort): UShort =
    ((v.toInt() and 0xFF shl 8) or (v.toInt() ushr 8 and 0xFF)).toUShort()

/** Dotted-decimal → network-byte-order UInt. */
internal fun ssdpInetAddr(ip: String): UInt {
    val p = ip.split(".")
    if (p.size != 4) return 0u
    val b0 = p[0].toUInt() and 0xFFu
    val b1 = p[1].toUInt() and 0xFFu
    val b2 = p[2].toUInt() and 0xFFu
    val b3 = p[3].toUInt() and 0xFFu
    return (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
}

/** Network-byte-order UInt → dotted-decimal string. */
internal fun ssdpAddrToString(addr: UInt): String {
    val b0 = (addr and 0xFFu).toInt()
    val b1 = ((addr shr 8) and 0xFFu).toInt()
    val b2 = ((addr shr 16) and 0xFFu).toInt()
    val b3 = ((addr shr 24) and 0xFFu).toInt()
    return "$b0.$b1.$b2.$b3"
}

/**
 * Returns the device's primary non-loopback IPv4 address using the
 * connect-then-getsockname trick (no routing needed, no ifaddrs required).
 */
internal fun ssdpGetLocalIpIos(): String = memScoped {
    val sock = socket(AF_INET, SOCK_DGRAM, 0)
    if (sock < 0) return "0.0.0.0"
    val remote = alloc<sockaddr_in>()
    remote.sin_family = AF_INET.convert()
    remote.sin_port = ssdpHtons(80u)
    remote.sin_addr.s_addr = ssdpInetAddr("8.8.8.8")
    platform.posix.connect(sock, remote.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
    val local = alloc<sockaddr_in>()
    val len = alloc<socklen_tVar>()
    len.value = sizeOf<sockaddr_in>().convert()
    getsockname(sock, local.ptr.reinterpret(), len.ptr)
    close(sock)
    ssdpAddrToString(local.sin_addr.s_addr)
}

// ── TCP framing (4-byte big-endian length prefix) ─────────────────────────────

internal fun ssdpTcpSend(fd: Int, data: ByteArray) {
    val len = data.size
    val prefix = ByteArray(4).apply {
        this[0] = (len ushr 24 and 0xFF).toByte()
        this[1] = (len ushr 16 and 0xFF).toByte()
        this[2] = (len ushr 8  and 0xFF).toByte()
        this[3] = (len         and 0xFF).toByte()
    }
    prefix.usePinned { p -> send(fd, p.addressOf(0), 4.convert(), 0) }
    data.usePinned   { p -> send(fd, p.addressOf(0), len.convert(), 0) }
}

internal fun ssdpTcpRecv(fd: Int): ByteArray? {
    val lb = ByteArray(4); var r = 0
    while (r < 4) {
        val n = lb.usePinned { p -> recv(fd, p.addressOf(r), (4 - r).convert(), 0).toInt() }
        if (n <= 0) return null
        r += n
    }
    val len = ((lb[0].toInt() and 0xFF) shl 24) or ((lb[1].toInt() and 0xFF) shl 16) or
              ((lb[2].toInt() and 0xFF) shl 8)  or  (lb[3].toInt() and 0xFF)
    if (len <= 0 || len > 10_000_000) return null
    val buf = ByteArray(len); r = 0
    while (r < len) {
        val n = buf.usePinned { p -> recv(fd, p.addressOf(r), (len - r).convert(), 0).toInt() }
        if (n <= 0) return null
        r += n
    }
    return buf
}

// ── UDP send/receive helpers ──────────────────────────────────────────────────

/** Send [msg] as UTF-8 UDP datagram to [destIp]:[destPort] from [sock]. */
internal fun ssdpUdpSend(sock: Int, msg: String, destIp: String, destPort: UShort) {
    val bytes = msg.encodeToByteArray()
    memScoped {
        val dest = alloc<sockaddr_in>()
        dest.sin_family = AF_INET.convert()
        dest.sin_port = ssdpHtons(destPort)
        dest.sin_addr.s_addr = ssdpInetAddr(destIp)
        bytes.usePinned { p ->
            sendto(sock, p.addressOf(0), bytes.size.convert(), 0,
                dest.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
        }
    }
}

/**
 * Receive one UDP datagram (max 2048 bytes) with a [timeoutMs] millisecond
 * deadline using SO_RCVTIMEO. Returns null on timeout or error.
 * Also fills [srcIp] and [srcPort] if non-null.
 */
internal fun ssdpUdpRecv(
    sock: Int,
    timeoutMs: Int = 2_000,
    srcIp: StringBuilder? = null,
    srcPort: IntArray? = null
): String? = memScoped {
    val tv = alloc<timeval>()
    tv.tv_sec = (timeoutMs / 1000).convert()
    tv.tv_usec = ((timeoutMs % 1000) * 1000).convert()
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, tv.ptr, sizeOf<timeval>().convert())

    val buf = allocArray<ByteVar>(2048)
    val srcAddr = alloc<sockaddr_in>()
    val srcLen = alloc<socklen_tVar>()
    srcLen.value = sizeOf<sockaddr_in>().convert()

    val n = recvfrom(sock, buf, 2048u, 0,
        srcAddr.ptr.reinterpret(), srcLen.ptr).toInt()
    if (n <= 0) return null

    srcIp?.append(ssdpAddrToString(srcAddr.sin_addr.s_addr))
    srcPort?.let { it[0] = ssdpHtons(srcAddr.sin_port).toInt() }

    buf.toKString().substring(0, n)
}
