@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.foodics.crosscommunicationlibrary.modbus_tcp

import kotlinx.cinterop.*
import platform.posix.*

// ── TCP helpers ───────────────────────────────────────────────────────────────

private fun modbusHtons(v: UShort): UShort =
    ((v.toInt() and 0xFF shl 8) or (v.toInt() ushr 8 and 0xFF)).toUShort()

private fun modbusInetAddr(ip: String): UInt {
    val p = ip.split(".")
    if (p.size != 4) return 0u
    return ((p[3].toUInt() and 0xFFu) shl 24) or
           ((p[2].toUInt() and 0xFFu) shl 16) or
           ((p[1].toUInt() and 0xFFu) shl  8) or
            (p[0].toUInt() and 0xFFu)
}

internal fun modbusTcpConnect(host: String, port: Int): Int = memScoped {
    val actualHost = if (host == "localhost") "127.0.0.1" else host
    val fd = socket(AF_INET, SOCK_STREAM, 0)
    if (fd < 0) return -1
    val addr = alloc<sockaddr_in>()
    addr.sin_family      = AF_INET.convert()
    addr.sin_port        = modbusHtons(port.toUShort())
    addr.sin_addr.s_addr = modbusInetAddr(actualHost)
    if (connect(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) != 0) {
        close(fd); return -1
    }
    fd
}

internal fun modbusTcpBind(): Pair<Int, Int> = memScoped {
    val fd = socket(AF_INET, SOCK_STREAM, 0)
    if (fd < 0) return -1 to 0
    val one = alloc<IntVar>().apply { value = 1 }
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, one.ptr, sizeOf<IntVar>().convert())
    val addr = alloc<sockaddr_in>()
    addr.sin_family      = AF_INET.convert()
    addr.sin_port        = modbusHtons(0u)
    addr.sin_addr.s_addr = INADDR_ANY
    if (bind(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) < 0) {
        close(fd); return -1 to 0
    }
    listen(fd, 5)
    fd to modbusGetPort(fd)
}

internal fun modbusGetPort(fd: Int): Int = memScoped {
    val addr = alloc<sockaddr_in>()
    val len  = alloc<socklen_tVar>().apply { value = sizeOf<sockaddr_in>().convert() }
    getsockname(fd, addr.ptr.reinterpret(), len.ptr)
    val p = addr.sin_port.toInt()
    ((p and 0xFF) shl 8) or ((p ushr 8) and 0xFF)
}

internal fun modbusAccept(serverFd: Int): Int = memScoped {
    val tv = alloc<timeval>(); tv.tv_sec = 1; tv.tv_usec = 0
    setsockopt(serverFd, SOL_SOCKET, SO_RCVTIMEO, tv.ptr, sizeOf<timeval>().convert())
    accept(serverFd, null, null)
}

internal fun modbusSetTimeout(fd: Int, ms: Int) = memScoped {
    val tv = alloc<timeval>()
    tv.tv_sec  = (ms / 1000).convert()
    tv.tv_usec = ((ms % 1000) * 1000).convert()
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, tv.ptr, sizeOf<timeval>().convert())
}

internal fun modbusSendAll(fd: Int, data: ByteArray): Boolean {
    var sent = 0
    while (sent < data.size) {
        val n = data.usePinned { p -> send(fd, p.addressOf(sent), (data.size - sent).convert(), 0).toInt() }
        if (n <= 0) return false
        sent += n
    }
    return true
}

internal fun modbusRecvExact(fd: Int, n: Int): ByteArray? {
    if (n == 0) return ByteArray(0)
    val buf = ByteArray(n); var read = 0
    while (read < n) {
        val r = buf.usePinned { p -> recv(fd, p.addressOf(read), (n - read).convert(), 0).toInt() }
        if (r <= 0) return null
        read += r
    }
    return buf
}

// ── MBAP frame read ───────────────────────────────────────────────────────────

internal fun modbusReadFrame(fd: Int): ModbusFrame? {
    val header = modbusRecvExact(fd, 6) ?: return null
    val txId   = ((header[0].toInt() and 0xFF) shl 8) or (header[1].toInt() and 0xFF)
    val length = ((header[4].toInt() and 0xFF) shl 8) or (header[5].toInt() and 0xFF)
    if (length < 2) return null
    val pdu  = modbusRecvExact(fd, length) ?: return null
    val uid  = pdu[0]
    val fc   = pdu[1]
    val data = if (pdu.size > 2) pdu.sliceArray(2 until pdu.size) else ByteArray(0)
    return ModbusFrame(txId, uid, fc, data)
}

// ── UDP broadcast helpers ─────────────────────────────────────────────────────

internal fun modbusUdpSenderFd(): Int = memScoped {
    val fd = socket(AF_INET, SOCK_DGRAM, 0)
    if (fd < 0) return -1
    val one = alloc<IntVar>().apply { value = 1 }
    setsockopt(fd, SOL_SOCKET, SO_BROADCAST, one.ptr, sizeOf<IntVar>().convert())
    fd
}

internal fun modbusUdpReceiverFd(port: Int): Int = memScoped {
    val fd = socket(AF_INET, SOCK_DGRAM, 0)
    if (fd < 0) return -1
    val one = alloc<IntVar>().apply { value = 1 }
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, one.ptr, sizeOf<IntVar>().convert())
    setsockopt(fd, SOL_SOCKET, SO_BROADCAST, one.ptr, sizeOf<IntVar>().convert())
    val addr = alloc<sockaddr_in>()
    addr.sin_family      = AF_INET.convert()
    addr.sin_port        = modbusHtons(port.toUShort())
    addr.sin_addr.s_addr = INADDR_ANY
    if (bind(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) < 0) {
        close(fd); return -1
    }
    val tv = alloc<timeval>(); tv.tv_sec = 2; tv.tv_usec = 0
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, tv.ptr, sizeOf<timeval>().convert())
    fd
}

internal fun modbusUdpBroadcast(senderFd: Int, data: ByteArray, port: Int) = memScoped {
    val addr = alloc<sockaddr_in>()
    addr.sin_family      = AF_INET.convert()
    addr.sin_port        = modbusHtons(port.toUShort())
    addr.sin_addr.s_addr = modbusInetAddr("255.255.255.255")
    data.usePinned { p ->
        sendto(senderFd, p.addressOf(0), data.size.convert(), 0,
               addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
    }
}

internal fun modbusUdpReceive(fd: Int): Pair<String, ByteArray>? = memScoped {
    val senderAddr = alloc<sockaddr_in>()
    val senderLen  = alloc<socklen_tVar>().apply { value = sizeOf<sockaddr_in>().convert() }
    val buf        = ByteArray(512)
    val n = buf.usePinned { p ->
        recvfrom(fd, p.addressOf(0), 512u.convert(), 0,
                 senderAddr.ptr.reinterpret(), senderLen.ptr).toInt()
    }
    if (n <= 0) return null
    val s  = senderAddr.sin_addr.s_addr.toInt()
    val ip = "${s and 0xFF}.${(s ushr 8) and 0xFF}.${(s ushr 16) and 0xFF}.${(s ushr 24) and 0xFF}"
    ip to buf.copyOf(n)
}
