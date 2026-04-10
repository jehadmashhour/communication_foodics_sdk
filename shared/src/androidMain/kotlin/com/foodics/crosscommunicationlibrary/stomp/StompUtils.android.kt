package com.foodics.crosscommunicationlibrary.stomp

import java.io.BufferedInputStream
import java.io.InputStream

internal const val STOMP_SERVICE_TYPE = "_foodics_stomp._tcp."
internal const val STOMP_DESTINATION = "/queue/foodics"

internal data class StompFrame(
    val command: String,
    val headers: Map<String, String>,
    val body: ByteArray
)

/**
 * Builds a STOMP 1.2 frame.
 * Binary bodies are supported via [content-length] header.
 * Frame ends with a null byte (0x00) per the spec.
 */
internal fun buildStompFrame(
    command: String,
    headers: Map<String, String> = emptyMap(),
    body: ByteArray = ByteArray(0)
): ByteArray {
    val sb = StringBuilder()
    sb.append(command).append('\n')
    headers.forEach { (k, v) -> sb.append("$k:$v\n") }
    if (body.isNotEmpty()) {
        sb.append("content-length:${body.size}\n")
        sb.append("content-type:application/octet-stream\n")
    }
    sb.append('\n')
    return sb.toString().encodeToByteArray() + body + byteArrayOf(0)
}

/**
 * Reads one STOMP frame from [input].
 * Reads byte-by-byte until the null (0x00) frame terminator.
 * Leading null bytes are skipped (STOMP heartbeats).
 * Returns null on EOF or unrecoverable error.
 */
internal fun readStompFrame(input: InputStream): StompFrame? {
    val buf = mutableListOf<Byte>()
    while (true) {
        val b = input.read()
        if (b == -1) return null
        if (b == 0) {
            if (buf.isEmpty()) continue // heartbeat null — skip
            break
        }
        buf.add(b.toByte())
    }
    return parseStompFrameBytes(buf.toByteArray())
}

private fun parseStompFrameBytes(raw: ByteArray): StompFrame? {
    // Locate \n\n (header/body separator)
    var sepIdx = -1
    for (i in 0 until raw.size - 1) {
        if (raw[i] == '\n'.code.toByte() && raw[i + 1] == '\n'.code.toByte()) {
            sepIdx = i; break
        }
    }

    val headerText: String
    val rawBody: ByteArray
    if (sepIdx >= 0) {
        headerText = raw.copyOfRange(0, sepIdx).decodeToString()
        rawBody = if (sepIdx + 2 < raw.size) raw.copyOfRange(sepIdx + 2, raw.size) else ByteArray(0)
    } else {
        headerText = raw.decodeToString()
        rawBody = ByteArray(0)
    }

    val lines = headerText.split('\n')
    val command = lines.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return null

    val headers = mutableMapOf<String, String>()
    lines.drop(1).forEach { line ->
        val idx = line.indexOf(':')
        if (idx >= 0) headers[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
    }

    // Respect content-length to avoid including trailing \n bytes in binary body
    val contentLength = headers["content-length"]?.toIntOrNull()
    val body = if (contentLength != null && contentLength in 1..rawBody.size)
        rawBody.copyOfRange(0, contentLength) else rawBody

    return StompFrame(command, headers, body)
}

/** Wraps a raw InputStream in a 64 KB buffer for efficient byte-by-byte reading. */
internal fun InputStream.bufferedStomp(): BufferedInputStream = BufferedInputStream(this, 65_536)
