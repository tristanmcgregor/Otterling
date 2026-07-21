import Darwin
import Foundation

/// Checks whether a given uid belongs to the `admin` group. Under the Guardian-account model
/// this is the actual authorization boundary for privileged daemon calls: your daily account is
/// Standard, so `isUserAdmin` returns false for it no matter what the calling code claims.
public enum AdminGroupCheck {
    public static func isUserAdmin(uid: uid_t) -> Bool {
        guard let pw = getpwuid(uid) else { return false }
        let username = String(cString: pw.pointee.pw_name)

        guard let gr = getgrnam("admin") else { return false }
        if pw.pointee.pw_gid == gr.pointee.gr_gid { return true }

        var member = gr.pointee.gr_mem
        while let namePointer = member?.pointee {
            if String(cString: namePointer) == username { return true }
            member = member?.advanced(by: 1)
        }
        return false
    }
}
