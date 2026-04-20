package com.foodics.crosscommunicationlibrary.zmq

/**
 * ZMTP 3.1 framing helpers — shared by Android and iOS.
 *
 * Greeting (64 bytes, each side sends immediately on connect):
 *   [0xFF][0x00*8][0x7F]   — signature (10 bytes)
 *   [0x03][0x01]           — version 3.1 (2 bytes)
 *   ["NULL\0*16"]          — mechanism, padded to 20 bytes
 *   [0x00|0x01]            — as-server flag (1 byte)
 *   [0x00*31]              — filler (31 bytes)
 *
 * Command frame (READY):
 *   [0x04][len][body]  where body = [nameLen]["READY"][propNameLen][propName][4-byte valLen][value]
 *
 * Message frame (short):   [0x00][len ≤ 255][data]
 * Message frame (long):    [0x02][len as 8-byte BE][data]
 *
 * Flags:  bit 0 = MORE, bit 1 = LONG, bit 2 = COMMAND
 */

internal const val ZMQ_BEACON_INTERVAL_MS = 2_000L
internal const val ZMQ_DEVICE_TTL_MS      = 10_000L

// ── Greeting ──────────────────────────────────────────────────────────────────

internal fun zmtpGreeting(asServer: Boolean): ByteArray = ByteArray(64).also { g ->
    g[0] = 0xFF.toByte()
    // bytes 1–8 are 0x00
    g[9] = 0x7F.toByte()
    g[10] = 3        // major version
    g[11] = 1        // minor version
    // mechanism: "NULL" padded to 20 bytes (bytes 12–31)
    val mech = "NULL".encodeToByteArray()
    mech.copyInto(g, 12)
    g[32] = if (asServer) 1 else 0 // as-server flag
    // bytes 33–63 stay 0x00 (filler)
}

// ── READY command ─────────────────────────────────────────────────────────────

private val ZMTP_READY_BODY: ByteArray = run {
    val cmdName  = "READY".encodeToByteArray()        // 5 bytes
    val propName = "Socket-Type".encodeToByteArray()  // 11 bytes
    val propVal  = "PAIR".encodeToByteArray()          // 4 bytes
    val buf = ByteArray(1 + cmdName.size + 1 + propName.size + 4 + propVal.size)
    var i = 0
    buf[i++] = cmdName.size.toByte()
    cmdName.copyInto(buf, i); i += cmdName.size
    buf[i++] = propName.size.toByte()
    propName.copyInto(buf, i); i += propName.size
    buf[i++] = 0; buf[i++] = 0; buf[i++] = 0; buf[i++] = propVal.size.toByte()
    propVal.copyInto(buf, i)
    buf
}

/** Short command frame carrying the READY handshake. */
internal fun zmtpReadyFrame(): ByteArray {
    val body = ZMTP_READY_BODY
    return byteArrayOf(0x04, body.size.toByte()) + body
}

// ── Message frame builders ────────────────────────────────────────────────────

internal fun zmtpMessageFrame(data: ByteArray): ByteArray =
    if (data.size <= 255) {
        byteArrayOf(0x00, data.size.toByte()) + data
    } else {
        val size = data.size.toLong()
        val lenBytes = ByteArray(8) { i -> (size ushr ((7 - i) * 8) and 0xFF).toByte() }
        byteArrayOf(0x02) + lenBytes + data
    }

// ── Beacon format ─────────────────────────────────────────────────────────────

/** UDP beacon payload: "name|id|zmqPort" */
internal fun zmtpBeacon(name: String, id: String, port: Int) =
    "$name|$id|$port".encodeToByteArray()

internal fun zmtpParseBeacon(data: ByteArray): Triple<String, String, Int>? {
    val parts = data.decodeToString().split("|")
    if (parts.size < 3) return null
    val port = parts[2].toIntOrNull() ?: return null
    return Triple(parts[0], parts[1], port)
}
