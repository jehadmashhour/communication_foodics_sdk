package com.foodics.crosscommunicationlibrary.sample

import BluetoothConstants.HELLO_PREFIX
import ConnectionType
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.foodics.crosscommunicationlibrary.core.CommunicationSDK
import com.foodics.crosscommunicationlibrary.core.ConnectedClient
import com.foodics.crosscommunicationlibrary.core.DiscoveredDevice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun BluetoothSampleApp() {
    val sdk = remember {
        CommunicationSDK.builder()
            .enableBluetooth()
            .build()
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            var screen by remember { mutableStateOf(SampleScreen.ROLES) }
            when (screen) {
                SampleScreen.ROLES -> RoleSelectionScreen(
                    onServer = { screen = SampleScreen.SERVER },
                    onClient = { screen = SampleScreen.CLIENT }
                )
                SampleScreen.SERVER -> ServerScreen(sdk) { screen = SampleScreen.ROLES }
                SampleScreen.CLIENT -> ClientScreen(sdk) { screen = SampleScreen.ROLES }
            }
        }
    }
}

private enum class SampleScreen { ROLES, SERVER, CLIENT }

@Composable
private fun RoleSelectionScreen(onServer: () -> Unit, onClient: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Bluetooth SDK Sample", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(48.dp))
        Button(onClick = onServer, modifier = Modifier.fillMaxWidth()) {
            Text("Run as Server")
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = onClient, modifier = Modifier.fillMaxWidth()) {
            Text("Run as Client")
        }
    }
}

@Composable
private fun ServerScreen(sdk: CommunicationSDK, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Idle") }
    var messages by remember { mutableStateOf(listOf<String>()) }
    var input by remember { mutableStateOf("") }
    var deviceName by remember { mutableStateOf("") }
    var advertisingAs by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var connectedClients by remember { mutableStateOf(listOf<ConnectedClient>()) }
    val prefix = remember { devicePlatformPrefix() }
    // Scan response budget: 31 bytes − 4 bytes ServiceData16 overhead = 27 bytes for the full
    // advertised name ("$prefix-$deviceName"). Reserve prefix.length + 1 ("-") for the prefix.
    val maxNameLength = 27 - prefix.length - 1

    val startServer = rememberBluetoothEnableLauncher {
        scope.launch {
            status = "Starting..."
            val fullName = "$prefix-${deviceName.trim()}"
            advertisingAs = fullName
            sdk.startServer(ConnectionType.BLUETOOTH, fullName, deviceIdentifier())
            running = true
            status = "Running — waiting for client"
        }
    }

    PlatformBackHandler {
        scope.launch { if (running) sdk.stopServer(ConnectionType.BLUETOOTH) }
        onBack()
    }

    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        sdk.connectedClients(ConnectionType.BLUETOOTH).collect { clients ->
            connectedClients = clients
            status = when {
                clients.isEmpty() -> "Running — waiting for client"
                clients.size == 1 -> "Connected: ${clients[0].name}"
                else -> "Connected: ${clients.size} clients"
            }
        }
    }

    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        try {
            sdk.receiveMessagesFromClient(ConnectionType.BLUETOOTH).collect { msg ->
                messages = messages + "${msg.client.name}: ${msg.data.decodeToString()}"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            status = "Receive error: ${e.message}"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(top = 48.dp, start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                scope.launch { if (running) sdk.stopServer(ConnectionType.BLUETOOTH) }
                onBack()
            }) { Text("Back") }
            Spacer(Modifier.width(12.dp))
            Text("Server", style = MaterialTheme.typography.titleLarge)
        }

        Text("Status: $status", style = MaterialTheme.typography.bodyMedium)
        if (advertisingAs.isNotBlank()) {
            Text(
                "Advertising as: $advertisingAs",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }

        OutlinedTextField(
            value = deviceName,
            onValueChange = { if (it.length <= maxNameLength) deviceName = it },
            label = { Text("Device name (${deviceName.length}/$maxNameLength)") },
            placeholder = { Text("e.g. MyPhone → $prefix-MyPhone") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !running,
            singleLine = true
        )

        Button(
            onClick = { startServer() },
            enabled = !running && deviceName.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Start Server") }

        if (running && connectedClients.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Connected clients (${connectedClients.size})",
                        style = MaterialTheme.typography.labelLarge
                    )
                    connectedClients.forEach { client ->
                        Text("• ${client.name}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(messages) { msg ->
                Text(msg, modifier = Modifier.padding(vertical = 4.dp))
                HorizontalDivider()
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("Message to client") },
                modifier = Modifier.weight(1f),
                enabled = running && connectedClients.isNotEmpty()
            )
            Button(
                onClick = {
                    val msg = input.trim()
                    input = ""
                    scope.launch {
                        sdk.sendDataToClient(ConnectionType.BLUETOOTH, msg.encodeToByteArray())
                        messages = messages + "Me: $msg"
                    }
                },
                enabled = running && connectedClients.isNotEmpty() && input.isNotBlank()
            ) { Text("Send") }
        }

        Button(
            onClick = {
                scope.launch {
                    sdk.stopServer(ConnectionType.BLUETOOTH)
                    running = false
                    connectedClients = emptyList()
                    status = "Idle"
                }
            },
            enabled = running,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        ) { Text("Stop Server") }
    }
}

