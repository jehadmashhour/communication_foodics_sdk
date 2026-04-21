package com.foodics.crosscommunicationlibrary.sample

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

@Composable
actual fun rememberBluetoothEnableLauncher(onEnabled: () -> Unit): () -> Unit {
    val currentOnEnabled = rememberUpdatedState(onEnabled)

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) currentOnEnabled.value()
    }

    return remember {
        {
            @Suppress("DEPRECATION")
            if (BluetoothAdapter.getDefaultAdapter()?.isEnabled == true) {
                currentOnEnabled.value()
            } else {
                launcher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            }
        }
    }
}
