import Foundation
import FocusLockShared

/// Accepts XPC connections from this app's own signed binaries and refuses everything else.
///
/// This used to `return true` unconditionally, which -- because the helper's plist registers its
/// Mach service in the global bootstrap namespace -- meant every method on
/// `FocusLockXPCProtocol` was reachable by any process on the machine, at any uid. See
/// `XPCPeerValidator` for the full reasoning, what the check does and doesn't cover, and why
/// enforcement is gated on the daemon's own signature.
final class ListenerDelegate: NSObject, NSXPCListenerDelegate {
    private let exportedObject: XPCService

    init(exportedObject: XPCService) {
        self.exportedObject = exportedObject
    }

    func listener(_ listener: NSXPCListener, shouldAcceptNewConnection newConnection: NSXPCConnection) -> Bool {
        if let reason = XPCPeerValidator.rejectionReason(forPID: newConnection.processIdentifier) {
            FileHandle.standardError.write("[xpc-auth] refused connection: \(reason)\n".data(using: .utf8)!)
            // An unrecognized process reaching this service at all is worth telling the
            // accountability partner about -- it is either a bug or somebody probing the daemon,
            // and both are things the Guardian should hear about rather than only find in a log.
            TamperReporter.report(
                type: "xpc_peer_rejected",
                details: "Refused an XPC connection from an unrecognized process: \(reason)"
            )
            return false
        }

        newConnection.exportedInterface = NSXPCInterface(with: FocusLockXPCProtocol.self)
        newConnection.exportedObject = exportedObject
        newConnection.resume()
        return true
    }
}
