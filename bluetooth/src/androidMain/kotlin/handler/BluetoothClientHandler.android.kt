package handler

import BluetoothConstants.CHAR_FROM_CLIENT_UUID
import BluetoothConstants.CHAR_TO_CLIENT_UUID
import BluetoothConstants.SERVICE_UUID
import BluetoothConstants.TAG
import ConnectionQuality
import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import client.Client
import client.ClientCharacteristic
import client.WriteType
import com.foodics.crosscommunicationlibrary.logger.CommunicationLogger
import com.foodics.crosscommunicationlibrary.logger.debug
import com.foodics.crosscommunicationlibrary.logger.error
import com.foodics.crosscommunicationlibrary.logger.info
import com.foodics.crosscommunicationlibrary.logger.warn
import rssiToDistance
import rssiToSignalLevel
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

private const val LOG_TITLE = "BLE_CLIENT"

@SuppressLint("MissingPermission")
actual class BluetoothClientHandler(
    private val context: Context,
    private val logger: CommunicationLogger?
) {

    private val client: Client = Client(context)
    private val scanner: Scanner = Scanner(context)
    private var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var clientToServerChar: ClientCharacteristic
    private lateinit var clientFromServerChar: ClientCharacteristic

    @Volatile private var bytesSent = 0L
    @Volatile private var bytesReceived = 0L
    private var negotiatedMtu = 23

    fun scan(): Flow<List<IoTDevice>> {
        logger?.info(LOG_TITLE, "BLE scan started")
        return scanner.scan().onEach { devices ->
            if (devices.isNotEmpty()) {
                logger?.debug(
                    LOG_TITLE, "BLE devices found",
                    mapOf("count" to devices.size, "names" to devices.joinToString { it.name })
                )
            }
        }
    }

    suspend fun connect(device: IoTDevice) {
        Log.i(TAG, "Attempting to connect to device: ${device.name} (${device.address})")
        logger?.info(LOG_TITLE, "Connecting to BLE device", mapOf("device_name" to device.name, "device_address" to device.address))

        client.connect(device, scope)

        val service = client.discoverServices().findService(SERVICE_UUID)
            ?: run {
                logger?.error(LOG_TITLE, "BLE service not found on ${device.name}", extra = mapOf("service_uuid" to SERVICE_UUID))
                throw Exception("Bluetooth service $SERVICE_UUID not found on ${device.name}")
            }

        clientToServerChar = service.findCharacteristic(CHAR_FROM_CLIENT_UUID)
            ?: run {
                logger?.error(LOG_TITLE, "Characteristic not found", extra = mapOf("char_uuid" to CHAR_FROM_CLIENT_UUID))
                throw Exception("Characteristic $CHAR_FROM_CLIENT_UUID not found on ${device.name}")
            }

        clientFromServerChar = service.findCharacteristic(CHAR_TO_CLIENT_UUID)
            ?: run {
                logger?.error(LOG_TITLE, "Characteristic not found", extra = mapOf("char_uuid" to CHAR_TO_CLIENT_UUID))
                throw Exception("Characteristic $CHAR_TO_CLIENT_UUID not found on ${device.name}")
            }

        negotiatedMtu = client.requestMtu(512)
        logger?.info(LOG_TITLE, "Connected to BLE server", mapOf("device_name" to device.name, "mtu" to negotiatedMtu))
        Log.i(TAG, "Connected to ${device.name}, MTU=$negotiatedMtu")
    }

    suspend fun sendToServer(data: ByteArray, writeType: WriteType) {
        clientToServerChar.write(data, writeType)
        bytesSent += data.size
        logger?.debug(LOG_TITLE, "Sent data to server", mapOf("bytes" to data.size, "write_type" to writeType.name))
        Log.d(TAG, "Sent to server: ${String(data)}")
    }

    suspend fun receiveFromServer(): Flow<ByteArray> = merge(
        clientFromServerChar.getNotifications().onEach {
            bytesReceived += it.size
            logger?.debug(LOG_TITLE, "Received data from server", mapOf("bytes" to it.size))
        },
        client.disconnectEvent().map {
            logger?.warn(LOG_TITLE, "Server disconnected unexpectedly")
            throw Exception("Server disconnected")
        }
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
            val quality = ConnectionQuality(
                rssiDbm = rssi,
                signalLevel = rssiToSignalLevel(rssi),
                estimatedDistanceMeters = rssiToDistance(rssi),
                mtuBytes = negotiatedMtu,
                throughputBytesPerSecond = throughput
            )
            logger?.debug(
                LOG_TITLE, "Connection quality",
                mapOf(
                    "rssi_dbm" to quality.rssiDbm,
                    "signal_level" to quality.signalLevel.name,
                    "distance_m" to quality.estimatedDistanceMeters,
                    "mtu_bytes" to quality.mtuBytes,
                    "throughput_bps" to quality.throughputBytesPerSecond
                )
            )
            emit(quality)
        }
    }.flowOn(Dispatchers.IO)

    suspend fun disconnect() {
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        bytesSent = 0L
        bytesReceived = 0L
        client.disconnect()
        logger?.info(LOG_TITLE, "Disconnected from BLE server")
        Log.i(TAG, "Disconnected from server")
    }
}
