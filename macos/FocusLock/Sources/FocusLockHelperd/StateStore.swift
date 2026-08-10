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

    /// Writes via a 0600 temp file + atomic replace, rather than `Data.write(.atomic)` followed by
    /// a separate chmod -- the latter briefly creates the file under the process's default umask
    /// (more permissive than intended) before the chmod call catches up.
    private func save() {
        let data = FocusLockCodec.encode(state)
        let fm = FileManager.default
        let tmpURL = fileURL.appendingPathExtension("tmp-\(UUID().uuidString)")
        guard fm.createFile(atPath: tmpURL.path, contents: data, attributes: [.posixPermissions: 0o600]) else { return }
        do {
            _ = try fm.replaceItemAt(fileURL, withItemAt: tmpURL, options: .usingNewMetadataOnly)
        } catch {
            // First-ever save, when fileURL doesn't exist yet for replaceItemAt to replace.
            try? fm.moveItem(at: tmpURL, to: fileURL)
            try? fm.setAttributes([.posixPermissions: 0o600], ofItemAtPath: fileURL.path)
        }
    }
}
