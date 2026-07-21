import Foundation
import FocusLockShared

let stateStore = StateStore()
let xpcService = XPCService(stateStore: stateStore) {
    EnforcementLoop.shared.reapplyNow()
}
let delegate = ListenerDelegate(exportedObject: xpcService)

let listener = NSXPCListener(machServiceName: FocusLockConstants.machServiceName)
listener.delegate = delegate
listener.resume()

EnforcementLoop.shared.start(stateStore: stateStore)

FileHandle.standardError.write("FocusLockHelperd started, listening on \(FocusLockConstants.machServiceName)\n".data(using: .utf8)!)

RunLoop.current.run()
