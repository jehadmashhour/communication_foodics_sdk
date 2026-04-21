import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ConnectionQualityTest {

    // ── rssiToSignalLevel ────────────────────────────────────────────────────

    @Test
    fun rssiToSignalLevel_minValue_returnsUnknown() {
        assertEquals(SignalLevel.UNKNOWN, rssiToSignalLevel(Int.MIN_VALUE))
    }

    @Test
    fun rssiToSignalLevel_minus60_returnsExcellent() {
        assertEquals(SignalLevel.EXCELLENT, rssiToSignalLevel(-60))
    }

    @Test
    fun rssiToSignalLevel_aboveMinus60_returnsExcellent() {
        assertEquals(SignalLevel.EXCELLENT, rssiToSignalLevel(-50))
        assertEquals(SignalLevel.EXCELLENT, rssiToSignalLevel(-1))
    }

    @Test
    fun rssiToSignalLevel_minus61_returnsGood() {
        assertEquals(SignalLevel.GOOD, rssiToSignalLevel(-61))
        assertEquals(SignalLevel.GOOD, rssiToSignalLevel(-70))
    }

    @Test
    fun rssiToSignalLevel_minus71_returnsFair() {
        assertEquals(SignalLevel.FAIR, rssiToSignalLevel(-71))
        assertEquals(SignalLevel.FAIR, rssiToSignalLevel(-80))
    }

    @Test
    fun rssiToSignalLevel_minus81_returnsPoor() {
        assertEquals(SignalLevel.POOR, rssiToSignalLevel(-81))
        assertEquals(SignalLevel.POOR, rssiToSignalLevel(-100))
    }

    // ── rssiToDistance ───────────────────────────────────────────────────────

    @Test
    fun rssiToDistance_zeroRssi_returnsMinusOne() {
        assertEquals(-1.0, rssiToDistance(0))
    }

    @Test
    fun rssiToDistance_positiveRssi_returnsMinusOne() {
        assertEquals(-1.0, rssiToDistance(10))
    }

    @Test
    fun rssiToDistance_minValue_returnsMinusOne() {
        assertEquals(-1.0, rssiToDistance(Int.MIN_VALUE))
    }

    @Test
    fun rssiToDistance_negativeRssi_returnsPositiveDistance() {
        val distance = rssiToDistance(-70)
        assertTrue(distance > 0.0, "Expected positive distance for rssi=-70, got $distance")
    }

    @Test
    fun rssiToDistance_strongerSignal_givesCloserDistance() {
        val close = rssiToDistance(-50)
        val far = rssiToDistance(-90)
        assertTrue(close < far, "Stronger signal (-50) should yield smaller distance than weaker (-90)")
    }

    // ── ConnectionQuality data class ─────────────────────────────────────────

    @Test
    fun connectionQuality_fields_returnCorrectValues() {
        val q = ConnectionQuality(
            rssiDbm = -65,
            signalLevel = SignalLevel.GOOD,
            estimatedDistanceMeters = 3.5,
            mtuBytes = 512,
            throughputBytesPerSecond = 1024L
        )
        assertEquals(-65, q.rssiDbm)
        assertEquals(SignalLevel.GOOD, q.signalLevel)
        assertEquals(3.5, q.estimatedDistanceMeters)
        assertEquals(512, q.mtuBytes)
        assertEquals(1024L, q.throughputBytesPerSecond)
    }

    @Test
    fun connectionQuality_equality_sameValues_areEqual() {
        val a = ConnectionQuality(-65, SignalLevel.GOOD, 3.5, 512, 1024L)
        val b = ConnectionQuality(-65, SignalLevel.GOOD, 3.5, 512, 1024L)
        assertEquals(a, b)
    }

    @Test
    fun connectionQuality_equality_differentRssi_areNotEqual() {
        val a = ConnectionQuality(-65, SignalLevel.GOOD, 3.5, 512, 1024L)
        val b = ConnectionQuality(-80, SignalLevel.GOOD, 3.5, 512, 1024L)
        assertNotEquals(a, b)
    }

    @Test
    fun connectionQuality_copy_changesOnlySpecifiedField() {
        val original = ConnectionQuality(-65, SignalLevel.GOOD, 3.5, 512, 1024L)
        val copy = original.copy(mtuBytes = 256)
        assertEquals(256, copy.mtuBytes)
        assertEquals(original.rssiDbm, copy.rssiDbm)
        assertEquals(original.signalLevel, copy.signalLevel)
    }

    // ── SignalLevel enum ─────────────────────────────────────────────────────

    @Test
    fun signalLevel_allValuesPresent() {
        val values = SignalLevel.entries
        assertTrue(SignalLevel.EXCELLENT in values)
        assertTrue(SignalLevel.GOOD in values)
        assertTrue(SignalLevel.FAIR in values)
        assertTrue(SignalLevel.POOR in values)
        assertTrue(SignalLevel.UNKNOWN in values)
    }
}
