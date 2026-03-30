package com.foodics.crosscommunicationlibrary.wifi_direct

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat

@Composable
fun RequireWiFiDirect(
    context: Context,
    content: @Composable () -> Unit
) {
    val grantedState = remember { mutableStateOf(false) }

    val permissions = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
    }.toTypedArray()

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val allGranted = result.values.all { it }
        grantedState.value = allGranted
    }

    // Launch permission request once
    LaunchedEffect(Unit) {
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            grantedState.value = true
        } else {
            launcher.launch(missing.toTypedArray())
        }
    }

    // Show content only when granted
    if (grantedState.value) {
        content()
    } else {
        // Optional: show placeholder / message / button
        Text("Wi-Fi Direct permissions required")
    }
}

