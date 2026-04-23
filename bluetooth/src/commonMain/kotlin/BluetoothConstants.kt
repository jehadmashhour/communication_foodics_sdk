import com.benasher44.uuid.uuidFrom

object BluetoothConstants {
    val SERVICE_UUID = uuidFrom("00001523-1212-efde-1523-785feabcd123")
    val ADVERTISER_UUID = uuidFrom("0000180F-0000-1000-8000-00805F9B34FB")

    val CHAR_FROM_CLIENT_UUID = uuidFrom("00001524-1212-efde-1523-785feabcd123") // client → server
    val CHAR_TO_CLIENT_UUID = uuidFrom("00001525-1212-efde-1523-785feabcd123")   // server → client
    val CCCD_UUID = uuidFrom("00002902-0000-1000-8000-00805f9b34fb")             // Client Characteristic Config Descriptor

    const val TAG = "BluetoothCommunicationChannel"
    const val HELLO_PREFIX = "__HELLO__:"
    const val SERVER_STOP_SIGNAL = "__STOP__"
}