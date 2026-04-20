@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.foodics.crosscommunicationlibrary.zmq

import kotlinx.cinterop.*
import platform.posix.*

// ── TCP helpers ───────────────────────────────────────────────────────────────

private fun zmqHtons(v: UShort): UShort =
    ((v.toInt() and 0xFF shl 8) or (v.toInt() ushr 8 and 0xFF)).toUShort()

private fun zmqInetAddr(ip: String): UInt {
    val p = ip.split(".")
    if (p.size != 4) return 0u
    return ((p[3].toUInt() and 0xFFu) shl 24) or
           ((p[2].toUInt() and 0xFFu) shl 16) or
           ((p[1].toUInt() and 0xFFu) shl  8) or
            (p[0].toUInt() and 0xFFu)
}

/** Connect to [host]:[port]. Returns fd ≥ 0 or -1. */
internal fun zmqTcpConnect(host: String, port: Int): Int = memScoped {
    val actualHost = if (host == "localhost") "127.0.0.1" else host
    val fd = socket(AF_INET, SOCK_STREAM, 0)
    if (fd < 0) return -1
    val addr = alloc<sockaddr_in>()
    addr.sin_family      = AF_INET.convert()
    addr.sin_port        = zmqHtons(port.toUShort())
    addr.sin_addr.s_addr = zmqInetAddr(actualHost)
    if (connect(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) != 0) {
        close(fd); return -1
    }
    fd
}

/** Bind a TCP server socket on an ephemeral port. Returns (fd, port) or (-1, 0). */
internal fun zmqTcpBind(): Pair<Int, Int> = memScoped {
    val fd = socket(AF_INET, SOCK_STREAM, 0)
    if (fd < 0) return -1 to 0
    val one = alloc<IntVar>().apply { value = 1 }
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, one.ptr, sizeOf<IntVar>().convert())
    val addr = alloc<sockaddr_in>()
    addr.sin_family      = AF_INET.convert()
    addr.sin_port        = zmqHtons(0u)          // ephemeral
    addr.sin_addr.s_addr = INADDR_ANY
    if (bind(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) < 0) {
        close(fd); return -1 to 0
    }
    listen(fd, 5)
    val port = zmqGetPort(fd)
    fd to port
}

/** Read the bound port from a server socket fd. */
internal fun zmqGetPort(fd: Int): Int = memScoped {
    val addr = alloc<sockaddr_in>()
    val len  = alloc<socklen_tVar>().apply { value = sizeOf<sockaddr_in>().convert() }
    getsockname(fd, addr.ptr.reinterpret(), len.ptr)
    val p = addr.sin_port.toInt()
    ((p and 0xFF) shl 8) or ((p ushr 8) and 0xFF)
}

/** Accept one connection with a 1-second timeout loop. Returns fd or -1. */
internal fun zmqAccept(serverFd: Int): Int = memScoped {
    val tv = alloc<timeval>()
    tv.tv_sec = 1; tv.tv_usec = 0
    setsockopt(serverFd, SOL_SOCKET, SO_RCVTIMEO, tv.ptr, sizeOf<timeval>().convert())
    accept(serverFd, null, null)
}

/** Set SO_RCVTIMEO on [fd] to [ms] milliseconds. */
internal fun zmqSetTimeout(fd: Int, ms: Int) = memScoped {
    val tv = alloc<timeval>()
    tv.tv_sec  = (ms / 1000).convert()
    tv.tv_usec = ((ms % 1000) * 1000).convert()
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, tv.ptr, sizeOf<timeval>().convert())
}

internal fun zmqSendAll(fd: Int, data: ByteArray): Boolean {
    var sent = 0
    while (sent < data.size) {
        val n = data.usePinned { p -> send(fd, p.addressOf(sent), (data.size - sent).convert(), 0).toInt() }
        if (n <= 0) return false
        sent += n
    }
    return true
}

internal fun zmqRecvExact(fd: Int, n: Int): ByteArray? {
    if (n == 0) return ByteArray(0)
    val buf = ByteArray(n); var read = 0
    while (read < n) {
        val r = buf.usePinned { p -> recv(fd, p.addressOf(read), (n - read).convert(), 0).toInt() }
        if (r <= 0) return null
        read += r
    }
    return buf
}

// ── ZMTP frame read/write ─────────────────────────────────────────────────────

