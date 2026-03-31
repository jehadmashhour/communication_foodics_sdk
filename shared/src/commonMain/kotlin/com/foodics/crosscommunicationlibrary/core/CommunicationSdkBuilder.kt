package com.foodics.crosscommunicationlibrary.core

import com.foodics.crosscommunicationlibrary.bluetooth.BluetoothCommunicationChannel
import com.foodics.crosscommunicationlibrary.cloud.CloudCommunicationChannel
import com.foodics.crosscommunicationlibrary.google_nearby.GoogleNearbyCommunicationChannel
import com.foodics.crosscommunicationlibrary.lan.LanCommunicationChannel
import com.foodics.crosscommunicationlibrary.qr.QRCommunicationChannel
import com.foodics.crosscommunicationlibrary.udp.UDPCommunicationChannel
import com.foodics.crosscommunicationlibrary.wifi_aware.WifiAwareCommunicationChannel
import com.foodics.crosscommunicationlibrary.wifi_direct.WifiDirectCommunicationChannel

class CommunicationSdkBuilder {

    private val channels = mutableListOf<CommunicationChannel>()

    fun enableLan(): CommunicationSdkBuilder = apply {
        channels += LanCommunicationChannel()
    }

    fun enableBluetooth(): CommunicationSdkBuilder = apply {
        channels += BluetoothCommunicationChannel()
    }

    fun enableWifiDirect(): CommunicationSdkBuilder = apply {
        channels += WifiDirectCommunicationChannel()
    }

    fun enableWifiAware(): CommunicationSdkBuilder = apply {
        channels += WifiAwareCommunicationChannel()
    }

    fun enableGoogleNearby(): CommunicationSdkBuilder = apply {
        channels += GoogleNearbyCommunicationChannel()
    }

    fun enableUdp(): CommunicationSdkBuilder = apply {
        channels += UDPCommunicationChannel()
    }

    fun enableCloud(): CommunicationSdkBuilder = apply {
        channels += CloudCommunicationChannel()
    }

    fun enableQR(): CommunicationSdkBuilder = apply {
        channels += QRCommunicationChannel()
    }

    fun build(): CommunicationSDK {
        require(channels.isNotEmpty()) {
            "At least one communication channel must be enabled"
        }
        return CommunicationSDK(channels.toList())
    }
}
