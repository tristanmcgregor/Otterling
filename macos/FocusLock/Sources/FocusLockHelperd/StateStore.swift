import Foundation
import FocusLockShared

/// Owns the on-disk block state. The daemon runs as root and writes this file with 0600
/// permissions, so the GUI app (running as a Standard user) can read status via XPC but has no
/// direct filesystem access to mutate it.
final class StateStore {
    private let lock = NSLock()
    private var state: FocusLockState
    private let fileURL: URL

    init(fileURL: URL = URL(fileURLWithPath: FocusLockConstants.stateFilePath)) {
        self.fileURL = fileURL
        Self.ensureDirectory(for: fileURL)
        self.state = Self.load(from: fileURL) ?? FocusLockState()
        self.save()
    }

    private static func ensureDirectory(for fileURL: URL) {
        let dir = fileURL.deletingLastPathComponent()
        if !FileManager.default.fileExists(atPath: dir.path) {
            try? FileManager.default.createDirectory(
                at: dir,
                withIntermediateDirectories: true,
                attributes: [.posixPermissions: 0o700]
            )
        }
    }

    private static func load(from fileURL: URL) -> FocusLockState? {
        guard let data = try? Data(contentsOf: fileURL) else { return nil }
        return FocusLockCodec.decode(FocusLockState.self, from: data)
    }

    func snapshot() -> FocusLockState {
        lock.lock()
        defer { lock.unlock() }
        return state
    }

    /// Mutates state under the lock and persists immediately. `block` should be quick and
    /// synchronous -- no I/O other than the persist this triggers.
    func mutate(_ block: (inout FocusLockState) -> Void) {
        lock.lock()
        defer { lock.unlock() }
        block(&state)
        save()
    }

    private func save() {
        let data = FocusLockCodec.encode(state)
        try? data.write(to: fileURL, options: .atomic)
        try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: fileURL.path)
    }
}
