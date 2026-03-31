package com.foodics.crosscommunicationlibrary.nfc

import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface

// ── JSON helpers (shared format with QR channel) ──────────────────────────────

internal fun buildNfcJson(id: String, name: String, ip: String, port: Int): String =
    """{"id":"$id","name":"$name","ip":"$ip","port":$port}"""

internal data class NfcDeviceInfo(val id: String, val name: String, val ip: String, val port: Int)

internal fun parseNfcJson(content: String): NfcDeviceInfo? = runCatching {
    val id   = Regex(""""id"\s*:\s*"([^"]+)"""").find(content)?.groupValues?.get(1) ?: return null
    val name = Regex(""""name"\s*:\s*"([^"]+)"""").find(content)?.groupValues?.get(1) ?: return null
    val ip   = Regex(""""ip"\s*:\s*"([\d.]+)"""").find(content)?.groupValues?.get(1) ?: return null
    val port = Regex(""""port"\s*:\s*(\d+)""").find(content)?.groupValues?.get(1)?.toIntOrNull() ?: return null
    NfcDeviceInfo(id, name, ip, port)
}.getOrNull()

// ── NDEF Type 4 Tag file builder ──────────────────────────────────────────────

/**
 * Build the raw NDEF File content for a UTF-8 Text record carrying [json].
 *
 * Layout:
 *   [2 bytes] NLEN   — NDEF message length, big-endian
 *   [N bytes] NDEF message
 *
 * NDEF Text record:
 *   0xD1  header  (MB=1, ME=1, SR=1, TNF=001 Well-Known)
 *   0x01  type length
 *   0xXX  payload length
 *   0x54  type byte ('T')
 *   0x02  status (UTF-8, language code length 2)
 *   0x65 0x6E  language "en"
 *   [json bytes]
 */
internal fun buildNdefFile(json: String): ByteArray {
    val jsonBytes    = json.toByteArray(Charsets.UTF_8)
    val langBytes    = "en".toByteArray(Charsets.UTF_8)
    // payload = status(1) + lang(2) + json
    val payload      = byteArrayOf(0x02) + langBytes + jsonBytes
    val payloadLen   = payload.size
    // NDEF record header + type + payload
    val ndefRecord   = byteArrayOf(
        0xD1.toByte(),          // MB=1, ME=1, CF=0, SR=1, IL=0, TNF=001
        0x01,                   // type length = 1
        payloadLen.toByte(),    // payload length (≤255 for SR)
        'T'.code.toByte()       // type 'T' (Text)
    ) + payload
    val nlen         = ndefRecord.size
    return byteArrayOf((nlen ushr 8).toByte(), nlen.toByte()) + ndefRecord
}

/**
 * Parse an NDEF Text record payload back to its String value.
 * [payload] is the raw NDEF record payload (after skipping header + type).
 */
internal fun parseNdefTextPayload(payload: ByteArray): String? {
    if (payload.isEmpty()) return null
    val langLen  = payload[0].toInt() and 0x3F
    if (payload.size < 1 + langLen) return null
    return runCatching {
        String(payload, 1 + langLen, payload.size - 1 - langLen, Charsets.UTF_8)
    }.getOrNull()
}

// ── TCP framing (4-byte big-endian length prefix) ─────────────────────────────

internal fun nfcLengthPrefix(data: ByteArray): ByteArray {
    val len = data.size
    return byteArrayOf(
        (len ushr 24 and 0xFF).toByte(),
        (len ushr 16 and 0xFF).toByte(),
        (len ushr  8 and 0xFF).toByte(),
        (len         and 0xFF).toByte()
    )
}

internal fun nfcReadFramed(input: InputStream): ByteArray? {
    val lb = ByteArray(4); var r = 0
    while (r < 4) { val n = input.read(lb, r, 4 - r); if (n < 0) return null; r += n }
    val len = ((lb[0].toInt() and 0xFF) shl 24) or ((lb[1].toInt() and 0xFF) shl 16) or
              ((lb[2].toInt() and 0xFF) shl  8) or  (lb[3].toInt() and 0xFF)
    if (len <= 0 || len > 10_000_000) return null
    val buf = ByteArray(len); r = 0
    while (r < len) { val n = input.read(buf, r, len - r); if (n < 0) return null; r += n }
    return buf
}

// ── Local IP helper ───────────────────────────────────────────────────────────

internal fun nfcGetLocalIpAndroid(): String = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { !it.isLoopbackAddress }
        ?.hostAddress ?: "0.0.0.0"
}.getOrDefault("0.0.0.0")
