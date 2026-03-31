@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.foodics.crosscommunicationlibrary.uwb

import kotlinx.cinterop.*
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.*

// ── OOB constants ─────────────────────────────────────────────────────────────

internal const val UWB_MULTICAST_IP   = "239.255.255.250"
internal const val UWB_OOB_PORT_US: UShort = 1901u
internal const val UWB_IOS_ANNOUNCE  = "UWB-IOS-ANNOUNCE"
internal const val UWB_IOS_SEARCH    = "UWB-IOS-SEARCH"

// ── Announce message ──────────────────────────────────────────────────────────

/** Format: UWB-IOS-ANNOUNCE|name|id|ip|tcpPort */
internal fun uwbIosAnnounce(name: String, id: String, ip: String, tcpPort: Int) =
    "$UWB_IOS_ANNOUNCE|$name|$id|$ip|$tcpPort"

internal data class UwbIosInfo(
    val name: String, val id: String,
    val ip: String, val tcpPort: Int,
    /** address stored in IoTDevice: "<ip>:<tcpPort>" */
    val address: String
)

internal fun parseUwbIosAnnounce(msg: String): UwbIosInfo? {
    if (!msg.startsWith(UWB_IOS_ANNOUNCE)) return null
    val p = msg.split("|")
    if (p.size < 5) return null
    return runCatching {
        UwbIosInfo(p[1], p[2], p[3], p[4].trim().toInt(), "${p[3]}:${p[4].trim()}")
    }.getOrNull()
}

// ── Ranging result encoding ───────────────────────────────────────────────────

/**
 * Encode ranging result as 12-byte big-endian ByteArray:
 *   [0-3] distance (m), [4-7] azimuth (°), [8-11] elevation (°)
 */
internal fun encodeRangingIos(distance: Float, azimuth: Float, elevation: Float): ByteArray {
    fun Float.toBeBytes(): ByteArray {
        val bits = this.toBits()
        return byteArrayOf(
            (bits ushr 24 and 0xFF).toByte(),
            (bits ushr 16 and 0xFF).toByte(),
            (bits ushr  8 and 0xFF).toByte(),
            (bits         and 0xFF).toByte()
        )
    }
    return distance.toBeBytes() + azimuth.toBeBytes() + elevation.toBeBytes()
}

// ── NSData ↔ ByteArray ────────────────────────────────────────────────────────

internal fun NSData.toByteArray(): ByteArray {
    val bytes = this.bytes ?: return ByteArray(0)
    return ByteArray(this.length.toInt()) { bytes.reinterpret<ByteVar>()[it] }
}

internal fun ByteArray.toNSData(): NSData =
    usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
    }

// ── Network helpers (POSIX) ───────────────────────────────────────────────────

internal fun uwbHtons(v: UShort): UShort =
    ((v.toInt() and 0xFF shl 8) or (v.toInt() ushr 8 and 0xFF)).toUShort()

internal fun uwbInetAddr(ip: String): UInt {
    val p = ip.split(".")
    if (p.size != 4) return 0u
    return ((p[3].toUInt() and 0xFFu) shl 24) or
           ((p[2].toUInt() and 0xFFu) shl 16) or
           ((p[1].toUInt() and 0xFFu) shl 8 ) or
            (p[0].toUInt() and 0xFFu)
}

internal fun uwbAddrToString(addr: UInt): String {
    return "${addr and 0xFFu}.${(addr shr 8) and 0xFFu}." +
           "${(addr shr 16) and 0xFFu}.${(addr shr 24) and 0xFFu}"
}

internal fun uwbGetLocalIpIos(): String = memScoped {
    val sock = socket(AF_INET, SOCK_DGRAM, 0)
    if (sock < 0) return "0.0.0.0"
    val remote = alloc<sockaddr_in>()
    remote.sin_family = AF_INET.convert()
    remote.sin_port = uwbHtons(80u)
    remote.sin_addr.s_addr = uwbInetAddr("8.8.8.8")
    platform.posix.connect(sock, remote.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
    val local = alloc<sockaddr_in>()
    val len = alloc<socklen_tVar>().also { it.value = sizeOf<sockaddr_in>().convert() }
    getsockname(sock, local.ptr.reinterpret(), len.ptr)
    close(sock)
    uwbAddrToString(local.sin_addr.s_addr)
}

// ── UDP send/receive (POSIX) ──────────────────────────────────────────────────

internal fun uwbUdpSend(sock: Int, msg: String, destIp: String, destPort: UShort) {
    val bytes = msg.encodeToByteArray()
    memScoped {
        val dest = alloc<sockaddr_in>()
        dest.sin_family = AF_INET.convert()
        dest.sin_port = uwbHtons(destPort)
        dest.sin_addr.s_addr = uwbInetAddr(destIp)
        bytes.usePinned { p ->
            sendto(sock, p.addressOf(0), bytes.size.convert(), 0,
                dest.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
        }
    }
}

internal fun uwbUdpRecv(
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
    val src = alloc<sockaddr_in>()
    val srcLen = alloc<socklen_tVar>().also { it.value = sizeOf<sockaddr_in>().convert() }

    val n = recvfrom(sock, buf, 2048u, 0, src.ptr.reinterpret(), srcLen.ptr).toInt()
    if (n <= 0) return null

    srcIp?.append(uwbAddrToString(src.sin_addr.s_addr))
    srcPort?.let { it[0] = uwbHtons(src.sin_port).toInt() }

    buf.toKString().substring(0, n)
}

// ── TCP token framing (4-byte big-endian length prefix) ──────────────────────

internal fun uwbTcpSendToken(fd: Int, token: ByteArray) {
    val len = token.size
    val prefix = byteArrayOf(
        (len ushr 24 and 0xFF).toByte(),
        (len ushr 16 and 0xFF).toByte(),
        (len ushr  8 and 0xFF).toByte(),
        (len         and 0xFF).toByte()
    )
    prefix.usePinned { p -> send(fd, p.addressOf(0), 4.convert(), 0) }
    token.usePinned  { p -> send(fd, p.addressOf(0), len.convert(), 0) }
}

internal fun uwbTcpRecvToken(fd: Int): ByteArray? {
    val lb = ByteArray(4); var r = 0
    while (r < 4) {
        val n = lb.usePinned { p -> recv(fd, p.addressOf(r), (4 - r).convert(), 0).toInt() }
        if (n <= 0) return null
        r += n
    }
    val len = ((lb[0].toInt() and 0xFF) shl 24) or ((lb[1].toInt() and 0xFF) shl 16) or
              ((lb[2].toInt() and 0xFF) shl  8) or  (lb[3].toInt() and 0xFF)
    if (len <= 0 || len > 1_000) return null
    val buf = ByteArray(len); r = 0
    while (r < len) {
        val n = buf.usePinned { p -> recv(fd, p.addressOf(r), (len - r).convert(), 0).toInt() }
        if (n <= 0) return null
        r += n
    }
    return buf
}

// ── TCP port helper ───────────────────────────────────────────────────────────

internal fun uwbGetTcpServerPort(fd: Int): Int = memScoped {
    val addr = alloc<sockaddr_in>()
    val len  = alloc<socklen_tVar>().also { it.value = sizeOf<sockaddr_in>().convert() }
    getsockname(fd, addr.ptr.reinterpret(), len.ptr)
    uwbHtons(addr.sin_port).toInt()
}