internal fun zmqReadFrame(fd: Int): Pair<Boolean, ByteArray>? {
    val flagsBuf = zmqRecvExact(fd, 1) ?: return null
    val flags    = flagsBuf[0].toInt() and 0xFF
    val isCommand = (flags and 0x04) != 0
    val isLong    = (flags and 0x02) != 0
    val bodySize: Long = if (!isLong) {
        (zmqRecvExact(fd, 1) ?: return null)[0].toLong() and 0xFF
    } else {
        val lenBuf = zmqRecvExact(fd, 8) ?: return null
        var s = 0L
        for (b in lenBuf) s = (s shl 8) or (b.toLong() and 0xFF)
        s
    }
    val body = zmqRecvExact(fd, bodySize.toInt()) ?: return null
    return isCommand to body
}

/**
 * Perform the ZMTP 3.1 handshake (greeting + READY exchange).
 * Returns true on success.
 */
internal fun zmqHandshake(fd: Int, asServer: Boolean): Boolean {
    return try {
        zmqSendAll(fd, zmtpGreeting(asServer))
        zmqRecvExact(fd, 64) ?: return false     // peer greeting
        zmqSendAll(fd, zmtpReadyFrame())
        val (isCmd, _) = zmqReadFrame(fd) ?: return false
        isCmd
    } catch (_: Exception) { false }
}

// ── UDP broadcast helpers ─────────────────────────────────────────────────────

private const val UDP_BROADCAST_ADDR = "255.255.255.255"

/** Open a UDP socket for sending broadcasts. Returns fd or -1. */
internal fun zmqUdpSenderFd(): Int = memScoped {
    val fd = socket(AF_INET, SOCK_DGRAM, 0)
    if (fd < 0) return -1
    val one = alloc<IntVar>().apply { value = 1 }
    setsockopt(fd, SOL_SOCKET, SO_BROADCAST, one.ptr, sizeOf<IntVar>().convert())
    fd
}

/** Open a UDP socket bound to [port] for receiving broadcasts. Returns fd or -1. */
internal fun zmqUdpReceiverFd(port: Int): Int = memScoped {
    val fd = socket(AF_INET, SOCK_DGRAM, 0)
    if (fd < 0) return -1
    val one = alloc<IntVar>().apply { value = 1 }
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, one.ptr, sizeOf<IntVar>().convert())
    setsockopt(fd, SOL_SOCKET, SO_BROADCAST, one.ptr, sizeOf<IntVar>().convert())
    val addr = alloc<sockaddr_in>()
    addr.sin_family      = AF_INET.convert()
    addr.sin_port        = zmqHtons(port.toUShort())
    addr.sin_addr.s_addr = INADDR_ANY
    if (bind(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) < 0) {
        close(fd); return -1
    }
    val tv = alloc<timeval>(); tv.tv_sec = 2; tv.tv_usec = 0
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, tv.ptr, sizeOf<timeval>().convert())
    fd
}

/** Send [data] as a UDP broadcast to [port]. */
internal fun zmqUdpBroadcast(senderFd: Int, data: ByteArray, port: Int) = memScoped {
    val addr = alloc<sockaddr_in>()
    addr.sin_family      = AF_INET.convert()
    addr.sin_port        = zmqHtons(port.toUShort())
    addr.sin_addr.s_addr = zmqInetAddr(UDP_BROADCAST_ADDR)
    data.usePinned { p ->
        sendto(senderFd, p.addressOf(0), data.size.convert(), 0,
               addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
    }
}

/** Receive one UDP datagram. Returns (senderIp, data) or null on timeout/error. */
internal fun zmqUdpReceive(receiverFd: Int): Pair<String, ByteArray>? = memScoped {
    val senderAddr = alloc<sockaddr_in>()
    val senderLen  = alloc<socklen_tVar>().apply { value = sizeOf<sockaddr_in>().convert() }
    val buf        = ByteArray(512)
    val n = buf.usePinned { p ->
        recvfrom(receiverFd, p.addressOf(0), 512u.convert(), 0,
                 senderAddr.ptr.reinterpret(), senderLen.ptr).toInt()
    }
    if (n <= 0) return null
    // Extract sender IP from sin_addr.s_addr (little-endian stored)
    val s = senderAddr.sin_addr.s_addr.toInt()
    val ip = "${s and 0xFF}.${(s ushr 8) and 0xFF}.${(s ushr 16) and 0xFF}.${(s ushr 24) and 0xFF}"
    ip to buf.copyOf(n)
}
