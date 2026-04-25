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
    fun rssiToDistance_atTxPower_returnsOneMetre() {
        // At exactly txPower (-59), log-distance model gives 10^0 = 1.0 m
        assertEquals(1.0, rssiToDistance(-59), 0.001)
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

    @Test
    fun rssiToDistance_weakSignal_givesReasonableRange() {
        // At -80 dBm with n=2.7 and txPower=-59: 10^(21/27) ≈ 6 metres
        val distance = rssiToDistance(-80)
        assertTrue(distance in 4.0..10.0, "At -80 dBm expected 4–10 m indoors, got $distance")
    }

    @Test
    fun rssiToDistance_strongSignal_givesSubMetreRange() {
        // At -50 dBm with n=2.7 and txPower=-59: 10^(-9/27) ≈ 0.46 metres
        val distance = rssiToDistance(-50)
        assertTrue(distance in 0.1..1.0, "At -50 dBm expected 0.1–1.0 m, got $distance")
    }

    // ── rssiToQuality ────────────────────────────────────────────────────────

    @Test
    fun rssiToQuality_minValue_returnsZero() {
        assertEquals(0.0f, rssiToQuality(Int.MIN_VALUE))
    }

    @Test
    fun rssiToQuality_atBestAnchor_returnsOne() {
        // -45 dBm is the "best practical" anchor → 1.0
        assertEquals(1.0f, rssiToQuality(-45))
    }

    @Test
    fun rssiToQuality_aboveBestAnchor_clampedToOne() {
        assertEquals(1.0f, rssiToQuality(-30))
        assertEquals(1.0f, rssiToQuality(-10))
    }

    @Test
    fun rssiToQuality_atWorstAnchor_returnsZero() {
        // -95 dBm is the "barely connected" anchor → 0.0
        assertEquals(0.0f, rssiToQuality(-95))
    }

    @Test
    fun rssiToQuality_belowWorstAnchor_clampedToZero() {
        assertEquals(0.0f, rssiToQuality(-100))
        assertEquals(0.0f, rssiToQuality(-120))
    }

    @Test
    fun rssiToQuality_midPoint_returnsHalf() {
        // Midpoint between -45 and -95 is -70 dBm → 0.5
        assertEquals(0.5f, rssiToQuality(-70), 0.001f)
    }

    @Test
    fun rssiToQuality_isMonotonicallyNonDecreasing() {
        val rssiValues = listOf(-95, -85, -75, -65, -55, -45)
        val qualities = rssiValues.map { rssiToQuality(it) }
        for (i in 0 until qualities.size - 1) {
            assertTrue(
                qualities[i] <= qualities[i + 1],
                "Quality should increase as RSSI strengthens: $qualities"
            )
        }
    }

    @Test
    fun rssiToQuality_changeIsSmooth_noLargeJumps() {
        // Adjacent dBm values should differ by no more than 2.1% (1/50 of range)
        for (rssi in -94 downTo -46) {
            val diff = rssiToQuality(rssi + 1) - rssiToQuality(rssi)
            assertTrue(diff <= 0.025f, "Quality jump between ${rssi+1} and $rssi dBm is $diff (> 2.5%)")
        }
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

    @Test
    fun connectionQuality_quality_isAutoComputedFromRssi() {
        val q = ConnectionQuality(-65, SignalLevel.GOOD, 3.5, 512, 1024L)
        assertEquals(rssiToQuality(-65), q.quality, 0.001f)
    }

    @Test
    fun connectionQuality_quality_atDifferentRssiValues_matchesRssiToQuality() {
        listOf(-45, -60, -70, -80, -95).forEach { rssi ->
            val q = ConnectionQuality(rssi, rssiToSignalLevel(rssi), rssiToDistance(rssi), 512, 0L)
            assertEquals(rssiToQuality(rssi), q.quality, 0.001f,
                "quality field should match rssiToQuality($rssi)")
        }
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

    // ── signalLevelToQuality ─────────────────────────────────────────────────

    @Test
    fun signalLevelToQuality_excellent_returnsOne() {
        assertEquals(1.0f, signalLevelToQuality(SignalLevel.EXCELLENT))
    }

    @Test
    fun signalLevelToQuality_good_returnsThreeQuarters() {
        assertEquals(0.75f, signalLevelToQuality(SignalLevel.GOOD))
    }

    @Test
    fun signalLevelToQuality_fair_returnsHalf() {
        assertEquals(0.5f, signalLevelToQuality(SignalLevel.FAIR))
    }

    @Test
    fun signalLevelToQuality_poor_returnsQuarter() {
        assertEquals(0.25f, signalLevelToQuality(SignalLevel.POOR))
    }

    @Test
    fun signalLevelToQuality_unknown_returnsZero() {
        assertEquals(0.0f, signalLevelToQuality(SignalLevel.UNKNOWN))
    }

    // ── RSSI → quality pipeline ──────────────────────────────────────────────

    @Test
    fun rssiPipeline_excellentBoundary_returnsOne() {
        assertEquals(1.0f, signalLevelToQuality(rssiToSignalLevel(-60)))
        assertEquals(1.0f, signalLevelToQuality(rssiToSignalLevel(-50)))
    }

    @Test
    fun rssiPipeline_goodBoundary_returnsThreeQuarters() {
        assertEquals(0.75f, signalLevelToQuality(rssiToSignalLevel(-61)))
        assertEquals(0.75f, signalLevelToQuality(rssiToSignalLevel(-70)))
    }

    @Test
    fun rssiPipeline_fairBoundary_returnsHalf() {
        assertEquals(0.5f, signalLevelToQuality(rssiToSignalLevel(-71)))
        assertEquals(0.5f, signalLevelToQuality(rssiToSignalLevel(-80)))
    }

    @Test
    fun rssiPipeline_poorBoundary_returnsQuarter() {
        assertEquals(0.25f, signalLevelToQuality(rssiToSignalLevel(-81)))
        assertEquals(0.25f, signalLevelToQuality(rssiToSignalLevel(-100)))
    }

    @Test
    fun rssiPipeline_minValue_returnsZero() {
        assertEquals(0.0f, signalLevelToQuality(rssiToSignalLevel(Int.MIN_VALUE)))
    }

    @Test
    fun rssiPipeline_qualityIsMonotonicallyNonIncreasing() {
        val rssiValues = listOf(-50, -61, -71, -81, Int.MIN_VALUE)
        val qualities = rssiValues.map { signalLevelToQuality(rssiToSignalLevel(it)) }
        for (i in 0 until qualities.size - 1) {
            assertTrue(
                qualities[i] >= qualities[i + 1],
                "Quality should not increase as RSSI weakens: $qualities"
            )
        }
    }
}
