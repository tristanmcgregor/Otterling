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

    /// Always allowed, from any account. Locks the bundle immediately (best-effort) and adds it
    /// to the enforcement loop's relaunch-if-not-running list.
    func addProtectedApp(_ appJSON: Data, reply: @escaping (Data) -> Void)
    /// Requires the calling account to be in the `admin` group (i.e. the Guardian account).
    /// Unlocks the bundle (clears the immutable flag) before removing it from the list.
    func removeProtectedApp(executableName: String, reply: @escaping (Data) -> Void)

    /// Always allowed, from any account. Points every network service's DNS at the configured
    /// cloud content filter (or Cloudflare Family as fallback) and blocks alternate/DoH resolvers
    /// so it can't be sidestepped.
    func enableDNSEnforcement(reply: @escaping (Data) -> Void)
    /// Requires the calling account to be in the `admin` group (i.e. the Guardian account).
    func disableDNSEnforcement(reply: @escaping (Data) -> Void)

    /// Always allowed, from any account -- picking where the cloud filter points doesn't reduce
    /// protection (Cloudflare Family stays the fallback either way).
    func setCloudFilterHost(_ host: String, reply: @escaping (Data) -> Void)
    /// Turning ON is always allowed; turning OFF (falling back to Cloudflare Family only) requires
    /// the calling account to be in the `admin` group, same asymmetry as disabling DNS enforcement
    /// outright.
    func setCloudFilterEnabled(_ enabled: Bool, reply: @escaping (Data) -> Void)

    /// Always allowed, from any account -- checking/installing an update isn't a way to weaken
    /// protection (the opposite, if anything), so it doesn't need the admin-group gate. See
    /// `UpdateManager`. Reply is an encoded `UpdateCheckStatus`.
    func checkForUpdate(reply: @escaping (Data) -> Void)
    /// Re-checks (never trusts a manifest the caller might supply) then downloads/verifies/installs
    /// if newer, and -- only on success -- restarts both LaunchDaemons a couple of seconds after
    /// replying (enough time for this reply to actually reach the caller first). Reply is an
    /// encoded `UpdateInstallResult`.
    func installAvailableUpdate(reply: @escaping (Data) -> Void)
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
