package com.foodics.crosscommunicationlibrary.serial

import java.io.File
import java.io.FileInputStream

/** Serial port device paths probed on Android. */
private val SERIAL_CANDIDATES = listOf(
    "/dev/ttyS0", "/dev/ttyS1", "/dev/ttyS2", "/dev/ttyS3",
    "/dev/ttyUSB0", "/dev/ttyUSB1", "/dev/ttyUSB2",
    "/dev/ttyACM0", "/dev/ttyACM1",
    "/dev/ttyHS0", "/dev/ttyHS1",   // Qualcomm high-speed UART
    "/dev/ttyGS0",                   // USB gadget serial
    "/dev/ttyMT0", "/dev/ttyMT1"    // MediaTek UART
)

/** Returns every serial port path that exists and is accessible on this device. */
internal fun enumerateSerialPorts(): List<String> =
    SERIAL_CANDIDATES.filter { path -> File(path).let { it.exists() && it.canRead() } }

/**
 * Configures baud rate and raw mode via [stty].
 * Works on industrial Android devices where the shell has access to the port.
 * Fails silently on consumer devices — port will use its pre-configured settings.
 */
internal fun configureSerialPort(portPath: String, baudRate: Int) {
    runCatching {
        Runtime.getRuntime()
            .exec(arrayOf("stty", "-F", portPath,
                baudRate.toString(), "cs8", "-cstopb", "-parenb", "raw", "-echo"))
            .waitFor()
    }
}

// ── Framing ────────────────────────────────────────────────────────────────────
// 4-byte big-endian length header + payload, same scheme as TCP_SOCKET.

private const val MAX_SERIAL_FRAME = 65_536

internal fun serialFrameEncode(data: ByteArray): ByteArray {
    val len = data.size
    val out = ByteArray(4 + len)
    out[0] = (len ushr 24 and 0xFF).toByte()
    out[1] = (len ushr 16 and 0xFF).toByte()
    out[2] = (len ushr 8  and 0xFF).toByte()
    out[3] = (len         and 0xFF).toByte()
    data.copyInto(out, destinationOffset = 4)
    return out
}

internal fun FileInputStream.serialReadFrame(): ByteArray? {
    val header = ByteArray(4)
    var total = 0
    while (total < 4) {
        val n = read(header, total, 4 - total)
        if (n < 0) return null
        total += n
    }
    val len = ((header[0].toInt() and 0xFF) shl 24) or
              ((header[1].toInt() and 0xFF) shl 16) or
              ((header[2].toInt() and 0xFF) shl 8)  or
               (header[3].toInt() and 0xFF)
    if (len <= 0 || len > MAX_SERIAL_FRAME) return null
    val payload = ByteArray(len)
    var read = 0
    while (read < len) {
        val n = read(payload, read, len - read)
        if (n < 0) return null
        read += n
    }
    return payload
}
