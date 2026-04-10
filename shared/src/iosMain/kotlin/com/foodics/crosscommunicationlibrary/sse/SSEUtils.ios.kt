@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.foodics.crosscommunicationlibrary.sse

import kotlinx.cinterop.*
import platform.posix.*

internal const val SSE_SERVICE_TYPE = "_foodics_sse._tcp."

// ── Base64 (pure Kotlin — no java.util.Base64 on iOS) ────────────────────────

private const val B64_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

internal fun base64Encode(data: ByteArray): String {
    val sb = StringBuilder()
    var i = 0
    while (i < data.size) {
        val b0 = data[i].toInt() and 0xFF
        val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xFF else 0
        val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xFF else 0
        sb.append(B64_CHARS[b0 ushr 2])
        sb.append(B64_CHARS[(b0 and 3) shl 4 or (b1 ushr 4)])
        sb.append(if (i + 1 < data.size) B64_CHARS[(b1 and 0xF) shl 2 or (b2 ushr 6)] else '=')
        sb.append(if (i + 2 < data.size) B64_CHARS[b2 and 0x3F] else '=')
        i += 3
    }
    return sb.toString()
}

internal fun base64Decode(s: String): ByteArray {
    val clean = s.filter { it != '\n' && it != '\r' && it != ' ' }
    val padded = clean.trimEnd('=')
    val out = mutableListOf<Byte>()
    var i = 0
    while (i < padded.length) {
        val c0 = B64_CHARS.indexOf(padded[i])
        val c1 = if (i + 1 < padded.length) B64_CHARS.indexOf(padded[i + 1]) else 0
        val c2 = if (i + 2 < padded.length) B64_CHARS.indexOf(padded[i + 2]) else 0
        val c3 = if (i + 3 < padded.length) B64_CHARS.indexOf(padded[i + 3]) else 0
        out.add(((c0 shl 2) or (c1 ushr 4)).toByte())
        if (i + 2 < padded.length) out.add(((c1 and 0xF) shl 4 or (c2 ushr 2)).toByte())
        if (i + 3 < padded.length) out.add(((c2 and 3) shl 6 or c3).toByte())
        i += 4
    }
    return out.toByteArray()
}

// ── POSIX socket helpers ──────────────────────────────────────────────────────

internal fun sseHtons(v: UShort): UShort =
    ((v.toInt() and 0xFF shl 8) or (v.toInt() ushr 8 and 0xFF)).toUShort()

internal fun sseInetAddr(ip: String): UInt {
    val p = ip.split(".")
    if (p.size != 4) return 0u
    return ((p[3].toUInt() and 0xFFu) shl 24) or
            ((p[2].toUInt() and 0xFFu) shl 16) or
            ((p[1].toUInt() and 0xFFu) shl 8) or
            (p[0].toUInt() and 0xFFu)
}

internal fun sseGetBoundPort(fd: Int): Int = memScoped {
    val addr = alloc<sockaddr_in>()
    val len = alloc<socklen_tVar>()
    len.value = sizeOf<sockaddr_in>().convert()
    getsockname(fd, addr.ptr.reinterpret(), len.ptr)
    sseHtons(addr.sin_port).toInt()
}

/** Accept one connection with a timeout. Returns client fd or null on timeout/error. */
internal fun sseAcceptWithTimeout(serverFd: Int, timeoutMs: Int = 2_000): Int? = memScoped {
    val tv = alloc<timeval>()
    tv.tv_sec = (timeoutMs / 1000).convert()
    tv.tv_usec = ((timeoutMs % 1000) * 1000).convert()
    setsockopt(serverFd, SOL_SOCKET, SO_RCVTIMEO, tv.ptr, sizeOf<timeval>().convert())
    val cFd = accept(serverFd, null, null)
    if (cFd < 0) null else cFd
}

/** Open a TCP client socket connected to [ip]:[port]. Returns fd or -1 on failure. */
internal fun sseConnect(ip: String, port: Int): Int = memScoped {
    val fd = socket(AF_INET, SOCK_STREAM, 0)
    if (fd < 0) return -1
    val addr = alloc<sockaddr_in>()
    addr.sin_family = AF_INET.convert()
    addr.sin_port = sseHtons(port.toUShort())
    addr.sin_addr.s_addr = sseInetAddr(ip)
    if (platform.posix.connect(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) != 0) {
        close(fd); return -1
    }
    fd
}

/** Send all bytes in [data]. Returns false on error. */
internal fun sseSendAll(fd: Int, data: ByteArray): Boolean {
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

/** Send a string over [fd]. */
internal fun sseSendString(fd: Int, s: String) = sseSendAll(fd, s.encodeToByteArray())

/**
 * Read one CRLF- or LF-terminated line from [fd], byte-by-byte.
 * Returns null on EOF/error.
 */
internal fun sseReadLine(fd: Int): String? {
    val sb = StringBuilder()
    val buf = ByteArray(1)
    var prev = ' '
    while (true) {
        val n = buf.usePinned { p -> recv(fd, p.addressOf(0), 1u.convert(), 0).toInt() }
        if (n <= 0) return if (sb.isEmpty()) null else sb.toString()
        val c = buf[0].toInt().toChar()
        if (c == '\n') {
            if (prev == '\r' && sb.isNotEmpty()) sb.deleteAt(sb.length - 1)
            return sb.toString()
        }
        sb.append(c)
        prev = c
    }
}

/** Read exactly [n] bytes from [fd]. Returns null on EOF/error. */
internal fun sseRecvExact(fd: Int, n: Int): ByteArray? {
    val buf = ByteArray(n)
    var read = 0
    while (read < n) {
        val r = buf.usePinned { p -> recv(fd, p.addressOf(read), (n - read).convert(), 0).toInt() }
        if (r <= 0) return null
        read += r
    }
    return buf
}
