import kotlin.math.pow

data class ConnectionQuality(
    val rssiDbm: Int,
    val signalLevel: SignalLevel,
    val estimatedDistanceMeters: Double,
    val mtuBytes: Int,
    val throughputBytesPerSecond: Long,
    // Continuous quality in [0, 1] derived from rssiDbm — use this for progress bars.
    // Avoids the 25% step jumps that come from going through SignalLevel buckets.
    val quality: Float = rssiToQuality(rssiDbm)
)

enum class SignalLevel { EXCELLENT, GOOD, FAIR, POOR, UNKNOWN }

fun rssiToSignalLevel(rssi: Int): SignalLevel = when {
    rssi == Int.MIN_VALUE -> SignalLevel.UNKNOWN
    rssi >= -60           -> SignalLevel.EXCELLENT
    rssi >= -70           -> SignalLevel.GOOD
    rssi >= -80           -> SignalLevel.FAIR
    else                  -> SignalLevel.POOR
}

// Maps RSSI directly to a continuous quality float in [0, 1].
// Anchored to the practical BLE range: -45 dBm (very close) → 1.0, -95 dBm (barely connected) → 0.0.
// Use this for quality bars. Avoids the discrete 25% jumps of signalLevelToQuality(rssiToSignalLevel(rssi)).
fun rssiToQuality(rssi: Int): Float {
    if (rssi == Int.MIN_VALUE) return 0f
    return ((rssi.toFloat() + 95f) / 50f).coerceIn(0f, 1f)
}

// Maps a SignalLevel bucket to a quality float. Useful for categorical display.
// For smooth progress bars, prefer rssiToQuality(rssiDbm) directly.
fun signalLevelToQuality(level: SignalLevel): Float = when (level) {
    SignalLevel.EXCELLENT -> 1.0f
    SignalLevel.GOOD      -> 0.75f
    SignalLevel.FAIR      -> 0.5f
    SignalLevel.POOR      -> 0.25f
    SignalLevel.UNKNOWN   -> 0.0f
}

// Log-distance path loss model: distance = 10 ^ ((txPower - rssi) / (10 * n))
// txPower: RSSI at 1 metre (-59 dBm typical for BLE chips).
// pathLossExponent: 3.0 suits obstructed indoor environments (restaurants, offices).
//   Free-space = 2.0, open office = 2.5–3.0, walls/equipment = 3.0–4.0.
fun rssiToDistance(rssi: Int, txPower: Int = -59, pathLossExponent: Double = 3.0): Double {
    if (rssi >= 0 || rssi == Int.MIN_VALUE) return -1.0
    return 10.0.pow((txPower - rssi).toDouble() / (10.0 * pathLossExponent))
}
