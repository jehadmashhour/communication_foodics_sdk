package advertisement

import server.IOSServer

actual class Advertiser(private val server: IOSServer) {

    actual suspend fun advertise(settings: AdvertisementSettings) {
        server.advertise(settings)
    }

    actual suspend fun stop() {
        server.stopAdvertising()
    }
}
