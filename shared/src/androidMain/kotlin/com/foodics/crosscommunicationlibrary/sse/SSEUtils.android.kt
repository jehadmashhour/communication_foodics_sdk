package com.foodics.crosscommunicationlibrary.sse

import java.io.InputStream
import java.util.Base64

internal const val SSE_SERVICE_TYPE = "_foodics_sse._tcp."

internal fun base64Encode(data: ByteArray): String = Base64.getEncoder().encodeToString(data)
internal fun base64Decode(s: String): ByteArray = Base64.getDecoder().decode(s.trim())

/**
 * Reads one CRLF- or LF-terminated line from an InputStream.
 * Returns null on EOF with an empty buffer.
 */
internal fun InputStream.readHttpLine(): String? {
    val sb = StringBuilder()
    var prev = -1
    while (true) {
        val b = read()
        if (b == -1) return if (sb.isEmpty()) null else sb.toString()
        if (b == '\n'.code) {
            if (prev == '\r'.code && sb.isNotEmpty()) sb.deleteCharAt(sb.length - 1)
            return sb.toString()
        }
        sb.append(b.toChar())
        prev = b
    }
}
