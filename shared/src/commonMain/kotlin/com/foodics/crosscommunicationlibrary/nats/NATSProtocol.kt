package com.foodics.crosscommunicationlibrary.nats

/**
 * Pure-Kotlin NATS protocol helpers — shared by Android and iOS.
 *
 * NATS text protocol (all lines \r\n terminated):
 *   S→C  INFO {json}
 *   C→S  CONNECT {json}
 *   C→S  SUB <subject> <sid>
 *   C→S  UNSUB <sid>
 *   C→S  PUB <subject> <bytes>\r\n<data>\r\n
 *   S→C  MSG <subject> <sid> [reply] <bytes>\r\n<data>\r\n
 *   Both PING / PONG  (server keepalive — client must reply)
 */

internal const val NATS_DISCOVERY_SUBJECT = "foodics.nats.discovery"
internal const val NATS_BEACON_INTERVAL_MS = 2_000L
internal const val NATS_DEVICE_TTL_MS      = 10_000L

internal const val NATS_SID_DATA     = "d1"
internal const val NATS_SID_SCAN     = "s1"

internal fun natsSubjectIn(id: String)  = "foodics.nats.$id.in"
internal fun natsSubjectOut(id: String) = "foodics.nats.$id.out"

// ── Command builders ──────────────────────────────────────────────────────────

/** CONNECT command — verbose=false so server won't send +OK on every op. */
internal const val NATS_CONNECT_CMD =
    "CONNECT {\"verbose\":false,\"pedantic\":false}\r\n"

internal fun natsSub(subject: String, sid: String)   = "SUB $subject $sid\r\n"
internal fun natsUnsub(sid: String)                   = "UNSUB $sid\r\n"
/** Returns only the PUB header line; caller must write the payload bytes + "\r\n". */
internal fun natsPubHeader(subject: String, size: Int) = "PUB $subject $size\r\n"

// ── Parsing ───────────────────────────────────────────────────────────────────

/**
 * Parse MSG header line: `MSG <subject> <sid> [reply] <bytes>`.
 * Returns (subject, bytes) or null on parse failure.
 */
internal fun parseNatsMsgLine(line: String): Pair<String, Int>? {
    val parts = line.trim().split(' ')
    if (parts.size < 4 || parts[0] != "MSG") return null
    val subject = parts[1]
    val bytes   = parts.last().toIntOrNull() ?: return null
    return subject to bytes
}

/** Parse broker URL `nats://host:port` → (host, port). */
internal fun parseNatsUrl(url: String): Pair<String, Int> {
    val s     = url.removePrefix("nats://").removePrefix("tls://")
    val colon = s.lastIndexOf(':')
    val host  = if (colon >= 0) s.substring(0, colon) else s
    val port  = if (colon >= 0) s.substring(colon + 1).toIntOrNull() ?: 4222 else 4222
    return host to port
}

/** Parse discovery beacon `{"id":"...","name":"..."}` → (id, name) or null. */
internal fun parseNatsBeacon(json: String): Pair<String, String>? = try {
    val id   = Regex(""""id"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: return null
    val name = Regex(""""name"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: return null
    id to name
} catch (_: Exception) { null }
