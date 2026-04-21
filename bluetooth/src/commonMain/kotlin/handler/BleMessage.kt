package handler

data class BleMessage(val client: BleClient, val data: ByteArray)
