package com.foodics.crosscommunicationlibrary.amqp

/**
 * Pure-Kotlin AMQP 0-9-1 binary codec — shared by Android and iOS.
 *
 * Wire format recap:
 *   Frame:  [type:1][channel:2][payload-size:4][payload][0xCE]
 *   Method: payload = [class-id:2][method-id:2][args...]
 *   Header: payload = [class-id:2][weight:2=0][body-size:8][property-flags:2=0]
 *   Body:   payload = raw bytes (≤ frameMax per frame)
 *
 * All multi-byte integers are big-endian.
 */

// ── Topology constants ────────────────────────────────────────────────────────

internal const val AMQP_DISCOVERY_EXCHANGE = "foodics.amqp.discovery"
internal const val AMQP_BEACON_INTERVAL_MS = 2_000L
internal const val AMQP_DEVICE_TTL_MS      = 10_000L

internal fun amqpQIn(id: String)  = "foodics.amqp.$id.in"
internal fun amqpQOut(id: String) = "foodics.amqp.$id.out"

// ── Encoding helpers ──────────────────────────────────────────────────────────

private operator fun ByteArray.plus(other: ByteArray): ByteArray {
    val r = ByteArray(size + other.size)
    copyInto(r); other.copyInto(r, size); return r
}

private fun ws(v: Int)  = byteArrayOf((v shr 8 and 0xFF).toByte(), (v and 0xFF).toByte())
private fun wi(v: Int)  = byteArrayOf(
    (v shr 24 and 0xFF).toByte(), (v shr 16 and 0xFF).toByte(),
    (v shr  8 and 0xFF).toByte(), (v         and 0xFF).toByte()
)
private fun wl(v: Long) = byteArrayOf(
    (v shr 56 and 0xFF).toByte(), (v shr 48 and 0xFF).toByte(),
    (v shr 40 and 0xFF).toByte(), (v shr 32 and 0xFF).toByte(),
    (v shr 24 and 0xFF).toByte(), (v shr 16 and 0xFF).toByte(),
    (v shr  8 and 0xFF).toByte(), (v         and 0xFF).toByte()
)
private fun wss(s: String): ByteArray { val b = s.encodeToByteArray(); return byteArrayOf(b.size.toByte()) + b }
private fun wls(b: ByteArray) = wi(b.size) + b
private fun tbl() = byteArrayOf(0, 0, 0, 0) // empty field-table

private fun frame(type: Int, ch: Int, payload: ByteArray): ByteArray =
    byteArrayOf(type.toByte()) + ws(ch) + wi(payload.size) + payload + byteArrayOf(0xCE.toByte())

private fun mf(ch: Int, cid: Int, mid: Int, vararg args: ByteArray): ByteArray =
    frame(1, ch, args.fold(ws(cid) + ws(mid)) { a, b -> a + b })

// ── Pre-built frames ──────────────────────────────────────────────────────────

internal val AMQP_PROTOCOL_HEADER: ByteArray = byteArrayOf(0x41, 0x4D, 0x51, 0x50, 0, 0, 9, 1)

internal fun amqpStartOk(user: String, pass: String): ByteArray =
    mf(0, 10, 11,
        tbl(),                                                  // client-properties (empty)
        wss("PLAIN"),                                           // mechanism
        wls(("\u0000$user\u0000$pass").encodeToByteArray()),    // response
        wss("en_US")                                            // locale
    )

internal fun amqpTuneOk(chMax: Int, fMax: Int): ByteArray =
    mf(0, 10, 31, ws(chMax.coerceIn(1, 2047)), wi(fMax.coerceAtLeast(4096)), ws(0))

internal fun amqpConnectionOpen(): ByteArray = mf(0, 10, 40, wss("/"), wss(""), byteArrayOf(0))

internal fun amqpChannelOpen(): ByteArray = mf(1, 20, 10, wss(""))

/** Exchange.Declare — passive=0, durable=0, auto-delete=0, internal=0, no-wait=0 */
internal fun amqpExchangeDeclare(name: String, type: String = "fanout"): ByteArray =
    mf(1, 40, 10, ws(0), wss(name), wss(type), byteArrayOf(0), tbl())

