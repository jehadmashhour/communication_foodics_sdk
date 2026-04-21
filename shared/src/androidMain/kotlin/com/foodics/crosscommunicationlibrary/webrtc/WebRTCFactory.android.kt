package com.foodics.crosscommunicationlibrary.webrtc

import com.foodics.crosscommunicationlibrary.AppContext
import org.webrtc.PeerConnectionFactory

internal object WebRTCFactory {
    @Volatile private var initialized = false

    fun initialize() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions
                    .builder(AppContext.get())
                    .createInitializationOptions()
            )
            initialized = true
        }
    }

    fun create(): PeerConnectionFactory = PeerConnectionFactory.builder().createPeerConnectionFactory()
}
