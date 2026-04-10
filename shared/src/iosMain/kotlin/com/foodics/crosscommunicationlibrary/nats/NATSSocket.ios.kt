@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.foodics.crosscommunicationlibrary.nats

import kotlinx.cinterop.*
import platform.posix.*

// ── TCP helpers ───────────────────────────────────────────────────────────────

private fun natsHtons(v: UShort): UShort =
    ((v.toInt() and 0xFF shl 8) or (v.toInt() ushr 8 and 0xFF)).toUShort()

private fun natsInetAddr(ip: String): UInt {
    val p = ip.split(".")
    if (p.size != 4) return 0u
    return ((p[3].toUInt() and 0xFFu) shl 24) or
            ((p[2].toUInt() and 0xFFu) shl 16) or
            ((p[1].toUInt() and 0xFFu) shl  8) or
             (p[0].toUInt() and 0xFFu)
}

/** Open a TCP connection to [host]:[port]. Returns fd ≥ 0 or -1. */
internal fun natsTcpConnect(host: String, port: Int): Int = memScoped {
    val fd = socket(AF_INET, SOCK_STREAM, 0)
    if (fd < 0) return -1
    val addr = alloc<sockaddr_in>()
    addr.sin_family = AF_INET.convert()
    addr.sin_port   = natsHtons(port.toUShort())
    addr.sin_addr.s_addr = natsInetAddr(host)
    if (connect(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) != 0) {
        close(fd); return -1
    }
    fd
}

/** Set SO_RCVTIMEO on [fd] to [ms] milliseconds (0 = no timeout). */
internal fun natsSetTimeout(fd: Int, ms: Int) = memScoped {
    val tv = alloc<timeval>()
    tv.tv_sec  = (ms / 1000).convert()
    tv.tv_usec = ((ms % 1000) * 1000).convert()
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, tv.ptr, sizeOf<timeval>().convert())
}

/** Send all bytes in [data]. Returns false on error. */
internal fun natsSendAll(fd: Int, data: ByteArray): Boolean {
    var sent = 0
    while (sent < data.size) {
        val n = data.usePinned { p ->
            send(fd, p.addressOf(sent), (data.size - sent).convert(), 0).toInt()
        }
        if (n <= 0) return false
        sent += n
    }
    return true
}

/** Send a string command over [fd]. */
internal fun natsSendCmd(fd: Int, cmd: String) = natsSendAll(fd, cmd.encodeToByteArray())

/** Publish subject + binary body over [fd]. */
internal fun natsPublish(fd: Int, subject: String, data: ByteArray) {
    natsSendCmd(fd, natsPubHeader(subject, data.size))
    natsSendAll(fd, data)
    natsSendAll(fd, "\r\n".encodeToByteArray())
}

/** Receive exactly [n] bytes. Returns null on EOF/timeout. */
internal fun natsRecvExact(fd: Int, n: Int): ByteArray? {
    if (n == 0) return ByteArray(0)
    val buf = ByteArray(n); var read = 0
    while (read < n) {
        val r = buf.usePinned { p -> recv(fd, p.addressOf(read), (n - read).convert(), 0).toInt() }
        if (r <= 0) return null
        read += r
    }
    return buf
}

/**
 * Read a CRLF-terminated line from [fd] byte-by-byte.
 * Returns null on EOF / timeout (errno = EAGAIN/EWOULDBLOCK when SO_RCVTIMEO fires).
 */
internal fun natsReadLine(fd: Int): String? {
    val sb = StringBuilder()
    val b = ByteArray(1)
    var prev = ' '
    while (true) {
        val n = b.usePinned { p -> recv(fd, p.addressOf(0), 1u.convert(), 0).toInt() }
        if (n <= 0) return if (sb.isEmpty()) null else sb.toString()
        val c = b[0].toInt().toChar()
        if (c == '\n') {
            if (prev == '\r' && sb.isNotEmpty()) sb.deleteAt(sb.length - 1)
            return sb.toString()
        }
        sb.append(c); prev = c
    }
}