/** Queue.Declare — passive=0, durable=0, [exclusive], [auto-delete], no-wait=0 */
internal fun amqpQueueDeclare(name: String, exclusive: Boolean = false, autoDelete: Boolean = false): ByteArray {
    val bits = (if (exclusive) 4 else 0) or (if (autoDelete) 8 else 0)
    return mf(1, 50, 10, ws(0), wss(name), byteArrayOf(bits.toByte()), tbl())
}

/** Queue.Bind — bind [queue] to [exchange] with optional [routingKey] */
internal fun amqpQueueBind(queue: String, exchange: String, routingKey: String = ""): ByteArray =
    mf(1, 50, 20, ws(0), wss(queue), wss(exchange), wss(routingKey), byteArrayOf(0), tbl())

/** Basic.Consume — no-ack=true (auto-ack), no-wait=false */
internal fun amqpBasicConsume(queue: String): ByteArray =
    mf(1, 60, 20, ws(0), wss(queue), wss(""), byteArrayOf(2), tbl())

/**
 * Returns [method-frame, header-frame, body-frame] for publishing [body] to
 * [exchange] with [routingKey].
 */
internal fun amqpPublishFrames(exchange: String, routingKey: String, body: ByteArray): Array<ByteArray> = arrayOf(
    mf(1, 60, 40, ws(0), wss(exchange), wss(routingKey), byteArrayOf(0)),
    frame(2, 1, ws(60) + ws(0) + wl(body.size.toLong()) + ws(0)),
    frame(3, 1, body)
)

// ── Parsing helpers ───────────────────────────────────────────────────────────

internal data class AmqpFrame(val type: Int, val channel: Int, val payload: ByteArray)

/** Extract (classId, methodId) from method-frame payload. */
internal fun ByteArray.amqpCM(): Pair<Int, Int> =
    (((this[0].toInt() and 0xFF) shl 8) or (this[1].toInt() and 0xFF)) to
    (((this[2].toInt() and 0xFF) shl 8) or (this[3].toInt() and 0xFF))

/** Read a short-string at [off] in [this]; returns (string, nextOffset). */
internal fun ByteArray.amqpSStr(off: Int): Pair<String, Int> {
    val len = this[off].toInt() and 0xFF
    return decodeToString(off + 1, off + 1 + len) to (off + 1 + len)
}

/** Parse Connection.Tune payload → (channelMax, frameMax). */
internal fun amqpParseTune(p: ByteArray): Pair<Int, Int> {
    val ch = ((p[4].toInt() and 0xFF) shl 8) or (p[5].toInt() and 0xFF)
    val fm = ((p[6].toInt() and 0xFF) shl 24) or ((p[7].toInt() and 0xFF) shl 16) or
             ((p[8].toInt() and 0xFF) shl  8) or  (p[9].toInt() and 0xFF)
    return ch to fm
}

/** Parse Queue.DeclareOk payload → broker-assigned queue name. */
internal fun amqpParseQDeclOk(p: ByteArray): String = p.amqpSStr(4).first

/**
 * Parse Basic.Deliver payload → routing-key (used to identify sender queue).
 * Layout: [class:2][method:2][consumer-tag:sstr][delivery-tag:8][redelivered:bits][exchange:sstr][routing-key:sstr]
 */
internal fun amqpParseDeliver(p: ByteArray): String {
    var (_, i) = p.amqpSStr(4); i += 9 // skip consumer-tag + delivery-tag(8) + redelivered(1)
    val (_, i2) = p.amqpSStr(i)
    return p.amqpSStr(i2).first
}

/** Parse Content-Header payload → body-size (bytes 4..11). */
internal fun amqpBodySize(p: ByteArray): Long =
    ((p[4].toLong() and 0xFF) shl 56) or ((p[5].toLong() and 0xFF) shl 48) or
    ((p[6].toLong() and 0xFF) shl 40) or ((p[7].toLong() and 0xFF) shl 32) or
    ((p[8].toLong() and 0xFF) shl 24) or ((p[9].toLong() and 0xFF) shl 16) or
    ((p[10].toLong() and 0xFF) shl 8) or (p[11].toLong() and 0xFF)

/**
 * Parse discovery beacon JSON `{"id":"...","name":"..."}`.
 * Returns (id, name) or null on parse failure.
 */
internal fun amqpParseBeacon(json: String): Pair<String, String>? = try {
    val id   = Regex(""""id"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: return null
    val name = Regex(""""name"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: return null
    id to name
} catch (_: Exception) { null }
