@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.foodics.crosscommunicationlibrary.google_nearby

import com.foodics.crosscommunicationlibrary.cloud.toByteArray
import com.foodics.crosscommunicationlibrary.cloud.toNSData
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import platform.Foundation.NSData
import platform.Foundation.NSSelectorFromString
import platform.Foundation.NSClassFromString
import platform.Foundation.create
import platform.Foundation.setValue
import platform.darwin.NSObject

actual class GoogleNearbyServerHandler {

    private val _fromClient = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

    // Get the bridge instance at runtime — avoids compile-time symbol reference
    private val bridge: NSObject? by lazy {
        val cls = NSClassFromString("NearbyServerBridge") ?: run {
            println("[NearbyServer] NearbyServerBridge class not found at runtime")
            return@lazy null
        }
        val sharedSel = NSSelectorFromString("shared")
        (cls as NSObject).performSelector(sharedSel) as? NSObject
    }

    init {
        bridge?.let { b ->
            // Set the onDataReceived closure
            val block: (NSData?) -> Unit = { nsData ->
                nsData?.let { _fromClient.tryEmit(it.toByteArray()) }
            }
            b.setValue(block, forKey = "onDataReceived")
        }
    }

    suspend fun start(deviceName: String, identifier: String) {
        val name = "$deviceName|$identifier"
        bridge?.performSelector(
            NSSelectorFromString("startAdvertising:"),
            withObject = name
        )
    }

    suspend fun sendToClient(data: ByteArray) {
        bridge?.performSelector(
            NSSelectorFromString("sendData:"),
            withObject = data.toNSData()
        )
    }

    fun receiveFromClient(): Flow<ByteArray> = _fromClient.asSharedFlow()

    suspend fun stop() {
        bridge?.performSelector(NSSelectorFromString("stopAdvertising"))
    }
}
