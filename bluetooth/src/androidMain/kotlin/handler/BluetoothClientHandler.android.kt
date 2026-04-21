package handler

import BluetoothConstants.CHAR_FROM_CLIENT_UUID
import BluetoothConstants.CHAR_TO_CLIENT_UUID
import BluetoothConstants.SERVICE_UUID
import BluetoothConstants.TAG
import ConnectionQuality
import rssiToDistance
import rssiToSignalLevel
import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import client.Client
import client.ClientCharacteristic
import client.WriteType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import scanner.IoTDevice
import scanner.Scanner

@SuppressLint("MissingPermission")
actual class BluetoothClientHandler(private val context: Context) {

    private val client: Client = Client(context)
    private val scanner: Scanner = Scanner(context)
    private var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var clientToServerChar: ClientCharacteristic
    private lateinit var clientFromServerChar: ClientCharacteristic

    @Volatile private var bytesSent = 0L
    @Volatile private var bytesReceived = 0L
    private var negotiatedMtu = 23

    fun scan(): Flow<List<IoTDevice>> = scanner.scan()

    suspend fun connect(device: IoTDevice) {
        Log.i(TAG, "Attempting to connect to device: ${device.name} (${device.address})")
        client.connect(device, scope)

        val service = client.discoverServices().findService(SERVICE_UUID)
            ?: throw Exception("Bluetooth service $SERVICE_UUID not found on ${device.name}")

        clientToServerChar = service.findCharacteristic(CHAR_FROM_CLIENT_UUID)
            ?: throw Exception("Characteristic $CHAR_FROM_CLIENT_UUID not found on ${device.name}")

        clientFromServerChar = service.findCharacteristic(CHAR_TO_CLIENT_UUID)
            ?: throw Exception("Characteristic $CHAR_TO_CLIENT_UUID not found on ${device.name}")

        negotiatedMtu = client.requestMtu(512)
        Log.i(TAG, "Connected to ${device.name}, MTU=$negotiatedMtu")
    }

    suspend fun sendToServer(data: ByteArray, writeType: WriteType) {
        clientToServerChar.write(data, writeType)
        bytesSent += data.size
        Log.d(TAG, "Sent to server: ${String(data)}")
    }

    suspend fun receiveFromServer(): Flow<ByteArray> = merge(
        clientFromServerChar.getNotifications().onEach { bytesReceived += it.size },
        client.disconnectEvent().map { throw Exception("Server disconnected") }
    )

    fun connectionQuality(): Flow<ConnectionQuality> = flow {
        var windowStart = System.currentTimeMillis()
        while (true) {
            delay(3000)
            val rssi = client.readRssi()
            val now = System.currentTimeMillis()
            val windowSec = ((now - windowStart).coerceAtLeast(1)) / 1000.0
            val throughput = ((bytesSent + bytesReceived) / windowSec).toLong()
            bytesSent = 0L
            bytesReceived = 0L
            windowStart = now
            emit(
                ConnectionQuality(
                    rssiDbm = rssi,
                    signalLevel = rssiToSignalLevel(rssi),
                    estimatedDistanceMeters = rssiToDistance(rssi),
                    mtuBytes = negotiatedMtu,
                    throughputBytesPerSecond = throughput
                )
            )
        }
    }.flowOn(Dispatchers.IO)

    suspend fun disconnect() {
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        bytesSent = 0L
        bytesReceived = 0L
        client.disconnect()
        Log.i(TAG, "Disconnected from server")
    }
}
