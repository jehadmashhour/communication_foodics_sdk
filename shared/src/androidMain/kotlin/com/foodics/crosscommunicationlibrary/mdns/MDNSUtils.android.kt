package com.foodics.crosscommunicationlibrary.mdns

import java.io.InputStream
import java.io.OutputStream

/** Maximum single-message size (16 MB). */
private const val MAX_MDNS_FRAME = 16 * 1024 * 1024

/** Writes one 4-byte-length-prefixed frame to the stream. */
internal fun OutputStream.writeMdnsFrame(data: ByteArray) {
    val len = data.size
    write((len ushr 24) and 0xFF)
    write((len ushr 16) and 0xFF)
    write((len ushr 8)  and 0xFF)
    write( len          and 0xFF)
    write(data)
    flush()
}

/** Reads one length-prefixed frame. Returns null on EOF or invalid length. */
internal fun InputStream.readMdnsFrame(): ByteArray? {
    val header = ByteArray(4)
    var total = 0
    while (total < 4) {
        val n = read(header, total, 4 - total)
        if (n < 0) return null
        total += n
    }
    val len = ((header[0].toInt() and 0xFF) shl 24) or
              ((header[1].toInt() and 0xFF) shl 16) or
              ((header[2].toInt() and 0xFF) shl 8)  or
               (header[3].toInt() and 0xFF)
    if (len <= 0 || len > MAX_MDNS_FRAME) return null
    val payload = ByteArray(len)
    var read = 0
    while (read < len) {
        val n = read(payload, read, len - read)
        if (n < 0) return null
        read += n
    }
    return payload
}
