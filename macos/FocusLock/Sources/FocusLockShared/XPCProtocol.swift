import Foundation

/// XPC surface exposed by FocusLockHelperd. Complex payloads cross the wire as JSON `Data`
/// (NSXPCConnection can't carry arbitrary Codable structs directly).
///
/// The daemon enforces the actual asymmetry here, not the GUI. Two gates, applied in order to
/// every protection-reducing call:
///
/// 1. **The passcode.** Once `setGuardianPasscode` has been called, a correct passcode is the only
///    way through -- `admin` group membership no longer counts for anything. This is what makes the
///    design work on a machine whose daily user *is* the admin: the boundary stops being an
///    identity the user already has and becomes a secret they don't. Before any passcode is set the
///    daemon falls back to the historical `admin`-group check, so an install that never runs
///    `set-passcode` behaves exactly as it did under the Guardian-account model.
/// 2. **The cooldown.** A correct passcode doesn't apply the change; it *schedules* it, `cooldownHours`
///    out (see `PendingAction`). `EnforcementLoop` applies it when due, and `TamperReporter` files the
///    request the moment it's made. Anyone can cancel a pending action without the passcode --
///    cancelling restores protection, so it's on the ungated side of the same asymmetry.
///
/// Blocking itself stays unconditional and permanent for whatever is in the list, with no session
/// or timer to wait out. Every `passcode` parameter below is ignored while no passcode is set.
@objc public protocol FocusLockXPCProtocol {
    func getStatus(reply: @escaping (Data?) -> Void)

    /// Always allowed, from any account.
    func addBlockedApp(_ appJSON: Data, reply: @escaping (Data) -> Void)
    /// Always allowed, from any account.
    func addBlockedDomain(_ domain: String, reply: @escaping (Data) -> Void)

    /// Passcode-gated, then queued for the cooldown.
    func removeBlockedApp(executableName: String, passcode: String, reply: @escaping (Data) -> Void)
    /// Passcode-gated, then queued for the cooldown.
    func removeBlockedDomain(_ domain: String, passcode: String, reply: @escaping (Data) -> Void)

    /// Always allowed, from any account. Locks the bundle immediately (best-effort) and adds it
    /// to the enforcement loop's relaunch-if-not-running list.
    func addProtectedApp(_ appJSON: Data, reply: @escaping (Data) -> Void)
    /// Passcode-gated, then queued for the cooldown. Unlocks the bundle (clears the immutable flag)
    /// only when the action actually matures, not when it's requested.
    func removeProtectedApp(executableName: String, passcode: String, reply: @escaping (Data) -> Void)

    /// Always allowed, from any account. Points every network service's DNS at the configured
    /// cloud content filter (or Cloudflare Family as fallback) and blocks alternate/DoH resolvers
    /// so it can't be sidestepped.
    func enableDNSEnforcement(reply: @escaping (Data) -> Void)
    /// Passcode-gated, then queued for the cooldown.
    func disableDNSEnforcement(passcode: String, reply: @escaping (Data) -> Void)

    /// Routes the Mac's web traffic through the filter-server's mitmproxy (content filtering +
    /// trigger-word reporting on blocked pages), like the phone. `forceViaFirewall` additionally
    /// drops direct :80/:443 so non-proxy-aware apps can't sidestep it. Always allowed / immediate
    /// (protection-increasing). Fail-open: the daemon only actually sets the proxy when it's
    /// reachable and the proxy password is provisioned (Scripts/setup_mac_proxy.command); otherwise
    /// it stays off so web access never breaks.
    func enableProxyEnforcement(forceViaFirewall: Bool, reply: @escaping (Data) -> Void)
    /// Passcode-gated, then queued for the cooldown. Clears both proxy toggles and removes the
    /// system proxy when it matures.
    func disableProxyEnforcement(passcode: String, reply: @escaping (Data) -> Void)

    /// Passcode-gated, then queued for the cooldown -- repointing the host at an unfiltered
    /// resolver is equivalent to turning the filter off, so it's gated like a removal rather than
    /// left open the way *adding* a block is.
    func setCloudFilterHost(_ host: String, passcode: String, reply: @escaping (Data) -> Void)
    /// Turning ON is immediate and ungated; turning OFF is passcode-gated and queued.
    func setCloudFilterEnabled(_ enabled: Bool, passcode: String, reply: @escaping (Data) -> Void)

