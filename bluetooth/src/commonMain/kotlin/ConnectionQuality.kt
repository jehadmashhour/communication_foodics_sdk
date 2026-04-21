import kotlin.math.pow

data class ConnectionQuality(
    val rssiDbm: Int,
    val signalLevel: SignalLevel,
    val estimatedDistanceMeters: Double,
    val mtuBytes: Int,
    val throughputBytesPerSecond: Long
)

enum class SignalLevel { EXCELLENT, GOOD, FAIR, POOR, UNKNOWN }

fun rssiToSignalLevel(rssi: Int): SignalLevel = when {
    rssi == Int.MIN_VALUE -> SignalLevel.UNKNOWN
    rssi >= -60           -> SignalLevel.EXCELLENT
    rssi >= -70           -> SignalLevel.GOOD
    rssi >= -80           -> SignalLevel.FAIR
    else                  -> SignalLevel.POOR
}

fun rssiToDistance(rssi: Int, txPower: Int = -59): Double {
    if (rssi >= 0 || rssi == Int.MIN_VALUE) return -1.0
    val ratio = rssi.toDouble() / txPower.toDouble()
    return if (ratio < 1.0) ratio.pow(10.0)
    else 0.89976 * ratio.pow(7.7095) + 0.111
}
