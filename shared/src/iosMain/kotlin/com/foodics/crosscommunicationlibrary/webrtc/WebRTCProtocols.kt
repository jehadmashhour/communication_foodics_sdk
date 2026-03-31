package com.foodics.crosscommunicationlibrary.webrtc

import platform.Foundation.NSData

/**
 * Delegate that Swift calls back into Kotlin when WebRTC events occur.
 */
interface WebRTCServerDelegate {
    /** Raw bytes received on the DataChannel from the remote client. */
    fun onDataReceived(data: NSData)
    /** DataChannel has opened and data can now flow. */
    fun onConnectionReady()
}

interface WebRTCClientDelegate {
    fun onDataReceived(data: NSData)
    fun onConnectionReady()
}

/**
 * Swift (GoogleWebRTC) server-side bridge.
 * Kotlin calls [createOffer] to start negotiation; completion returns (sdp, iceCandidatesJson).
 * Kotlin calls [handleAnswer] when the client's answer arrives via Ably.
 */
interface WebRTCServerBridgeProtocol {
    var delegate: WebRTCServerDelegate?
    /** Creates RTCPeerConnection + DataChannel, generates offer, gathers ICE, then calls completion. */
    fun createOffer(completion: (String, String) -> Unit)
    /** Apply the client's SDP answer + ICE candidates (JSON array string). */
    fun handleAnswer(sdp: String, iceCandidatesJson: String)
    fun sendData(bytes: NSData): Boolean
    fun close()
}

/**
 * Swift (GoogleWebRTC) client-side bridge.
 * Kotlin calls [setOffer] with the server's SDP + ICE; completion returns the client's answer.
 */
interface WebRTCClientBridgeProtocol {
    var delegate: WebRTCClientDelegate?
    /** Sets remote offer + ICE, creates answer, gathers ICE, then calls completion. */
    fun setOffer(sdp: String, iceCandidatesJson: String, completion: (String, String) -> Unit)
    fun sendData(bytes: NSData): Boolean
    fun close()
}
