import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ClientQualityTest {

    @Test
    fun fields_returnCorrectValues() {
        val q = ClientQuality(clientId = "id-1", clientName = "Cashier", quality = 0.75f)
        assertEquals("id-1", q.clientId)
        assertEquals("Cashier", q.clientName)
        assertEquals(0.75f, q.quality)
    }

    @Test
    fun equality_sameValues_areEqual() {
        val a = ClientQuality("id-1", "Cashier", 0.75f)
        val b = ClientQuality("id-1", "Cashier", 0.75f)
        assertEquals(a, b)
    }

    @Test
    fun equality_differentId_areNotEqual() {
        val a = ClientQuality("id-1", "Cashier", 0.75f)
        val b = ClientQuality("id-2", "Cashier", 0.75f)
        assertNotEquals(a, b)
    }

    @Test
    fun equality_differentName_areNotEqual() {
        val a = ClientQuality("id-1", "Cashier", 0.75f)
        val b = ClientQuality("id-1", "Manager", 0.75f)
        assertNotEquals(a, b)
    }

    @Test
    fun equality_differentQuality_areNotEqual() {
        val a = ClientQuality("id-1", "Cashier", 1.0f)
        val b = ClientQuality("id-1", "Cashier", 0.5f)
        assertNotEquals(a, b)
    }

    @Test
    fun copy_changesOnlySpecifiedField() {
        val original = ClientQuality("id-1", "Cashier", 0.75f)
        val copy = original.copy(quality = 0.25f)
        assertEquals("id-1", copy.clientId)
        assertEquals("Cashier", copy.clientName)
        assertEquals(0.25f, copy.quality)
    }

    @Test
    fun quality_zero_isValid() {
        val q = ClientQuality("id-1", "Cashier", 0.0f)
        assertEquals(0.0f, q.quality)
    }

    @Test
    fun quality_one_isValid() {
        val q = ClientQuality("id-1", "Cashier", 1.0f)
        assertEquals(1.0f, q.quality)
    }

    @Test
    fun qualityValues_fromSignalLevels_areDistinct() {
        val excellent = ClientQuality("a", "A", signalLevelToQuality(SignalLevel.EXCELLENT))
        val good      = ClientQuality("b", "B", signalLevelToQuality(SignalLevel.GOOD))
        val fair      = ClientQuality("c", "C", signalLevelToQuality(SignalLevel.FAIR))
        val poor      = ClientQuality("d", "D", signalLevelToQuality(SignalLevel.POOR))
        val unknown   = ClientQuality("e", "E", signalLevelToQuality(SignalLevel.UNKNOWN))
        val qualities = listOf(excellent.quality, good.quality, fair.quality, poor.quality, unknown.quality)
        assertEquals(5, qualities.toSet().size, "Each signal level should produce a distinct quality value")
    }

    @Test
    fun qualityValues_fromSignalLevels_areDescending() {
        val levels = listOf(SignalLevel.EXCELLENT, SignalLevel.GOOD, SignalLevel.FAIR, SignalLevel.POOR, SignalLevel.UNKNOWN)
        val qualities = levels.map { signalLevelToQuality(it) }
        for (i in 0 until qualities.size - 1) {
            assertTrue(
                qualities[i] > qualities[i + 1],
                "Quality for ${levels[i]} (${qualities[i]}) should be greater than ${levels[i+1]} (${qualities[i+1]})"
            )
        }
    }
}
