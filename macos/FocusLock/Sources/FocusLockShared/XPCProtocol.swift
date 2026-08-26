import Foundation

/// XPC surface exposed by FocusLockHelperd. Complex payloads cross the wire as JSON `Data`
/// (NSXPCConnection can't carry arbitrary Codable structs directly).
///
/// The daemon enforces the actual asymmetry here, not the GUI. Every protection-reducing call is
/// gated by **the passcode**: once `setGuardianPasscode` has been called, a correct passcode is
/// the only way through -- `admin` group membership no longer counts for anything. This is what
/// makes the design work on a machine whose daily user *is* the admin: the boundary stops being an
/// identity the user already has and becomes a secret they don't. Before any passcode is set the
/// daemon falls back to the historical `admin`-group check, so an install that never runs
/// `set-passcode` behaves exactly as it did under the Guardian-account model. A correct passcode
/// applies the change immediately, and `TamperReporter` files the request the moment it's made.
///
/// Blocking itself stays unconditional and permanent for whatever is in the list, with no session
/// or timer to wait out. Every `passcode` parameter below is ignored while no passcode is set.
@objc public protocol FocusLockXPCProtocol {
    func getStatus(reply: @escaping (Data?) -> Void)

    /// Always allowed, from any account.
    func addBlockedApp(_ appJSON: Data, reply: @escaping (Data) -> Void)
    /// Always allowed, from any account.
    func addBlockedDomain(_ domain: String, reply: @escaping (Data) -> Void)

    /// Passcode-gated. Applies immediately once authorized.
    func removeBlockedApp(executableName: String, passcode: String, reply: @escaping (Data) -> Void)
    /// Passcode-gated. Applies immediately once authorized.
    func removeBlockedDomain(_ domain: String, passcode: String, reply: @escaping (Data) -> Void)

    /// Always allowed, from any account. Locks the bundle immediately (best-effort) and adds it
    /// to the enforcement loop's relaunch-if-not-running list.
    func addProtectedApp(_ appJSON: Data, reply: @escaping (Data) -> Void)
    /// Passcode-gated. Unlocks the bundle (clears the immutable flag) immediately once authorized.
    func removeProtectedApp(executableName: String, passcode: String, reply: @escaping (Data) -> Void)

    /// Always allowed, from any account. Points every network service's DNS at the configured
    /// cloud content filter (or Cloudflare Family as fallback) and blocks alternate/DoH resolvers
    /// so it can't be sidestepped.
    func enableDNSEnforcement(reply: @escaping (Data) -> Void)
    /// Passcode-gated. Applies immediately once authorized.
    func disableDNSEnforcement(passcode: String, reply: @escaping (Data) -> Void)

    /// Routes the Mac's web traffic through the filter-server's mitmproxy (content filtering +
    /// trigger-word reporting on blocked pages), like the phone. `forceViaFirewall` additionally
    /// drops direct :80/:443 so non-proxy-aware apps can't sidestep it. Always allowed / immediate
    /// (protection-increasing). Fail-open: the daemon only actually sets the proxy when it's
    /// reachable and the proxy password is provisioned (Scripts/setup_mac_proxy.command); otherwise
    /// it stays off so web access never breaks.
    func enableProxyEnforcement(forceViaFirewall: Bool, reply: @escaping (Data) -> Void)
    /// Passcode-gated. Clears both proxy toggles and removes the system proxy immediately once
    /// authorized.
    func disableProxyEnforcement(passcode: String, reply: @escaping (Data) -> Void)

    /// Passcode-gated -- repointing the host at an unfiltered resolver is equivalent to turning
    /// the filter off, so it's gated like a removal rather than left open the way *adding* a block
    /// is.
    func setCloudFilterHost(_ host: String, passcode: String, reply: @escaping (Data) -> Void)
    /// Turning ON is immediate and ungated; turning OFF is passcode-gated.
    func setCloudFilterEnabled(_ enabled: Bool, passcode: String, reply: @escaping (Data) -> Void)