    /// Sets or changes the Guardian passcode. Setting the *first* one is always allowed from any
    /// account -- it only ever adds a gate where there was none, so it's protection-increasing --
    /// and takes effect immediately. Changing an existing one requires the current passcode and is
    /// likewise immediate (a rotation doesn't weaken anything). Pass an empty `newPasscode` to
    /// request removal of the passcode entirely, which is protection-*reducing* and therefore
    /// queued for the cooldown like any other removal.
    func setGuardianPasscode(newPasscode: String, currentPasscode: String, reply: @escaping (Data) -> Void)

    /// Raising the cooldown is immediate and ungated. Lowering it is passcode-gated and queued --
    /// at the *current* (higher) cooldown, so the wait can't be shortened by first shortening the
    /// wait. Clamped to `FocusLockConstants.maximumCooldownHours`.
    func setCooldownHours(_ hours: Double, passcode: String, reply: @escaping (Data) -> Void)

    /// Always allowed, from any account, no passcode: cancelling a queued action restores
    /// protection, which is never the direction that needs gating.
    func cancelPendingAction(id: String, reply: @escaping (Data) -> Void)

    /// Always allowed, from any account -- checking/installing an update isn't a way to weaken
    /// protection (the opposite, if anything), so it doesn't need the gate. See
    /// `UpdateManager`. Reply is an encoded `UpdateCheckStatus`.
    func checkForUpdate(reply: @escaping (Data) -> Void)
    /// Re-checks (never trusts a manifest the caller might supply) then downloads/verifies/installs
    /// if newer, and -- only on success -- restarts both LaunchDaemons a couple of seconds after
    /// replying (enough time for this reply to actually reach the caller first). Reply is an
    /// encoded `UpdateInstallResult`.
    ///
    /// Passcode-gated but *not* cooldown-queued: an update only ever moves the install forward to a
    /// build the pinned Team ID vouches for, and delaying security fixes by a day is the wrong
    /// trade. The gate is here because installing swaps the running bundle and restarts both
    /// daemons, which is too close to the tamper surface to leave open.
    func installAvailableUpdate(passcode: String, reply: @escaping (Data) -> Void)

    /// The privilege-elevation broker for once the Guardian's own macOS account is Standard (no
    /// direct sudo) -- see `SudoBroker.swift`'s doc comment for the full decision pipeline. No
    /// passcode parameter: unlike everything else in this protocol, a passcode the Guardian knows
    /// is exactly what this must NOT be gated by, since the Guardian is who this exists to gate.
    /// Every request is decided (denylist, then allowlist, then an AI review round-trip that fails
    /// closed on any error/ambiguity) and reported via TamperReporter regardless of outcome --
    /// requestJSON/reply carry an encoded `ElevatedCommandRequest`/`ElevatedCommandResult`.
    func requestElevatedCommand(_ requestJSON: Data, reply: @escaping (Data) -> Void)

    /// Pushes the trigger-word scanner into a DIFFERENT local user's GUI session -- see
    /// `UserScannerInstaller.swift`'s doc comment for the "admin protecting a separate Standard
    /// account" deployment shape this is for, and what it does NOT cover (the DNS-floor profile,
    /// which needs its own per-user provisioning design). Always allowed, from any account -- this
    /// only ever adds monitoring coverage, never removes it.
    func protectUser(username: String, reply: @escaping (Data) -> Void)

    /// The GUI "AI Assistant" chat box -- see `AIAssistantClient.swift`'s doc comment. Translates
    /// `requestJSON` (an encoded `AssistantRequest`) into candidate command(s), then runs EACH one
    /// through the exact same `SudoBroker` pipeline `requestElevatedCommand` uses -- never a
    /// separate, ungated execution path. Reply is an encoded `AssistantActionResult`.
    func requestAssistantAction(_ requestJSON: Data, reply: @escaping (Data) -> Void)
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
