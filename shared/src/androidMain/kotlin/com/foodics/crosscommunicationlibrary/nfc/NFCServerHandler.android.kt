package com.foodics.crosscommunicationlibrary.nfc

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.net.ServerSocket
import java.net.Socket

/**
 * Android NFC bootstrapping server.
 *
 * 1. Opens a TCP server on a random port.
 * 2. Builds a JSON connection payload and an NDEF file from it.
 * 3. Registers the NDEF file with [NfcHceNdefRegistry] so that
 *    [NfcHceService] serves it over NFC when a client taps.
 * 4. Accepts TCP connections for bidirectional data exchange.
 *
 * Requires NFC hardware and the NFC permission.
 * On devices without NFC, start() logs a warning and returns.
 */
actual class NFCServerHandler {

    companion object { private const val TAG = "NFCServer" }

    private var scope        = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket : ServerSocket? = null
    private var clientSocket : Socket? = null

    private val _fromClient  = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

    // ── Public API ────────────────────────────────────────────────────────────

    suspend fun start(deviceName: String, identifier: String): Unit = withContext(Dispatchers.IO) {
        stop()
        val ip   = nfcGetLocalIpAndroid()
        val srv  = ServerSocket(0).also { serverSocket = it }
        val port = srv.localPort

        // Build and register the NDEF payload for HCE
        val json = buildNfcJson(identifier, deviceName, ip, port)
        NfcHceNdefRegistry.ndefFileData = buildNdefFile(json)
        Log.i(TAG, "NFC HCE advertising: $deviceName @ $ip:$port")

        // Accept TCP connections
        scope.launch {
            while (isActive) {
                val client = runCatching { srv.accept() }.getOrNull() ?: break
                clientSocket?.close()
                clientSocket = client
                Log.i(TAG, "NFC TCP client connected from ${client.inetAddress.hostAddress}")
                scope.launch { runReceiveLoop(client) }
            }
        }
    }

    suspend fun sendToClient(data: ByteArray): Unit = withContext(Dispatchers.IO) {
        val sock = clientSocket ?: run { Log.w(TAG, "No client connected"); return@withContext }
        runCatching {
            sock.getOutputStream().apply {
                write(nfcLengthPrefix(data))
                write(data)
                flush()
            }
        }.onFailure { Log.e(TAG, "Send error", it) }
    }

    fun receiveFromClient(): Flow<ByteArray> = _fromClient.asSharedFlow()

    suspend fun stop(): Unit = withContext(Dispatchers.IO) {
        NfcHceNdefRegistry.ndefFileData = null
        clientSocket?.close()
        serverSocket?.close()
        scope.cancel()
        scope        = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        clientSocket = null
        serverSocket = null
        Log.i(TAG, "NFC server stopped")
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun runReceiveLoop(socket: Socket) {
        scope.launch {
            val input = socket.getInputStream()
            while (isActive && !socket.isClosed) {
                val data = runCatching { nfcReadFramed(input) }.getOrNull() ?: break
                _fromClient.emit(data)
            }
            runCatching { socket.close() }
            if (clientSocket == socket) clientSocket = null
            Log.i(TAG, "NFC TCP client disconnected")
        }
    }
}
