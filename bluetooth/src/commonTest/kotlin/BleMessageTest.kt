import handler.BleClient
import handler.BleMessage
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class BleMessageTest {

    private val client = BleClient(id = "c1", name = "Client1")

    @Test
    fun fields_returnCorrectValues() {
        val data = "hello".encodeToByteArray()
        val message = BleMessage(client = client, data = data)
        assertEquals(client, message.client)
        assertContentEquals(data, message.data)
    }

    @Test
    fun equality_sameValues_areEqual() {
        val data = byteArrayOf(1, 2, 3)
        val a = BleMessage(client = client, data = data)
        val b = BleMessage(client = client, data = data)
        assertEquals(a, b)
    }

    @Test
    fun equality_differentClient_areNotEqual() {
        val data = byteArrayOf(1, 2, 3)
        val otherClient = BleClient(id = "c2", name = "Client2")
        val a = BleMessage(client = client, data = data)
        val b = BleMessage(client = otherClient, data = data)
        assertNotEquals(a, b)
    }

    @Test
    fun data_canDecodeToString() {
        val text = "ping"
        val message = BleMessage(client = client, data = text.encodeToByteArray())
        assertEquals(text, message.data.decodeToString())
    }

    @Test
    fun copy_preservesOriginalFields() {
        val original = BleMessage(client = client, data = byteArrayOf(0x01))
        val newClient = BleClient(id = "c2", name = "Client2")
        val copy = original.copy(client = newClient)
        assertEquals(newClient, copy.client)
        assertContentEquals(original.data, copy.data)
    }
}
