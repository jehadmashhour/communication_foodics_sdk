package scanner

import android.annotation.SuppressLint
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import sdk.core.scanner.BleScanMode
import sdk.core.scanner.BleScannerSettings
import sdk.scanner.BleScanner
import java.util.concurrent.TimeUnit

fun ByteArray.toUtf8String(): String = try {
    String(this, Charsets.UTF_8)
} catch (e: Exception) {
    ""
}

actual class Scanner(private val context: Context) {

    val scanner = BleScanner(context)

    @SuppressLint("MissingPermission")
    actual fun scan(): Flow<List<IoTDevice>> = callbackFlow {
        stopScan()

        val targetUuid = ParcelUuid(BluetoothConstants.ADVERTISER_UUID)

        // device key -> Pair(device, lastSeenTimestamp)
        val devicesMap = mutableMapOf<String, Pair<IoTDevice, Long>>()

        val scanJob = scanner.scan(settings = BleScannerSettings(scanMode = BleScanMode.SCAN_MODE_LOW_LATENCY))
            .filter { scanResult ->
                val record = scanResult.data?.scanRecord
                record?.serviceUuids?.contains(targetUuid) == true ||
                record?.serviceData?.containsKey(targetUuid) == true
            }
            .onEach { scanResult ->
                // Extract Service Data
                val serviceData =
                    scanResult.data?.scanRecord?.serviceData?.get(targetUuid)?.value?.toUtf8String()

                // scanRecord.deviceName comes from the scan response (more reliable for iOS)
                val rawName = scanResult.data?.scanRecord?.deviceName?.takeIf { it.isNotEmpty() }
                    ?: scanResult.device.name

                val nameFromServiceData = serviceData?.split("|")?.firstOrNull()
                val nameFromName = rawName?.split("|")?.firstOrNull()

                val identifierFromServiceData = serviceData?.split("|")?.getOrNull(1)
                val identifierFromName = rawName?.split("|")?.getOrNull(1)

                // Determine key for devicesMap
                val key =
                    identifierFromServiceData ?: identifierFromName ?: scanResult.device.address

                // Device name to display (full serviceData or fallback to device name/address)
                val deviceName = nameFromServiceData ?: nameFromName ?: ""

                val device = IoTDevice(scanResult.device, deviceName, ConnectionType.BLUETOOTH, key)
                devicesMap[key] = device to System.currentTimeMillis()
            }
            .launchIn(CoroutineScope(Dispatchers.IO))

        val cleanupJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                devicesMap.entries.removeIf { (_, value) ->
                    val lastSeen = value.second
                    now - lastSeen > TimeUnit.SECONDS.toMillis(5)
                }

                // Emit updated device list
                trySend(devicesMap.values.map { it.first })

                delay(1000) // check every second
            }
        }

        awaitClose {
            scanJob.cancel()
            cleanupJob.cancel()
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun stopScan() {
        scanner.stop()
        delay(300)
    }
}
