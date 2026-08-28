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
            // 0711, not 0700: this directory also holds `proxyCACertPath`, a deliberately
            // world-READABLE file (see its doc comment) that the console user's own shell/CLI
            // tools must open directly by path. `--x` for group/other grants exactly that --
            // traverse-to-a-known-name -- without granting `r` (no directory listing, so anyone
            // stuck at 0700 can't enumerate what else lives in here). Individual files still carry
            // their own restrictive mode (state.json, proxy_password, etc. stay 0600) -- this only
            // changes whether the directory itself is a wall or a locked door with named keys.
            try? FileManager.default.createDirectory(
                at: dir,
                withIntermediateDirectories: true,
                attributes: [.posixPermissions: 0o711]
            )
        }
    }

    private static func load(from fileURL: URL) -> FocusLockState? {
        guard let data = try? Data(contentsOf: fileURL) else { return nil }
        guard var state = FocusLockCodec.decode(FocusLockState.self, from: data) else { return nil }
        // `passcodeConfigured` is only meaningful as a transport field (getStatus sets it after
        // stripping the digest). Re-derive it from what's actually on disk so a hand-edited
        // state.json can't make the daemon claim a passcode it can't verify against.
        state.passcodeConfigured = state.guardianPasscode != nil
        return state
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