    /// Sets or changes the Guardian passcode. Setting the *first* one is always allowed from any
    /// account -- it only ever adds a gate where there was none, so it's protection-increasing --
    /// and takes effect immediately. Changing an existing one requires the current passcode and is
    /// likewise immediate (a rotation doesn't weaken anything). Pass an empty `newPasscode` to
    /// request removal of the passcode entirely, which is protection-*reducing* and therefore
    /// requires the current passcode like any other removal.
    func setGuardianPasscode(newPasscode: String, currentPasscode: String, reply: @escaping (Data) -> Void)

    /// Always allowed, from any account -- checking/installing an update isn't a way to weaken
    /// protection (the opposite, if anything), so it doesn't need the gate. See
    /// `UpdateManager`. Reply is an encoded `UpdateCheckStatus`.
    func checkForUpdate(reply: @escaping (Data) -> Void)
    /// Re-checks (never trusts a manifest the caller might supply) then downloads/verifies/installs
    /// if newer, and -- only on success -- restarts both LaunchDaemons a couple of seconds after
    /// replying (enough time for this reply to actually reach the caller first). Reply is an
    /// encoded `UpdateInstallResult`.
    ///
    /// Passcode-gated, applied immediately: an update only ever moves the install forward to a
    /// build the pinned Team ID vouches for. The gate is here because installing swaps the running
    /// bundle and restarts both daemons, which is too close to the tamper surface to leave open.
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
    /// `requestJSON` (an encoded `AssistantRequest`) into candidate command(s), runs EACH one
    /// through the exact same `SudoBroker` pipeline `requestElevatedCommand` uses -- never a
    /// separate, ungated execution path -- then feeds the real result back for another round of
    /// translation, multi-turn, until the assistant says nothing more is needed or
    /// `XPCService`'s round/step caps are hit. Reply is an encoded `AssistantActionResult` covering
    /// every round.
    func requestAssistantAction(_ requestJSON: Data, reply: @escaping (Data) -> Void)

    /// Emergency stop for the WHOLE app, not just content filtering: clears DNS/proxy/pf, persists
    /// `protectionEnabled = false` (see `FocusLockState`) so nothing silently resumes if a daemon
    /// somehow comes back without going through `restoreFromKillSwitch`, unloads the trigger-word
    /// scanner LaunchAgent, quits the GUI app if it's running, then unloads both LaunchDaemons and
    /// exits. Deliberately NOT routed through `SudoBroker` -- that broker's own denylist blocks
    /// `launchctl bootout`/`pfctl -d` (exactly what this needs to run) precisely because those
    /// commands can disable protection, which is normally the right call. But once this account is
    /// Standard, the broker becomes the ONLY path to privileged commands at all -- if this recovery
    /// path were denylisted like everything else, a genuine enforcement-layer bug (this project has
    /// had real ones: a DNS-resolution hang and a firewall-reload storm, both causing severe
    /// outages) would have NO way to be recovered from except a full reinstall. A hardcoded,
    /// parameter-less, single-purpose XPC method sidesteps that: it can't be repurposed into a
    /// general bypass (it takes no arguments and does exactly one fixed thing), but it's always
    /// reachable through the already-root daemon regardless of the caller's own account privilege.
    /// Always allowed, no passcode -- see the project's fail-open philosophy: the filter must never
    /// be able to take the machine fully offline with no way back. Reported via TamperReporter the
    /// moment it fires, same as everything else that reduces protection. See `restoreFromKillSwitch`
    /// for the only normal way back.
    func killSwitch(reply: @escaping (Data) -> Void)

    /// Reverses `killSwitch`: restores DNS/proxy settings to exactly what they were right before it
    /// fired (from the snapshot `killSwitch` wrote -- see `FocusLockConstants.killSwitchSnapshotPath`),
    /// sets `protectionEnabled` back to true, and deletes the snapshot. Only meaningful to call
    /// AFTER the daemon has been manually re-bootstrapped (killSwitch unloads it -- there is no XPC
    /// connection to call this over until that happens; see `otterlingctl restore`, the intended
    /// caller). No passcode, matching `killSwitch` itself -- the recovery path back from an
    /// emergency stop can't be gated behind a passcode that might not be known/reachable at that
    /// moment either. If no snapshot exists (killSwitch was never actually triggered, or restore
    /// already ran), this is a safe no-op that reports success without changing anything.
    func restoreFromKillSwitch(reply: @escaping (Data) -> Void)
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