@Composable
private fun ClientScreen(sdk: CommunicationSDK, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Idle") }
    var devices by remember { mutableStateOf(listOf<DiscoveredDevice>()) }
    var messages by remember { mutableStateOf(listOf<String>()) }
    var input by remember { mutableStateOf("") }
    var clientName by remember { mutableStateOf("") }
    var myFullName by remember { mutableStateOf("") }
    var serverName by remember { mutableStateOf("Server") }
    var scanning by remember { mutableStateOf(false) }
    var connected by remember { mutableStateOf(false) }
    val prefix = remember { devicePlatformPrefix() }

    val startScan = rememberBluetoothEnableLauncher {
        myFullName = "$prefix-${clientName.trim()}"
        scanning = true
        status = "Scanning..."
    }

    PlatformBackHandler {
        scope.launch { if (connected) sdk.disconnectClient(ConnectionType.BLUETOOTH) }
        onBack()
    }

    LaunchedEffect(scanning) {
        if (!scanning) return@LaunchedEffect
        sdk.scan().collect { found -> devices = found }
    }

    LaunchedEffect(connected) {
        if (!connected) return@LaunchedEffect
        try {
            sdk.receiveFromServer(ConnectionType.BLUETOOTH).collect { data ->
                messages = messages + "$serverName: ${data.decodeToString()}"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            status = "Disconnected: ${e.message}"
            connected = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(top = 48.dp, start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                scope.launch { if (connected) sdk.disconnectClient(ConnectionType.BLUETOOTH) }
                onBack()
            }) { Text("Back") }
            Spacer(Modifier.width(12.dp))
            Text("Client", style = MaterialTheme.typography.titleLarge)
        }

        Text("Status: $status", style = MaterialTheme.typography.bodyMedium)
        if (myFullName.isNotBlank()) {
            Text(
                "Your name: $myFullName",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }

        if (!connected) {
            OutlinedTextField(
                value = clientName,
                onValueChange = { clientName = it },
                label = { Text("Your name") },
                placeholder = { Text("e.g. MyPhone → $prefix-MyPhone") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !scanning,
                singleLine = true
            )

            Button(
                onClick = { startScan() },
                enabled = !scanning && clientName.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (scanning) "Scanning..." else "Scan for Servers") }

            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(devices) { device ->
                    Card(
                        onClick = {
                            scope.launch {
                                try {
                                    scanning = false
                                    serverName = device.name
                                    status = "Connecting to ${device.name}..."
                                    sdk.connectToServer(device, ConnectionType.BLUETOOTH)
                                    val fullName = "$prefix-${clientName.trim()}"
                                    sdk.sendDataToServer(
                                        ConnectionType.BLUETOOTH,
                                        "$HELLO_PREFIX$fullName".encodeToByteArray()
                                    )
                                    connected = true
                                    status = "Connected to ${device.name}"
                                } catch (e: Exception) {
                                    status = "Connection failed: ${e.message}"
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(device.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                device.addressByType.entries.joinToString { "${it.key}: ${it.value}" },
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(messages) { msg ->
                    Text(msg, modifier = Modifier.padding(vertical = 4.dp))
                    HorizontalDivider()
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("Message to server") },
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = {
                        val msg = input.trim()
                        input = ""
                        scope.launch {
                            sdk.sendDataToServer(ConnectionType.BLUETOOTH, msg.encodeToByteArray())
                            messages = messages + "Me: $msg"
                        }
                    },
                    enabled = input.isNotBlank()
                ) { Text("Send") }
            }

            Button(
                onClick = {
                    scope.launch {
                        sdk.disconnectClient(ConnectionType.BLUETOOTH)
                        connected = false
                        status = "Disconnected"
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) { Text("Disconnect") }
        }
    }
}
