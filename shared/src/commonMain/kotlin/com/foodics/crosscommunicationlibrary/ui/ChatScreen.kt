package com.foodics.crosscommunicationlibrary.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import com.foodics.crosscommunicationlibrary.core.CommunicationSDK
import com.foodics.crosscommunicationlibrary.core.DeviceRole
import kotlinx.coroutines.launch

class ChatScreen(
    private val sdk: CommunicationSDK,
    private val role: DeviceRole
) : Screen {

    @Composable
    override fun Content() {

        val coroutineScope = rememberCoroutineScope()

        var messages by remember { mutableStateOf<List<String>>(emptyList()) }
        var inputMessage by remember { mutableStateOf("") }

//        LaunchedEffect(Unit) {
//            if (role == DeviceRole.CLIENT) {
//                coroutineScope.launch {
//                    sdk.receiveDateFromServer().collect { data ->
//                        messages = messages + "Server: ${data.decodeToString()}"
//                    }
//                }
//            } else {
//                coroutineScope.launch {
//                    sdk.receiveDateFromClient().collect { data ->
//                        messages = messages + "Client: ${data.decodeToString()}"
//                    }
//                }
//            }
//        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 32.dp, start = 16.dp, end = 16.dp, bottom = 16.dp)
        ) {
            Text("Chat (${role.name})", style = MaterialTheme.typography.titleLarge)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextField(
                    value = inputMessage,
                    placeholder = { Text("Write here") },
                    onValueChange = { inputMessage = it },
                    modifier = Modifier
                        .weight(1f)
                        .padding(8.dp)
                        .height(56.dp)
                )

//                Button(onClick = {
//                    if (inputMessage.isNotBlank()) {
//                        coroutineScope.launch {
//                            if (role == DeviceRole.CLIENT) {
//                                sdk.sendDataToServer(inputMessage.encodeToByteArray())
//                            } else {
//                                sdk.sendDataToClient(inputMessage.encodeToByteArray())
//                            }
//                            messages = messages + "Me: $inputMessage"
//                            inputMessage = ""
//                        }
//                    }
//                }) {
//                    Text("Send")
//                }
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                items(messages) { msg ->
                    Text(msg)
                }
            }
        }
    }
}
