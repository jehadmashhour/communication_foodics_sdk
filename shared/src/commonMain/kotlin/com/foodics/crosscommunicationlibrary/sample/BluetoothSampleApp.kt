package com.foodics.crosscommunicationlibrary.sample

import BluetoothConstants.HELLO_PREFIX
import ConnectionQuality
import ConnectionType
import SignalLevel
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
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
import com.foodics.crosscommunicationlibrary.logger.LogAttributes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun BluetoothSampleApp() {
    val sdk = remember {
        CommunicationSDK.builder()
            .enableLogging(sampleDatadogConfig(), LogAttributes())
            .enableBluetooth()
//            .enableLan()
            .build()
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            var screen by remember { mutableStateOf(BluetoothScreen.SELECT) }
            when (screen) {
                BluetoothScreen.SELECT -> ModeSelectionScreen(
                    onServer = { screen = BluetoothScreen.SERVER },
                    onClient = { screen = BluetoothScreen.CLIENT },
                    onDual   = { screen = BluetoothScreen.DUAL }
                )
                BluetoothScreen.SERVER -> ServerScreen(sdk) { screen = BluetoothScreen.SELECT }
                BluetoothScreen.CLIENT -> ClientScreen(sdk) { screen = BluetoothScreen.SELECT }
                BluetoothScreen.DUAL   -> DualScreen(sdk)   { screen = BluetoothScreen.SELECT }
            }
        }
    }
}

private enum class BluetoothScreen { SELECT, SERVER, CLIENT, DUAL }

