@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.foodics.crosscommunicationlibrary.redis

import kotlinx.cinterop.*
import platform.posix.*

// ── TCP helpers ───────────────────────────────────────────────────────────────

private fun redisHtons(v: UShort): UShort =
    ((v.toInt() and 0xFF shl 8) or (v.toInt() ushr 8 and 0xFF)).toUShort()

private fun redisInetAddr(ip: String): UInt {
    val p = ip.split(".")
    if (p.size != 4) return 0u
    return ((p[3].toUInt() and 0xFFu) shl 24) or
           ((p[2].toUInt() and 0xFFu) shl 16) or
           ((p[1].toUInt() and 0xFFu) shl  8) or
            (p[0].toUInt() and 0xFFu)
}

/** Open a TCP connection to [host]:[port]. Returns fd ≥ 0 or -1 on failure. */
internal fun redisTcpConnect(host: String, port: Int): Int = memScoped {
    val actualHost = if (host == "localhost") "127.0.0.1" else host
    val fd = socket(AF_INET, SOCK_STREAM, 0)
    if (fd < 0) return -1
    val addr = alloc<sockaddr_in>()
    addr.sin_family      = AF_INET.convert()
    addr.sin_port        = redisHtons(port.toUShort())
    addr.sin_addr.s_addr = redisInetAddr(actualHost)
    if (connect(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) != 0) {
        close(fd); return -1
    }
    fd
}

/** Set SO_RCVTIMEO on [fd] to [ms] milliseconds (0 = block indefinitely). */
internal fun redisSetTimeout(fd: Int, ms: Int) = memScoped {
    val tv = alloc<timeval>()
    tv.tv_sec  = (ms / 1000).convert()
    tv.tv_usec = ((ms % 1000) * 1000).convert()
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, tv.ptr, sizeOf<timeval>().convert())
}

/** Send all bytes in [data]. Returns false on error. */
internal fun redisSendAll(fd: Int, data: ByteArray): Boolean {
    var sent = 0
    while (sent < data.size) {
        val n = data.usePinned { p -> send(fd, p.addressOf(sent), (data.size - sent).convert(), 0).toInt() }
        if (n <= 0) return false
        sent += n
    }
    return true
}

/** Receive exactly [n] bytes. Returns null on EOF / timeout. */
internal fun redisRecvExact(fd: Int, n: Int): ByteArray? {
    if (n == 0) return ByteArray(0)
    val buf = ByteArray(n); var read = 0
    while (read < n) {
        val r = buf.usePinned { p -> recv(fd, p.addressOf(read), (n - read).convert(), 0).toInt() }
        if (r <= 0) return null
        read += r
    }
    return buf
}

// ── RESP command builders ─────────────────────────────────────────────────────

internal fun redisBuildCmd(vararg args: String): ByteArray {
    val pieces = mutableListOf<ByteArray>()
    pieces += "*${args.size}\r\n".encodeToByteArray()
    for (arg in args) {
        val b = arg.encodeToByteArray()
        pieces += "\$${b.size}\r\n".encodeToByteArray()
        pieces += b
        pieces += "\r\n".encodeToByteArray()
    }
    val total = pieces.sumOf { it.size }
    val out = ByteArray(total); var off = 0
    for (p in pieces) { p.copyInto(out, off); off += p.size }
    return out
}

internal fun redisBuildPublish(channel: String, data: ByteArray): ByteArray {
    val ch = channel.encodeToByteArray()
    val pieces = listOf(
        "*3\r\n\$7\r\nPUBLISH\r\n".encodeToByteArray(),
        "\$${ch.size}\r\n".encodeToByteArray(), ch, "\r\n".encodeToByteArray(),
        "\$${data.size}\r\n".encodeToByteArray(), data, "\r\n".encodeToByteArray()
    )
    val total = pieces.sumOf { it.size }
    val out = ByteArray(total); var off = 0
    for (p in pieces) { p.copyInto(out, off); off += p.size }
    return out
}

// ── RESP reading ──────────────────────────────────────────────────────────────

internal fun redisReadLine(fd: Int): String? {
    val sb = StringBuilder()
    val b  = ByteArray(1); var prev = ' '
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

internal fun redisReadValue(fd: Int): Pair<String, ByteArray>? {
    val line = redisReadLine(fd) ?: return null
    if (line.isEmpty()) return null
    return when (line[0]) {
        '+' -> "str"   to line.substring(1).encodeToByteArray()
        '-' -> "error" to line.substring(1).encodeToByteArray()
        ':' -> "int"   to line.substring(1).encodeToByteArray()
        '$' -> {
            val len = line.substring(1).toIntOrNull() ?: return null
            if (len < 0) return "null" to ByteArray(0)
            val data = redisRecvExact(fd, len) ?: return null
            redisReadLine(fd) // consume trailing \r\n
            "bulk" to data
        }
        '*' -> "array" to line.substring(1).encodeToByteArray()
        else -> null
    }
}

/**
 * Read one pub/sub frame. Returns (channel, payload) for "message" type,
 * null for subscribe confirmations or any other non-data frame.
 */
internal fun redisReadPubSub(fd: Int): Pair<String, ByteArray>? {
    val line = redisReadLine(fd) ?: return null
    if (!line.startsWith("*")) return null
    val count = line.substring(1).toIntOrNull() ?: return null
    val (_, typeBytes) = redisReadValue(fd) ?: return null
    val type = typeBytes.decodeToString()
    if (type != "message") { repeat(count - 1) { redisReadValue(fd) }; return null }
    val (_, channelBytes) = redisReadValue(fd) ?: return null
    val (_, data) = redisReadValue(fd) ?: return null
    return channelBytes.decodeToString() to data
}
