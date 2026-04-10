@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.foodics.crosscommunicationlibrary.multicast

import kotlinx.cinterop.*
import platform.posix.*

internal const val MULTICAST_GROUP = "239.255.42.42"
internal const val MULTICAST_PORT  = 5422

internal const val PKT_BEACON: Byte = 0x01
internal const val PKT_DATA:   Byte = 0x02

// Darwin: IP_ADD_MEMBERSHIP = 12, IPPROTO_IP = 0
private const val IP_ADD_MEMBERSHIP_VAL = 12
private const val IP_MULTICAST_TTL_VAL  = 10  // IP_MULTICAST_TTL = 10 on Darwin

// ── Packet helpers ────────────────────────────────────────────────────────────

internal fun buildBeacon(identifier: String, deviceName: String): ByteArray {
    val payload = "$identifier|$deviceName".encodeToByteArray()
    return byteArrayOf(PKT_BEACON) + payload
}

internal fun buildData(payload: ByteArray): ByteArray = byteArrayOf(PKT_DATA) + payload

internal fun parsePacket(raw: ByteArray): Pair<Byte, ByteArray>? {
    if (raw.isEmpty()) return null
    return raw[0] to (if (raw.size > 1) raw.copyOfRange(1, raw.size) else ByteArray(0))
}

// ── POSIX helpers ─────────────────────────────────────────────────────────────

internal fun mcHtons(v: UShort): UShort =
    ((v.toInt() and 0xFF shl 8) or (v.toInt() ushr 8 and 0xFF)).toUShort()

internal fun mcInetAddr(ip: String): UInt {
    val p = ip.split(".")
    if (p.size != 4) return 0u
    return ((p[3].toUInt() and 0xFFu) shl 24) or
            ((p[2].toUInt() and 0xFFu) shl 16) or
            ((p[1].toUInt() and 0xFFu) shl 8)  or
             (p[0].toUInt() and 0xFFu)
}

internal fun mcInetNtop(sAddr: UInt): String {
    val b0 = sAddr and 0xFFu
    val b1 = (sAddr shr 8) and 0xFFu
    val b2 = (sAddr shr 16) and 0xFFu
    val b3 = (sAddr shr 24) and 0xFFu
    return "$b0.$b1.$b2.$b3"
}

/**
 * Create a UDP socket joined to [MULTICAST_GROUP] and bound to [MULTICAST_PORT].
 * Uses SO_REUSEADDR + SO_REUSEPORT so multiple sockets can bind to the same port.
 * Returns fd or -1 on failure.
 *
 * ip_mreq layout (8 bytes):
 *   [0..3] imr_multiaddr: multicast group address (network byte order)
 *   [4..7] imr_interface: local interface address (INADDR_ANY = 0)
 */
internal fun mcOpenSocket(recvTimeoutSec: Long = 1L): Int = memScoped {
    val fd = socket(AF_INET, SOCK_DGRAM, 0)
    if (fd < 0) return -1

    val one = alloc<IntVar>().apply { value = 1 }
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, one.ptr, sizeOf<IntVar>().convert())
    setsockopt(fd, SOL_SOCKET, SO_REUSEPORT, one.ptr, sizeOf<IntVar>().convert())

    // Set TTL = 4 (do not cross WAN routers)
    val ttl = alloc<IntVar>().apply { value = 4 }
    setsockopt(fd, IPPROTO_IP, IP_MULTICAST_TTL_VAL, ttl.ptr, sizeOf<IntVar>().convert())

    // Receive timeout so loops stay interruptible
    val tv = alloc<timeval>()
    tv.tv_sec = recvTimeoutSec; tv.tv_usec = 0
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, tv.ptr, sizeOf<timeval>().convert())

    // Bind to INADDR_ANY:MULTICAST_PORT
    val addr = alloc<sockaddr_in>()
    addr.sin_family = AF_INET.convert()
    addr.sin_port = mcHtons(MULTICAST_PORT.toUShort())
    addr.sin_addr.s_addr = INADDR_ANY
    if (bind(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) != 0) {
        close(fd); return -1
    }

    // IP_ADD_MEMBERSHIP: join the multicast group
    // Manually build ip_mreq as 8 raw bytes (avoids cinterop struct availability issue)
    val groupAddr = mcInetAddr(MULTICAST_GROUP)
    val mreq = ByteArray(8).apply {
        this[0] = (groupAddr        and 0xFFu).toByte()
        this[1] = ((groupAddr shr 8)  and 0xFFu).toByte()
        this[2] = ((groupAddr shr 16) and 0xFFu).toByte()
        this[3] = ((groupAddr shr 24) and 0xFFu).toByte()
        // imr_interface = INADDR_ANY
        this[4] = 0; this[5] = 0; this[6] = 0; this[7] = 0
    }
    mreq.usePinned { p ->
        setsockopt(fd, IPPROTO_IP, IP_ADD_MEMBERSHIP_VAL, p.addressOf(0), 8u.convert())
    }

    fd
}

/** Send [data] to the multicast group. */
internal fun mcSendTo(fd: Int, data: ByteArray): Boolean = memScoped {
    val dest = alloc<sockaddr_in>()
    dest.sin_family = AF_INET.convert()
    dest.sin_port = mcHtons(MULTICAST_PORT.toUShort())
    dest.sin_addr.s_addr = mcInetAddr(MULTICAST_GROUP)
    val n = data.usePinned { p ->
        sendto(fd, p.addressOf(0), data.size.convert(), 0,
            dest.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
    }
    n.toInt() == data.size
}

/** Receive one datagram. Returns (data, senderIp) or null on timeout/error. */
internal fun mcRecvFrom(fd: Int): Pair<ByteArray, String>? = memScoped {
    val buf = allocArray<ByteVar>(65_507)
    val sender = alloc<sockaddr_in>()
    val addrLen = alloc<socklen_tVar>()
    addrLen.value = sizeOf<sockaddr_in>().convert()
    val n = recvfrom(fd, buf, 65_507.convert(), 0,
        sender.ptr.reinterpret(), addrLen.ptr).toInt()
    if (n <= 0) return null
    val data = ByteArray(n) { buf[it] }
    val ip = mcInetNtop(sender.sin_addr.s_addr)
    data to ip
}
