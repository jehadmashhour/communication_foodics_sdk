package com.foodics.crosscommunicationlibrary.uwb

import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.nio.ByteOrder

// ── OOB constants ─────────────────────────────────────────────────────────────

internal const val UWB_MULTICAST_IP    = "239.255.255.250"
internal const val UWB_OOB_PORT        = 1901

/** Sent by clients to discover UWB servers. */
internal const val UWB_ANDROID_SEARCH  = "UWB-ANDROID-SEARCH"

private const val UWB_ANDROID_ANNOUNCE = "UWB-ANDROID-ANNOUNCE"

// ── Announce message ──────────────────────────────────────────────────────────

/**
 * Build an OOB announce string.
 * Format: UWB-ANDROID-ANNOUNCE|name|id|ip|tcpPort|addrB0|addrB1|channel|preamble|sessionId
 */
internal fun uwbAndroidAnnounce(
    name: String, id: String, ip: String, tcpPort: Int,
    addrBytes: ByteArray, channel: Int, preamble: Int, sessionId: Int
): String {
    val b0 = addrBytes[0].toInt() and 0xFF
    val b1 = addrBytes[1].toInt() and 0xFF
    return "$UWB_ANDROID_ANNOUNCE|$name|$id|$ip|$tcpPort|$b0|$b1|$channel|$preamble|$sessionId"
}

// ── Parsed announce info ──────────────────────────────────────────────────────

internal data class UwbAndroidInfo(
    val name: String,
    val id: String,
    val ip: String,
    val tcpPort: Int,
    val addrB0: Int,
    val addrB1: Int,
    val channel: Int,
    val preamble: Int,
    val sessionId: Int,
    /** Compact string stored in IoTDevice.address for later retrieval. */
    val address: String
)

/**
 * Parse an announce message received over UDP.
 * Returns null if the message is not a valid UWB announce.
 */
internal fun parseUwbAndroidAnnounce(msg: String): UwbAndroidInfo? {
    if (!msg.startsWith(UWB_ANDROID_ANNOUNCE)) return null
    val p = msg.split("|")
    if (p.size < 10) return null
    return runCatching {
        val name = p[1]; val id = p[2]
        val ip   = p[3]; val tcpPort = p[4].toInt()
        val b0 = p[5].toInt(); val b1 = p[6].toInt()
        val ch = p[7].toInt(); val pre = p[8].toInt()
        val sid = p[9].trim().toInt()
        val addr = "$ip:$tcpPort|$b0|$b1|$ch|$pre|$sid"
        UwbAndroidInfo(name, id, ip, tcpPort, b0, b1, ch, pre, sid, addr)
    }.getOrNull()
}

/**
 * Reconstruct UwbAndroidInfo from the compact address string stored in IoTDevice.
 * Format: "<ip>:<tcpPort>|b0|b1|channel|preamble|sessionId"
 */
internal fun parseUwbAndroidAddress(address: String): UwbAndroidInfo? = runCatching {
    val p = address.split("|")
    if (p.size < 6) return null
    val (ip, tcpPortStr) = p[0].split(":")
    UwbAndroidInfo(
        name = "", id = "",
        ip = ip, tcpPort = tcpPortStr.toInt(),
        addrB0 = p[1].toInt(), addrB1 = p[2].toInt(),
        channel = p[3].toInt(), preamble = p[4].toInt(),
        sessionId = p[5].toInt(),
        address = address
    )
}.getOrNull()

// ── Ranging result encoding ───────────────────────────────────────────────────

/**
 * Encode a ranging result as a 12-byte big-endian ByteArray.
 *   bytes  0-3  : distance (metres, Float)
 *   bytes  4-7  : azimuth  (degrees, Float)
 *   bytes  8-11 : elevation (degrees, Float)
 * Any unavailable value is encoded as Float.NaN.
 */
internal fun encodeRanging(distance: Float, azimuth: Float, elevation: Float): ByteArray {
    return ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
        .putFloat(distance)
        .putFloat(azimuth)
        .putFloat(elevation)
        .array()
}

// ── Network helpers ───────────────────────────────────────────────────────────

internal fun uwbGetLocalIpAndroid(): String = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { !it.isLoopbackAddress }
        ?.hostAddress ?: "0.0.0.0"
}.getOrDefault("0.0.0.0")
