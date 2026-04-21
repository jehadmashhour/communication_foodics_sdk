package com.foodics.crosscommunicationlibrary.sse

import ConnectionType
import client.WriteType
import com.foodics.crosscommunicationlibrary.core.CommunicationChannel
import kotlinx.coroutines.flow.Flow
import scanner.IoTDevice

/**
 * Server-Sent Events (SSE / EventSource — WHATWG Living Standard) communication channel.
 *
 * SSE is an HTTP/1.1 text-streaming protocol that maintains a persistent
 * server-to-client push connection while client-to-server messages travel
 * over ordinary stateless HTTP POST requests.
 *
 * Endpoints (served on the same mDNS-advertised TCP port):
 *   GET  /events  — SSE stream; server pushes base64-encoded binary events.
 *   POST /message — client sends a base64-encoded binary body; server emits
 *                   the decoded bytes to [receiveDataFromClient].
 *
 * SSE event format: "data: <base64>\n\n"
 * Heartbeat (keeps proxies from closing the connection): ": heartbeat\n\n" every 5 s.
 *
 * Discovery  : mDNS service type "_foodics_sse._tcp." — dynamic port.
 *
 * Key differences from other channels:
 *   - WebSocket    : WS handshake + bidirectional framing; SSE is unidirectional push + REST upload.
 *   - HTTP REST    : stateless request-response; SSE keeps a persistent push channel open.
 *   - TCP Socket   : custom framing; SSE is plain HTTP — any browser EventSource API works.
 *
 * POS use-cases:
 *   - Streaming live transaction / order events to a web dashboard.
 *   - Any scenario where a browser or third-party HTTP client must receive server pushes
 *     without an SDK (the WHATWG EventSource API is natively supported in all browsers).
 *
 * Works across Android ↔ Android, iOS ↔ iOS, and Android ↔ iOS.
 */
expect class SSECommunicationChannel() : CommunicationChannel {
    override val connectionType: ConnectionType
    override suspend fun startServer(deviceName: String, identifier: String)
    override fun scan(): Flow<List<IoTDevice>>
    override suspend fun connectToServer(device: IoTDevice)
    override suspend fun sendDataToServer(data: ByteArray, writeType: WriteType)
    override suspend fun receiveDataFromServer(): Flow<ByteArray>
    override suspend fun sendDataToClient(data: ByteArray)
    override suspend fun receiveDataFromClient(): Flow<ByteArray>
    override suspend fun stopServer()
    override suspend fun disconnectClient()
}
