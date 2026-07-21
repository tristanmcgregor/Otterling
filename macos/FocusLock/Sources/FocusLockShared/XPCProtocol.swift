import Foundation

/// XPC surface exposed by FocusLockHelperd. Complex payloads cross the wire as JSON `Data`
/// (NSXPCConnection can't carry arbitrary Codable structs directly).
///
/// The daemon enforces the actual asymmetry here, not the GUI: it checks the *calling process's*
/// real user ID and only honors `removeBlockedApp`/`removeBlockedDomain` if that user is in the
/// `admin` group. Under the Guardian-account model your day-to-day account is Standard, so those
/// calls are structurally rejected no matter what the GUI sends -- the GUI binary itself has no
/// way to make the daemon accept them. Blocking itself is unconditional and permanent for
/// whatever is in the list, with no session/timer to wait out.
@objc public protocol FocusLockXPCProtocol {
    func getStatus(reply: @escaping (Data?) -> Void)

    /// Always allowed, from any account.
    func addBlockedApp(_ appJSON: Data, reply: @escaping (Data) -> Void)
    /// Always allowed, from any account.
    func addBlockedDomain(_ domain: String, reply: @escaping (Data) -> Void)

    /// Requires the calling account to be in the `admin` group (i.e. the Guardian account).
    func removeBlockedApp(executableName: String, reply: @escaping (Data) -> Void)
    /// Requires the calling account to be in the `admin` group (i.e. the Guardian account).
    func removeBlockedDomain(_ domain: String, reply: @escaping (Data) -> Void)
}

public enum FocusLockCodec {
    public static func encode<T: Encodable>(_ value: T) -> Data {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        return (try? encoder.encode(value)) ?? Data()
    }

    public static func decode<T: Decodable>(_ type: T.Type, from data: Data) -> T? {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return try? decoder.decode(type, from: data)
    }
}
