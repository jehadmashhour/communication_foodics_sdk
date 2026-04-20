package com.foodics.crosscommunicationlibrary.modbus_tcp

import java.io.BufferedInputStream
import java.io.OutputStream

@Synchronized internal fun OutputStream.modbusWrite(data: ByteArray) { write(data); flush() }

internal fun BufferedInputStream.readModbusExact(n: Int): ByteArray? {
    if (n == 0) return ByteArray(0)
    val buf = ByteArray(n); var r = 0
    while (r < n) { val c = read(buf, r, n - r); if (c <= 0) return null; r += c }
    return buf
}

/**
 * Read one Modbus TCP ADU.
 * Returns null on EOF or a malformed frame.
 */
internal fun BufferedInputStream.readModbusFrame(): ModbusFrame? {
    val header = readModbusExact(6) ?: return null
    val txId   = ((header[0].toInt() and 0xFF) shl 8) or (header[1].toInt() and 0xFF)
    val length = ((header[4].toInt() and 0xFF) shl 8) or (header[5].toInt() and 0xFF)
    if (length < 2) return null                   // must contain at least Unit ID + FC
    val pdu  = readModbusExact(length) ?: return null
    val uid  = pdu[0]
    val fc   = pdu[1]
    val data = if (pdu.size > 2) pdu.copyOfRange(2, pdu.size) else ByteArray(0)
    return ModbusFrame(txId, uid, fc, data)
}
