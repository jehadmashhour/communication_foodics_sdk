package handler

import BluetoothConstants.CHAR_FROM_CLIENT_UUID
import BluetoothConstants.CHAR_TO_CLIENT_UUID
import BluetoothConstants.SERVICE_UUID
import ConnectionQuality
import client.Client
import client.ClientCharacteristic
import client.IOSClientWrapper
import client.IOSClient
import client.WriteType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import platform.Foundation.*
import rssiToDistance
import rssiToSignalLevel
import scanner.IoTDevice
import scanner.Scanner

actual class BluetoothClientHandler {

    private val iosClientWrapper = IOSClientWrapper(IOSClient())
    private val client: Client = Client(iosClientWrapper)
    private val scanner: Scanner = Scanner(iosClientWrapper)
    private var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var clientToServerChar: ClientCharacteristic
    private lateinit var clientFromServerChar: ClientCharacteristic

    private var bytesSent = 0L
    private var bytesReceived = 0L

    fun scan(): Flow<List<IoTDevice>> = scanner.scan()

    suspend fun connect(device: IoTDevice) {
        client.connect(device, scope)

        val service = client.discoverServices().findService(SERVICE_UUID)
            ?: throw Exception("Bluetooth service $SERVICE_UUID not found on ${device.name}")

        clientToServerChar = service.findCharacteristic(CHAR_FROM_CLIENT_UUID)
            ?: throw Exception("Characteristic $CHAR_FROM_CLIENT_UUID not found on ${device.name}")

        clientFromServerChar = service.findCharacteristic(CHAR_TO_CLIENT_UUID)
            ?: throw Exception("Characteristic $CHAR_TO_CLIENT_UUID not found on ${device.name}")

        iosClientWrapper.value.startRssiPolling(scope)
    }

    suspend fun sendToServer(data: ByteArray, writeType: WriteType) {
        clientToServerChar.write(data, writeType)
        bytesSent += data.size
    }

    suspend fun receiveFromServer(): Flow<ByteArray> = merge(
        clientFromServerChar.getNotifications().onEach { bytesReceived += it.size },
        iosClientWrapper.value.disconnectEvent.map { throw Exception("Server disconnected") }
    )

    fun connectionQuality(): Flow<ConnectionQuality> = flow {
        var windowStart = (NSDate().timeIntervalSince1970 * 1000).toLong()
        while (true) {
            delay(3000)
            val rssi = iosClientWrapper.value.rssiFlow.value
            val now = (NSDate().timeIntervalSince1970 * 1000).toLong()
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
                    mtuBytes = iosClientWrapper.value.mtu(),
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
    }
}
