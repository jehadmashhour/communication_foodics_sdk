import model.BleClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class BleClientTest {

    @Test
    fun equality_sameValues_areEqual() {
        val a = BleClient(id = "abc", name = "Device A")
        val b = BleClient(id = "abc", name = "Device A")
        assertEquals(a, b)
    }

    @Test
    fun equality_differentId_areNotEqual() {
        val a = BleClient(id = "abc", name = "Device A")
        val b = BleClient(id = "xyz", name = "Device A")
        assertNotEquals(a, b)
    }

    @Test
    fun equality_differentName_areNotEqual() {
        val a = BleClient(id = "abc", name = "Device A")
        val b = BleClient(id = "abc", name = "Device B")
        assertNotEquals(a, b)
    }

    @Test
    fun copy_changesOnlySpecifiedField() {
        val original = BleClient(id = "abc", name = "Device A")
        val copy = original.copy(name = "Device B")
        assertEquals("abc", copy.id)
        assertEquals("Device B", copy.name)
    }

    @Test
    fun fields_returnCorrectValues() {
        val client = BleClient(id = "id-1", name = "Printer")
        assertEquals("id-1", client.id)
        assertEquals("Printer", client.name)
    }
}
