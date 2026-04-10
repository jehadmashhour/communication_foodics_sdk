package com.foodics.crosscommunicationlibrary.http

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface

internal const val HTTP_SERVICE_TYPE = "_foodics_http._tcp."

internal fun getLocalIpHttp(): String = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { !it.isLoopbackAddress }
        ?.hostAddress ?: "0.0.0.0"
}.getOrDefault("0.0.0.0")

/** Read HTTP request headers from [input], returning a lowercase header map. */
internal fun readHttpHeaders(input: InputStream): Map<String, String> {
    val buf = ByteArrayOutputStream()
    var b3 = 0; var b2 = 0; var b1 = 0
    while (true) {
        val b = input.read()
        if (b < 0) break
        buf.write(b)
        if (b3 == '\r'.code && b2 == '\n'.code && b1 == '\r'.code && b == '\n'.code) break
        b3 = b2; b2 = b1; b1 = b
    }
    val headers = mutableMapOf<String, String>()
    buf.toByteArray().toString(Charsets.UTF_8).lines().drop(1).forEach { line ->
        val idx = line.indexOf(':')
        if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
    }
    return headers
}

/** Read exactly [contentLength] bytes from [input]. */
internal fun readBody(input: InputStream, contentLength: Int): ByteArray {
    if (contentLength <= 0) return ByteArray(0)
    val buf = ByteArray(contentLength)
    var read = 0
    while (read < contentLength) {
        val n = input.read(buf, read, contentLength - read)
        if (n < 0) break
        read += n
    }
    return buf.copyOf(read)
}

/** Build a minimal HTTP 200 OK response with [body]. */
internal fun httpOkResponse(body: ByteArray): ByteArray {
    val header = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        .toByteArray(Charsets.UTF_8)
    return header + body
}
