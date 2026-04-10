@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.foodics.crosscommunicationlibrary.stomp

import kotlinx.cinterop.*
import platform.posix.*

internal const val STOMP_SERVICE_TYPE = "_foodics_stomp._tcp."
internal const val STOMP_DESTINATION = "/queue/foodics"

internal data class StompFrame(
    val command: String,
    val headers: Map<String, String>,
    val body: ByteArray
)

// ── Frame building ────────────────────────────────────────────────────────────

internal fun buildStompFrame(
    command: String,
    headers: Map<String, String> = emptyMap(),
    body: ByteArray = ByteArray(0)
): ByteArray {
    val sb = StringBuilder()
    sb.append(command).append('\n')
    headers.forEach { (k, v) -> sb.append("$k:$v\n") }
    if (body.isNotEmpty()) {
        sb.append("content-length:${body.size}\n")
        sb.append("content-type:application/octet-stream\n")
    }
    sb.append('\n')
    return sb.toString().encodeToByteArray() + body + byteArrayOf(0)
}

// ── Frame reading (byte-by-byte over POSIX fd) ────────────────────────────────

/**
 * Reads one STOMP 1.2 frame from a connected POSIX TCP socket [fd].
 * Reads bytes until the null (0x00) terminator required by the spec.
 * Skips leading null bytes (STOMP heartbeats).
 * Returns null on EOF or receive error.
 */
internal fun stompReadFrame(fd: Int): StompFrame? {
    val buf = mutableListOf<Byte>()
    val oneByte = ByteArray(1)
    while (true) {
        val n = oneByte.usePinned { p -> recv(fd, p.addressOf(0), 1.convert(), 0).toInt() }
        if (n <= 0) return null      // EOF or error
        val b = oneByte[0]
        if (b == 0.toByte()) {
            if (buf.isEmpty()) continue  // heartbeat null — skip
            break
        }
        buf.add(b)
    }
    return parseStompFrameBytes(buf.toByteArray())
}

private fun parseStompFrameBytes(raw: ByteArray): StompFrame? {
    var sepIdx = -1
    for (i in 0 until raw.size - 1) {
        if (raw[i] == '\n'.code.toByte() && raw[i + 1] == '\n'.code.toByte()) {
            sepIdx = i; break
        }
    }
    val headerText: String
    val rawBody: ByteArray
    if (sepIdx >= 0) {
        headerText = raw.copyOfRange(0, sepIdx).decodeToString()
        rawBody = if (sepIdx + 2 < raw.size) raw.copyOfRange(sepIdx + 2, raw.size) else ByteArray(0)
    } else {
        headerText = raw.decodeToString()
        rawBody = ByteArray(0)
    }

    val lines = headerText.split('\n')
    val command = lines.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val headers = mutableMapOf<String, String>()
    lines.drop(1).forEach { line ->
        val idx = line.indexOf(':')
        if (idx >= 0) headers[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
    }
    val contentLength = headers["content-length"]?.toIntOrNull()
    val body = if (contentLength != null && contentLength in 1..rawBody.size)
        rawBody.copyOfRange(0, contentLength) else rawBody
    return StompFrame(command, headers, body)
}

// ── Frame sending ─────────────────────────────────────────────────────────────

internal fun stompSendFrame(fd: Int, frame: ByteArray): Boolean {
    var sent = 0
    while (sent < frame.size) {
        val n = frame.usePinned { p ->
            send(fd, p.addressOf(sent), (frame.size - sent).convert(), 0).toInt()
        }
        if (n <= 0) return false
        sent += n
    }
    return true
}

// ── POSIX TCP helpers ─────────────────────────────────────────────────────────

internal fun stompHtons(v: UShort): UShort =
    ((v.toInt() and 0xFF shl 8) or (v.toInt() ushr 8 and 0xFF)).toUShort()

internal fun stompInetAddr(ip: String): UInt {
    val p = ip.split(".")
    if (p.size != 4) return 0u
    return ((p[3].toUInt() and 0xFFu) shl 24) or
            ((p[2].toUInt() and 0xFFu) shl 16) or
            ((p[1].toUInt() and 0xFFu) shl 8) or
            (p[0].toUInt() and 0xFFu)
}

internal fun stompGetBoundPort(fd: Int): Int = memScoped {
    val addr = alloc<sockaddr_in>()
    val len = alloc<socklen_tVar>()
    len.value = sizeOf<sockaddr_in>().convert()
    getsockname(fd, addr.ptr.reinterpret(), len.ptr)
    stompHtons(addr.sin_port).toInt()
}

/** Opens a TCP client socket connected to [ip]:[port]. Returns fd or -1. */
internal fun stompConnectTcp(ip: String, port: Int): Int = memScoped {
    val fd = socket(AF_INET, SOCK_STREAM, 0)
    if (fd < 0) return -1
    val addr = alloc<sockaddr_in>()
    addr.sin_family = AF_INET.convert()
    addr.sin_port = stompHtons(port.toUShort())
    addr.sin_addr.s_addr = stompInetAddr(ip)
    if (platform.posix.connect(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) != 0) {
        close(fd); return -1
    }
    // 5 s receive timeout so read loops stay interruptible
    val tv = alloc<timeval>()
    tv.tv_sec = 5; tv.tv_usec = 0
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, tv.ptr, sizeOf<timeval>().convert())
    fd
}

/**
 * Accepts one connection on [serverFd] with a [timeoutMs] timeout.
 * Returns client fd, or null on timeout / error.
 */
internal fun stompAcceptWithTimeout(serverFd: Int, timeoutMs: Int = 2_000): Int? = memScoped {
    val tv = alloc<timeval>()
    tv.tv_sec = (timeoutMs / 1000).convert()
    tv.tv_usec = ((timeoutMs % 1000) * 1000).convert()
    setsockopt(serverFd, SOL_SOCKET, SO_RCVTIMEO, tv.ptr, sizeOf<timeval>().convert())
    val cFd = accept(serverFd, null, null)
    if (cFd < 0) null else cFd
}
