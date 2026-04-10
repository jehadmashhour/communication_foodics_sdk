@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.foodics.crosscommunicationlibrary.ws_discovery

import kotlinx.cinterop.*
import platform.posix.*

internal const val WSD_MULTICAST_IP   = "239.255.255.250"
internal const val WSD_PORT_US: UShort = 3702u
internal const val WSD_SERVICE_TYPE   = "urn:foodics:service:crosscomm:1"

// ── WS-Discovery SOAP message builders ───────────────────────────────────────

internal fun wsdProbeIos(messageId: String) = buildString {
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

internal fun wsdHelloIos(id: String, name: String, ip: String, tcpPort: Int, msgId: String) = buildString {
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

internal fun wsdProbeMatchIos(
    id: String, name: String, ip: String, tcpPort: Int, relatesTo: String, msgId: String
) = buildString {
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

internal data class WSDDeviceInfoIos(val id: String, val name: String, val ip: String, val port: Int)

internal fun parseWSDMessageIos(xml: String): WSDDeviceInfoIos? {
    if (!xml.contains("ProbeMatches") && !xml.contains("Hello")) return null
    if (!xml.contains(WSD_SERVICE_TYPE)) return null
    val idMatch   = Regex("""<d:DeviceId>([^<]+)</d:DeviceId>""").find(xml) ?: return null
    val nameMatch = Regex("""<d:DeviceName>([^<]+)</d:DeviceName>""").find(xml) ?: return null
    val addrMatch = Regex("""<d:XAddrs>tcp://([^:]+):(\d+)</d:XAddrs>""").find(xml) ?: return null
    val port = addrMatch.groupValues[2].toIntOrNull() ?: return null
    return WSDDeviceInfoIos(
        id   = idMatch.groupValues[1],
        name = nameMatch.groupValues[1],
        ip   = addrMatch.groupValues[1],
        port = port
    )
}

internal fun extractMessageIdIos(xml: String): String? =
    Regex("""<a:MessageID>(urn:uuid:[^<]+)</a:MessageID>""").find(xml)?.groupValues?.get(1)

// ── Network byte-order helpers ────────────────────────────────────────────────

internal fun wsdHtons(v: UShort): UShort =
    ((v.toInt() and 0xFF shl 8) or (v.toInt() ushr 8 and 0xFF)).toUShort()

internal fun wsdInetAddr(ip: String): UInt {
    val p = ip.split(".")
    if (p.size != 4) return 0u
    val b0 = p[0].toUInt() and 0xFFu
    val b1 = p[1].toUInt() and 0xFFu
    val b2 = p[2].toUInt() and 0xFFu
    val b3 = p[3].toUInt() and 0xFFu
    return (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
}

internal fun wsdAddrToString(addr: UInt): String {
    val b0 = (addr and 0xFFu).toInt()
    val b1 = ((addr shr 8) and 0xFFu).toInt()
    val b2 = ((addr shr 16) and 0xFFu).toInt()
    val b3 = ((addr shr 24) and 0xFFu).toInt()
    return "$b0.$b1.$b2.$b3"
}

internal fun wsdGetLocalIpIos(): String = memScoped {
    val sock = socket(AF_INET, SOCK_DGRAM, 0)
    if (sock < 0) return "0.0.0.0"
    val remote = alloc<sockaddr_in>()
    remote.sin_family = AF_INET.convert()
    remote.sin_port = wsdHtons(80u)
    remote.sin_addr.s_addr = wsdInetAddr("8.8.8.8")
    platform.posix.connect(sock, remote.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
    val local = alloc<sockaddr_in>()
    val len = alloc<socklen_tVar>()
    len.value = sizeOf<sockaddr_in>().convert()
    getsockname(sock, local.ptr.reinterpret(), len.ptr)
    close(sock)
    wsdAddrToString(local.sin_addr.s_addr)
}

// ── TCP framing (4-byte big-endian length prefix) ─────────────────────────────

internal fun wsdTcpSend(fd: Int, data: ByteArray) {
    val len = data.size
    val prefix = ByteArray(4).apply {
        this[0] = (len ushr 24 and 0xFF).toByte()
        this[1] = (len ushr 16 and 0xFF).toByte()
        this[2] = (len ushr 8  and 0xFF).toByte()
        this[3] = (len         and 0xFF).toByte()
    }
    prefix.usePinned { p -> send(fd, p.addressOf(0), 4.convert(), 0) }
    data.usePinned   { p -> send(fd, p.addressOf(0), len.convert(), 0) }
}

internal fun wsdTcpRecv(fd: Int): ByteArray? {
    val lb = ByteArray(4); var r = 0
    while (r < 4) {
        val n = lb.usePinned { p -> recv(fd, p.addressOf(r), (4 - r).convert(), 0).toInt() }
        if (n <= 0) return null
        r += n
    }
    val len = ((lb[0].toInt() and 0xFF) shl 24) or ((lb[1].toInt() and 0xFF) shl 16) or
              ((lb[2].toInt() and 0xFF) shl 8)  or  (lb[3].toInt() and 0xFF)
    if (len <= 0 || len > 10_000_000) return null
    val buf = ByteArray(len); r = 0
    while (r < len) {
        val n = buf.usePinned { p -> recv(fd, p.addressOf(r), (len - r).convert(), 0).toInt() }
        if (n <= 0) return null
        r += n
    }
    return buf
}

// ── UDP send/receive helpers ──────────────────────────────────────────────────

internal fun wsdUdpSend(sock: Int, msg: String, destIp: String, destPort: UShort) {
    val bytes = msg.encodeToByteArray()
    memScoped {
        val dest = alloc<sockaddr_in>()
        dest.sin_family = AF_INET.convert()
        dest.sin_port = wsdHtons(destPort)
        dest.sin_addr.s_addr = wsdInetAddr(destIp)
        bytes.usePinned { p ->
            sendto(sock, p.addressOf(0), bytes.size.convert(), 0,
                dest.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
        }
    }
}

internal fun wsdUdpRecv(
    sock: Int,
    timeoutMs: Int = 2_000,
    srcIp: StringBuilder? = null,
    srcPort: IntArray? = null
): String? = memScoped {
    val tv = alloc<timeval>()
    tv.tv_sec = (timeoutMs / 1000).convert()
    tv.tv_usec = ((timeoutMs % 1000) * 1000).convert()
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, tv.ptr, sizeOf<timeval>().convert())

    val buf = allocArray<ByteVar>(4096)
    val srcAddr = alloc<sockaddr_in>()
    val srcLen = alloc<socklen_tVar>()
    srcLen.value = sizeOf<sockaddr_in>().convert()

    val n = recvfrom(sock, buf, 4096u, 0,
        srcAddr.ptr.reinterpret(), srcLen.ptr).toInt()
    if (n <= 0) return null

    srcIp?.append(wsdAddrToString(srcAddr.sin_addr.s_addr))
    srcPort?.let { it[0] = wsdHtons(srcAddr.sin_port).toInt() }

    buf.toKString().substring(0, n)
}
