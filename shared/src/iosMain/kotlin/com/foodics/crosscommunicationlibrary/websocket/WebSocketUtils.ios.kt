@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.foodics.crosscommunicationlibrary.websocket

import kotlinx.cinterop.*
import platform.posix.*

internal const val WS_SERVICE_TYPE = "_foodics_ws._tcp."
private const val WS_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

// ── Network byte-order helpers ─────────────────────────────────────────────────

internal fun wsHtons(v: UShort): UShort =
    ((v.toInt() and 0xFF shl 8) or (v.toInt() ushr 8 and 0xFF)).toUShort()

internal fun wsInetAddr(ip: String): UInt {
    val p = ip.split(".")
    if (p.size != 4) return 0u
    return ((p[3].toUInt() and 0xFFu) shl 24) or ((p[2].toUInt() and 0xFFu) shl 16) or
           ((p[1].toUInt() and 0xFFu) shl 8) or (p[0].toUInt() and 0xFFu)
}

internal fun wsAddrToString(addr: UInt): String {
    return "${(addr and 0xFFu).toInt()}.${((addr shr 8) and 0xFFu).toInt()}.${((addr shr 16) and 0xFFu).toInt()}.${((addr shr 24) and 0xFFu).toInt()}"
}

internal fun wsGetLocalIp(): String = memScoped {
    val sock = socket(AF_INET, SOCK_DGRAM, 0)
    if (sock < 0) return "0.0.0.0"
    val remote = alloc<sockaddr_in>()
    remote.sin_family = AF_INET.convert(); remote.sin_port = wsHtons(80u)
    remote.sin_addr.s_addr = wsInetAddr("8.8.8.8")
    platform.posix.connect(sock, remote.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
    val local = alloc<sockaddr_in>(); val len = alloc<socklen_tVar>()
    len.value = sizeOf<sockaddr_in>().convert()
    getsockname(sock, local.ptr.reinterpret(), len.ptr)
    close(sock)
    wsAddrToString(local.sin_addr.s_addr)
}

internal fun wsGetServerPort(fd: Int): Int = memScoped {
    val addr = alloc<sockaddr_in>(); val len = alloc<socklen_tVar>()
    len.value = sizeOf<sockaddr_in>().convert()
    getsockname(fd, addr.ptr.reinterpret(), len.ptr)
    wsHtons(addr.sin_port).toInt()
}

internal fun wsAcceptWithTimeout(serverFd: Int, timeoutMs: Int): Int? = memScoped {
    val tv = alloc<timeval>()
    tv.tv_sec = (timeoutMs / 1000).convert(); tv.tv_usec = ((timeoutMs % 1000) * 1000).convert()
    setsockopt(serverFd, SOL_SOCKET, SO_RCVTIMEO, tv.ptr, sizeOf<timeval>().convert())
    val cFd = accept(serverFd, null, null)
    if (cFd < 0) null else cFd
}

// ── Pure Kotlin SHA-1 (for WS accept key) ─────────────────────────────────────

private fun sha1(input: ByteArray): ByteArray {
    var h0 = 0x67452301;  var h1 = 0xEFCDAB89.toInt()
    var h2 = 0x98BADCFE.toInt(); var h3 = 0x10325476; var h4 = 0xC3D2E1F0.toInt()
    val msgLen = input.size
    val paddedLen = ((msgLen + 9 + 63) / 64) * 64
    val msg = ByteArray(paddedLen)
    input.copyInto(msg)
    msg[msgLen] = 0x80.toByte()
    val bitLen = msgLen.toLong() * 8
    for (i in 0 until 8) msg[paddedLen - 8 + i] = ((bitLen ushr (56 - i * 8)) and 0xFF).toByte()
    val w = IntArray(80)
    for (ci in 0 until paddedLen / 64) {
        val b = ci * 64
        for (i in 0 until 16) {
            w[i] = ((msg[b + i*4].toInt() and 0xFF) shl 24) or ((msg[b + i*4+1].toInt() and 0xFF) shl 16) or
                   ((msg[b + i*4+2].toInt() and 0xFF) shl 8) or (msg[b + i*4+3].toInt() and 0xFF)
        }
        for (i in 16 until 80) { val t = w[i-3] xor w[i-8] xor w[i-14] xor w[i-16]; w[i] = (t shl 1) or (t ushr 31) }
        var a = h0; var b2 = h1; var c = h2; var d = h3; var e = h4
        for (i in 0 until 80) {
            val (f, k) = when {
                i < 20 -> ((b2 and c) or (b2.inv() and d)) to 0x5A827999
                i < 40 -> (b2 xor c xor d) to 0x6ED9EBA1
                i < 60 -> ((b2 and c) or (b2 and d) or (c and d)) to 0x8F1BBCDC.toInt()
                else   -> (b2 xor c xor d) to 0xCA62C1D6.toInt()
            }
            val tmp = ((a shl 5) or (a ushr 27)) + f + e + k + w[i]
            e = d; d = c; c = (b2 shl 30) or (b2 ushr 2); b2 = a; a = tmp
        }
        h0 += a; h1 += b2; h2 += c; h3 += d; h4 += e
    }
    val out = ByteArray(20)
    listOf(h0, h1, h2, h3, h4).forEachIndexed { idx, h ->
        for (i in 0 until 4) out[idx * 4 + i] = ((h ushr (24 - i * 8)) and 0xFF).toByte()
    }
    return out
}

// ── Pure Kotlin Base64 ────────────────────────────────────────────────────────

private val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

internal fun base64Encode(data: ByteArray): String {
    val sb = StringBuilder()
    var i = 0
    while (i < data.size) {
        val b0 = data[i].toInt() and 0xFF
        val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xFF else 0
        val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xFF else 0
        sb.append(B64[(b0 shr 2) and 0x3F])
        sb.append(B64[((b0 shl 4) or (b1 shr 4)) and 0x3F])
        sb.append(if (i + 1 < data.size) B64[((b1 shl 2) or (b2 shr 6)) and 0x3F] else '=')
        sb.append(if (i + 2 < data.size) B64[b2 and 0x3F] else '=')
        i += 3
    }
    return sb.toString()
}

internal fun computeWsAcceptKey(clientKey: String): String =
    base64Encode(sha1((clientKey + WS_MAGIC).encodeToByteArray()))

internal fun generateWsKey(): String =
    base64Encode(ByteArray(16).also { kotlin.random.Random.nextBytes(it) })

// ── HTTP header read/write ────────────────────────────────────────────────────

/** Read HTTP headers from [fd] until \r\n\r\n. Returns lowercase map. */
internal fun wsReadHeaders(fd: Int): Map<String, String>? {
    val headerBytes = mutableListOf<Byte>()
    val oneByte = ByteArray(1)
    while (true) {
        val n = oneByte.usePinned { p -> recv(fd, p.addressOf(0), 1.convert(), 0).toInt() }
        if (n <= 0) return null
        headerBytes.add(oneByte[0])
        val sz = headerBytes.size
        if (sz >= 4 &&
            headerBytes[sz-4] == '\r'.code.toByte() && headerBytes[sz-3] == '\n'.code.toByte() &&
            headerBytes[sz-2] == '\r'.code.toByte() && headerBytes[sz-1] == '\n'.code.toByte()) break
    }
    val headers = mutableMapOf<String, String>()
    headerBytes.toByteArray().decodeToString().lines().drop(1).forEach { line ->
        val idx = line.indexOf(':')
        if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
    }
    return headers
}

internal fun wsSend(fd: Int, text: String) {
    val bytes = text.encodeToByteArray()
    bytes.usePinned { p -> send(fd, p.addressOf(0), bytes.size.convert(), 0) }
}

// ── WebSocket handshake ───────────────────────────────────────────────────────

internal fun performWsServerHandshake(fd: Int): Boolean {
    val headers = wsReadHeaders(fd) ?: return false
    val key = headers["sec-websocket-key"] ?: return false
    wsSend(fd, "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ${computeWsAcceptKey(key)}\r\n\r\n")
    return true
}

internal fun performWsClientHandshake(fd: Int, host: String, port: Int): Boolean {
    val key = generateWsKey()
    wsSend(fd, "GET / HTTP/1.1\r\nHost: $host:$port\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n")
    wsReadHeaders(fd) // consume 101 response
    return true
}

// ── WebSocket frame encoding / decoding ───────────────────────────────────────

private fun recvExact(fd: Int, buf: ByteArray, count: Int): Boolean {
    var r = 0
    while (r < count) {
        val n = buf.usePinned { p -> recv(fd, p.addressOf(r), (count - r).convert(), 0).toInt() }
        if (n <= 0) return false
        r += n
    }
    return true
}

/**
 * Read one WebSocket binary frame from POSIX socket [fd].
 * Returns null on close frame or socket error.
 */
internal fun wsReadFrame(fd: Int): ByteArray? {
    val header = ByteArray(2)
    if (!recvExact(fd, header, 2)) return null

    val opcode = header[0].toInt() and 0x0F
    if (opcode == 8) return null // close frame

    val masked = (header[1].toInt() and 0x80) != 0
    var payloadLen = (header[1].toInt() and 0x7F).toLong()

    payloadLen = when (payloadLen.toInt()) {
        126 -> {
            val e = ByteArray(2); if (!recvExact(fd, e, 2)) return null
            ((e[0].toLong() and 0xFF) shl 8) or (e[1].toLong() and 0xFF)
        }
        127 -> {
            val e = ByteArray(8); if (!recvExact(fd, e, 8)) return null
            (0 until 8).fold(0L) { acc, i -> (acc shl 8) or (e[i].toLong() and 0xFF) }
        }
        else -> payloadLen
    }

    val maskKey = if (masked) {
        ByteArray(4).also { if (!recvExact(fd, it, 4)) return null }
    } else null

    val payload = ByteArray(payloadLen.toInt())
    if (!recvExact(fd, payload, payloadLen.toInt())) return null

    if (masked && maskKey != null) {
        for (i in payload.indices) payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
    }
    return payload
}

/**
 * Write one WebSocket binary frame to POSIX socket [fd].
 * [mask] = true for client→server (required by spec).
 */
internal fun wsWriteFrame(fd: Int, data: ByteArray, mask: Boolean = false) {
    val len = data.size
    val maskBit: Int = if (mask) 0x80 else 0x00

    val headerBytes = when {
        len < 126 -> byteArrayOf(0x82.toByte(), (maskBit or len).toByte())
        len < 65536 -> byteArrayOf(0x82.toByte(), (maskBit or 126).toByte(), (len ushr 8 and 0xFF).toByte(), (len and 0xFF).toByte())
        else -> byteArrayOf(
            0x82.toByte(), (maskBit or 127).toByte(),
            0, 0, 0, 0,
            (len ushr 24 and 0xFF).toByte(), (len ushr 16 and 0xFF).toByte(),
            (len ushr 8 and 0xFF).toByte(), (len and 0xFF).toByte()
        )
    }
    headerBytes.usePinned { p -> send(fd, p.addressOf(0), headerBytes.size.convert(), 0) }

    if (mask) {
        val maskKey = ByteArray(4).also { kotlin.random.Random.nextBytes(it) }
        maskKey.usePinned { p -> send(fd, p.addressOf(0), 4.convert(), 0) }
        val masked = ByteArray(len) { i -> (data[i].toInt() xor maskKey[i % 4].toInt()).toByte() }
        masked.usePinned { p -> send(fd, p.addressOf(0), len.convert(), 0) }
    } else {
        data.usePinned { p -> send(fd, p.addressOf(0), len.convert(), 0) }
    }
}

internal fun wsSendClose(fd: Int) {
    val frame = byteArrayOf(0x88.toByte(), 0x00)
    runCatching { frame.usePinned { p -> send(fd, p.addressOf(0), 2.convert(), 0) } }
}
