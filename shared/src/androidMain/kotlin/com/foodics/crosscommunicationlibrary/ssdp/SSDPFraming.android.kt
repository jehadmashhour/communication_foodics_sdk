package com.foodics.crosscommunicationlibrary.ssdp

import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface

internal const val SSDP_MULTICAST_IP   = "239.255.255.250"
internal const val SSDP_PORT           = 1900
internal const val SSDP_SERVICE_TYPE   = "urn:foodics:service:crosscomm:1"

// ── SSDP message builders ─────────────────────────────────────────────────────

internal fun ssdpMSearch() = buildString {
    append("M-SEARCH * HTTP/1.1\r\n")
    append("HOST: $SSDP_MULTICAST_IP:$SSDP_PORT\r\n")
    append("MAN: \"ssdp:discover\"\r\n")
    append("MX: 3\r\n")
    append("ST: $SSDP_SERVICE_TYPE\r\n")
    append("\r\n")
}

internal fun ssdpOkResponse(id: String, name: String, ip: String, tcpPort: Int) = buildString {
    append("HTTP/1.1 200 OK\r\n")
    append("CACHE-CONTROL: max-age=1800\r\n")
    append("LOCATION: tcp://$ip:$tcpPort\r\n")
    append("ST: $SSDP_SERVICE_TYPE\r\n")
    append("USN: uuid:$id\r\n")
    append("X-DEVICE-NAME: $name\r\n")
    append("X-DEVICE-ID: $id\r\n")
    append("\r\n")
}

internal fun ssdpNotifyAlive(id: String, name: String, ip: String, tcpPort: Int) = buildString {
    append("NOTIFY * HTTP/1.1\r\n")
    append("HOST: $SSDP_MULTICAST_IP:$SSDP_PORT\r\n")
    append("CACHE-CONTROL: max-age=1800\r\n")
    append("LOCATION: tcp://$ip:$tcpPort\r\n")
    append("NT: $SSDP_SERVICE_TYPE\r\n")
    append("NTS: ssdp:alive\r\n")
    append("USN: uuid:$id\r\n")
    append("X-DEVICE-NAME: $name\r\n")
    append("X-DEVICE-ID: $id\r\n")
    append("\r\n")
}

// ── Response parser ───────────────────────────────────────────────────────────

internal data class SSDPDeviceInfo(val id: String, val name: String, val ip: String, val port: Int)

internal fun parseSSDPMessage(message: String): SSDPDeviceInfo? {
    if (!message.startsWith("HTTP/1.1 200") && !message.startsWith("NOTIFY")) return null
    val headers = mutableMapOf<String, String>()
    message.lines().drop(1).forEach { line ->
        val idx = line.indexOf(':')
        if (idx > 0) headers[line.substring(0, idx).trim().uppercase()] = line.substring(idx + 1).trim()
    }
    val location = headers["LOCATION"] ?: return null
    val id       = headers["X-DEVICE-ID"] ?: return null
    val name     = headers["X-DEVICE-NAME"] ?: id
    val match    = Regex("""tcp://(.+?):(\d+)""").find(location) ?: return null
    val port     = match.groupValues[2].toIntOrNull() ?: return null
    return SSDPDeviceInfo(id = id, name = name, ip = match.groupValues[1], port = port)
}

// ── Local IP ──────────────────────────────────────────────────────────────────

internal fun getLocalIpAndroid(): String = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { !it.isLoopbackAddress }
        ?.hostAddress ?: "0.0.0.0"
}.getOrDefault("0.0.0.0")

// ── TCP framing (4-byte big-endian length prefix) ─────────────────────────────

internal fun ssdpFramed(data: ByteArray): ByteArray {
    val len = data.size
    return byteArrayOf(
        (len ushr 24).toByte(), (len ushr 16).toByte(),
        (len ushr 8).toByte(), len.toByte()
    ) + data
}

internal fun ssdpReadFramed(input: InputStream): ByteArray? {
    val lb = ByteArray(4); var r = 0
    while (r < 4) { val n = input.read(lb, r, 4 - r); if (n < 0) return null; r += n }
    val len = ((lb[0].toInt() and 0xff) shl 24) or ((lb[1].toInt() and 0xff) shl 16) or
              ((lb[2].toInt() and 0xff) shl 8)  or  (lb[3].toInt() and 0xff)
    if (len <= 0 || len > 10_000_000) return null
    val buf = ByteArray(len); r = 0
    while (r < len) { val n = input.read(buf, r, len - r); if (n < 0) return null; r += n }
    return buf
}
