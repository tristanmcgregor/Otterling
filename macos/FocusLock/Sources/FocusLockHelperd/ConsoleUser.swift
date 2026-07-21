import Foundation

/// Finds the uid of the currently logged-in GUI user, so the root daemon can launch a GUI app
/// *in that user's session* instead of running it (uselessly, and dangerously) as root. Uses the
/// owner of /dev/console, the standard trick for this from a LaunchDaemon.
enum ConsoleUser {
    static func currentUID() -> uid_t? {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/stat")
        process.arguments = ["-f", "%u", "/dev/console"]
        let pipe = Pipe()
        process.standardOutput = pipe
        process.standardError = FileHandle.nullDevice
        guard (try? process.run()) != nil else { return nil }
        process.waitUntilExit()
        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        guard let output = String(data: data, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines),
              let uid = UInt32(output) else { return nil }
        return uid_t(uid)
    }
}
