package com.foodics.crosscommunicationlibrary.redis

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream

// ── RESP command builders ─────────────────────────────────────────────────────

internal fun buildRespCmd(vararg args: String): ByteArray {
    val bos = ByteArrayOutputStream()
    bos.write("*${args.size}\r\n".toByteArray())
    for (arg in args) {
        val b = arg.toByteArray(Charsets.UTF_8)
        bos.write("\$${b.size}\r\n".toByteArray()); bos.write(b); bos.write("\r\n".toByteArray())
    }
    return bos.toByteArray()
}

internal fun buildPublishCmd(channel: String, data: ByteArray): ByteArray {
    val bos = ByteArrayOutputStream()
    bos.write("*3\r\n\$7\r\nPUBLISH\r\n".toByteArray())
    val ch = channel.toByteArray(Charsets.UTF_8)
    bos.write("\$${ch.size}\r\n".toByteArray()); bos.write(ch); bos.write("\r\n".toByteArray())
    bos.write("\$${data.size}\r\n".toByteArray()); bos.write(data); bos.write("\r\n".toByteArray())
    return bos.toByteArray()
}

/** Thread-safe Redis publisher. */
internal class RedisWriter(private val out: OutputStream) {
    @Synchronized fun publish(channel: String, data: ByteArray) {
        out.write(buildPublishCmd(channel, data)); out.flush()
    }
    @Synchronized fun subscribe(vararg channels: String) {
        out.write(buildRespCmd("SUBSCRIBE", *channels)); out.flush()
    }
}

// ── RESP reading ──────────────────────────────────────────────────────────────

internal fun BufferedInputStream.redisReadLine(): String? {
    val sb = StringBuilder(); var prev = -1
    while (true) {
        val b = read()
        if (b < 0) return if (sb.isEmpty()) null else sb.toString()
        if (b == '\n'.code && prev == '\r'.code) {
            if (sb.isNotEmpty()) sb.deleteCharAt(sb.length - 1)
            return sb.toString()
        }
        sb.append(b.toChar()); prev = b
    }
}

internal fun BufferedInputStream.redisReadExact(n: Int): ByteArray? {
    if (n == 0) return ByteArray(0)
    val buf = ByteArray(n); var r = 0
    while (r < n) { val c = read(buf, r, n - r); if (c <= 0) return null; r += c }
    return buf
}

internal fun BufferedInputStream.redisReadValue(): Pair<String, ByteArray>? {
    val line = redisReadLine() ?: return null
    if (line.isEmpty()) return null
    return when (line[0]) {
        '+' -> "str"   to line.substring(1).toByteArray()
        '-' -> "error" to line.substring(1).toByteArray()
        ':' -> "int"   to line.substring(1).toByteArray()
        '$' -> {
            val len = line.substring(1).toIntOrNull() ?: return null
            if (len < 0) return "null" to ByteArray(0)
            val data = redisReadExact(len) ?: return null
            redisReadLine() // consume trailing \r\n
            "bulk" to data
        }
        '*' -> "array" to line.substring(1).toByteArray()
        else -> null
    }
}

/**
 * Read one pub/sub frame. Returns (channel, payload) for "message" type,
 * null for subscribe confirmations or other non-data frames.
 */
internal fun BufferedInputStream.redisReadPubSub(): Pair<String, ByteArray>? {
    val line = redisReadLine() ?: return null
    if (!line.startsWith("*")) return null
    val count = line.substring(1).toIntOrNull() ?: return null
    val (_, typeBytes) = redisReadValue() ?: return null
    val type = typeBytes.toString(Charsets.UTF_8)
    if (type != "message") { repeat(count - 1) { redisReadValue() }; return null }
    val (_, channelBytes) = redisReadValue() ?: return null
    val (_, data) = redisReadValue() ?: return null
    return channelBytes.toString(Charsets.UTF_8) to data
}
