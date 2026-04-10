package com.foodics.crosscommunicationlibrary.websocket

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.MessageDigest
import java.util.Base64

internal const val WS_SERVICE_TYPE = "_foodics_ws._tcp."
private const val WS_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

// ── Local IP ──────────────────────────────────────────────────────────────────

internal fun getLocalIpWs(): String = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { !it.isLoopbackAddress }
        ?.hostAddress ?: "0.0.0.0"
}.getOrDefault("0.0.0.0")

// ── WebSocket handshake ───────────────────────────────────────────────────────

/** Computes Sec-WebSocket-Accept from Sec-WebSocket-Key. */
internal fun computeWsAcceptKey(clientKey: String): String {
    val sha1 = MessageDigest.getInstance("SHA-1").digest((clientKey + WS_MAGIC).toByteArray(Charsets.UTF_8))
    return Base64.getEncoder().encodeToString(sha1)
}

/** Generates a random 16-byte WebSocket key (Base64-encoded). */
internal fun generateWsKey(): String =
    Base64.getEncoder().encodeToString(java.util.Random().let { r ->
        ByteArray(16).also { r.nextBytes(it) }
    })

/**
 * Server-side handshake: read HTTP Upgrade request, respond with 101.
 * Returns false if the request is not a valid WS upgrade.
 */
internal fun performServerHandshake(input: InputStream, output: OutputStream): Boolean {
    val headers = readWsHeaders(input)
    val key = headers["sec-websocket-key"] ?: return false
    val response = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ${computeWsAcceptKey(key)}\r\n\r\n"
    output.write(response.toByteArray(Charsets.UTF_8))
    output.flush()
    return true
}

/**
 * Client-side handshake: send HTTP Upgrade request, discard 101 response.
 */
internal fun performClientHandshake(host: String, port: Int, input: InputStream, output: OutputStream) {
    val key = generateWsKey()
    val request = "GET / HTTP/1.1\r\nHost: $host:$port\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n"
    output.write(request.toByteArray(Charsets.UTF_8))
    output.flush()
    readWsHeaders(input) // consume 101 response headers
}

/** Reads HTTP headers from [input] until blank line, returns lowercase map. */
private fun readWsHeaders(input: InputStream): Map<String, String> {
    val buf = ByteArrayOutputStream()
    var b3 = 0; var b2 = 0; var b1 = 0
    while (true) {
        val b = input.read()
        if (b < 0) break
        buf.write(b)
        if (b3 == '\r'.code && b2 == '\n'.code && b1 == '\r'.code && b == '\n'.code) break
        b3 = b2; b2 = b1; b1 = b
    }
    val headers = mutableMapOf<String, String>()
    buf.toByteArray().toString(Charsets.UTF_8).lines().drop(1).forEach { line ->
        val idx = line.indexOf(':')
        if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
    }
    return headers
}

// ── WebSocket frame encoding / decoding ───────────────────────────────────────

/**
 * Read one WebSocket binary frame from [input].
 * Handles unmasked (server→client) and masked (client→server) frames.
 * Returns null on close frame or connection error.
 */
internal fun readWsFrame(input: InputStream): ByteArray? {
    fun readExact(n: Int): ByteArray? {
        val buf = ByteArray(n); var r = 0
        while (r < n) { val c = input.read(buf, r, n - r); if (c < 0) return null; r += c }
        return buf
    }

    val h = readExact(2) ?: return null
    val opcode = h[0].toInt() and 0x0F
    if (opcode == 8) return null // close frame

    val masked = (h[1].toInt() and 0x80) != 0
    var payloadLen = (h[1].toInt() and 0x7F).toLong()

    payloadLen = when (payloadLen.toInt()) {
        126 -> { val e = readExact(2) ?: return null; ((e[0].toLong() and 0xFF) shl 8) or (e[1].toLong() and 0xFF) }
        127 -> { val e = readExact(8) ?: return null; (0 until 8).fold(0L) { acc, i -> (acc shl 8) or (e[i].toLong() and 0xFF) } }
        else -> payloadLen
    }

    val maskKey = if (masked) readExact(4) ?: return null else null
    val payload = readExact(payloadLen.toInt()) ?: return null

    if (masked && maskKey != null) {
        for (i in payload.indices) payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
    }
    return payload
}

/**
 * Write one WebSocket binary frame to [output].
 * [mask] = true for client→server frames (required by spec); false for server→client.
 */
internal fun writeWsFrame(output: OutputStream, data: ByteArray, mask: Boolean = false) {
    val len = data.size
    val maskBit = if (mask) 0x80 else 0x00

    output.write(0x82) // FIN + binary opcode
    when {
        len < 126 -> output.write(maskBit or len)
        len < 65536 -> {
            output.write(maskBit or 126)
            output.write(len ushr 8); output.write(len and 0xFF)
        }
        else -> {
            output.write(maskBit or 127)
            repeat(4) { output.write(0) }
            output.write(len ushr 24); output.write(len ushr 16 and 0xFF)
            output.write(len ushr 8 and 0xFF); output.write(len and 0xFF)
        }
    }

    if (mask) {
        val maskKey = ByteArray(4).also { java.util.Random().nextBytes(it) }
        output.write(maskKey)
        output.write(ByteArray(len) { i -> (data[i].toInt() xor maskKey[i % 4].toInt()).toByte() })
    } else {
        output.write(data)
    }
    output.flush()
}

/** Send a WebSocket close frame. */
internal fun sendWsClose(output: OutputStream) {
    runCatching { output.write(byteArrayOf(0x88.toByte(), 0x00)); output.flush() }
}
