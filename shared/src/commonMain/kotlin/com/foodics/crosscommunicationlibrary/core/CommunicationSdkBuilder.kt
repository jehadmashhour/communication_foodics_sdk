package com.foodics.crosscommunicationlibrary.core

import com.foodics.crosscommunicationlibrary.bluetooth.BluetoothCommunicationChannel
import com.foodics.crosscommunicationlibrary.cloud.CloudCommunicationChannel
import com.foodics.crosscommunicationlibrary.google_nearby.GoogleNearbyCommunicationChannel
import com.foodics.crosscommunicationlibrary.lan.LanCommunicationChannel
import com.foodics.crosscommunicationlibrary.qr.QRCommunicationChannel
import com.foodics.crosscommunicationlibrary.udp.UDPCommunicationChannel
import com.foodics.crosscommunicationlibrary.ssdp.SSDPCommunicationChannel
import com.foodics.crosscommunicationlibrary.mqtt.MQTTCommunicationChannel
import com.foodics.crosscommunicationlibrary.nfc.NFCCommunicationChannel
import com.foodics.crosscommunicationlibrary.uwb.UWBCommunicationChannel
import com.foodics.crosscommunicationlibrary.webrtc.WebRTCCommunicationChannel
import com.foodics.crosscommunicationlibrary.http.HttpRestCommunicationChannel
import com.foodics.crosscommunicationlibrary.bluetooth_classic.BluetoothClassicCommunicationChannel
import com.foodics.crosscommunicationlibrary.usb.UsbCommunicationChannel
import com.foodics.crosscommunicationlibrary.websocket.WebSocketCommunicationChannel
import com.foodics.crosscommunicationlibrary.ws_discovery.WSDiscoveryCommunicationChannel
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

    fun enableWebRTC(): CommunicationSdkBuilder = apply {
        channels += WebRTCCommunicationChannel()
    }

    fun enableSsdp(): CommunicationSdkBuilder = apply {
        channels += SSDPCommunicationChannel()
    }

    fun enableUwb(): CommunicationSdkBuilder = apply {
        channels += UWBCommunicationChannel()
    }

    fun enableNfc(): CommunicationSdkBuilder = apply {
        channels += NFCCommunicationChannel()
    }

    fun enableMqtt(brokerUrl: String = "tcp://broker.hivemq.com:1883"): CommunicationSdkBuilder = apply {
        channels += MQTTCommunicationChannel(brokerUrl)
    }

    fun enableWsDiscovery(): CommunicationSdkBuilder = apply {
        channels += WSDiscoveryCommunicationChannel()
    }

    fun enableHttpRest(): CommunicationSdkBuilder = apply {
        channels += HttpRestCommunicationChannel()
    }

    fun enableWebSocket(): CommunicationSdkBuilder = apply {
        channels += WebSocketCommunicationChannel()
    }

    fun enableUsb(): CommunicationSdkBuilder = apply {
        channels += UsbCommunicationChannel()
    }

    fun enableBluetoothClassic(): CommunicationSdkBuilder = apply {
        channels += BluetoothClassicCommunicationChannel()
    }

    fun build(): CommunicationSDK {
        require(channels.isNotEmpty()) {
            "At least one communication channel must be enabled"
        }
        return CommunicationSDK(channels.toList())
    }
}
