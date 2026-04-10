@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.foodics.crosscommunicationlibrary.amqp

import kotlinx.cinterop.*
import platform.posix.*

// ── POSIX TCP helpers ─────────────────────────────────────────────────────────

private fun amqpHtons(v: UShort): UShort =
    ((v.toInt() and 0xFF shl 8) or (v.toInt() ushr 8 and 0xFF)).toUShort()

private fun amqpInetAddr(ip: String): UInt {
    val p = ip.split(".")
    if (p.size != 4) return 0u
    return ((p[3].toUInt() and 0xFFu) shl 24) or
            ((p[2].toUInt() and 0xFFu) shl 16) or
            ((p[1].toUInt() and 0xFFu) shl  8) or
             (p[0].toUInt() and 0xFFu)
}

/** Open a TCP connection to [host]:[port]. Returns fd ≥ 0 on success, -1 on failure. */
internal fun amqpTcpConnect(host: String, port: Int): Int = memScoped {
    val fd = socket(AF_INET, SOCK_STREAM, 0)
    if (fd < 0) return -1
    val addr = alloc<sockaddr_in>()
    addr.sin_family = AF_INET.convert()
    addr.sin_port   = amqpHtons(port.toUShort())
    addr.sin_addr.s_addr = amqpInetAddr(host)
    if (connect(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) != 0) {
        close(fd); return -1
    }
    fd
}

/** Send all bytes in [data]. Returns false on error. */
internal fun amqpSendAll(fd: Int, data: ByteArray): Boolean {
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

/** Receive exactly [n] bytes. Returns null on EOF/error. */
internal fun amqpRecvExact(fd: Int, n: Int): ByteArray? {
    val buf = ByteArray(n); var read = 0
    while (read < n) {
        val r = buf.usePinned { p -> recv(fd, p.addressOf(read), (n - read).convert(), 0).toInt() }
        if (r <= 0) return null
        read += r
    }
    return buf
}

/** Set SO_RCVTIMEO on [fd] to [timeoutMs] milliseconds. */
internal fun amqpSetTimeout(fd: Int, timeoutMs: Int) = memScoped {
    val tv = alloc<timeval>()
    tv.tv_sec  = (timeoutMs / 1000).convert()
    tv.tv_usec = ((timeoutMs % 1000) * 1000).convert()
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, tv.ptr, sizeOf<timeval>().convert())
}

// ── AMQP frame I/O ────────────────────────────────────────────────────────────

/** Read one complete AMQP frame from [fd]. Returns null on error. */
internal fun amqpReadFrame(fd: Int): AmqpFrame? {
    val h = amqpRecvExact(fd, 7) ?: return null
    val type = h[0].toInt() and 0xFF
    val ch   = ((h[1].toInt() and 0xFF) shl 8) or (h[2].toInt() and 0xFF)
    val size = ((h[3].toInt() and 0xFF) shl 24) or ((h[4].toInt() and 0xFF) shl 16) or
               ((h[5].toInt() and 0xFF) shl  8) or  (h[6].toInt() and 0xFF)
    val payload = amqpRecvExact(fd, size) ?: return null
    val end = amqpRecvExact(fd, 1) ?: return null
    if (end[0].toInt() and 0xFF != 0xCE) return null
    return AmqpFrame(type, ch, payload)
}

/** Send multiple byte-array frames. Returns false on error. */
internal fun amqpSendFrames(fd: Int, vararg frames: ByteArray): Boolean =
    frames.all { amqpSendAll(fd, it) }

// ── Handshake ─────────────────────────────────────────────────────────────────

internal data class AmqpIosConfig(val host: String, val port: Int, val user: String, val pass: String)

internal fun parseAmqpUrlIos(url: String): AmqpIosConfig {
    val s    = url.removePrefix("amqps://").removePrefix("amqp://")
    val at   = s.lastIndexOf('@')
    val cred = if (at >= 0) s.substring(0, at) else "guest:guest"
    val rest = if (at >= 0) s.substring(at + 1) else s
    val hp   = rest.substringBefore('/')
    val ci   = hp.lastIndexOf(':')
    val host = if (ci >= 0) hp.substring(0, ci) else hp
    val port = if (ci >= 0) hp.substring(ci + 1).toIntOrNull() ?: 5672 else 5672
    val ci2  = cred.indexOf(':')
    val user = if (ci2 >= 0) cred.substring(0, ci2) else cred
    val pass = if (ci2 >= 0) cred.substring(ci2 + 1) else ""
    return AmqpIosConfig(host, port, user, pass)
}

/**
 * Connect to AMQP broker and complete handshake (Connection + Channel 1 open).
 * Returns connected fd ≥ 0, or -1 on failure.
 */
internal fun amqpHandshakeIos(cfg: AmqpIosConfig): Int {
    val fd = amqpTcpConnect(cfg.host, cfg.port)
    if (fd < 0) { println("[AMQP] TCP connect failed"); return -1 }

    // Protocol header
    if (!amqpSendAll(fd, AMQP_PROTOCOL_HEADER)) { close(fd); return -1 }

    // Connection.Start (10,10)
    var f = amqpReadFrame(fd) ?: run { close(fd); return -1 }
    if (f.type != 1 || f.payload.amqpCM() != 10 to 10) { close(fd); return -1 }

    // Connection.StartOk
    if (!amqpSendAll(fd, amqpStartOk(cfg.user, cfg.pass))) { close(fd); return -1 }

    // Connection.Tune (10,30)
    f = amqpReadFrame(fd) ?: run { close(fd); return -1 }
    if (f.type != 1 || f.payload.amqpCM() != 10 to 30) { close(fd); return -1 }
    val (chMax, fMax) = amqpParseTune(f.payload)

    // Connection.TuneOk + Connection.Open
    if (!amqpSendFrames(fd, amqpTuneOk(chMax, fMax), amqpConnectionOpen())) { close(fd); return -1 }

    // Connection.OpenOk (10,41)
    f = amqpReadFrame(fd) ?: run { close(fd); return -1 }
    if (f.type != 1 || f.payload.amqpCM() != 10 to 41) { close(fd); return -1 }

    // Channel.Open → OpenOk (20,11)
    if (!amqpSendAll(fd, amqpChannelOpen())) { close(fd); return -1 }
    f = amqpReadFrame(fd) ?: run { close(fd); return -1 }
    if (f.type != 1 || f.payload.amqpCM() != 20 to 11) { close(fd); return -1 }

    println("[AMQP] Handshake complete, channel 1 open")
    return fd
}
