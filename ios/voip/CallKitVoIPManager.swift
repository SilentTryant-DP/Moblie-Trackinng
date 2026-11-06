import Foundation
import PushKit
import CallKit

/**
 * iOS CallKit & PushKit VoIP Manager
 *
 * Implements Tier 1/2 Call Mirroring on iOS:
 * - Subscribes to PKPushType.voIP via PushKit.
 * - Adheres strictly to iOS App Store guidelines: EVERY VoIP push MUST report
 *   an incoming call to CallKit via CXProvider to prevent process termination.
 * - Bypasses Do Not Disturb (DND) by invoking native full-screen incoming call UI.
 */
public final class CallKitVoIPManager: NSObject, PKPushRegistryDelegate, CXProviderDelegate {

    public static let shared = CallKitVoIPManager()

    private var pushRegistry: PKPushRegistry?
    private let callProvider: CXProvider
    private let callController = CXCallController()

    public override init() {
        let configuration = CXProviderConfiguration(localizedName: "Ecosystem Call Mirror")
        configuration.supportsVideo = false
        configuration.maximumCallsPerCallGroup = 1
        configuration.supportedHandleTypes = [.phoneNumber, .generic]
        configuration.includesCallsInRecents = false

        self.callProvider = CXProvider(configuration: configuration)
        super.init()

        self.callProvider.setDelegate(self, queue: nil)
    }

    public func setupPushKit() {
        let registry = PKPushRegistry(queue: DispatchQueue.main)
        registry.delegate = self
        registry.desiredPushTypes = [.voIP]
        self.pushRegistry = registry
    }

    // MARK: - PKPushRegistryDelegate

    public func pushRegistry(_ registry: PKPushRegistry, didUpdate pushCredentials: PKPushCredentials, for type: PKPushType) {
        let token = pushCredentials.token.map { String(format: "%02.2hhx", $0) }.joined()
        print("[CallKitVoIPManager] VoIP Device Token registered: \(token)")
        // Upload token to cloud backend for routing cross-device mirrored calls
    }

    public func pushRegistry(_ registry: PKPushRegistry, didReceiveIncomingPushWith payload: PKPushPayload, for type: PKPushType, completion: @escaping () -> Void) {
        guard type == .voIP else {
            completion()
            return
        }

        let dict = payload.dictionaryPayload
        guard let callerId = dict["caller_id"] as? String,
              let callerName = dict["caller_name"] as? String else {
            completion()
            return
        }

        let callUUID = UUID()
        let update = CXCallUpdate()
        update.remoteHandle = CXHandle(type: .generic, value: callerId)
        update.localizedCallerName = "\(callerName) (Mirrored)"
        update.hasVideo = false

        // CRITICAL iOS REQUIREMENT: Must report incoming call synchronously upon VoIP push
        callProvider.reportNewIncomingCall(with: callUUID, update: update) { error in
            if let error = error {
                print("[CallKitVoIPManager] Failed to report incoming call: \(error.localizedDescription)")
            } else {
                print("[CallKitVoIPManager] Successfully displayed native full-screen incoming call UI for \(callerName)")
            }
            completion()
        }
    }

    public func pushRegistry(_ registry: PKPushRegistry, didInvalidate pushTokenFor: PKPushType) {
        print("[CallKitVoIPManager] VoIP Push token invalidated")
    }

    // MARK: - CXProviderDelegate

    public func providerDidReset(_ provider: CXProvider) {
        // Reset local call states
    }

    public func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
        print("[CallKitVoIPManager] User answered mirrored call: \(action.callUUID)")
        // Dispatch answer event to originating device via WebSocket / P2P mesh
        action.fulfill()
    }

    public func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
        print("[CallKitVoIPManager] User rejected/ended mirrored call: \(action.callUUID)")
        // Dispatch hangup signal to originating device
        action.fulfill()
    }
}
