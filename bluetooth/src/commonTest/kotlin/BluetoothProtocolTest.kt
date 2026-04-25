import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BluetoothProtocolTest {

    // ── QREP parsing ─────────────────────────────────────────────────────────

    @Test
    fun qrepPrefix_parseRssi_negativeValue() {
        val message = "${BluetoothConstants.QUALITY_REPORT_PREFIX}-72"
        val rssi = message.removePrefix(BluetoothConstants.QUALITY_REPORT_PREFIX).toIntOrNull()
        assertEquals(-72, rssi)
    }

    @Test
    fun qrepPrefix_parseRssi_strongSignal() {
        val message = "${BluetoothConstants.QUALITY_REPORT_PREFIX}-45"
        val rssi = message.removePrefix(BluetoothConstants.QUALITY_REPORT_PREFIX).toIntOrNull()
        assertEquals(-45, rssi)
    }

    @Test
    fun qrepPrefix_parseRssi_weakSignal() {
        val message = "${BluetoothConstants.QUALITY_REPORT_PREFIX}-95"
        val rssi = message.removePrefix(BluetoothConstants.QUALITY_REPORT_PREFIX).toIntOrNull()
        assertEquals(-95, rssi)
    }

    @Test
    fun qrepPrefix_malformedPayload_returnsNull() {
        val message = "${BluetoothConstants.QUALITY_REPORT_PREFIX}not_a_number"
        val rssi = message.removePrefix(BluetoothConstants.QUALITY_REPORT_PREFIX).toIntOrNull()
        assertNull(rssi)
    }

    @Test
    fun qrepPrefix_emptyPayload_returnsNull() {
        val message = BluetoothConstants.QUALITY_REPORT_PREFIX
        val rssi = message.removePrefix(BluetoothConstants.QUALITY_REPORT_PREFIX).toIntOrNull()
        assertNull(rssi)
    }

    // ── HELLO parsing ─────────────────────────────────────────────────────────

    @Test
    fun helloPrefix_parseName_simpleDevice() {
        val message = "${BluetoothConstants.HELLO_PREFIX}My Device"
        val name = message.removePrefix(BluetoothConstants.HELLO_PREFIX).trim()
        assertEquals("My Device", name)
    }

    @Test
    fun helloPrefix_parseName_withLeadingTrailingSpaces() {
        val message = "${BluetoothConstants.HELLO_PREFIX}  Cashier Station  "
        val name = message.removePrefix(BluetoothConstants.HELLO_PREFIX).trim()
        assertEquals("Cashier Station", name)
    }

    @Test
    fun helloPrefix_roundtrip_preservesName() {
        val deviceName = "Android-POS-01"
        val encoded = "${BluetoothConstants.HELLO_PREFIX}$deviceName"
        val decoded = encoded.removePrefix(BluetoothConstants.HELLO_PREFIX).trim()
        assertEquals(deviceName, decoded)
    }

    // ── Bridge prefix byte stripping ─────────────────────────────────────────

    @Test
    fun bridgeC2sPrefix_byteStrip_recoverPayload() {
        val payload = "hello from iOS client".encodeToByteArray()
        val tagged = BluetoothConstants.BRIDGE_C2S_PREFIX.encodeToByteArray() + payload
        val recovered = tagged.copyOfRange(BluetoothConstants.BRIDGE_C2S_PREFIX.length, tagged.size)
        assertEquals("hello from iOS client", recovered.decodeToString())
    }

    @Test
    fun bridgeS2cPrefix_byteStrip_recoverPayload() {
        val payload = "reply from Android server".encodeToByteArray()
        val tagged = BluetoothConstants.BRIDGE_S2C_PREFIX.encodeToByteArray() + payload
        val recovered = tagged.copyOfRange(BluetoothConstants.BRIDGE_S2C_PREFIX.length, tagged.size)
        assertEquals("reply from Android server", recovered.decodeToString())
    }

    @Test
    fun bridgeInitPrefix_parseName() {
        val serverName = "iOS-Server"
        val message = "${BluetoothConstants.BRIDGE_INIT_PREFIX}$serverName"
        val parsed = message.removePrefix(BluetoothConstants.BRIDGE_INIT_PREFIX)
        assertEquals(serverName, parsed)
    }

    @Test
    fun bridgeC2sPrefix_withHelloPayload_isDetectable() {
        val helloPayload = "${BluetoothConstants.HELLO_PREFIX}iOS Device"
        val tagged = BluetoothConstants.BRIDGE_C2S_PREFIX.encodeToByteArray() + helloPayload.encodeToByteArray()
        val strippedText = tagged.copyOfRange(
            BluetoothConstants.BRIDGE_C2S_PREFIX.length, tagged.size
        ).decodeToString()
        assertTrue(strippedText.startsWith(BluetoothConstants.HELLO_PREFIX))
        assertEquals("iOS Device", strippedText.removePrefix(BluetoothConstants.HELLO_PREFIX).trim())
    }

    // ── No false prefix matches ───────────────────────────────────────────────

    @Test
    fun serverStopSignal_doesNotMatchQrepPrefix() {
        assertFalse(BluetoothConstants.SERVER_STOP_SIGNAL.startsWith(BluetoothConstants.QUALITY_REPORT_PREFIX))
    }

    @Test
    fun clientDisconnectSignal_doesNotMatchHelloPrefix() {
        assertFalse(BluetoothConstants.CLIENT_DISCONNECT_SIGNAL.startsWith(BluetoothConstants.HELLO_PREFIX))
    }

    @Test
    fun bridgeInitPrefix_doesNotMatchBridgeC2sPrefix() {
        assertFalse(BluetoothConstants.BRIDGE_INIT_PREFIX.startsWith(BluetoothConstants.BRIDGE_C2S_PREFIX))
        assertFalse(BluetoothConstants.BRIDGE_C2S_PREFIX.startsWith(BluetoothConstants.BRIDGE_INIT_PREFIX))
    }

    @Test
    fun bridgeS2cPrefix_doesNotMatchBridgeC2sPrefix() {
        assertFalse(BluetoothConstants.BRIDGE_S2C_PREFIX.startsWith(BluetoothConstants.BRIDGE_C2S_PREFIX))
        assertFalse(BluetoothConstants.BRIDGE_C2S_PREFIX.startsWith(BluetoothConstants.BRIDGE_S2C_PREFIX))
    }

    @Test
    fun qrepPrefix_doesNotMatchHelloPrefix() {
        assertFalse(BluetoothConstants.QUALITY_REPORT_PREFIX.startsWith(BluetoothConstants.HELLO_PREFIX))
        assertFalse(BluetoothConstants.HELLO_PREFIX.startsWith(BluetoothConstants.QUALITY_REPORT_PREFIX))
    }

    @Test
    fun allSignalPrefixes_areDistinct() {
        val prefixes = listOf(
            BluetoothConstants.HELLO_PREFIX,
            BluetoothConstants.SERVER_STOP_SIGNAL,
            BluetoothConstants.CLIENT_DISCONNECT_SIGNAL,
            BluetoothConstants.QUALITY_REPORT_PREFIX,
            BluetoothConstants.BRIDGE_INIT_PREFIX,
            BluetoothConstants.BRIDGE_C2S_PREFIX,
            BluetoothConstants.BRIDGE_S2C_PREFIX,
            BluetoothConstants.BRIDGE_DISCONNECT_PREFIX
        )
        assertEquals(prefixes.size, prefixes.toSet().size, "All protocol signal strings must be unique")
    }

    @Test
    fun allSignalPrefixes_noPrefixIsAPrefixOfAnother() {
        val prefixes = listOf(
            BluetoothConstants.HELLO_PREFIX,
            BluetoothConstants.QUALITY_REPORT_PREFIX,
            BluetoothConstants.BRIDGE_INIT_PREFIX,
            BluetoothConstants.BRIDGE_C2S_PREFIX,
            BluetoothConstants.BRIDGE_S2C_PREFIX,
            BluetoothConstants.BRIDGE_DISCONNECT_PREFIX
        )
        for (a in prefixes) {
            for (b in prefixes) {
                if (a != b) {
                    assertFalse(
                        a.startsWith(b),
                        "Protocol ambiguity: '$a' starts with '$b' — a message with prefix '$a' would also match '$b'"
                    )
                }
            }
        }
    }
}
