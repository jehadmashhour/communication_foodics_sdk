@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.foodics.crosscommunicationlibrary.nfc

import kotlinx.cinterop.*
import platform.posix.*

// ── JSON helpers ──────────────────────────────────────────────────────────────

internal data class NfcDeviceInfoIos(val id: String, val name: String, val ip: String, val port: Int)

internal fun parseNfcJsonIos(content: String): NfcDeviceInfoIos? = runCatching {
    val id   = Regex(""""id"\s*:\s*"([^"]+)"""").find(content)?.groupValues?.get(1) ?: return null
    val name = Regex(""""name"\s*:\s*"([^"]+)"""").find(content)?.groupValues?.get(1) ?: return null
    val ip   = Regex(""""ip"\s*:\s*"([\d.]+)"""").find(content)?.groupValues?.get(1) ?: return null
    val port = Regex(""""port"\s*:\s*(\d+)""").find(content)?.groupValues?.get(1)?.toIntOrNull() ?: return null
    NfcDeviceInfoIos(id, name, ip, port)
}.getOrNull()

// ── TCP framing ───────────────────────────────────────────────────────────────

internal fun nfcTcpSendFramed(fd: Int, data: ByteArray) {
    val len = data.size
    val prefix = ByteArray(4).apply {
        this[0] = (len ushr 24 and 0xFF).toByte()
        this[1] = (len ushr 16 and 0xFF).toByte()
        this[2] = (len ushr  8 and 0xFF).toByte()
        this[3] = (len         and 0xFF).toByte()
    }
    prefix.usePinned { p -> send(fd, p.addressOf(0), 4.convert(), 0) }
    data.usePinned   { p -> send(fd, p.addressOf(0), len.convert(), 0) }
}

internal fun nfcTcpRecvFramed(fd: Int): ByteArray? {
    val lb = ByteArray(4); var r = 0
    while (r < 4) {
        val n = lb.usePinned { p -> recv(fd, p.addressOf(r), (4 - r).convert(), 0).toInt() }
        if (n <= 0) return null
        r += n
    }
    val len = ((lb[0].toInt() and 0xFF) shl 24) or ((lb[1].toInt() and 0xFF) shl 16) or
              ((lb[2].toInt() and 0xFF) shl  8) or  (lb[3].toInt() and 0xFF)
    if (len <= 0 || len > 10_000_000) return null
    val buf = ByteArray(len); r = 0
    while (r < len) {
        val n = buf.usePinned { p -> recv(fd, p.addressOf(r), (len - r).convert(), 0).toInt() }
        if (n <= 0) return null
        r += n
    }
    return buf
}

// ── Network helpers ───────────────────────────────────────────────────────────

internal fun nfcHtons(v: UShort): UShort =
    ((v.toInt() and 0xFF shl 8) or (v.toInt() ushr 8 and 0xFF)).toUShort()

internal fun nfcInetAddr(ip: String): UInt {
    val p = ip.split(".")
    if (p.size != 4) return 0u
    return ((p[3].toUInt() and 0xFFu) shl 24) or
           ((p[2].toUInt() and 0xFFu) shl 16) or
           ((p[1].toUInt() and 0xFFu) shl  8) or
            (p[0].toUInt() and 0xFFu)
}
