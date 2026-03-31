import Foundation
import WebRTC
import shared   // KMP — WebRTCServerBridgeProtocol / WebRTCServerDelegate

/// Implements the Kotlin-defined WebRTCServerBridgeProtocol.
/// Creates an RTCPeerConnection + RTCDataChannel (offerer role), handles SDP
/// negotiation, and delivers received bytes to the Kotlin delegate.
public class WebRTCServerBridge: NSObject, WebRTCServerBridgeProtocol {

    public var delegate: (any WebRTCServerDelegate)?

    private let factory: RTCPeerConnectionFactory
    private var peerConnection: RTCPeerConnection?
    private var dataChannel: RTCDataChannel?

    private var collectedCandidates: [RTCIceCandidate] = []
    private var iceGatheringComplete = false
    private var offerCompletion: ((String, String) -> Void)?
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

    // MARK: - WebRTCServerBridgeProtocol

    public func createOffer(completion: @escaping (String, String) -> Void) {
        DispatchQueue.main.async { [weak self] in
            self?.doCreateOffer(completion: completion)
        }
    }

    public func handleAnswer(sdp: String, iceCandidatesJson: String) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            let sessionDesc = RTCSessionDescription(type: .answer, sdp: sdp)
            self.peerConnection?.setRemoteDescription(sessionDesc) { error in
                if let error = error {
                    NSLog("[WebRTCServerBridge] setRemoteDescription error: \(error)")
                }
            }
            self.applyIceCandidates(iceCandidatesJson)
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
            self?.offerCompletion = nil
            self?.iceGatheringComplete = false
        }
    }

    // MARK: - Private

    private func doCreateOffer(completion: @escaping (String, String) -> Void) {
        collectedCandidates = []
        iceGatheringComplete = false
        offerCompletion = completion

        let config = RTCConfiguration()
        config.iceServers = [RTCIceServer(urlStrings: ["stun:stun.l.google.com:19302"])]
        config.sdpSemantics = .unifiedPlan
        let constraints = RTCMediaConstraints(
            mandatoryConstraints: nil, optionalConstraints: nil
        )

        peerConnection = factory.peerConnection(
            with: config, constraints: constraints, delegate: self
        )

        let dcConfig = RTCDataChannelConfiguration()
        dcConfig.isOrdered = true
        dataChannel = peerConnection?.dataChannel(forLabel: "data", configuration: dcConfig)
        dataChannel?.delegate = self

        peerConnection?.offer(for: constraints) { [weak self] sdp, error in
            guard let self = self, let sdp = sdp else { return }
            self.peerConnection?.setLocalDescription(sdp) { _ in
                // ICE gathering starts; fire a 5 s safety timeout
                let item = DispatchWorkItem { [weak self] in
                    self?.deliverOffer()
                }
                self.iceTimeout = item
                DispatchQueue.main.asyncAfter(deadline: .now() + 5.0, execute: item)
            }
        }
    }

    private func deliverOffer() {
        guard let completion = offerCompletion,
              let sdp = peerConnection?.localDescription?.sdp else { return }
        offerCompletion = nil
        iceTimeout?.cancel()
        let candidatesJson = serializeCandidates(collectedCandidates)
        completion(sdp, candidatesJson)
    }

    private func applyIceCandidates(_ json: String) {
        guard let candidates = parseCandidates(json) else { return }
        candidates.forEach { peerConnection?.add($0) }
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

extension WebRTCServerBridge: RTCPeerConnectionDelegate {

    public func peerConnection(
        _ peerConnection: RTCPeerConnection,
        didChange newState: RTCIceGatheringState
    ) {
        if newState == .complete && !iceGatheringComplete {
            iceGatheringComplete = true
            deliverOffer()
        }
    }

    public func peerConnection(
        _ peerConnection: RTCPeerConnection,
        didGenerate candidate: RTCIceCandidate
    ) {
        collectedCandidates.append(candidate)
    }

    public func peerConnection(_ peerConnection: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {}
    public func peerConnection(_ peerConnection: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {}
    public func peerConnection(_ peerConnection: RTCPeerConnection, didAdd stream: RTCMediaStream) {}
    public func peerConnection(_ peerConnection: RTCPeerConnection, didRemove stream: RTCMediaStream) {}
    public func peerConnectionShouldNegotiate(_ peerConnection: RTCPeerConnection) {}
    public func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceConnectionState) {}
    public func peerConnection(_ peerConnection: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}
}

// MARK: - RTCDataChannelDelegate

extension WebRTCServerBridge: RTCDataChannelDelegate {

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
