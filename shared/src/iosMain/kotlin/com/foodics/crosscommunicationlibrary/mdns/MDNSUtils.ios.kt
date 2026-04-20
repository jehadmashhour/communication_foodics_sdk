@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.foodics.crosscommunicationlibrary.mdns

import kotlinx.cinterop.*
import platform.posix.*

private const val MAX_MDNS_FRAME = 16 * 1024 * 1024

// ── Frame I/O ─────────────────────────────────────────────────────────────────

internal fun mdnsWriteFrame(fd: Int, data: ByteArray): Boolean {
    val len = data.size
    val header = byteArrayOf(
        (len ushr 24 and 0xFF).toByte(),
        (len ushr 16 and 0xFF).toByte(),
        (len ushr 8  and 0xFF).toByte(),
        (len         and 0xFF).toByte()
    )
    return mdnsSendAll(fd, header) && mdnsSendAll(fd, data)
}

internal fun mdnsReadFrame(fd: Int): ByteArray? {
    val header = mdnsRecvExact(fd, 4) ?: return null
    val len = ((header[0].toInt() and 0xFF) shl 24) or
              ((header[1].toInt() and 0xFF) shl 16) or
              ((header[2].toInt() and 0xFF) shl 8)  or
               (header[3].toInt() and 0xFF)
    if (len <= 0 || len > MAX_MDNS_FRAME) return null
    return mdnsRecvExact(fd, len)
}

private fun mdnsSendAll(fd: Int, data: ByteArray): Boolean {
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

private fun mdnsRecvExact(fd: Int, n: Int): ByteArray? {
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

internal fun mdnsHtons(v: UShort): UShort =
    ((v.toInt() and 0xFF shl 8) or (v.toInt() ushr 8 and 0xFF)).toUShort()

internal fun mdnsInetAddr(ip: String): UInt {
    val p = ip.split(".")
    if (p.size != 4) return 0u
    return ((p[3].toUInt() and 0xFFu) shl 24) or
            ((p[2].toUInt() and 0xFFu) shl 16) or
            ((p[1].toUInt() and 0xFFu) shl 8)  or
             (p[0].toUInt() and 0xFFu)
}

internal fun mdnsGetBoundPort(fd: Int): Int = memScoped {
    val addr = alloc<sockaddr_in>()
    val len  = alloc<socklen_tVar>().also { it.value = sizeOf<sockaddr_in>().convert() }
    getsockname(fd, addr.ptr.reinterpret(), len.ptr)
    mdnsHtons(addr.sin_port).toInt()
}

internal fun mdnsAcceptWithTimeout(serverFd: Int, timeoutMs: Int = 2_000): Int? = memScoped {
    val tv = alloc<timeval>()
    tv.tv_sec  = (timeoutMs / 1000).convert()
    tv.tv_usec = ((timeoutMs % 1000) * 1000).convert()
    setsockopt(serverFd, SOL_SOCKET, SO_RCVTIMEO, tv.ptr, sizeOf<timeval>().convert())
    val cFd = accept(serverFd, null, null)
    if (cFd < 0) null else cFd
}

internal fun mdnsConnect(ip: String, port: Int): Int = memScoped {
    val fd = socket(AF_INET, SOCK_STREAM, 0)
    if (fd < 0) return -1
    val addr = alloc<sockaddr_in>()
    addr.sin_family    = AF_INET.convert()
    addr.sin_port      = mdnsHtons(port.toUShort())
    addr.sin_addr.s_addr = mdnsInetAddr(ip)
    if (connect(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) != 0) {
        close(fd); return -1
    }
    fd
}
