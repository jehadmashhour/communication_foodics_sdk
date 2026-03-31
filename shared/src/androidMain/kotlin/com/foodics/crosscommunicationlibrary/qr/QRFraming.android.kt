package com.foodics.crosscommunicationlibrary.qr

import java.io.InputStream

/** Build a 4-byte big-endian length prefix for [data]. */
internal fun lengthPrefix(data: ByteArray): ByteArray {
    val len = data.size
    return byteArrayOf(
        (len ushr 24 and 0xFF).toByte(),
        (len ushr 16 and 0xFF).toByte(),
        (len ushr 8  and 0xFF).toByte(),
        (len         and 0xFF).toByte()
    )
}

/** Read a length-prefixed frame from [input]. Returns null on EOF/error. */
internal fun readFramed(input: InputStream): ByteArray? {
    val lenBuf = ByteArray(4)
    var read = 0
    while (read < 4) {
        val n = input.read(lenBuf, read, 4 - read)
        if (n < 0) return null
        read += n
    }
    val len = ((lenBuf[0].toInt() and 0xFF) shl 24) or
              ((lenBuf[1].toInt() and 0xFF) shl 16) or
              ((lenBuf[2].toInt() and 0xFF) shl 8)  or
               (lenBuf[3].toInt() and 0xFF)
    val buf = ByteArray(len)
    var dataRead = 0
    while (dataRead < len) {
        val n = input.read(buf, dataRead, len - dataRead)
        if (n < 0) return null
        dataRead += n
    }
    return buf
}

/** Build the QR JSON payload. */
internal fun buildQRJson(id: String, name: String, ip: String, port: Int): String =
    """{"id":"$id","name":"$name","ip":"$ip","port":$port}"""

internal data class QRDeviceInfo(val id: String, val name: String, val ip: String, val port: Int)

/** Parse the QR JSON payload. Returns null if the content is not a valid QR payload. */
internal fun parseQRJson(content: String): QRDeviceInfo? = runCatching {
    val id   = Regex(""""id"\s*:\s*"([^"]+)"""").find(content)?.groupValues?.get(1) ?: return null
    val name = Regex(""""name"\s*:\s*"([^"]+)"""").find(content)?.groupValues?.get(1) ?: return null
    val ip   = Regex(""""ip"\s*:\s*"([\d.]+)"""").find(content)?.groupValues?.get(1) ?: return null
    val port = Regex(""""port"\s*:\s*(\d+)""").find(content)?.groupValues?.get(1)?.toIntOrNull() ?: return null
    QRDeviceInfo(id, name, ip, port)
}.getOrNull()
