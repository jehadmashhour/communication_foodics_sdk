package com.foodics.crosscommunicationlibrary.coap

import kotlin.random.Random

internal const val COAP_SERVICE_TYPE = "_foodics_coap._udp."

// CoAP message type nibbles (bits 5-4 of first byte, Ver=1 → bits 7-6 = 01)
private const val VER_NON: Int = 0x50  // Ver=1 (01), T=NON (01), TKL=0 → base 0x50

// CoAP codes
internal const val CODE_POST: Byte = 0x02      // 0.02 POST
internal const val CODE_CONTENT: Byte = 0x45   // 2.05 Content

private fun nextMsgId(): Int = Random.nextInt(0x0001, 0xFFFF)
private fun nextToken(): ByteArray = Random.nextBytes(4)

/**
 * Builds a NON POST CoAP datagram (client → server data push).
 * Format: [Ver=1|T=NON|TKL=4] [POST] [MsgID-hi] [MsgID-lo] [4-byte token] [0xFF] [payload]
 */
internal fun coapBuildPost(payload: ByteArray): ByteArray {
    val msgId = nextMsgId()
    val token = nextToken()
    return coapFrame(type = 1, code = CODE_POST, msgId = msgId, token = token, payload = payload)
}

/**
 * Builds a NON CONTENT CoAP datagram (server → client data push).
 */
internal fun coapBuildContent(payload: ByteArray): ByteArray {
    val msgId = nextMsgId()
    val token = nextToken()
    return coapFrame(type = 1, code = CODE_CONTENT, msgId = msgId, token = token, payload = payload)
}

private fun coapFrame(type: Int, code: Byte, msgId: Int, token: ByteArray, payload: ByteArray): ByteArray {
    val tkl = token.size
    val hasPayload = payload.isNotEmpty()
    val size = 4 + tkl + (if (hasPayload) 1 + payload.size else 0)
    val buf = ByteArray(size)
    buf[0] = ((0x40) or (type shl 4) or tkl).toByte()  // Ver=1, T, TKL
    buf[1] = code
    buf[2] = (msgId shr 8 and 0xFF).toByte()
    buf[3] = (msgId and 0xFF).toByte()
    token.copyInto(buf, 4)
    if (hasPayload) {
        buf[4 + tkl] = 0xFF.toByte()
        payload.copyInto(buf, 4 + tkl + 1)
    }
    return buf
}

/**
 * Extracts the payload from a CoAP datagram.
 * Scans for the 0xFF payload marker after the fixed header and token.
 * Returns an empty array if no payload marker is found or the datagram is malformed.
 */
internal fun coapParsePayload(data: ByteArray): ByteArray {
    if (data.size < 4) return ByteArray(0)
    val tkl = data[0].toInt() and 0x0F
    val start = 4 + tkl
    for (i in start until data.size) {
        if (data[i] == 0xFF.toByte()) {
            return if (i + 1 < data.size) data.copyOfRange(i + 1, data.size) else ByteArray(0)
        }
    }
    return ByteArray(0)
}

/** Returns true if this datagram looks like a valid CoAP 1.x packet. */
internal fun isValidCoap(data: ByteArray): Boolean {
    if (data.size < 4) return false
    val ver = (data[0].toInt() ushr 6) and 0x03
    return ver == 1
}
