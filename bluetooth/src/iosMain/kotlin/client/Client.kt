package client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import scanner.IoTDevice

@Suppress("CONFLICTING_OVERLOADS")
actual class Client(private val client: IOSClient) {

    actual suspend fun connect(device: IoTDevice, scope: CoroutineScope) {
        client.connect(device)
    }

    actual suspend fun disconnect() {
        client.disconnect()
    }

    actual suspend fun discoverServices(): ClientServices {
        return client.discoverServices()
    }

    actual fun disconnectEvent(): Flow<Unit> = client.disconnectEvent
}
