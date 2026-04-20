package com.foodics.crosscommunicationlibrary.zmq

import java.io.BufferedInputStream
import java.io.OutputStream

// ── ZMTP send helpers ─────────────────────────────────────────────────────────

@Synchronized internal fun OutputStream.zmtpSend(data: ByteArray) { write(data); flush() }

// ── ZMTP read helpers ─────────────────────────────────────────────────────────

internal fun BufferedInputStream.readExactZmtp(n: Int): ByteArray? {
    if (n == 0) return ByteArray(0)
    val buf = ByteArray(n); var r = 0
    while (r < n) { val c = read(buf, r, n - r); if (c <= 0) return null; r += c }
    return buf
}

/**
 * Read one ZMTP frame. Returns (isCommand, body) or null on EOF / error.
 * Handles both short frames (1-byte size) and long frames (8-byte size).
 */
internal fun BufferedInputStream.readZmtpFrame(): Pair<Boolean, ByteArray>? {
    val flagsByte = read(); if (flagsByte < 0) return null
    val isCommand = (flagsByte and 0x04) != 0
    val isLong    = (flagsByte and 0x02) != 0
    val bodySize: Long = if (!isLong) {
        val b = read(); if (b < 0) return null; b.toLong()
    } else {
        var s = 0L; repeat(8) { s = (s shl 8) or read().toLong() }; s
    }
    val body = readExactZmtp(bodySize.toInt()) ?: return null
    return isCommand to body
}

/**
 * Perform the full ZMTP 3.1 handshake (greeting + READY exchange).
 * Returns true if both sides are ready to exchange messages.
 */
internal fun zmtpHandshake(input: BufferedInputStream, output: OutputStream, asServer: Boolean): Boolean {
    return try {
        output.zmtpSend(zmtpGreeting(asServer))
        readExactZmtp(input, 64) ?: return false   // peer greeting (ignored for NULL mechanism)
        output.zmtpSend(zmtpReadyFrame())
        val (isCmd, _) = input.readZmtpFrame() ?: return false
        isCmd // peer READY command received
    } catch (_: Exception) { false }
}

private fun readExactZmtp(input: BufferedInputStream, n: Int): ByteArray? = input.readExactZmtp(n)
