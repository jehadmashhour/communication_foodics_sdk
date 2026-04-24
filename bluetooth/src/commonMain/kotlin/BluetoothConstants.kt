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
    const val CLIENT_DISCONNECT_SIGNAL = "__CSTOP__"

    // Bridge-mode multiplexing: sent over the iOS-server ↔ Android-client pipe to carry
    // the second logical channel (iOS-as-client ↔ Android-as-server) on the same connection.
    const val BRIDGE_INIT_PREFIX       = "__BINIT__:"   // iOS→Android: bridge activated, carries iOS display name
    const val BRIDGE_C2S_PREFIX        = "__BC2S__:"    // iOS→Android: iOS-client sending to Android-server
    const val BRIDGE_S2C_PREFIX        = "__BS2C__:"    // Android→iOS: Android-server sending to iOS-client
    const val BRIDGE_DISCONNECT_PREFIX = "__BDISC__"    // iOS→Android: iOS-client is disconnecting from bridge
    const val BRIDGE_CLIENT_ID         = "__bridge_client__"
}