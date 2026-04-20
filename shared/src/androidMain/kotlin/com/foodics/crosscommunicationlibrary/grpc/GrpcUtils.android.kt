package com.foodics.crosscommunicationlibrary.grpc

import java.io.InputStream
import java.io.OutputStream

private const val MAX_GRPC_FRAME = 16 * 1024 * 1024   // 16 MB
private const val GRPC_NO_COMPRESSION = 0x00

/**
 * Writes one gRPC Length-Prefixed Message:
 *   [0x00: compression][length: 4 bytes BE][payload]
 */
internal fun OutputStream.writeGrpcFrame(data: ByteArray) {
    write(GRPC_NO_COMPRESSION)
    val len = data.size
    write((len ushr 24) and 0xFF)
    write((len ushr 16) and 0xFF)
    write((len ushr 8)  and 0xFF)
    write( len          and 0xFF)
    write(data)
    flush()
}

/**
 * Reads one gRPC Length-Prefixed Message.
 * Returns null on EOF, error, or unsupported compressed frame.
 */
internal fun InputStream.readGrpcFrame(): ByteArray? {
    val compressionFlag = read()
    if (compressionFlag < 0) return null
    // Compressed messages are not yet supported; skip them to stay in sync.
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
    if (len <= 0 || len > MAX_GRPC_FRAME) return null
    val payload = ByteArray(len)
    var read = 0
    while (read < len) {
        val n = read(payload, read, len - read)
        if (n < 0) return null
        read += n
    }
    return payload
}
