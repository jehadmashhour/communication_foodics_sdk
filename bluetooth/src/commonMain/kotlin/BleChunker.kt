// Magic marker — null bytes won't appear in any UTF-8 text, making false positives extremely unlikely.
private val CHUNK_MAGIC = byteArrayOf(0x00, 0x43, 0x48, 0x4B, 0x00)
private const val CHUNK_HEADER_SIZE = 10 // 5 magic + 1 msgId + 2 chunkIdx + 2 totalChunks

object BleChunker {
    fun isChunk(data: ByteArray): Boolean =
        data.size > CHUNK_HEADER_SIZE &&
        data[0] == 0x00.toByte() && data[1] == 0x43.toByte() &&
        data[2] == 0x48.toByte() && data[3] == 0x4B.toByte() && data[4] == 0x00.toByte()

    /** Split [data] into BLE-safe packets. [maxPayload] is the full ATT payload (MTU - 3). */
    fun buildChunks(data: ByteArray, maxPayload: Int, msgId: Byte): List<ByteArray> {
        val chunkDataSize = (maxPayload - CHUNK_HEADER_SIZE).coerceAtLeast(1)
        val totalChunks = (data.size + chunkDataSize - 1) / chunkDataSize
        return (0 until totalChunks).map { idx ->
            val from = idx * chunkDataSize
            val to = minOf(from + chunkDataSize, data.size)
            buildPacket(msgId, idx, totalChunks, data.copyOfRange(from, to))
        }
    }

    fun parseHeader(data: ByteArray): Triple<Byte, Int, Int>? {
        if (!isChunk(data)) return null
        val msgId = data[5]
        val chunkIdx = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
        val totalChunks = ((data[8].toInt() and 0xFF) shl 8) or (data[9].toInt() and 0xFF)
        return Triple(msgId, chunkIdx, totalChunks)
    }

    fun extractPayload(data: ByteArray): ByteArray = data.copyOfRange(CHUNK_HEADER_SIZE, data.size)

    private fun buildPacket(msgId: Byte, idx: Int, total: Int, payload: ByteArray): ByteArray {
        val header = ByteArray(CHUNK_HEADER_SIZE)
        CHUNK_MAGIC.copyInto(header)
        header[5] = msgId
        header[6] = ((idx shr 8) and 0xFF).toByte()
        header[7] = (idx and 0xFF).toByte()
        header[8] = ((total shr 8) and 0xFF).toByte()
        header[9] = (total and 0xFF).toByte()
        return header + payload
    }
}

class ChunkReassembler {
    private val buffers = mutableMapOf<Byte, Array<ByteArray?>>()

    /** Returns the reassembled message when all chunks have arrived, null while still incomplete. */
    fun process(data: ByteArray): ByteArray? {
        val (msgId, chunkIdx, totalChunks) = BleChunker.parseHeader(data) ?: return null
        if (totalChunks <= 0 || chunkIdx >= totalChunks) return null
        val buffer = buffers.getOrPut(msgId) { arrayOfNulls(totalChunks) }
        buffer[chunkIdx] = BleChunker.extractPayload(data)
        return if (buffer.all { it != null }) {
            buffers.remove(msgId)
            buffer.filterNotNull().fold(byteArrayOf()) { acc, chunk -> acc + chunk }
        } else null
    }
}
