@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.foodics.crosscommunicationlibrary.http

import kotlinx.cinterop.*
import platform.posix.*

internal const val HTTP_SERVICE_TYPE = "_foodics_http._tcp."

// ── Network byte-order helpers ────────────────────────────────────────────────

internal fun httpHtons(v: UShort): UShort =
    ((v.toInt() and 0xFF shl 8) or (v.toInt() ushr 8 and 0xFF)).toUShort()

internal fun httpInetAddr(ip: String): UInt {
    val p = ip.split(".")
    if (p.size != 4) return 0u
    return ((p[3].toUInt() and 0xFFu) shl 24) or
           ((p[2].toUInt() and 0xFFu) shl 16) or
           ((p[1].toUInt() and 0xFFu) shl 8)  or
            (p[0].toUInt() and 0xFFu)
}

internal fun httpAddrToString(addr: UInt): String {
    val b0 = (addr and 0xFFu).toInt()
    val b1 = ((addr shr 8) and 0xFFu).toInt()
    val b2 = ((addr shr 16) and 0xFFu).toInt()
    val b3 = ((addr shr 24) and 0xFFu).toInt()
    return "$b0.$b1.$b2.$b3"
}

/** Returns the device's primary non-loopback IPv4 address. */
internal fun httpGetLocalIp(): String = memScoped {
    val sock = socket(AF_INET, SOCK_DGRAM, 0)
    if (sock < 0) return "0.0.0.0"
    val remote = alloc<sockaddr_in>()
    remote.sin_family = AF_INET.convert()
    remote.sin_port = httpHtons(80u)
    remote.sin_addr.s_addr = httpInetAddr("8.8.8.8")
    platform.posix.connect(sock, remote.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
    val local = alloc<sockaddr_in>()
    val len = alloc<socklen_tVar>()
    len.value = sizeOf<sockaddr_in>().convert()
    getsockname(sock, local.ptr.reinterpret(), len.ptr)
    close(sock)
    httpAddrToString(local.sin_addr.s_addr)
}

/** Returns the port that the bound TCP server socket is actually listening on. */
internal fun httpGetServerPort(fd: Int): Int = memScoped {
    val addr = alloc<sockaddr_in>()
    val len = alloc<socklen_tVar>()
    len.value = sizeOf<sockaddr_in>().convert()
    getsockname(fd, addr.ptr.reinterpret(), len.ptr)
    httpHtons(addr.sin_port).toInt()
}

/**
 * Accept one connection with a [timeoutMs] receive deadline.
 * Returns null on timeout or error.
 */
internal fun httpAcceptWithTimeout(serverFd: Int, timeoutMs: Int): Int? = memScoped {
    val tv = alloc<timeval>()
    tv.tv_sec = (timeoutMs / 1000).convert()
    tv.tv_usec = ((timeoutMs % 1000) * 1000).convert()
    setsockopt(serverFd, SOL_SOCKET, SO_RCVTIMEO, tv.ptr, sizeOf<timeval>().convert())
    val cFd = accept(serverFd, null, null)
    if (cFd < 0) null else cFd
}

/**
 * Read the full HTTP request from [fd] (headers + body).
 * Returns (headers, body) or null on error.
 */
internal fun httpReadRequest(fd: Int): Pair<Map<String, String>, ByteArray>? {
    // Read headers byte-by-byte until \r\n\r\n
    val headerBytes = mutableListOf<Byte>()
    val oneByte = ByteArray(1)
    while (true) {
        val n = oneByte.usePinned { p -> recv(fd, p.addressOf(0), 1.convert(), 0).toInt() }
        if (n <= 0) return null
        headerBytes.add(oneByte[0])
        val sz = headerBytes.size
        if (sz >= 4 &&
            headerBytes[sz - 4] == '\r'.code.toByte() &&
            headerBytes[sz - 3] == '\n'.code.toByte() &&
            headerBytes[sz - 2] == '\r'.code.toByte() &&
            headerBytes[sz - 1] == '\n'.code.toByte()) break
    }

    val headers = mutableMapOf<String, String>()
    headerBytes.toByteArray().decodeToString().lines().drop(1).forEach { line ->
        val idx = line.indexOf(':')
        if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
    }

    val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
    val body = if (contentLength > 0) {
        val buf = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = buf.usePinned { p ->
                recv(fd, p.addressOf(read), (contentLength - read).convert(), 0).toInt()
            }
            if (n <= 0) break
            read += n
        }
        buf.copyOf(read)
    } else ByteArray(0)

    return headers to body
}

/** Send an HTTP 200 OK response with binary [body]. */
internal fun httpSendResponse(fd: Int, body: ByteArray) {
    val header = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        .encodeToByteArray()
    header.usePinned { p -> send(fd, p.addressOf(0), header.size.convert(), 0) }
    if (body.isNotEmpty()) body.usePinned { p -> send(fd, p.addressOf(0), body.size.convert(), 0) }
}

/**
 * Send an HTTP POST to [ip]:[port]/message with [body] and return the response body.
 * Returns null on connection or protocol error.
 */
internal fun httpSendPost(ip: String, port: Int, body: ByteArray): ByteArray? {
    val fd = socket(AF_INET, SOCK_STREAM, 0)
    if (fd < 0) return null

    val connected = memScoped {
        val addr = alloc<sockaddr_in>()
        addr.sin_family = AF_INET.convert()
        addr.sin_port = httpHtons(port.toUShort())
        addr.sin_addr.s_addr = httpInetAddr(ip)
        platform.posix.connect(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) == 0
    }
    if (!connected) { close(fd); return null }

    val headerStr = "POST /message HTTP/1.1\r\nHost: $ip:$port\r\nContent-Type: application/octet-stream\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
    val headerBytes = headerStr.encodeToByteArray()
    headerBytes.usePinned { p -> send(fd, p.addressOf(0), headerBytes.size.convert(), 0) }
    if (body.isNotEmpty()) body.usePinned { p -> send(fd, p.addressOf(0), body.size.convert(), 0) }

    // Read full response into a list of chunks
    val chunks = mutableListOf<ByteArray>()
    val buf = ByteArray(4096)
    while (true) {
        val n = buf.usePinned { p -> recv(fd, p.addressOf(0), 4096.convert(), 0).toInt() }
        if (n <= 0) break
        chunks.add(buf.copyOf(n))
    }
    close(fd)
    if (chunks.isEmpty()) return ByteArray(0)

    // Flatten chunks and strip HTTP headers
    val total = chunks.sumOf { it.size }
    val raw = ByteArray(total)
    var pos = 0
    for (chunk in chunks) { chunk.copyInto(raw, pos); pos += chunk.size }

    // Find \r\n\r\n separator
    for (i in 0 until raw.size - 3) {
        if (raw[i] == '\r'.code.toByte() && raw[i + 1] == '\n'.code.toByte() &&
            raw[i + 2] == '\r'.code.toByte() && raw[i + 3] == '\n'.code.toByte()) {
            return if (i + 4 < raw.size) raw.copyOfRange(i + 4, raw.size) else ByteArray(0)
        }
    }
    return ByteArray(0)
}
