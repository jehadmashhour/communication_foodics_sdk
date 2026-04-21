package com.foodics.crosscommunicationlibrary.sample

import androidx.compose.runtime.Composable

@Composable
expect fun rememberBluetoothEnableLauncher(onEnabled: () -> Unit): () -> Unit
