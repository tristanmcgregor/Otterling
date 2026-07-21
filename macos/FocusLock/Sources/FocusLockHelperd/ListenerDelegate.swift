import Foundation
import FocusLockShared

final class ListenerDelegate: NSObject, NSXPCListenerDelegate {
    private let exportedObject: XPCService

    init(exportedObject: XPCService) {
        self.exportedObject = exportedObject
    }

    func listener(_ listener: NSXPCListener, shouldAcceptNewConnection newConnection: NSXPCConnection) -> Bool {
        newConnection.exportedInterface = NSXPCInterface(with: FocusLockXPCProtocol.self)
        newConnection.exportedObject = exportedObject
        newConnection.resume()
        return true
    }
}
