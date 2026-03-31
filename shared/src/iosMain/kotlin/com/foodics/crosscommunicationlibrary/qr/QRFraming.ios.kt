@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.foodics.crosscommunicationlibrary.qr

import kotlinx.cinterop.*
import platform.Foundation.NSData
import platform.posix.*

// ── TCP framing (4-byte big-endian length prefix) ────────────────────────────

/** Send [data] over [fd] with a 4-byte length prefix. */
internal fun tcpSendFramed(fd: Int, data: ByteArray) {
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

/**
 * Receive a length-prefixed frame from [fd].
 * Returns null on EOF or socket error.
 */
internal fun tcpRecvFramed(fd: Int): ByteArray? {
    // Read 4-byte length
    val lenBuf = ByteArray(4)
    var received = 0
    while (received < 4) {
        val n = lenBuf.usePinned { p ->
            recv(fd, p.addressOf(received), (4 - received).convert(), 0).toInt()
        }
        if (n <= 0) return null
        received += n
    }
    val len = ((lenBuf[0].toInt() and 0xFF) shl 24) or
              ((lenBuf[1].toInt() and 0xFF) shl 16) or
              ((lenBuf[2].toInt() and 0xFF) shl 8)  or
               (lenBuf[3].toInt() and 0xFF)

    // Read payload
    val buf = ByteArray(len)
    var dataReceived = 0
    while (dataReceived < len) {
        val n = buf.usePinned { p ->
            recv(fd, p.addressOf(dataReceived), (len - dataReceived).convert(), 0).toInt()
        }
        if (n <= 0) return null
        dataReceived += n
    }
    return buf
}

// ── Network byte-order helpers ────────────────────────────────────────────────

internal fun qrHtons(v: UShort): UShort =
    ((v.toInt() and 0xFF shl 8) or (v.toInt() ushr 8 and 0xFF)).toUShort()

internal fun qrNtohs(v: UShort): UShort = qrHtons(v)

/** Dotted-decimal string → network-byte-order UInt (like inet_addr). */
internal fun qrInetAddr(ip: String): UInt {
    val p = ip.split(".")
    if (p.size != 4) return 0u
    val b0 = p[0].toUInt() and 0xFFu
    val b1 = p[1].toUInt() and 0xFFu
    val b2 = p[2].toUInt() and 0xFFu
    val b3 = p[3].toUInt() and 0xFFu
    return (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
}

// ── JSON helpers ─────────────────────────────────────────────────────────────

internal fun buildQRJsonIos(id: String, name: String, ip: String, port: Int): String =
    """{"id":"$id","name":"$name","ip":"$ip","port":$port}"""

internal data class QRDeviceInfoIos(val id: String, val name: String, val ip: String, val port: Int)

internal fun parseQRJsonIos(content: String): QRDeviceInfoIos? = runCatching {
    val id   = Regex(""""id"\s*:\s*"([^"]+)"""").find(content)?.groupValues?.get(1) ?: return null
    val name = Regex(""""name"\s*:\s*"([^"]+)"""").find(content)?.groupValues?.get(1) ?: return null
    val ip   = Regex(""""ip"\s*:\s*"([\d.]+)"""").find(content)?.groupValues?.get(1) ?: return null
    val port = Regex(""""port"\s*:\s*(\d+)""").find(content)?.groupValues?.get(1)?.toIntOrNull() ?: return null
    QRDeviceInfoIos(id, name, ip, port)
}.getOrNull()

// ── NSData → ByteArray ────────────────────────────────────────────────────────

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
internal fun NSData.toKByteArray(): ByteArray {
    val size = length.toInt()
    return ByteArray(size).also { arr ->
        arr.usePinned { pin -> memcpy(pin.addressOf(0), bytes, length) }
    }
}
