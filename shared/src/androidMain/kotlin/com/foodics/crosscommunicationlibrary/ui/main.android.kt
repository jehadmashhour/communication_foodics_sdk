package com.foodics.crosscommunicationlibrary.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.foodics.crosscommunicationlibrary.wifi_direct.RequireWiFiDirect
import com.foodics.crosscommunicationlibrary.permission.ble.RequireBluetooth
import com.foodics.crosscommunicationlibrary.permission.ble.RequireLocation
import com.foodics.crosscommunicationlibrary.permission.ble.bluetooth.BluetoothStateManager
import com.foodics.crosscommunicationlibrary.permission.ble.location.LocationStateManager
import com.foodics.crosscommunicationlibrary.permission.ble.viewmodel.PermissionViewModel
import com.foodics.crosscommunicationlibrary.wifi_aware.RequireWiFiAware

class PermissionViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(PermissionViewModel::class.java)) {
            val bluetoothManager = BluetoothStateManager(context)
            val locationManager = LocationStateManager(context)
            @Suppress("UNCHECKED_CAST")
            return PermissionViewModel(bluetoothManager, locationManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

@Composable
fun MainView(context: Context) {
    val viewModel: PermissionViewModel = viewModel(
        factory = PermissionViewModelFactory(context)
    )

    RequireBluetooth(viewModel) {
        RequireLocation(viewModel) {
            RequireWiFiDirect(context) {
                RequireWiFiAware(context) {
                    CrossCommunicationApp()
                }
            }
        }
    }
}
