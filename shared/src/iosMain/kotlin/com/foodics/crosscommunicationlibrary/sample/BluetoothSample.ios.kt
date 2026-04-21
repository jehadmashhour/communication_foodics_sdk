package com.foodics.crosscommunicationlibrary.sample

import androidx.compose.ui.window.ComposeUIViewController

fun BluetoothSampleViewController() = ComposeUIViewController(
    configure = { enforceStrictPlistSanityCheck = false }
) { BluetoothSampleApp() }
