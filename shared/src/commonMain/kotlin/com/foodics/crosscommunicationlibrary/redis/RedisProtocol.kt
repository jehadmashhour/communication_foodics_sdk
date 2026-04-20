package com.foodics.crosscommunicationlibrary.redis

internal const val REDIS_DISCOVERY_CHANNEL  = "foodics:redis:discovery"
internal const val REDIS_BEACON_INTERVAL_MS = 2_000L
internal const val REDIS_DEVICE_TTL_MS      = 10_000L

internal fun redisSubjectIn(id: String) = "foodics:redis:$id:in"

/** Parse "redis://host:port" → (host, port). Falls back to localhost:6379. */
internal fun parseRedisUrl(url: String): Pair<String, Int> {
    val s     = url.removePrefix("redis://").removePrefix("rediss://")
    val colon = s.lastIndexOf(':')
    val host  = if (colon >= 0) s.substring(0, colon) else s
    val port  = if (colon >= 0) s.substring(colon + 1).toIntOrNull() ?: 6379 else 6379
    return host to port
}

/** Encode [replyChannel] + [data] into a single payload: [4-byte-BE-len][channel][data]. */
internal fun redisEncodePayload(replyChannel: String, data: ByteArray): ByteArray {
    val ch  = replyChannel.encodeToByteArray()
    val len = ch.size
    return ByteArray(4 + len + data.size).also { buf ->
        buf[0] = (len ushr 24 and 0xFF).toByte()
        buf[1] = (len ushr 16 and 0xFF).toByte()
        buf[2] = (len ushr  8 and 0xFF).toByte()
        buf[3] = (len         and 0xFF).toByte()
        ch.copyInto(buf, 4)
        data.copyInto(buf, 4 + len)
    }
}

/** Inverse of [redisEncodePayload]. Returns (replyChannel, data) or null on error. */
internal fun redisDecodePayload(bytes: ByteArray): Pair<String, ByteArray>? {
    if (bytes.size < 4) return null
    val len = ((bytes[0].toInt() and 0xFF) shl 24) or
              ((bytes[1].toInt() and 0xFF) shl 16) or
              ((bytes[2].toInt() and 0xFF) shl  8) or
               (bytes[3].toInt() and 0xFF)
    if (len < 0 || bytes.size < 4 + len) return null
    val channel = bytes.sliceArray(4 until 4 + len).decodeToString()
    val data    = bytes.sliceArray(4 + len until bytes.size)
    return channel to data
}
