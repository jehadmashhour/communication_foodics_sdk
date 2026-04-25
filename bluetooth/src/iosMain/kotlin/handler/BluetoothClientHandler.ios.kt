package handler

import BluetoothConstants.CHAR_FROM_CLIENT_UUID
import BluetoothConstants.CHAR_TO_CLIENT_UUID
import BluetoothConstants.CLIENT_DISCONNECT_SIGNAL
import BluetoothConstants.HELLO_PREFIX
import BluetoothConstants.QUALITY_REPORT_PREFIX
import BluetoothConstants.SERVER_STOP_SIGNAL
import BluetoothConstants.SERVICE_UUID
import ConnectionQuality
import client.Client
import client.ClientCharacteristic
import client.IOSClient
import client.WriteType
import com.foodics.crosscommunicationlibrary.logger.CommunicationLogger
import com.foodics.crosscommunicationlibrary.logger.debug
import com.foodics.crosscommunicationlibrary.logger.error
import com.foodics.crosscommunicationlibrary.logger.info
import com.foodics.crosscommunicationlibrary.logger.warn
import model.BleClient
import model.BleMessage
import rssiToDistance
import rssiToSignalLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import platform.Foundation.*
import scanner.IoTDevice
import scanner.Scanner

private const val LOG_TITLE = "BLE_CLIENT"

actual class BluetoothClientHandler(
    private val logger: CommunicationLogger?
) {

    private val iosClient = IOSClient()
    private val client: Client = Client(iosClient)
    private val scanner: Scanner = Scanner(iosClient)
    private var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var clientToServerChar: ClientCharacteristic
    private lateinit var clientFromServerChar: ClientCharacteristic

    private var bytesSent = 0L
    private var bytesReceived = 0L

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

        iosClient.startRssiPolling(scope)
        logger?.info(LOG_TITLE, "Connected to BLE server", mapOf("device_name" to device.name))

        scope.launch {
            delay(3000)
            while (isActive) {
                try {
                    val rssi = iosClient.rssiFlow.value
                    if (rssi != Int.MIN_VALUE) {
                        clientToServerChar.write("$QUALITY_REPORT_PREFIX$rssi".encodeToByteArray(), WriteType.DEFAULT)
                    }
                } catch (e: Exception) {
                    logger?.warn(LOG_TITLE, "RSSI reporting stopped", mapOf("error" to (e.message ?: "unknown")))
                    break
                }
                delay(3000)
            }
        }
    }

    suspend fun sendToServer(data: ByteArray, writeType: WriteType) {
        clientToServerChar.write(data, writeType)
        bytesSent += data.size
        logger?.debug(LOG_TITLE, "Sent data to server", mapOf("bytes" to data.size))
    }

    suspend fun receiveFromServer(): Flow<ByteArray> = merge(
        clientFromServerChar.getNotifications()
            .onEach { data ->
                val text = data.decodeToString()
                if (text.startsWith(SERVER_STOP_SIGNAL)) throw Exception("Server stopped")
            }
            .filter { data ->
                val text = data.decodeToString()
                !text.startsWith(HELLO_PREFIX)
            }
            .onEach { data ->
                bytesReceived += data.size
                logger?.debug(LOG_TITLE, "Received data from server", mapOf("bytes" to data.size))
            },
        iosClient.disconnectEvent.map {
            logger?.warn(LOG_TITLE, "Server disconnected unexpectedly")
            throw Exception("Server disconnected")
        }
    )

    fun connectionQuality(): Flow<ConnectionQuality> = flow {
        var windowStart = (NSDate().timeIntervalSince1970 * 1000).toLong()
        while (true) {
            delay(3000)
            val rssi = iosClient.rssiFlow.value
            val now = (NSDate().timeIntervalSince1970 * 1000).toLong()
            val windowSec = ((now - windowStart).coerceAtLeast(1)) / 1000.0
            val throughput = ((bytesSent + bytesReceived) / windowSec).toLong()
            bytesSent = 0L
            bytesReceived = 0L
            windowStart = now
            val quality = ConnectionQuality(
                rssiDbm = rssi,
                signalLevel = rssiToSignalLevel(rssi),
                estimatedDistanceMeters = rssiToDistance(rssi),
                mtuBytes = iosClient.mtu(),
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
        try {
            if (::clientToServerChar.isInitialized) {
                clientToServerChar.write(CLIENT_DISCONNECT_SIGNAL.encodeToByteArray(), WriteType.DEFAULT)
            }
        } catch (_: Exception) { }
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        bytesSent = 0L
        bytesReceived = 0L
        client.disconnect()
        logger?.info(LOG_TITLE, "Disconnected from BLE server")
    }
}
