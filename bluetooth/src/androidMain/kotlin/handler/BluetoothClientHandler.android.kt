package handler

import BluetoothConstants
import BluetoothConstants.BRIDGE_C2S_PREFIX
import BluetoothConstants.BRIDGE_DISCONNECT_PREFIX
import BluetoothConstants.BRIDGE_INIT_PREFIX
import BluetoothConstants.CLIENT_DISCONNECT_SIGNAL
import BluetoothConstants.QUALITY_REPORT_PREFIX
import BluetoothConstants.CHAR_FROM_CLIENT_UUID
import BluetoothConstants.CHAR_TO_CLIENT_UUID
import BluetoothConstants.SERVER_STOP_SIGNAL
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
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import model.BleClient
import model.BleMessage
import scanner.IoTDevice
import scanner.Scanner

private const val LOG_TITLE = "BLE_CLIENT"

private fun newScope() = CoroutineScope(
    SupervisorJob() + Dispatchers.IO +
    CoroutineExceptionHandler { _, _ -> /* swallow DeviceDisconnectedException on scope cancel */ }
)

@SuppressLint("MissingPermission")
actual class BluetoothClientHandler(
    private val context: Context,
    private val logger: CommunicationLogger?
) {

    private val client: Client = Client(context)
    private val scanner: Scanner = Scanner(context)
    private var scope: CoroutineScope = newScope()

    private lateinit var clientToServerChar: ClientCharacteristic
    private lateinit var clientFromServerChar: ClientCharacteristic
    private lateinit var _rawFromServerFlow: SharedFlow<ByteArray>

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

        // Share the raw notification stream so both receiveFromServer() and the bridge monitor
        // can subscribe without creating two independent GATT notification subscriptions.
        _rawFromServerFlow = clientFromServerChar.getNotifications()
            .shareIn(scope, SharingStarted.Eagerly, replay = 1)

        scope.launch {
            delay(3000)
            while (isActive) {
                try {
                    val rssi = client.readRssi()
                    clientToServerChar.write("$QUALITY_REPORT_PREFIX$rssi".encodeToByteArray(), WriteType.DEFAULT)
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
        logger?.debug(LOG_TITLE, "Sent data to server", mapOf("bytes" to data.size, "write_type" to writeType.name))
        Log.d(TAG, "Sent to server: ${String(data)}")
    }

    // Exposes the raw notification stream so BluetoothCommunicationChannel can tap it for bridge routing.
    fun rawNotifications(): SharedFlow<ByteArray> = _rawFromServerFlow

    suspend fun receiveFromServer(): Flow<ByteArray> = merge(
        _rawFromServerFlow
            .onEach { data ->
                val text = data.decodeToString()
                if (text.startsWith(SERVER_STOP_SIGNAL)) throw Exception("Server stopped")
            }
            .filter { data ->
                val text = data.decodeToString()
                !text.startsWith(BluetoothConstants.HELLO_PREFIX) &&
                !text.startsWith(BRIDGE_INIT_PREFIX) &&
                !text.startsWith(BRIDGE_C2S_PREFIX) &&
                !text.startsWith(BRIDGE_DISCONNECT_PREFIX)
            }
            .onEach { data ->
                bytesReceived += data.size
                logger?.debug(LOG_TITLE, "Received data from server", mapOf("bytes" to data.size))
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
        try {
            if (::clientToServerChar.isInitialized) {
                clientToServerChar.write(CLIENT_DISCONNECT_SIGNAL.encodeToByteArray(), WriteType.DEFAULT)
            }
        } catch (_: Exception) { }
        scope.cancel()
        scope = newScope()
        bytesSent = 0L
        bytesReceived = 0L
        client.disconnect()
        logger?.info(LOG_TITLE, "Disconnected from BLE server")
        Log.i(TAG, "Disconnected from server")
    }
}
