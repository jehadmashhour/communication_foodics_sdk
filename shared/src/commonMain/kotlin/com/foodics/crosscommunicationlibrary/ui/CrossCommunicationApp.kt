package com.foodics.crosscommunicationlibrary.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import cafe.adriel.voyager.navigator.Navigator
import com.foodics.crosscommunicationlibrary.core.CommunicationSDK

@Composable
fun CrossCommunicationApp() {

    val character = "A"
    val deviceName = "Android $character"
    val identifier = "${character}01"


    val sdk = remember {
        CommunicationSDK.builder()
            .enableLan()
            .enableBluetooth()
            .enableWifiDirect()
            .enableWifiAware()
            .enableGoogleNearby()
            .enableUdp()
            .enableCloud()
//            .enableQR()
            .enableWebRTC()
            .enableSsdp()
            .enableUwb()
            .enableNfc()
            .enableMqtt()
            .enableWsDiscovery()
            .enableHttpRest()
            .enableWebSocket()
            .enableUsb()
            .enableBluetoothClassic()
            .enableCoap()
            .build()
    }


    LaunchedEffect(Unit) {
        sdk.startServers(deviceName, identifier)
    }

    Navigator(MainScreen(sdk, deviceName))
}
