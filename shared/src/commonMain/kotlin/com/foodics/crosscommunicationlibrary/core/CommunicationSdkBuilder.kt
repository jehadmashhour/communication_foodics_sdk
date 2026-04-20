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
import com.foodics.crosscommunicationlibrary.coap.CoAPCommunicationChannel
import com.foodics.crosscommunicationlibrary.stomp.StompCommunicationChannel
import com.foodics.crosscommunicationlibrary.tcp.TcpCommunicationChannel
import com.foodics.crosscommunicationlibrary.multicast.MulticastCommunicationChannel
import com.foodics.crosscommunicationlibrary.sse.SSECommunicationChannel
import com.foodics.crosscommunicationlibrary.amqp.AMQPCommunicationChannel
import com.foodics.crosscommunicationlibrary.amqp.AMQP_DEFAULT_BROKER
import com.foodics.crosscommunicationlibrary.nats.NATSCommunicationChannel
import com.foodics.crosscommunicationlibrary.nats.NATS_DEFAULT_BROKER
import com.foodics.crosscommunicationlibrary.serial.SerialCommunicationChannel
import com.foodics.crosscommunicationlibrary.serial.SERIAL_DEFAULT_BAUD_RATE
import com.foodics.crosscommunicationlibrary.apple_multipeer.AppleMultipeerCommunicationChannel
import com.foodics.crosscommunicationlibrary.mdns.MDNSCommunicationChannel
import com.foodics.crosscommunicationlibrary.mdns.MDNS_DEFAULT_SERVICE_TYPE
import com.foodics.crosscommunicationlibrary.grpc.GrpcCommunicationChannel
import com.foodics.crosscommunicationlibrary.usb.UsbCommunicationChannel
import com.foodics.crosscommunicationlibrary.websocket.WebSocketCommunicationChannel
import com.foodics.crosscommunicationlibrary.ws_discovery.WSDiscoveryCommunicationChannel
import com.foodics.crosscommunicationlibrary.wifi_aware.WifiAwareCommunicationChannel
import com.foodics.crosscommunicationlibrary.wifi_direct.WifiDirectCommunicationChannel
import com.foodics.crosscommunicationlibrary.redis.RedisCommunicationChannel
import com.foodics.crosscommunicationlibrary.redis.REDIS_DEFAULT_BROKER
import com.foodics.crosscommunicationlibrary.zmq.ZMQCommunicationChannel
import com.foodics.crosscommunicationlibrary.modbus_tcp.ModbusCommunicationChannel

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

    fun enableCoap(): CommunicationSdkBuilder = apply {
        channels += CoAPCommunicationChannel()
    }

    fun enableStomp(): CommunicationSdkBuilder = apply {
        channels += StompCommunicationChannel()
    }

    fun enableTcpSocket(): CommunicationSdkBuilder = apply {
        channels += TcpCommunicationChannel()
    }

    fun enableMulticast(): CommunicationSdkBuilder = apply {
        channels += MulticastCommunicationChannel()
    }

    fun enableSse(): CommunicationSdkBuilder = apply {
        channels += SSECommunicationChannel()
    }

    fun enableAmqp(brokerUrl: String = AMQP_DEFAULT_BROKER): CommunicationSdkBuilder = apply {
        channels += AMQPCommunicationChannel(brokerUrl)
    }

    fun enableNats(brokerUrl: String = NATS_DEFAULT_BROKER): CommunicationSdkBuilder = apply {
        channels += NATSCommunicationChannel(brokerUrl)
    }

    fun enableSerial(
        portPath: String = "",
        baudRate: Int = SERIAL_DEFAULT_BAUD_RATE
    ): CommunicationSdkBuilder = apply {
        channels += SerialCommunicationChannel(portPath, baudRate)
    }

    fun enableAppleMultipeer(): CommunicationSdkBuilder = apply {
        channels += AppleMultipeerCommunicationChannel()
    }

    fun enableMdns(
        serviceType: String = MDNS_DEFAULT_SERVICE_TYPE
    ): CommunicationSdkBuilder = apply {
        channels += MDNSCommunicationChannel(serviceType)
    }

    fun enableGrpc(): CommunicationSdkBuilder = apply {
        channels += GrpcCommunicationChannel()
    }

    fun enableRedis(brokerUrl: String = REDIS_DEFAULT_BROKER): CommunicationSdkBuilder = apply {
        channels += RedisCommunicationChannel(brokerUrl)
    }

    fun enableZmq(): CommunicationSdkBuilder = apply {
        channels += ZMQCommunicationChannel()
    }

    fun enableModbusTcp(): CommunicationSdkBuilder = apply {
        channels += ModbusCommunicationChannel()
    }

    fun build(): CommunicationSDK {
        require(channels.isNotEmpty()) {
            "At least one communication channel must be enabled"
        }
        return CommunicationSDK(channels.toList())
    }
}
