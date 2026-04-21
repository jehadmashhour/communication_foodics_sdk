package server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import scanner.IoTDevice

actual class Server(private val server: IOSServer) {

    actual val connections: Flow<Map<IoTDevice, ServerProfile>>
        get() = server.connections

    actual suspend fun startServer(services: List<BleServerServiceConfig>, scope: CoroutineScope) {
        server.startServer(services)
    }

    actual suspend fun stopServer() {
        server.stopAdvertising()
    }
}
