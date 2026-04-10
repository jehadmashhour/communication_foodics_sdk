@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.foodics.crosscommunicationlibrary.tcp

import kotlinx.cinterop.*
import platform.posix.*

internal const val TCP_SERVICE_TYPE = "_foodics_tcpsock._tcp."

private const val MAX_FRAME_BYTES = 16 * 1024 * 1024

// ── Frame I/O ─────────────────────────────────────────────────────────────────

/**
 * Writes one length-prefixed frame to [fd]: [4-byte BE length][payload].
 * Returns false if any send fails.
 */
internal fun tcpWriteFrame(fd: Int, data: ByteArray): Boolean {
    val len = data.size
    val header = byteArrayOf(
        (len ushr 24 and 0xFF).toByte(),
        (len ushr 16 and 0xFF).toByte(),
        (len ushr 8  and 0xFF).toByte(),
        (len         and 0xFF).toByte()
    )
    return tcpSendAll(fd, header) && tcpSendAll(fd, data)
}

/**
 * Reads one length-prefixed frame from [fd].
 * Returns null on EOF, error, or oversized frame.
 */
internal fun tcpReadFrame(fd: Int): ByteArray? {
    val header = tcpRecvExact(fd, 4) ?: return null
    val len = ((header[0].toInt() and 0xFF) shl 24) or
              ((header[1].toInt() and 0xFF) shl 16) or
              ((header[2].toInt() and 0xFF) shl 8)  or
               (header[3].toInt() and 0xFF)
    if (len <= 0 || len > MAX_FRAME_BYTES) return null
    return tcpRecvExact(fd, len)
}

/** Sends all bytes in [data], handling partial writes. Returns false on error. */
private fun tcpSendAll(fd: Int, data: ByteArray): Boolean {
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

/** Reads exactly [n] bytes, handling partial reads. Returns null on EOF/error. */
private fun tcpRecvExact(fd: Int, n: Int): ByteArray? {
    val buf = ByteArray(n)
    var read = 0
    while (read < n) {
        val r = buf.usePinned { p ->
            recv(fd, p.addressOf(read), (n - read).convert(), 0).toInt()
        }
        if (r <= 0) return null
        read += r
    }
    return buf
}

// ── Socket helpers ────────────────────────────────────────────────────────────

internal fun tcpHtons(v: UShort): UShort =
    ((v.toInt() and 0xFF shl 8) or (v.toInt() ushr 8 and 0xFF)).toUShort()

internal fun tcpInetAddr(ip: String): UInt {
    val p = ip.split(".")
    if (p.size != 4) return 0u
    return ((p[3].toUInt() and 0xFFu) shl 24) or
            ((p[2].toUInt() and 0xFFu) shl 16) or
            ((p[1].toUInt() and 0xFFu) shl 8)  or
             (p[0].toUInt() and 0xFFu)
}

internal fun tcpGetBoundPort(fd: Int): Int = memScoped {
    val addr = alloc<sockaddr_in>()
    val len = alloc<socklen_tVar>()
    len.value = sizeOf<sockaddr_in>().convert()
    getsockname(fd, addr.ptr.reinterpret(), len.ptr)
    tcpHtons(addr.sin_port).toInt()
}

/** Accept one connection with a timeout. Returns client fd or null on timeout/error. */
internal fun tcpAcceptWithTimeout(serverFd: Int, timeoutMs: Int = 2_000): Int? = memScoped {
    val tv = alloc<timeval>()
    tv.tv_sec = (timeoutMs / 1000).convert()
    tv.tv_usec = ((timeoutMs % 1000) * 1000).convert()
    setsockopt(serverFd, SOL_SOCKET, SO_RCVTIMEO, tv.ptr, sizeOf<timeval>().convert())
    val cFd = accept(serverFd, null, null)
    if (cFd < 0) null else cFd
}

/** Open a TCP client socket connected to [ip]:[port]. Returns fd or -1 on failure. */
internal fun tcpConnect(ip: String, port: Int): Int = memScoped {
    val fd = socket(AF_INET, SOCK_STREAM, 0)
    if (fd < 0) return -1
    val addr = alloc<sockaddr_in>()
    addr.sin_family = AF_INET.convert()
    addr.sin_port = tcpHtons(port.toUShort())
    addr.sin_addr.s_addr = tcpInetAddr(ip)
    if (platform.posix.connect(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) != 0) {
        close(fd); return -1
    }
    fd
}
