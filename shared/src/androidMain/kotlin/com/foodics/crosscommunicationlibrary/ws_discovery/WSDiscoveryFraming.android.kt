package com.foodics.crosscommunicationlibrary.ws_discovery

import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID

internal const val WSD_MULTICAST_IP  = "239.255.255.250"
internal const val WSD_PORT          = 3702
internal const val WSD_SERVICE_TYPE  = "urn:foodics:service:crosscomm:1"

// ── WS-Discovery SOAP message builders ───────────────────────────────────────

internal fun wsdProbe(messageId: String = UUID.randomUUID().toString()) = buildString {
    append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n")
    append("<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\"")
    append(" xmlns:a=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\"")
    append(" xmlns:d=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\">\r\n")
    append("  <s:Header>\r\n")
    append("    <a:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</a:Action>\r\n")
    append("    <a:MessageID>urn:uuid:$messageId</a:MessageID>\r\n")
    append("    <a:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</a:To>\r\n")
    append("  </s:Header>\r\n")
    append("  <s:Body>\r\n")
    append("    <d:Probe><d:Types>$WSD_SERVICE_TYPE</d:Types></d:Probe>\r\n")
    append("  </s:Body>\r\n")
    append("</s:Envelope>\r\n")
}

internal fun wsdHello(id: String, name: String, ip: String, tcpPort: Int) = buildString {
    val msgId = UUID.randomUUID().toString()
    append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n")
    append("<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\"")
    append(" xmlns:a=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\"")
    append(" xmlns:d=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\">\r\n")
    append("  <s:Header>\r\n")
    append("    <a:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Hello</a:Action>\r\n")
    append("    <a:MessageID>urn:uuid:$msgId</a:MessageID>\r\n")
    append("    <a:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</a:To>\r\n")
    append("  </s:Header>\r\n")
    append("  <s:Body>\r\n")
    append("    <d:Hello>\r\n")
    append("      <a:EndpointReference><a:Address>urn:uuid:$id</a:Address></a:EndpointReference>\r\n")
    append("      <d:Types>$WSD_SERVICE_TYPE</d:Types>\r\n")
    append("      <d:XAddrs>tcp://$ip:$tcpPort</d:XAddrs>\r\n")
    append("      <d:MetadataVersion>1</d:MetadataVersion>\r\n")
    append("      <d:DeviceName>$name</d:DeviceName>\r\n")
    append("      <d:DeviceId>$id</d:DeviceId>\r\n")
    append("    </d:Hello>\r\n")
    append("  </s:Body>\r\n")
    append("</s:Envelope>\r\n")
}

internal fun wsdProbeMatch(
    id: String, name: String, ip: String, tcpPort: Int, relatesTo: String
) = buildString {
    val msgId = UUID.randomUUID().toString()
    append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n")
    append("<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\"")
    append(" xmlns:a=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\"")
    append(" xmlns:d=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\">\r\n")
    append("  <s:Header>\r\n")
    append("    <a:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/ProbeMatches</a:Action>\r\n")
    append("    <a:MessageID>urn:uuid:$msgId</a:MessageID>\r\n")
    append("    <a:RelatesTo>$relatesTo</a:RelatesTo>\r\n")
    append("    <a:To>http://schemas.xmlsoap.org/ws/2004/08/addressing/role/anonymous</a:To>\r\n")
    append("  </s:Header>\r\n")
    append("  <s:Body>\r\n")
    append("    <d:ProbeMatches><d:ProbeMatch>\r\n")
    append("      <a:EndpointReference><a:Address>urn:uuid:$id</a:Address></a:EndpointReference>\r\n")
    append("      <d:Types>$WSD_SERVICE_TYPE</d:Types>\r\n")
    append("      <d:XAddrs>tcp://$ip:$tcpPort</d:XAddrs>\r\n")
    append("      <d:MetadataVersion>1</d:MetadataVersion>\r\n")
    append("      <d:DeviceName>$name</d:DeviceName>\r\n")
    append("      <d:DeviceId>$id</d:DeviceId>\r\n")
    append("    </d:ProbeMatch></d:ProbeMatches>\r\n")
    append("  </s:Body>\r\n")
    append("</s:Envelope>\r\n")
}

// ── Response parser ───────────────────────────────────────────────────────────

internal data class WSDDeviceInfo(val id: String, val name: String, val ip: String, val port: Int)

internal fun parseWSDMessage(xml: String): WSDDeviceInfo? {
    if (!xml.contains("ProbeMatches") && !xml.contains("Hello")) return null
    if (!xml.contains(WSD_SERVICE_TYPE)) return null
    val idMatch   = Regex("""<d:DeviceId>([^<]+)</d:DeviceId>""").find(xml) ?: return null
    val nameMatch = Regex("""<d:DeviceName>([^<]+)</d:DeviceName>""").find(xml) ?: return null
    val addrMatch = Regex("""<d:XAddrs>tcp://([^:]+):(\d+)</d:XAddrs>""").find(xml) ?: return null
    val port = addrMatch.groupValues[2].toIntOrNull() ?: return null
    return WSDDeviceInfo(
        id   = idMatch.groupValues[1],
        name = nameMatch.groupValues[1],
        ip   = addrMatch.groupValues[1],
        port = port
    )
}

internal fun extractMessageId(xml: String): String? =
    Regex("""<a:MessageID>(urn:uuid:[^<]+)</a:MessageID>""").find(xml)?.groupValues?.get(1)

// ── Local IP ──────────────────────────────────────────────────────────────────

internal fun wsdGetLocalIpAndroid(): String = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { !it.isLoopbackAddress }
        ?.hostAddress ?: "0.0.0.0"
}.getOrDefault("0.0.0.0")

// ── TCP framing (4-byte big-endian length prefix) ─────────────────────────────

internal fun wsdFramed(data: ByteArray): ByteArray {
    val len = data.size
    return byteArrayOf(
        (len ushr 24).toByte(), (len ushr 16).toByte(),
        (len ushr 8).toByte(), len.toByte()
    ) + data
}

internal fun wsdReadFramed(input: InputStream): ByteArray? {
    val lb = ByteArray(4); var r = 0
    while (r < 4) { val n = input.read(lb, r, 4 - r); if (n < 0) return null; r += n }
    val len = ((lb[0].toInt() and 0xff) shl 24) or ((lb[1].toInt() and 0xff) shl 16) or
              ((lb[2].toInt() and 0xff) shl 8)  or  (lb[3].toInt() and 0xff)
    if (len <= 0 || len > 10_000_000) return null
    val buf = ByteArray(len); r = 0
    while (r < len) { val n = input.read(buf, r, len - r); if (n < 0) return null; r += n }
    return buf
}
