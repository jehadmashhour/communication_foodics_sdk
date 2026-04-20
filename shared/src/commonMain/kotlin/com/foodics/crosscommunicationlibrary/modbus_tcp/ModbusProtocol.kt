package com.foodics.crosscommunicationlibrary.modbus_tcp

internal const val MODBUS_DISCOVERY_PORT   = 5557
internal const val MODBUS_BEACON_INTERVAL_MS = 2_000L
internal const val MODBUS_DEVICE_TTL_MS      = 10_000L

// Custom function codes (0x41–0x48 are user-defined in the Modbus spec)
internal const val FC_UPLOAD: Byte = 0x41   // client → server
internal const val FC_PUSH:   Byte = 0x42   // server → client (unsolicited)

/**
 * Build a Modbus TCP ADU:
 *   MBAP header (6 bytes) + Unit ID (1) + Function Code (1) + data
 */
internal fun buildModbusAdu(functionCode: Byte, data: ByteArray, unitId: Byte = 0x01): ByteArray {
    val txId   = kotlin.random.Random.nextInt(0xFFFF)
    val length = 1 + 1 + data.size   // Unit ID + FC + data
    return ByteArray(6 + length).also { buf ->
        buf[0] = (txId ushr 8 and 0xFF).toByte()
        buf[1] = (txId        and 0xFF).toByte()
        buf[2] = 0x00  // Protocol ID high
        buf[3] = 0x00  // Protocol ID low
        buf[4] = (length ushr 8 and 0xFF).toByte()
        buf[5] = (length        and 0xFF).toByte()
        buf[6] = unitId
        buf[7] = functionCode
        data.copyInto(buf, 8)
    }
}

internal data class ModbusFrame(val txId: Int, val unitId: Byte, val fc: Byte, val data: ByteArray)

/** UDP beacon payload: "name|id|tcpPort" */
internal fun modbusBeacon(name: String, id: String, port: Int) =
    "$name|$id|$port".encodeToByteArray()

internal fun modbusParseBeacon(data: ByteArray): Triple<String, String, Int>? {
    val parts = data.decodeToString().split("|")
    if (parts.size < 3) return null
    val port = parts[2].toIntOrNull() ?: return null
    return Triple(parts[0], parts[1], port)
}
