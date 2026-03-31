package com.foodics.crosscommunicationlibrary.ui

import ConnectionType
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import com.foodics.crosscommunicationlibrary.core.CommunicationSDK
import com.foodics.crosscommunicationlibrary.core.DeviceRole
import com.foodics.crosscommunicationlibrary.core.DiscoveredDevice
import kotlinx.coroutines.launch
import scanner.IoTDevice

class MainScreen(
    private val sdk: CommunicationSDK,
    private val deviceName: String
) : Screen {

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val coroutineScope = rememberCoroutineScope()

        var devices by remember { mutableStateOf<List<DiscoveredDevice>>(emptyList()) }
        var isServerRunning by remember { mutableStateOf(false) }
        var isScanning by remember { mutableStateOf(false) }


        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 32.dp, start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start
            ) {
                Text(
                    text = "Cross Communication SDK",
                    style = MaterialTheme.typography.titleLarge
                )

                Spacer(modifier = Modifier.weight(1f))

                Text(
                    text = deviceName,
                    style = MaterialTheme.typography.titleMedium
                )
            }
//
//            coroutineScope.launch {
//                navigator?.push(ChatScreen(sdk, DeviceRole.SERVER))
//            }


//            Button(
//                onClick = {
//                    coroutineScope.launch {
//
//                        isServerRunning = true
//                        navigator?.push(ChatScreen(sdk, DeviceRole.SERVER))
//                    }
//                },
//                enabled = !isServerRunning && !isScanning
//            ) {
//                Text("Start Server")
//            }
//
//            Button(
//                onClick = {
//                    coroutineScope.launch {
//                        sdk.startServer("Android ZEEZ")
//                       // isServerRunning = true
//                        // navigator?.push(ChatScreen(sdk, DeviceRole.SERVER))
//                    }
//                },
//                enabled = !isServerRunning && !isScanning
//            ) {
//                Text("Start Server")
//            }

            LaunchedEffect(isScanning) {
                if (!isScanning) return@LaunchedEffect

                sdk.scan().collect { list ->
                    list.forEach { device ->
                        println(
                            "Scanned Device -> " +
                                    "id=${device.id}, " +
                                    "name=${device.name}, " +
                                    "connections=${device.connectionTypes}, " +
                                    "addresses=${device.addressByType}"
                        )
                    }
                    devices = list
                }
            }



            Button(
                onClick = { isScanning = true },
                enabled = !isScanning && !isServerRunning
            ) {
                Text(if (isScanning) "Scanning..." else "Scan for Servers")
            }

            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(devices) { device ->

                    // Concatenate addresses for display
                    val addressesText = device.addressByType.entries.joinToString { (type, addr) ->
                        "$type: $addr"
                    }

                    Card(
                        onClick = {
                            coroutineScope.launch {
//                                sdk.connectToServer(device)
//                                navigator?.push(ChatScreen(sdk, DeviceRole.CLIENT))
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("Name: ${device.name}")
                            Text("Addresses: \n$addressesText")

                            Spacer(modifier = Modifier.height(4.dp))

                            // Show multiple connection tags
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                device.connectionTypes.forEach { type ->
                                    ConnectionTag(type)
                                }
                            }
                        }

                    }
                }
            }
        }
    }

    @Composable
    fun ConnectionTag(type: ConnectionType?) {
        val color = when (type) {
            ConnectionType.LAN -> MaterialTheme.colorScheme.primary
            ConnectionType.BLUETOOTH -> MaterialTheme.colorScheme.tertiary
            ConnectionType.WIFI_DIRECT -> MaterialTheme.colorScheme.secondary
            ConnectionType.WIFI_AWARE -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.8f)
            ConnectionType.GOOGLE_NEARBY -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.8f)
            ConnectionType.UDP -> MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
            ConnectionType.CLOUD -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.8f)
            ConnectionType.QR -> MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
            ConnectionType.WEBRTC -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.6f)
            ConnectionType.SSDP -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.6f)
            null -> MaterialTheme.colorScheme.outline

        }


        AssistChip(
            onClick = {},
            label = { Text(type?.name ?: "UNKNOWN") },
            colors = AssistChipDefaults.assistChipColors(
                containerColor = color.copy(alpha = 0.15f),
                labelColor = color
            )
        )
    }


}
