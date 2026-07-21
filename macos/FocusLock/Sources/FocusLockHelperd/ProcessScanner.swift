import Darwin
import Foundation

struct RunningProcess {
    let pid: pid_t
    let executableName: String
    let path: String
}

enum ProcessScanner {
    /// Enumerates all running processes visible to root via libproc. Running as root means this
    /// sees every user's processes, not just ours.
    static func listRunningProcesses() -> [RunningProcess] {
        let bufferSize = proc_listpids(UInt32(PROC_ALL_PIDS), 0, nil, 0)
        guard bufferSize > 0 else { return [] }

        let capacity = Int(bufferSize) / MemoryLayout<pid_t>.size
        var pids = [pid_t](repeating: 0, count: capacity)
        let actualSize = proc_listpids(UInt32(PROC_ALL_PIDS), 0, &pids, bufferSize)
        guard actualSize > 0 else { return [] }
        let actualCount = Int(actualSize) / MemoryLayout<pid_t>.size

        var results: [RunningProcess] = []
        results.reserveCapacity(actualCount)

        // PROC_PIDPATHINFO_MAXSIZE (4 * MAXPATHLEN) isn't importable into Swift as a macro;
        // inlined here since it's a stable, documented constant from <sys/proc_info.h>.
        let maxPathSize = 4 * 1024
        for pid in pids.prefix(actualCount) where pid > 0 {
            var pathBuffer = [CChar](repeating: 0, count: maxPathSize)
            let len = proc_pidpath(pid, &pathBuffer, UInt32(pathBuffer.count))
            guard len > 0 else { continue }
            let path = String(cString: pathBuffer)
            let name = (path as NSString).lastPathComponent
            results.append(RunningProcess(pid: pid, executableName: name, path: path))
        }
        return results
    }
}
