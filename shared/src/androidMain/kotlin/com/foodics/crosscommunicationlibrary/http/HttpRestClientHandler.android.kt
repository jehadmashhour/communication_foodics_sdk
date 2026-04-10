package com.foodics.crosscommunicationlibrary.http

import android.util.Log
import client.WriteType
import ConnectionType
import com.appstractive.dnssd.DiscoveryEvent
import com.appstractive.dnssd.discoverServices
import com.appstractive.dnssd.key
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import scanner.IoTDevice
import java.net.HttpURLConnection
import java.net.URL

actual class HttpRestClientHandler {

    companion object {
        private const val TAG = "HttpRestClient"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    private var serverUrl: String? = null

    fun scan(): Flow<List<IoTDevice>> =
        discoverServices(HTTP_SERVICE_TYPE)
            .scan(emptyMap<String, IoTDevice>()) { acc, event ->
                val devices = acc.toMutableMap()
                when (event) {
                    is DiscoveryEvent.Discovered -> event.resolve()
                    is DiscoveryEvent.Resolved -> {
                        val s = event.service
                        val id = s.txt["id"]?.decodeToString()?.trim()?.ifBlank { null } ?: s.name
                        devices[s.key] = IoTDevice(
                            id = id,
                            name = s.name,
                            address = "${s.host}:${s.port}",
                            connectionType = ConnectionType.HTTP_REST
                        )
                    }
                    is DiscoveryEvent.Removed -> devices.remove(event.service.key)
                }
                devices
            }
            .map { it.values.toList() }
            .distinctUntilChanged()

    fun connect(device: IoTDevice) {
        val (host, port) = device.address.split(":")
        serverUrl = "http://$host:$port"
        Log.i(TAG, "Connected to HTTP REST server at $serverUrl")
    }

    suspend fun sendToServer(data: ByteArray, writeType: WriteType): Unit = withContext(Dispatchers.IO) {
        val url = serverUrl ?: run { Log.w(TAG, "Not connected"); return@withContext }
        try {
            val conn = URL("$url/message").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.setRequestProperty("Content-Length", data.size.toString())
            conn.setRequestProperty("Connection", "close")
            conn.outputStream.use { it.write(data) }
            val responseBody = conn.inputStream.use { it.readBytes() }
            if (responseBody.isNotEmpty()) _incoming.emit(responseBody)
            conn.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Send error", e)
        }
    }

    fun receiveFromServer(): Flow<ByteArray> = _incoming.asSharedFlow()

    fun disconnect() {
        serverUrl = null
        Log.i(TAG, "Disconnected from HTTP REST server")
    }
}
