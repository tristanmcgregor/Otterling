import Foundation

/// Builds the set of "currently running executable basenames" from full command lines, not just
/// each process's own binary path. `ProcessScanner`'s `proc_pidpath`-based name only sees the
/// interpreter for a script (e.g. `/bin/bash`), never the script itself, so it can't detect
/// whether a specific interpreted script (like a watchdog `.sh`) is running. This instead looks
/// at every whitespace-separated token of every process's full command line and takes its last
/// path component, which catches both "the binary itself is named X" and "X is a script argument
/// passed to an interpreter" -- while still requiring an exact (case-insensitive) basename match,
/// so a peer file that merely shares a parent directory (e.g. a sibling script in the same .app
/// bundle) can't produce a false positive.
enum CommandLineScanner {
    static func runningExecutableBasenames() -> Set<String> {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/bin/ps")
        process.arguments = ["-eo", "command="]
        let pipe = Pipe()
        process.standardOutput = pipe
        process.standardError = FileHandle.nullDevice
        guard (try? process.run()) != nil else { return [] }
        // Must read before waiting: `ps -eo command=` output for every process on the system
        // easily exceeds the pipe's buffer, so if we called waitUntilExit() first, `ps` would
        // block writing to a full pipe while we block waiting for it to exit -- a deadlock.
        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        process.waitUntilExit()
        let output = String(data: data, encoding: .utf8) ?? ""

        var basenames = Set<String>()
        for line in output.split(separator: "\n") {
            for token in line.split(separator: " ") {
                let basename = (String(token) as NSString).lastPathComponent.lowercased()
                if !basename.isEmpty {
                    basenames.insert(basename)
                }
            }
        }
        return basenames
    }
}
