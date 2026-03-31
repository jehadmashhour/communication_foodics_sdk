package com.foodics.crosscommunicationlibrary.uwb

import platform.Foundation.NSData

/**
 * Implemented by Kotlin to receive NearbyInteraction ranging events from Swift.
 */
interface UWBRangingDelegate {
    /** Called each time a ranging result arrives. NaN signals unavailable. */
    fun onRangingResult(distanceMeters: Float, azimuthDegrees: Float, elevationDegrees: Float)
    fun onSessionError(description: String)
    fun onSessionInvalidated()
}

/**
 * Implemented by Swift's UWBBridge.
 *
 * Swift must set this up before the KMP framework is used, e.g.:
 *   UWBBridgeProvider.shared.serverBridge = UWBServerBridge()
 *   UWBBridgeProvider.shared.clientBridge = UWBClientBridge()
 *
 * Token lifecycle:
 *  1. Kotlin calls [getDiscoveryToken] to obtain the local NIDiscoveryToken
 *     as NSData (serialised with NSKeyedArchiver by Swift).
 *  2. Kotlin exchanges the token over the UDP/TCP OOB channel.
 *  3. Kotlin calls [startRanging] with the peer's token bytes.
 *  4. Swift deserialises the token (NSKeyedUnarchiver) and runs NISession.
 *  5. Swift calls [delegate.onRangingResult] with each measurement.
 *
 * Requires iPhone 11+ running iOS 14+.
 * Add NSNearbyInteractionUsageDescription in Info.plist and the
 * com.apple.developer.nearby-interaction entitlement.
 */
interface UWBServerBridgeProtocol {
    var delegate: UWBRangingDelegate?
    /** Returns the serialised NIDiscoveryToken, or null if UWB is unavailable. */
    fun getDiscoveryToken(): NSData?
    /** Begin ranging with the peer's serialised NIDiscoveryToken. */
    fun startRanging(peerToken: NSData)
    fun stop()
}

interface UWBClientBridgeProtocol {
    var delegate: UWBRangingDelegate?
    fun getDiscoveryToken(): NSData?
    fun startRanging(peerToken: NSData)
    fun stop()
}
