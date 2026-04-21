import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BluetoothConstantsTest {

    @Test
    fun helloPrefix_hasExpectedValue() {
        assertEquals("__HELLO__:", BluetoothConstants.HELLO_PREFIX)
    }

    @Test
    fun tag_hasExpectedValue() {
        assertEquals("BluetoothCommunicationChannel", BluetoothConstants.TAG)
    }

    @Test
    fun serviceUuid_isNotNull() {
        assertNotNull(BluetoothConstants.SERVICE_UUID)
    }

    @Test
    fun advertiserUuid_isNotNull() {
        assertNotNull(BluetoothConstants.ADVERTISER_UUID)
    }

    @Test
    fun charFromClientUuid_isNotNull() {
        assertNotNull(BluetoothConstants.CHAR_FROM_CLIENT_UUID)
    }

    @Test
    fun charToClientUuid_isNotNull() {
        assertNotNull(BluetoothConstants.CHAR_TO_CLIENT_UUID)
    }

    @Test
    fun allCharacteristicUuids_areDistinct() {
        val uuids = setOf(
            BluetoothConstants.SERVICE_UUID,
            BluetoothConstants.ADVERTISER_UUID,
            BluetoothConstants.CHAR_FROM_CLIENT_UUID,
            BluetoothConstants.CHAR_TO_CLIENT_UUID
        )
        assertEquals(4, uuids.size, "All BLE UUIDs should be unique")
    }

    @Test
    fun helloPrefix_isNonEmpty() {
        assertTrue(BluetoothConstants.HELLO_PREFIX.isNotEmpty())
    }
}
