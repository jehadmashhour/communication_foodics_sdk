package scanner

import client.IOSClient
import kotlinx.coroutines.flow.Flow

actual class Scanner(private val client: IOSClient) {

    actual fun scan(): Flow<List<IoTDevice>> = client.scan()
}
