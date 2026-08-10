import Foundation

/// Shared subprocess-spawning helper -- previously reimplemented nearly identically five times
/// (in DNSEnforcer, HostsFileBlocker, PFBlocker, UpdateManager, and FocusLockWatchdog) across
/// three targets, each a slightly different combination of "capture output or not" / "capture
/// stderr too or not". One place to get right instead of five to keep in sync.
public enum ProcessRunner {
    public struct Result {
        public let status: Int32
        public let output: String
    }

    /// Runs `path` with `args` to completion, capturing combined stdout+stderr.
    @discardableResult
    public static func run(_ path: String, _ args: [String]) -> Result {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: path)
        process.arguments = args
        let pipe = Pipe()
        process.standardOutput = pipe
        process.standardError = pipe
        guard (try? process.run()) != nil else { return Result(status: -1, output: "failed to launch \(path)") }
        // Read before waiting -- a process that writes more than the pipe buffer holds would
        // otherwise deadlock waiting for a reader that only shows up after waitUntilExit().
        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        process.waitUntilExit()
        return Result(status: process.terminationStatus, output: String(data: data, encoding: .utf8) ?? "")
    }

    /// Runs `path` with `args`, capturing stdout only -- for tools whose stderr is diagnostic
    /// noise the caller doesn't want mixed into parsed output.
    public static func runCapturingStdout(_ path: String, _ args: [String]) -> String {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: path)
        process.arguments = args
        let pipe = Pipe()
        process.standardOutput = pipe
        process.standardError = FileHandle.nullDevice
        guard (try? process.run()) != nil else { return "" }
        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        process.waitUntilExit()
        return String(data: data, encoding: .utf8) ?? ""
    }

    /// Runs `path` with `args`, discarding all output -- for callers that only care about the
    /// exit status (or nothing at all).
    @discardableResult
    public static func runSilently(_ path: String, _ args: [String]) -> Int32 {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: path)
        process.arguments = args
        process.standardOutput = FileHandle.nullDevice
        process.standardError = FileHandle.nullDevice
        guard (try? process.run()) != nil else { return -1 }
        process.waitUntilExit()
        return process.terminationStatus
    }
}
