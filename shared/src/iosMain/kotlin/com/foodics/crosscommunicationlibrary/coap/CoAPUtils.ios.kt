@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.foodics.crosscommunicationlibrary.coap

import kotlinx.cinterop.*
import platform.posix.*
import kotlin.random.Random

internal const val COAP_SERVICE_TYPE = "_foodics_coap._udp."

internal const val CODE_POST: Byte = 0x02
internal const val CODE_CONTENT: Byte = 0x45

// ── CoAP packet builders ──────────────────────────────────────────────────────

internal fun coapBuildPost(payload: ByteArray): ByteArray =
    coapFrame(type = 1, code = CODE_POST, payload = payload)

internal fun coapBuildContent(payload: ByteArray): ByteArray =
    coapFrame(type = 1, code = CODE_CONTENT, payload = payload)

private fun coapFrame(type: Int, code: Byte, payload: ByteArray): ByteArray {
    val msgId = Random.nextInt(0x0001, 0xFFFF)
    val token = Random.nextBytes(4)
    val tkl = token.size
    val hasPayload = payload.isNotEmpty()
    val size = 4 + tkl + (if (hasPayload) 1 + payload.size else 0)
    val buf = ByteArray(size)
    buf[0] = ((0x40) or (type shl 4) or tkl).toByte()
    buf[1] = code
    buf[2] = (msgId shr 8 and 0xFF).toByte()
    buf[3] = (msgId and 0xFF).toByte()
    token.copyInto(buf, 4)
    if (hasPayload) {
        buf[4 + tkl] = 0xFF.toByte()
        payload.copyInto(buf, 4 + tkl + 1)
    }
    return buf
}

/** Extract payload bytes from a CoAP datagram. Returns empty if none. */
internal fun coapParsePayload(data: ByteArray): ByteArray {
    if (data.size < 4) return ByteArray(0)
    val tkl = data[0].toInt() and 0x0F
    val start = 4 + tkl
    for (i in start until data.size) {
        if (data[i] == 0xFF.toByte()) {
            return if (i + 1 < data.size) data.copyOfRange(i + 1, data.size) else ByteArray(0)
        }
    }
    return ByteArray(0)
}

internal fun isValidCoap(data: ByteArray): Boolean {
    if (data.size < 4) return false
    return ((data[0].toInt() ushr 6) and 0x03) == 1
}

// ── POSIX UDP helpers ─────────────────────────────────────────────────────────

internal fun coapHtons(v: UShort): UShort =
    ((v.toInt() and 0xFF shl 8) or (v.toInt() ushr 8 and 0xFF)).toUShort()

internal fun coapInetAddr(ip: String): UInt {
    val p = ip.split(".")
    if (p.size != 4) return 0u
    val b0 = p[0].toUInt() and 0xFFu
    val b1 = p[1].toUInt() and 0xFFu
    val b2 = p[2].toUInt() and 0xFFu
    val b3 = p[3].toUInt() and 0xFFu
    return (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
}

internal fun coapInetNtop(sAddr: UInt): String {
    val b0 = sAddr and 0xFFu
    val b1 = (sAddr shr 8) and 0xFFu
    val b2 = (sAddr shr 16) and 0xFFu
    val b3 = (sAddr shr 24) and 0xFFu
    return "$b0.$b1.$b2.$b3"
}

/** Returns the port that the bound socket is actually using. */
internal fun coapGetBoundPort(fd: Int): Int = memScoped {
    val addr = alloc<sockaddr_in>()
    val len = alloc<socklen_tVar>()
    len.value = sizeOf<sockaddr_in>().convert()
    getsockname(fd, addr.ptr.reinterpret(), len.ptr)
    coapHtons(addr.sin_port).toInt()
}

/**
 * Create and bind a UDP socket on port 0 (OS-assigned) with a receive timeout.
 * Returns the fd or -1 on failure.
 */
internal fun coapOpenUdpSocket(recvTimeoutSec: Long = 1L): Int = memScoped {
    val fd = socket(AF_INET, SOCK_DGRAM, 0)
    if (fd < 0) return -1
    val flag = alloc<IntVar>().apply { value = 1 }
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, flag.ptr, sizeOf<IntVar>().convert())
    val tv = alloc<timeval>()
    tv.tv_sec = recvTimeoutSec
    tv.tv_usec = 0
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, tv.ptr, sizeOf<timeval>().convert())
    val addr = alloc<sockaddr_in>()
    addr.sin_family = AF_INET.convert()
    addr.sin_port = coapHtons(0u)
    addr.sin_addr.s_addr = INADDR_ANY
    if (bind(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) != 0) {
        close(fd); return -1
    }
    fd
}

/**
 * Send a UDP datagram to [ip]:[port]. Returns true on success.
 */
internal fun coapSendTo(fd: Int, data: ByteArray, ip: String, port: Int): Boolean = memScoped {
    val dest = alloc<sockaddr_in>()
    dest.sin_family = AF_INET.convert()
    dest.sin_port = coapHtons(port.toUShort())
    dest.sin_addr.s_addr = coapInetAddr(ip)
    val n = data.usePinned { p ->
        sendto(fd, p.addressOf(0), data.size.convert(), 0,
            dest.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
    }
    n.toInt() == data.size
}

/**
 * Receive one UDP datagram. Returns (data, senderIp, senderPort) or null on timeout/error.
 */
internal fun coapRecvFrom(fd: Int, bufSize: Int = 65_507): Triple<ByteArray, String, Int>? = memScoped {
    val buf = allocArray<ByteVar>(bufSize)
    val sender = alloc<sockaddr_in>()
    val addrLen = alloc<socklen_tVar>()
    addrLen.value = sizeOf<sockaddr_in>().convert()
    val n = recvfrom(fd, buf, bufSize.convert(), 0,
        sender.ptr.reinterpret(), addrLen.ptr).toInt()
    if (n <= 0) return null
    val data = ByteArray(n) { buf[it] }
    val ip = coapInetNtop(sender.sin_addr.s_addr)
    val port = coapHtons(sender.sin_port).toInt()
    Triple(data, ip, port)
}
