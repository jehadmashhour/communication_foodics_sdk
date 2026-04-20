@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.foodics.crosscommunicationlibrary.grpc

import kotlinx.cinterop.*
import platform.posix.*

private const val MAX_GRPC_FRAME = 16 * 1024 * 1024

// ── gRPC Length-Prefixed Message framing ──────────────────────────────────────
//   [0x00 : 1 byte]         compression flag (0 = none)
//   [length : 4 bytes BE]   payload byte count
//   [payload : N bytes]

internal fun grpcWriteFrame(fd: Int, data: ByteArray): Boolean {
    val len    = data.size
    val header = byteArrayOf(
        0x00,                                   // compression flag
        (len ushr 24 and 0xFF).toByte(),
        (len ushr 16 and 0xFF).toByte(),
        (len ushr 8  and 0xFF).toByte(),
        (len         and 0xFF).toByte()
    )
    return grpcSendAll(fd, header) && grpcSendAll(fd, data)
}

internal fun grpcReadFrame(fd: Int): ByteArray? {
    // Read the 5-byte header
    val header = grpcRecvExact(fd, 5) ?: return null
    // header[0] is the compression flag — skip compressed frames
    val len = ((header[1].toInt() and 0xFF) shl 24) or
              ((header[2].toInt() and 0xFF) shl 16) or
              ((header[3].toInt() and 0xFF) shl 8)  or
               (header[4].toInt() and 0xFF)
    if (len <= 0 || len > MAX_GRPC_FRAME) return null
    return grpcRecvExact(fd, len)
}

private fun grpcSendAll(fd: Int, data: ByteArray): Boolean {
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

private fun grpcRecvExact(fd: Int, n: Int): ByteArray? {
    val buf  = ByteArray(n)
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

internal fun grpcHtons(v: UShort): UShort =
    ((v.toInt() and 0xFF shl 8) or (v.toInt() ushr 8 and 0xFF)).toUShort()

internal fun grpcInetAddr(ip: String): UInt {
    val p = ip.split(".")
    if (p.size != 4) return 0u
    return ((p[3].toUInt() and 0xFFu) shl 24) or
            ((p[2].toUInt() and 0xFFu) shl 16) or
            ((p[1].toUInt() and 0xFFu) shl 8)  or
             (p[0].toUInt() and 0xFFu)
}

internal fun grpcGetBoundPort(fd: Int): Int = memScoped {
    val addr = alloc<sockaddr_in>()
    val len  = alloc<socklen_tVar>().also { it.value = sizeOf<sockaddr_in>().convert() }
    getsockname(fd, addr.ptr.reinterpret(), len.ptr)
    grpcHtons(addr.sin_port).toInt()
}

internal fun grpcAcceptWithTimeout(serverFd: Int, timeoutMs: Int = 2_000): Int? = memScoped {
    val tv = alloc<timeval>()
    tv.tv_sec  = (timeoutMs / 1000).convert()
    tv.tv_usec = ((timeoutMs % 1000) * 1000).convert()
    setsockopt(serverFd, SOL_SOCKET, SO_RCVTIMEO, tv.ptr, sizeOf<timeval>().convert())
    val cFd = accept(serverFd, null, null)
    if (cFd < 0) null else cFd
}

internal fun grpcConnect(ip: String, port: Int): Int = memScoped {
    val fd = socket(AF_INET, SOCK_STREAM, 0)
    if (fd < 0) return -1
    val addr = alloc<sockaddr_in>()
    addr.sin_family      = AF_INET.convert()
    addr.sin_port        = grpcHtons(port.toUShort())
    addr.sin_addr.s_addr = grpcInetAddr(ip)
    if (connect(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) != 0) {
        close(fd); return -1
    }
    fd
}
