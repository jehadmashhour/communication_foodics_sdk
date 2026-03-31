import Foundation
import WebRTC
import shared   // KMP — WebRTCClientBridgeProtocol / WebRTCClientDelegate

/// Implements the Kotlin-defined WebRTCClientBridgeProtocol.
/// Receives the server's SDP offer + ICE via Kotlin, creates an answer,
/// gathers its own ICE candidates, and returns them to Kotlin via the
/// completion block so they can be forwarded through Ably.
public class WebRTCClientBridge: NSObject, WebRTCClientBridgeProtocol {

    public var delegate: (any WebRTCClientDelegate)?

    private let factory: RTCPeerConnectionFactory
    private var peerConnection: RTCPeerConnection?
    private var dataChannel: RTCDataChannel?

    private var collectedCandidates: [RTCIceCandidate] = []
    private var iceGatheringComplete = false
    private var answerCompletion: ((String, String) -> Void)?
    private var iceTimeout: DispatchWorkItem?

    override init() {
        RTCInitializeSSL()
        RTCSetMinDebugLogLevel(.error)
        let encoderFactory = RTCDefaultVideoEncoderFactory()
        let decoderFactory = RTCDefaultVideoDecoderFactory()
        factory = RTCPeerConnectionFactory(
            encoderFactory: encoderFactory,
            decoderFactory: decoderFactory
        )
        super.init()
    }

    // MARK: - WebRTCClientBridgeProtocol

    public func setOffer(
        sdp: String,
        iceCandidatesJson: String,
        completion: @escaping (String, String) -> Void
    ) {
        DispatchQueue.main.async { [weak self] in
            self?.doSetOffer(sdp: sdp, iceCandidatesJson: iceCandidatesJson, completion: completion)
        }
    }

    public func sendData(bytes: Data) -> Bool {
        guard let dc = dataChannel, dc.readyState == .open else { return false }
        let buffer = RTCDataBuffer(data: bytes, isBinary: true)
        return dc.sendData(buffer)
    }

    public func close() {
        DispatchQueue.main.async { [weak self] in
            self?.iceTimeout?.cancel()
            self?.dataChannel?.close()
            self?.peerConnection?.close()
            self?.dataChannel = nil
            self?.peerConnection = nil
            self?.collectedCandidates = []
            self?.answerCompletion = nil
            self?.iceGatheringComplete = false
        }
    }

    // MARK: - Private

    private func doSetOffer(
        sdp: String,
        iceCandidatesJson: String,
        completion: @escaping (String, String) -> Void
    ) {
        collectedCandidates = []
        iceGatheringComplete = false
        answerCompletion = completion

        let config = RTCConfiguration()
        config.iceServers = [RTCIceServer(urlStrings: ["stun:stun.l.google.com:19302"])]
        config.sdpSemantics = .unifiedPlan
        let constraints = RTCMediaConstraints(
            mandatoryConstraints: nil, optionalConstraints: nil
        )

        peerConnection = factory.peerConnection(
            with: config, constraints: constraints, delegate: self
        )

        // Set remote description (server's offer)
        let offerDesc = RTCSessionDescription(type: .offer, sdp: sdp)
        peerConnection?.setRemoteDescription(offerDesc) { [weak self] error in
            guard let self = self else { return }
            if let error = error {
                NSLog("[WebRTCClientBridge] setRemoteDescription error: \(error)")
                return
            }

            // Add server's ICE candidates
            if let candidates = self.parseCandidates(iceCandidatesJson) {
                candidates.forEach { self.peerConnection?.add($0) }
            }

            // Create answer
            self.peerConnection?.answer(for: constraints) { [weak self] sdpAnswer, error in
                guard let self = self, let sdpAnswer = sdpAnswer else { return }
                self.peerConnection?.setLocalDescription(sdpAnswer) { _ in
                    // ICE gathering starts; 5 s safety timeout
                    let item = DispatchWorkItem { [weak self] in
                        self?.deliverAnswer()
                    }
                    self.iceTimeout = item
                    DispatchQueue.main.asyncAfter(deadline: .now() + 5.0, execute: item)
                }
            }
        }
    }

    private func deliverAnswer() {
        guard let completion = answerCompletion,
              let sdp = peerConnection?.localDescription?.sdp else { return }
        answerCompletion = nil
        iceTimeout?.cancel()
        let candidatesJson = serializeCandidates(collectedCandidates)
        completion(sdp, candidatesJson)
    }

    private func serializeCandidates(_ candidates: [RTCIceCandidate]) -> String {
        let array: [[String: Any]] = candidates.map {
            ["candidate": $0.sdp, "sdpMid": $0.sdpMid ?? "", "sdpMLineIndex": $0.sdpMLineIndex]
        }
        guard let data = try? JSONSerialization.data(withJSONObject: array),
              let str = String(data: data, encoding: .utf8) else { return "[]" }
        return str
    }

    private func parseCandidates(_ json: String) -> [RTCIceCandidate]? {
        guard let data = json.data(using: .utf8),
              let array = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]]
        else { return nil }
        return array.compactMap { dict -> RTCIceCandidate? in
            guard let sdp = dict["candidate"] as? String,
                  let sdpMid = dict["sdpMid"] as? String,
                  let idx = dict["sdpMLineIndex"] as? Int32 else { return nil }
            return RTCIceCandidate(sdp: sdp, sdpMLineIndex: idx, sdpMid: sdpMid)
        }
    }
}

// MARK: - RTCPeerConnectionDelegate

extension WebRTCClientBridge: RTCPeerConnectionDelegate {

    public func peerConnection(
        _ peerConnection: RTCPeerConnection,
        didChange newState: RTCIceGatheringState
    ) {
        if newState == .complete && !iceGatheringComplete {
            iceGatheringComplete = true
            deliverAnswer()
        }
    }

    public func peerConnection(
        _ peerConnection: RTCPeerConnection,
        didGenerate candidate: RTCIceCandidate
    ) {
        collectedCandidates.append(candidate)
    }

    public func peerConnection(_ peerConnection: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {
        // Client receives the DataChannel opened by the server
        self.dataChannel = dataChannel
        dataChannel.delegate = self
        delegate?.onConnectionReady()
    }

    public func peerConnection(_ peerConnection: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {}
    public func peerConnection(_ peerConnection: RTCPeerConnection, didAdd stream: RTCMediaStream) {}
    public func peerConnection(_ peerConnection: RTCPeerConnection, didRemove stream: RTCMediaStream) {}
    public func peerConnectionShouldNegotiate(_ peerConnection: RTCPeerConnection) {}
    public func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceConnectionState) {}
    public func peerConnection(_ peerConnection: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}
}

// MARK: - RTCDataChannelDelegate

extension WebRTCClientBridge: RTCDataChannelDelegate {

    public func dataChannelDidChangeState(_ dataChannel: RTCDataChannel) {
        if dataChannel.readyState == .open {
            delegate?.onConnectionReady()
        }
    }

    public func dataChannel(
        _ dataChannel: RTCDataChannel,
        didReceiveMessageWith buffer: RTCDataBuffer
    ) {
        delegate?.onDataReceived(data: buffer.data)
    }
}
