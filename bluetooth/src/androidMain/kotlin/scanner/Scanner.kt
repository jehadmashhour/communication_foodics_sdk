package scanner

import android.annotation.SuppressLint
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
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

        val scanJob = scanner.scan()
            .filter { scanResult ->
                scanResult.data?.scanRecord?.serviceUuids?.contains(targetUuid) == true
            }
            .onEach { scanResult ->
                // Extract Service Data
                val serviceData =
                    scanResult.data?.scanRecord?.serviceData?.get(targetUuid)?.value?.toUtf8String()

                val nameFromServiceData = serviceData?.split("|")?.firstOrNull()
                val nameFromName = scanResult.device.name?.split("|")?.firstOrNull()

                val identifierFromServiceData = serviceData?.split("|")?.getOrNull(1)
                val identifierFromName = scanResult.device.name?.split("|")?.getOrNull(1)

                // Determine key for devicesMap
                val key =
                    identifierFromServiceData ?: identifierFromName ?: scanResult.device.address

                // Device name to display (full serviceData or fallback to device name/address)
                val deviceName = nameFromServiceData ?: nameFromName ?: ""

                println("Scanned Device_in -> name=$deviceName, address=${scanResult.device.address}, key=$key")

                val device = IoTDevice(scanResult.device, deviceName, ConnectionType.BLUETOOTH, key)
                devicesMap[key] = device to System.currentTimeMillis()
            }
            .launchIn(CoroutineScope(Dispatchers.IO))

        val cleanupJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                devicesMap.entries.removeIf { (_, value) ->
                    val lastSeen = value.second
                    now - lastSeen > TimeUnit.SECONDS.toMillis(7) // 7 seconds timeout
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
