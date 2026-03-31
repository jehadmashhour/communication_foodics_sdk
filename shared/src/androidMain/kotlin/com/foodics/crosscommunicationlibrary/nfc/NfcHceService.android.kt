package com.foodics.crosscommunicationlibrary.nfc

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log

/**
 * NFC Host Card Emulation service — emulates an NFC Forum Type 4 NDEF tag.
 *
 * The service handles the ISO 7816-4 APDU dialogue used by NFC readers to
 * discover and read an NDEF message:
 *
 *   1. SELECT NDEF Application  (AID: D2 76 00 00 85 01 01)
 *   2. SELECT CC File           (File ID: E1 03)
 *   3. READ BINARY CC           → 15-byte Capability Container
 *   4. SELECT NDEF File         (File ID: E1 04)
 *   5. READ BINARY NDEF length  (offset 0, len 2)
 *   6. READ BINARY NDEF data    (offset 2, len = NLEN)
 *
 * [NfcHceNdefRegistry.ndefFileData] must be populated before the service
 * handles any tap. [NFCServerHandler] sets this before starting the TCP server.
 */
class NfcHceService : HostApduService() {

    companion object {
        private const val TAG = "NfcHceService"

        // NDEF Application AID: D2 76 00 00 85 01 01
        private val NDEF_AID = byteArrayOf(0xD2.toByte(), 0x76, 0x00, 0x00, 0x85.toByte(), 0x01, 0x01)

        // Capability Container File ID and content (read-only, max NDEF 255 bytes)
        private val FILE_ID_CC   = byteArrayOf(0xE1.toByte(), 0x03)
        private val FILE_ID_NDEF = byteArrayOf(0xE1.toByte(), 0x04)

        // CC file: CCLEN=15, version 2.0, maxLe=127, maxLc=0, NDEF Control TLV
        private val CC_FILE = byteArrayOf(
            0x00, 0x0F, 0x20, 0x00, 0x7F, 0x00, 0x00,
            0x04, 0x06, 0xE1.toByte(), 0x04, 0x00, 0xFF.toByte(), 0x00, 0xFF.toByte()
        )

        // Status words
        private val SW_OK             = byteArrayOf(0x90.toByte(), 0x00)
        private val SW_FILE_NOT_FOUND = byteArrayOf(0x6A.toByte(), 0x82.toByte())
        private val SW_WRONG_P1P2     = byteArrayOf(0x6B.toByte(), 0x00)
        private val SW_UNKNOWN        = byteArrayOf(0x6F.toByte(), 0x00)
    }

    private enum class SelectedFile { NONE, CC, NDEF }

    private var selected = SelectedFile.NONE

    // ── HostApduService ───────────────────────────────────────────────────────

    override fun processCommandApdu(apdu: ByteArray, extras: Bundle?): ByteArray {
        Log.d(TAG, "APDU: ${apdu.toHex()}")
        return when {
            isSelectAid(apdu)  -> handleSelectAid()
            isSelectFile(apdu) -> handleSelectFile(apdu)
            isReadBinary(apdu) -> handleReadBinary(apdu)
            else               -> { Log.w(TAG, "Unknown APDU"); SW_UNKNOWN }
        }
    }

    override fun onDeactivated(reason: Int) {
        selected = SelectedFile.NONE
        Log.d(TAG, "Deactivated, reason=$reason")
    }

    // ── APDU handlers ────────────────────────────────────────────────────────

    /** SELECT by AID — selects the NDEF Application. */
    private fun handleSelectAid(): ByteArray {
        selected = SelectedFile.NONE
        Log.d(TAG, "SELECT NDEF Application → OK")
        return SW_OK
    }

    /** SELECT by File ID — selects CC or NDEF file. */
    private fun handleSelectFile(apdu: ByteArray): ByteArray {
        if (apdu.size < 7) return SW_UNKNOWN
        val fileId = apdu.slice(5..6).toByteArray()
        selected = when {
            fileId.contentEquals(FILE_ID_CC)   -> { Log.d(TAG, "SELECT CC File → OK"); SelectedFile.CC }
            fileId.contentEquals(FILE_ID_NDEF) -> { Log.d(TAG, "SELECT NDEF File → OK"); SelectedFile.NDEF }
            else                               -> { Log.w(TAG, "SELECT unknown file ${fileId.toHex()}"); return SW_FILE_NOT_FOUND }
        }
        return SW_OK
    }

    /** READ BINARY — returns requested slice of the selected file. */
    private fun handleReadBinary(apdu: ByteArray): ByteArray {
        if (apdu.size < 5) return SW_UNKNOWN
        val offset = ((apdu[2].toInt() and 0xFF) shl 8) or (apdu[3].toInt() and 0xFF)
        val length = apdu[4].toInt() and 0xFF

        val fileData = when (selected) {
            SelectedFile.CC   -> CC_FILE
            SelectedFile.NDEF -> NfcHceNdefRegistry.ndefFileData
                ?: run { Log.w(TAG, "NDEF data not set"); return SW_FILE_NOT_FOUND }
            SelectedFile.NONE -> return SW_WRONG_P1P2
        }

        if (offset >= fileData.size) return SW_WRONG_P1P2
        val end  = minOf(offset + length, fileData.size)
        val data = fileData.slice(offset until end).toByteArray()
        Log.d(TAG, "READ BINARY file=${selected} off=$offset len=$length → ${data.size} bytes")
        return data + SW_OK
    }

    // ── Command classification ────────────────────────────────────────────────

    private fun isSelectAid(apdu: ByteArray): Boolean =
        apdu.size >= 5 &&
        apdu[0] == 0x00.toByte() &&
        apdu[1] == 0xA4.toByte() &&
        apdu[2] == 0x04.toByte() &&
        apdu.size >= 5 + (apdu[4].toInt() and 0xFF) &&
        apdu.slice(5 until 5 + NDEF_AID.size).toByteArray().contentEquals(NDEF_AID)

    private fun isSelectFile(apdu: ByteArray): Boolean =
        apdu.size >= 7 &&
        apdu[0] == 0x00.toByte() &&
        apdu[1] == 0xA4.toByte() &&
        apdu[2] == 0x00.toByte()

    private fun isReadBinary(apdu: ByteArray): Boolean =
        apdu.size >= 5 &&
        apdu[0] == 0x00.toByte() &&
        apdu[1] == 0xB0.toByte()

    // ── Debug helper ─────────────────────────────────────────────────────────

    private fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }
}

/**
 * Singleton through which [NFCServerHandler] sets the live NDEF file content
 * for [NfcHceService] to serve when a reader taps.
 */
internal object NfcHceNdefRegistry {
    /** Raw NDEF File bytes: [2-byte NLEN] + [NDEF message]. Null = not advertising. */
    @Volatile var ndefFileData: ByteArray? = null
}
