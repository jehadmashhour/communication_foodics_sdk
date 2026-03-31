package com.foodics.crosscommunicationlibrary.nfc

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

private const val TAG = "NFCReaderHelper"

/**
 * Composable that enables NFC reader mode for as long as it is in the
 * composition. When a compatible NFC tag / HCE device is tapped, it reads
 * the NDEF payload via ISO 7816-4 APDU commands and calls [onNfcPayload]
 * with the decoded JSON string.
 *
 * Works with:
 *  • Android HCE servers running [NfcHceService]
 *  • Physical NFC Forum Type 4 tags carrying the same JSON NDEF record
 *
 * Usage (client side):
 * ```kotlin
 * val channel = remember { NFCCommunicationChannel() }
 * NFCReaderHelper { json -> channel.clientHandler.processNfcPayload(json) }
 * ```
 *
 * Requires the ACCESS_NFC permission and NFC to be enabled on the device.
 */
@Composable
fun NFCReaderHelper(onNfcPayload: (String) -> Unit) {
    val context  = LocalContext.current
    val activity = context as? Activity ?: return

    DisposableEffect(activity) {
        val adapter = NfcAdapter.getDefaultAdapter(context)
        if (adapter == null) {
            Log.w(TAG, "NFC not available on this device")
            return@DisposableEffect onDispose {}
        }

        val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B

        adapter.enableReaderMode(activity, { tag ->
            readNdefFromTag(tag, onNfcPayload)
        }, flags, null)

        onDispose {
            runCatching { adapter.disableReaderMode(activity) }
        }
    }
}

/**
 * Reads the NDEF message from [tag] using ISO 7816-4 APDU commands, then
 * decodes the Text record payload and delivers it to [onPayload].
 */
private fun readNdefFromTag(tag: Tag, onPayload: (String) -> Unit) {
    val isoDep = IsoDep.get(tag) ?: run { Log.w(TAG, "Tag is not IsoDep"); return }
    runCatching {
        isoDep.connect()

        // 1. SELECT NDEF Application (AID D2 76 00 00 85 01 01)
        val selApp = isoDep.transceive(
            byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, 0x07,
                0xD2.toByte(), 0x76, 0x00, 0x00, 0x85.toByte(), 0x01, 0x01, 0x00)
        )
        if (!selApp.isSW_OK()) { Log.w(TAG, "SELECT App failed: ${selApp.toHex()}"); return }

        // 2. SELECT NDEF File (E1 04) — skip CC selection for brevity
        val selNdef = isoDep.transceive(
            byteArrayOf(0x00, 0xA4.toByte(), 0x00, 0x0C, 0x02, 0xE1.toByte(), 0x04)
        )
        if (!selNdef.isSW_OK()) { Log.w(TAG, "SELECT NDEF failed: ${selNdef.toHex()}"); return }

        // 3. READ BINARY — NLEN (2 bytes at offset 0)
        val lenResp = isoDep.transceive(byteArrayOf(0x00, 0xB0.toByte(), 0x00, 0x00, 0x02))
        if (lenResp.size < 4 || !lenResp.takeLast(2).toByteArray().isSW_OK()) {
            Log.w(TAG, "READ BINARY (len) failed"); return
        }
        val nlen = ((lenResp[0].toInt() and 0xFF) shl 8) or (lenResp[1].toInt() and 0xFF)
        if (nlen <= 0 || nlen > 255) { Log.w(TAG, "Invalid NLEN=$nlen"); return }

        // 4. READ BINARY — NDEF message (offset 2, length NLEN)
        val ndefResp = isoDep.transceive(
            byteArrayOf(0x00, 0xB0.toByte(), 0x00, 0x02, nlen.toByte())
        )
        if (ndefResp.size < nlen + 2 || !ndefResp.takeLast(2).toByteArray().isSW_OK()) {
            Log.w(TAG, "READ BINARY (ndef) failed"); return
        }
        val ndefBytes = ndefResp.take(nlen).toByteArray()

        // 5. Parse NDEF Text record
        //    NDEF record: header(1), typeLen(1), payloadLen(1), type(typeLen), payload
        if (ndefBytes.size < 4) { Log.w(TAG, "NDEF too short"); return }
        val typeLen    = ndefBytes[1].toInt() and 0xFF
        val payloadLen = ndefBytes[2].toInt() and 0xFF
        val payloadOff = 3 + typeLen  // skip header + typeLen + payloadLen + type bytes
        if (ndefBytes.size < payloadOff + payloadLen) { Log.w(TAG, "NDEF payload truncated"); return }
        val payload = ndefBytes.slice(payloadOff until payloadOff + payloadLen).toByteArray()
        val text    = parseNdefTextPayload(payload) ?: run { Log.w(TAG, "Payload parse failed"); return }
        Log.i(TAG, "NFC payload: $text")
        onPayload(text)
    }.onFailure { Log.e(TAG, "NFC read error", it) }
    runCatching { isoDep.close() }
}

private fun ByteArray.isSW_OK(): Boolean = size >= 2 && this[size - 2] == 0x90.toByte() && this[size - 1] == 0x00.toByte()
private fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }
