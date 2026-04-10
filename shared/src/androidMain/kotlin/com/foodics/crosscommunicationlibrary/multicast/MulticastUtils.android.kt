package com.foodics.crosscommunicationlibrary.multicast

import java.net.InetAddress

/** Site-local multicast address reserved for application use (RFC 2365). */
internal const val MULTICAST_GROUP = "239.255.42.42"
internal const val MULTICAST_PORT  = 5422

/** Packet type byte: periodic server-presence announcement. */
internal const val PKT_BEACON: Byte = 0x01

/** Packet type byte: binary data payload. */
internal const val PKT_DATA: Byte = 0x02

internal val MULTICAST_ADDRESS: InetAddress = InetAddress.getByName(MULTICAST_GROUP)

/** Encode a BEACON packet: [0x01][UTF-8 "id|name"]. */
internal fun buildBeacon(identifier: String, deviceName: String): ByteArray {
    val payload = "$identifier|$deviceName".encodeToByteArray()
    return byteArrayOf(PKT_BEACON) + payload
}

/** Encode a DATA packet: [0x02][payload]. */
internal fun buildData(payload: ByteArray): ByteArray = byteArrayOf(PKT_DATA) + payload

/** Parse a received datagram. Returns (type, content) or null for malformed packets. */
internal fun parsePacket(raw: ByteArray): Pair<Byte, ByteArray>? {
    if (raw.isEmpty()) return null
    val type = raw[0]
    val content = if (raw.size > 1) raw.copyOfRange(1, raw.size) else ByteArray(0)
    return type to content
}