@Composable
private fun ModeSelectionScreen(onServer: () -> Unit, onClient: () -> Unit, onDual: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Bluetooth SDK Sample", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(48.dp))
        Button(onClick = onServer, modifier = Modifier.fillMaxWidth()) {
            Text("Server")
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = onClient, modifier = Modifier.fillMaxWidth()) {
            Text("Client")
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = onDual, modifier = Modifier.fillMaxWidth()) {
            Text("Server + Client")
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
    var selectedClientIds by remember { mutableStateOf(emptySet<String>()) }
    val prefix = remember { devicePlatformPrefix() }
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
            selectedClientIds = selectedClientIds.filter { id -> clients.any { it.id == id } }.toSet()
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
            Spacer(Modifier.weight(1f))
            if (advertisingAs.isNotBlank()) {
                Text(advertisingAs, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold))
            }
        }

        Text("Status: $status", style = MaterialTheme.typography.bodyMedium)

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
                    Text("Connected clients (${connectedClients.size})", style = MaterialTheme.typography.labelLarge)
                    connectedClients.forEach { Text("• ${it.name}", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }

        if (running && connectedClients.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedClientIds.isEmpty(),
                    onClick = { selectedClientIds = emptySet() },
                    label = { Text("All") }
                )
                connectedClients.forEach { client ->
                    FilterChip(
                        selected = client.id in selectedClientIds,
                        onClick = {
                            selectedClientIds = if (client.id in selectedClientIds) {
                                val updated = selectedClientIds - client.id
                                if (updated.isEmpty()) emptySet() else updated
                            } else {
                                selectedClientIds + client.id
                            }
                        },
                        label = { Text(client.name) }
                    )
                }
            }
        }

        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(messages) { msg ->
                Text(msg, modifier = Modifier.padding(vertical = 4.dp))
                HorizontalDivider()
            }
        }

        val sendLabel = if (selectedClientIds.isEmpty()) "Message to all clients"
                        else "Message to ${selectedClientIds.size} client(s)"
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text(sendLabel) },
                modifier = Modifier.weight(1f),
                enabled = running && connectedClients.isNotEmpty()
            )
            Button(
                onClick = {
                    val msg = input.trim()
                    input = ""
                    scope.launch {
                        sdk.sendDataToClients(ConnectionType.BLUETOOTH, msg.encodeToByteArray(), selectedClientIds.toList())
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
    var connecting by remember { mutableStateOf(false) }
    var connected by remember { mutableStateOf(false) }
    var quality by remember { mutableStateOf<ConnectionQuality?>(null) }
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
        if (!scanning) {
            devices = emptyList()
            return@LaunchedEffect
        }
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
            connected = false
            status = "Disconnected: ${e.message}"
            scope.launch { try { sdk.disconnectClient(ConnectionType.BLUETOOTH) } catch (_: Exception) {} }
        }
    }

    LaunchedEffect(connected) {
        if (!connected) { quality = null; return@LaunchedEffect }
        sdk.connectionQuality(ConnectionType.BLUETOOTH).collect { quality = it }
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
            Spacer(Modifier.weight(1f))
            if (myFullName.isNotBlank()) {
                Text(myFullName, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold))
            }
        }

        Text("Status: $status", style = MaterialTheme.typography.bodyMedium)

        if (!connected) {
            OutlinedTextField(
                value = clientName,
                onValueChange = { clientName = it },
                label = { Text("Your name") },
                placeholder = { Text("e.g. MyPhone → $prefix-MyPhone") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !scanning && !connecting,
                singleLine = true
            )

            Button(
                onClick = { startScan() },
                enabled = !scanning && !connecting && clientName.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (connecting) "Connecting..." else if (scanning) "Scanning..." else "Scan for Servers") }

            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(devices) { device ->
                    Card(
                        onClick = {
                            scope.launch {
                                try {
                                    scanning = false
                                    connecting = true
                                    serverName = device.name
                                    status = "Connecting to ${device.name}..."
                                    sdk.connectToServer(device, ConnectionType.BLUETOOTH)
                                    val fullName = "$prefix-${clientName.trim()}"
                                    sdk.sendDataToServer(
                                        ConnectionType.BLUETOOTH,
                                        "$HELLO_PREFIX$fullName".encodeToByteArray()
                                    )
                                    connecting = false
                                    connected = true
                                    status = "Connected to ${device.name}"
                                } catch (e: Exception) {
                                    connecting = false
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
            quality?.let { ConnectionQualityCard(it) }

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
                        quality = null
                        status = "Disconnected"
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) { Text("Disconnect") }
        }
    }
}

@Composable
private fun DualScreen(sdk: CommunicationSDK, onBack: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val prefix = remember { devicePlatformPrefix() }
    val maxNameLength = 27 - prefix.length - 1

    var deviceName by remember { mutableStateOf("") }
    var fullName by remember { mutableStateOf("") }

    // Server state
    var serverRunning by remember { mutableStateOf(false) }
    var serverStatus by remember { mutableStateOf("Idle") }
    var connectedClients by remember { mutableStateOf(listOf<ConnectedClient>()) }
    var selectedClientIds by remember { mutableStateOf(emptySet<String>()) }
    var serverMessages by remember { mutableStateOf(listOf<String>()) }
    var serverInput by remember { mutableStateOf("") }

    // Client state
    var scanning by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    var clientConnected by remember { mutableStateOf(false) }
    var clientStatus by remember { mutableStateOf("Idle") }
    var devices by remember { mutableStateOf(listOf<DiscoveredDevice>()) }
    var connectedServerName by remember { mutableStateOf("") }
    var clientMessages by remember { mutableStateOf(listOf<String>()) }
    var clientInput by remember { mutableStateOf("") }
    var clientQuality by remember { mutableStateOf<ConnectionQuality?>(null) }

    val startServer = rememberBluetoothEnableLauncher {
        scope.launch {
            val name = "$prefix-${deviceName.trim()}"
            fullName = name
            serverStatus = "Starting..."
            sdk.startServer(ConnectionType.BLUETOOTH, name, deviceIdentifier())
            serverRunning = true
            serverStatus = "Running — waiting for clients"
        }
    }

    val startScan = rememberBluetoothEnableLauncher {
        if (fullName.isBlank()) fullName = "$prefix-${deviceName.trim()}"
        scanning = true
        clientStatus = "Scanning..."
    }

    PlatformBackHandler {
        scope.launch {
            if (serverRunning) sdk.stopServer(ConnectionType.BLUETOOTH)
            if (clientConnected) sdk.disconnectClient(ConnectionType.BLUETOOTH)
        }
        onBack()
    }

    LaunchedEffect(serverRunning) {
        if (!serverRunning) return@LaunchedEffect
        sdk.connectedClients(ConnectionType.BLUETOOTH).collect { clients ->
            connectedClients = clients
            selectedClientIds = selectedClientIds.filter { id -> clients.any { it.id == id } }.toSet()
            serverStatus = when {
                clients.isEmpty() -> "Running — waiting for clients"
                clients.size == 1 -> "Connected: ${clients[0].name}"
                else -> "Connected: ${clients.size} clients"
            }
        }
    }

    LaunchedEffect(serverRunning) {
        if (!serverRunning) return@LaunchedEffect
        try {
            sdk.receiveMessagesFromClient(ConnectionType.BLUETOOTH).collect { msg ->
                serverMessages = serverMessages + "${msg.client.name}: ${msg.data.decodeToString()}"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            serverStatus = "Receive error: ${e.message}"
        }
    }

    LaunchedEffect(scanning) {
        if (!scanning) {
            devices = emptyList()
            return@LaunchedEffect
        }
        sdk.scan().collect { found -> devices = found }
    }

    LaunchedEffect(clientConnected) {
        if (!clientConnected) return@LaunchedEffect
        try {
            sdk.receiveFromServer(ConnectionType.BLUETOOTH).collect { data ->
                clientMessages = clientMessages + "$connectedServerName: ${data.decodeToString()}"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            clientConnected = false
            clientStatus = "Disconnected: ${e.message}"
            scope.launch { try { sdk.disconnectClient(ConnectionType.BLUETOOTH) } catch (_: Exception) {} }
        }
    }

    LaunchedEffect(clientConnected) {
        if (!clientConnected) { clientQuality = null; return@LaunchedEffect }
        sdk.connectionQuality(ConnectionType.BLUETOOTH).collect { clientQuality = it }
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
                scope.launch {
                    if (serverRunning) sdk.stopServer(ConnectionType.BLUETOOTH)
                    if (clientConnected) sdk.disconnectClient(ConnectionType.BLUETOOTH)
                }
                onBack()
            }) { Text("Back") }
            Spacer(Modifier.width(12.dp))
            Text("Dual Mode", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            if (fullName.isNotBlank()) {
                Text(fullName, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold))
            }
        }

        OutlinedTextField(
            value = deviceName,
            onValueChange = { if (it.length <= maxNameLength) deviceName = it },
            label = { Text("Device name (${deviceName.length}/$maxNameLength)") },
            placeholder = { Text("e.g. MyPhone → $prefix-MyPhone") },
            modifier = Modifier.fillMaxWidth(),
            enabled = fullName.isBlank(),
            singleLine = true
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { startServer() },
                enabled = deviceName.isNotBlank() && !serverRunning,
                modifier = Modifier.weight(1f)
            ) { Text(if (serverRunning) "Server Running" else "Start Server") }

            Button(
                onClick = { startScan() },
                enabled = deviceName.isNotBlank() && !scanning && !connecting && !clientConnected,
                modifier = Modifier.weight(1f)
            ) {
                Text(when {
                    connecting -> "Connecting..."
                    scanning -> "Scanning..."
                    clientConnected -> "Connected"
                    else -> "Scan & Connect"
                })
            }
        }

        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            item {
                Text(
                    "As Server",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text("Status: $serverStatus", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
            }

            if (serverRunning && connectedClients.isNotEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Connected clients (${connectedClients.size})", style = MaterialTheme.typography.labelMedium)
                            connectedClients.forEach { Text("• ${it.name}", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }

            items(serverMessages) { msg ->
                Text("[Server] $msg", modifier = Modifier.padding(vertical = 2.dp), style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
            }

            if (serverRunning && connectedClients.size > 1) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = selectedClientIds.isEmpty(),
                            onClick = { selectedClientIds = emptySet() },
                            label = { Text("All") }
                        )
                        connectedClients.forEach { client ->
                            FilterChip(
                                selected = client.id in selectedClientIds,
                                onClick = {
                                    selectedClientIds = if (client.id in selectedClientIds) {
                                        val updated = selectedClientIds - client.id
                                        if (updated.isEmpty()) emptySet() else updated
                                    } else {
                                        selectedClientIds + client.id
                                    }
                                },
                                label = { Text(client.name) }
                            )
                        }
                    }
                }
            }

            item {
                val dualSendLabel = if (selectedClientIds.isEmpty()) "To all clients"
                                    else "To ${selectedClientIds.size} client(s)"
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    OutlinedTextField(
                        value = serverInput,
                        onValueChange = { serverInput = it },
                        label = { Text(dualSendLabel) },
                        modifier = Modifier.weight(1f),
                        enabled = serverRunning && connectedClients.isNotEmpty(),
                        singleLine = true
                    )
                    Button(
                        onClick = {
                            val msg = serverInput.trim()
                            serverInput = ""
                            scope.launch {
                                sdk.sendDataToClients(ConnectionType.BLUETOOTH, msg.encodeToByteArray(), selectedClientIds.toList())
                                serverMessages = serverMessages + "Me→clients: $msg"
                            }
                        },
                        enabled = serverRunning && connectedClients.isNotEmpty() && serverInput.isNotBlank()
                    ) { Text("Send") }
                }
                if (serverRunning) {
                    Button(
                        onClick = {
                            scope.launch {
                                sdk.stopServer(ConnectionType.BLUETOOTH)
                                serverRunning = false
                                connectedClients = emptyList()
                                serverStatus = "Idle"
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) { Text("Stop Server") }
                }
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
            }

            item {
                Text(
                    "As Client",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
                Text("Status: $clientStatus", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
            }

            if (scanning && !clientConnected) {
                items(devices) { device ->
                    Card(
                        onClick = {
                            scope.launch {
                                try {
                                    scanning = false
                                    connecting = true
                                    connectedServerName = device.name
                                    clientStatus = "Connecting to ${device.name}..."
                                    sdk.connectToServer(device, ConnectionType.BLUETOOTH)
                                    val name = fullName.ifBlank { "$prefix-${deviceName.trim()}" }
                                    sdk.sendDataToServer(
                                        ConnectionType.BLUETOOTH,
                                        "$HELLO_PREFIX$name".encodeToByteArray()
                                    )
                                    connecting = false
                                    clientConnected = true
                                    clientStatus = "Connected to ${device.name}"
                                } catch (e: Exception) {
                                    connecting = false
                                    clientStatus = "Connection failed: ${e.message}"
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

            clientQuality?.let { q ->
                item { ConnectionQualityCard(q, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) }
            }

            items(clientMessages) { msg ->
                Text("[Client] $msg", modifier = Modifier.padding(vertical = 2.dp), style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    OutlinedTextField(
                        value = clientInput,
                        onValueChange = { clientInput = it },
                        label = { Text("To server") },
                        modifier = Modifier.weight(1f),
                        enabled = clientConnected,
                        singleLine = true
                    )
                    Button(
                        onClick = {
                            val msg = clientInput.trim()
                            clientInput = ""
                            scope.launch {
                                sdk.sendDataToServer(ConnectionType.BLUETOOTH, msg.encodeToByteArray())
                                clientMessages = clientMessages + "Me→server: $msg"
                            }
                        },
                        enabled = clientConnected && clientInput.isNotBlank()
                    ) { Text("Send") }
                }
                if (clientConnected) {
                    Button(
                        onClick = {
                            scope.launch {
                                sdk.disconnectClient(ConnectionType.BLUETOOTH)
                                clientConnected = false
                                clientQuality = null
                                clientStatus = "Disconnected"
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) { Text("Disconnect from Server") }
                }
            }
        }
    }
}

@Composable
private fun ConnectionQualityCard(quality: ConnectionQuality, modifier: Modifier = Modifier.fillMaxWidth()) {
    val signalColor = when (quality.signalLevel) {
        SignalLevel.EXCELLENT -> androidx.compose.ui.graphics.Color(0xFF2E7D32)
        SignalLevel.GOOD      -> androidx.compose.ui.graphics.Color(0xFF558B2F)
        SignalLevel.FAIR      -> androidx.compose.ui.graphics.Color(0xFFF57F17)
        SignalLevel.POOR      -> androidx.compose.ui.graphics.Color(0xFFC62828)
        SignalLevel.UNKNOWN   -> androidx.compose.ui.graphics.Color.Gray
    }
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Connection Quality", style = MaterialTheme.typography.labelMedium)
                Text(
                    quality.signalLevel.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = signalColor
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("RSSI", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${quality.rssiDbm} dBm", style = MaterialTheme.typography.bodySmall)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Distance", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val dist = quality.estimatedDistanceMeters
                    Text(if (dist < 0) "—" else "${(dist * 10).toInt() / 10}.${(dist * 10).toInt() % 10} m", style = MaterialTheme.typography.bodySmall)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("MTU", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${quality.mtuBytes} B", style = MaterialTheme.typography.bodySmall)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Throughput", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val bps = quality.throughputBytesPerSecond
                    Text(if (bps < 1024) "$bps B/s" else "${bps / 1024} KB/s", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
