import Foundation
import FocusLockShared

/// Implements the daemon side of FocusLockXPCProtocol, and is where both gates actually live.
///
/// Historically the only gate was `AdminGroupCheck` against the connecting process's real uid,
/// which assumed the Guardian-account split (your daily account Standard, a separate account
/// admin). That assumption is what `authorize` replaces: once a Guardian passcode is set, the
/// boundary is the passcode and group membership is irrelevant, so the model holds up on a machine
/// where the daily user is also the only admin. With no passcode set it falls back to the old
/// admin-group check, so existing installs are unaffected until they opt in.
///
/// Clearing the gate doesn't perform the change -- `schedule` queues it for `cooldownHours` and
/// `EnforcementLoop` applies it later. See `PendingActionApplier`.
final class XPCService: NSObject, FocusLockXPCProtocol {
    private let stateStore: StateStore
    private let onStateChanged: () -> Void

    // Guards against grinding the passcode over XPC. PBKDF2 at 210k iterations already caps an
    // attacker at roughly ten guesses a second, which is not enough on its own for a short numeric
    // passcode -- this turns a feasible overnight sweep into an infeasible one. In-memory only:
    // a daemon restart clears it, but restarting the daemon is itself watchdog-reported.
    private let throttleLock = NSLock()
    private var consecutiveFailures = 0
    private var lockedOutUntil: Date?

    init(stateStore: StateStore, onStateChanged: @escaping () -> Void) {
        self.stateStore = stateStore
        self.onStateChanged = onStateChanged
    }

    private func isCallerAdmin() -> Bool {
        guard let connection = NSXPCConnection.current() else { return false }
        return AdminGroupCheck.isUserAdmin(uid: uid_t(connection.effectiveUserIdentifier))
    }

    /// Returns nil when the caller may proceed, or the denial reason. `action` is a verb phrase
    /// ("remove a blocked app") folded into the message.
    private func authorize(passcode: String, action: String) -> String? {
        let state = stateStore.snapshot()

        guard let record = state.guardianPasscode else {
            // Pre-passcode behaviour, unchanged. Worth stating explicitly in the denial: on a
            // single-admin machine this branch grants the daily user everything, which is exactly
            // the hole `set-passcode` closes.
            guard isCallerAdmin() else {
                return "Only the Guardian admin account can \(action)."
            }
            return nil
        }

        if let until = currentLockout(), until > Date() {
            let seconds = Int(until.timeIntervalSinceNow.rounded(.up))
            return "Too many incorrect passcode attempts. Try again in \(seconds)s."
        }

        guard PasscodeHash.verify(passcode: passcode, against: record) else {
            recordFailedAttempt()
            TamperReporter.report(type: "passcode_rejected", details: "Failed attempt to \(action)")
            return "Incorrect Guardian passcode."
        }

        recordSuccessfulAttempt()
        return nil
    }

    private func currentLockout() -> Date? {
        throttleLock.lock()
        defer { throttleLock.unlock() }
        return lockedOutUntil
    }

    private func recordFailedAttempt() {
        throttleLock.lock()
        defer { throttleLock.unlock() }
        consecutiveFailures += 1
        // First couple of slips cost nothing; sustained guessing backs off to a 5-minute ceiling.
        guard consecutiveFailures > 2 else { return }
        let backoff = min(pow(2.0, Double(consecutiveFailures - 2)), 300)
        lockedOutUntil = Date().addingTimeInterval(backoff)
    }

    private func recordSuccessfulAttempt() {
        throttleLock.lock()
        defer { throttleLock.unlock() }
        consecutiveFailures = 0
        lockedOutUntil = nil
    }

    /// Queues an authorized protection-reducing action for the cooldown, or applies it inline when
    /// the cooldown is zero. Reports the request immediately either way -- the point of the paper
    /// trail is that it exists at *request* time, well before the change lands.
    private func schedule(_ kind: PendingActionKind, target: String = "") -> FocusLockResult {
        let state = stateStore.snapshot()

        if state.pendingActions.contains(where: { $0.kind == kind && $0.target == target }) {
            return .denied("That change is already scheduled. Check `focuslockctl status` for when it takes effect.")
        }

        let now = Date()
        let action = PendingAction(
            kind: kind,
            target: target,
            requestedAt: now,
            effectiveAt: now.addingTimeInterval(state.cooldownHours * 3600)
        )

        TamperReporter.report(type: "pending_action_requested", details: action.describedFully)

        guard state.cooldownHours > 0 else {
            PendingActionApplier.apply(action, stateStore: stateStore)
            onStateChanged()
            return .ok
        }

        stateStore.mutate { $0.pendingActions.append(action) }
        onStateChanged()

        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        return FocusLockResult(
            success: true,
            message: "Scheduled: \(action.describedFully). Takes effect \(formatter.string(from: action.effectiveAt)). "
                + "Anyone can cancel it before then with `focuslockctl cancel \(action.id)`."
        )
    }

    func getStatus(reply: @escaping (Data?) -> Void) {
        var state = stateStore.snapshot()
        // Overlaid, not persisted -- see the field's doc comment on FocusLockState.
        state.lockProfileInstalled = LockProfileGuard.lastKnownState
        state.vpnActive = VPNGuard.lastKnownState
        // Strips the passcode digest before it crosses the wire -- getStatus is ungated, so
        // anything left in here is readable by every account on the machine.
        reply(FocusLockCodec.encode(state.redactedForStatus()))
    }

    func addBlockedApp(_ appJSON: Data, reply: @escaping (Data) -> Void) {
        guard let app = FocusLockCodec.decode(BlockedApp.self, from: appJSON) else {
            reply(FocusLockCodec.encode(FocusLockResult.denied("Invalid app payload")))
            return
        }
        stateStore.mutate { state in
            if !state.blockedApps.contains(where: { $0.executableName == app.executableName }) {
                state.blockedApps.append(app)
            }
        }
        onStateChanged()
        reply(FocusLockCodec.encode(FocusLockResult.ok))
    }

    func addBlockedDomain(_ domain: String, reply: @escaping (Data) -> Void) {
        let normalized = domain.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !normalized.isEmpty else {
            reply(FocusLockCodec.encode(FocusLockResult.denied("Empty domain")))
            return
        }
        // Trimming only strips leading/trailing whitespace -- an embedded newline (e.g.
        // "x\n1.2.3.4 icloud.com") would otherwise become extra, attacker-chosen lines once this
        // value is written into root-owned /etc/hosts. Require plain hostname characters only.
        guard HostnameValidator.isValidHostname(normalized) else {
            reply(FocusLockCodec.encode(FocusLockResult.denied("Invalid domain")))
            return
        }
        stateStore.mutate { state in
            if !state.blockedDomains.contains(normalized) {
                state.blockedDomains.append(normalized)
            }
        }
        onStateChanged()
        reply(FocusLockCodec.encode(FocusLockResult.ok))
    }

    func removeBlockedApp(executableName: String, passcode: String, reply: @escaping (Data) -> Void) {
        if let denial = authorize(passcode: passcode, action: "remove a blocked app") {
            reply(FocusLockCodec.encode(FocusLockResult.denied(denial)))
            return
        }
        guard stateStore.snapshot().blockedApps.contains(where: { $0.executableName == executableName }) else {
            reply(FocusLockCodec.encode(FocusLockResult.denied("No blocked app named \(executableName).")))
            return
        }
        reply(FocusLockCodec.encode(schedule(.removeBlockedApp, target: executableName)))
    }

    func removeBlockedDomain(_ domain: String, passcode: String, reply: @escaping (Data) -> Void) {
        if let denial = authorize(passcode: passcode, action: "remove a blocked domain") {
            reply(FocusLockCodec.encode(FocusLockResult.denied(denial)))
            return
        }
        let normalized = domain.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard stateStore.snapshot().blockedDomains.contains(normalized) else {
            reply(FocusLockCodec.encode(FocusLockResult.denied("\(normalized) is not on the manual block list.")))
            return
        }
        reply(FocusLockCodec.encode(schedule(.removeBlockedDomain, target: normalized)))
    }

    func addProtectedApp(_ appJSON: Data, reply: @escaping (Data) -> Void) {
        guard let app = FocusLockCodec.decode(ProtectedApp.self, from: appJSON) else {
            reply(FocusLockCodec.encode(FocusLockResult.denied("Invalid app payload")))
            return
        }
        guard FileManager.default.fileExists(atPath: app.bundlePath) else {
            reply(FocusLockCodec.encode(FocusLockResult.denied("No app bundle found at \(app.bundlePath)")))
            return
        }
        stateStore.mutate { state in
            if !state.protectedApps.contains(where: { $0.executableName == app.executableName }) {
                state.protectedApps.append(app)
            }
        }
        AppProtector.lock(bundlePath: app.bundlePath)
        onStateChanged()
        reply(FocusLockCodec.encode(FocusLockResult.ok))
    }

    func removeProtectedApp(executableName: String, passcode: String, reply: @escaping (Data) -> Void) {
        if let denial = authorize(passcode: passcode, action: "stop protecting an app") {
            reply(FocusLockCodec.encode(FocusLockResult.denied(denial)))
            return
        }
        guard stateStore.snapshot().protectedApps.contains(where: { $0.executableName == executableName }) else {
            reply(FocusLockCodec.encode(FocusLockResult.denied("No protected app named \(executableName).")))
            return
        }
        // The bundle stays `schg`-locked and kept-alive for the whole cooldown -- unlocking happens
        // in PendingActionApplier when the action matures, not here.
        reply(FocusLockCodec.encode(schedule(.removeProtectedApp, target: executableName)))
    }

    func enableDNSEnforcement(reply: @escaping (Data) -> Void) {
        stateStore.mutate { state in
            state.dnsEnforcementEnabled = true
        }
        // Apply immediately -- don't wait for the enforcement loop's 15s DNS cadence.
        let state = stateStore.snapshot()
        DNSEnforcer.apply(cloudHost: state.cloudFilterHost, cloudEnabled: state.cloudFilterEnabled)
        onStateChanged()
        reply(FocusLockCodec.encode(FocusLockResult.ok))
    }

    func disableDNSEnforcement(passcode: String, reply: @escaping (Data) -> Void) {
        if let denial = authorize(passcode: passcode, action: "disable DNS enforcement") {
            reply(FocusLockCodec.encode(FocusLockResult.denied(denial)))
            return
        }
        guard stateStore.snapshot().dnsEnforcementEnabled else {
            reply(FocusLockCodec.encode(FocusLockResult.denied("DNS enforcement is already off.")))
            return
        }
        reply(FocusLockCodec.encode(schedule(.disableDNSEnforcement)))
    }

    func setCloudFilterHost(_ host: String, passcode: String, reply: @escaping (Data) -> Void) {
        // Repointing the cloud filter host is equivalent to defeating it (an unfiltered or
        // malicious host is one edit away) -- gated the same as disabling it outright, not left
        // open the way *adding* a block is.
        if let denial = authorize(passcode: passcode, action: "change the cloud filter host") {
            reply(FocusLockCodec.encode(FocusLockResult.denied(denial)))
            return
        }
        let normalized = host.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalized.isEmpty else {
            reply(FocusLockCodec.encode(FocusLockResult.denied("Empty host")))
            return
        }
        // Same validation the domain path gets -- this value is resolved and written into pf/DNS
        // config, so it must not be free-form text.
        guard HostnameValidator.isValidHostname(normalized.lowercased()) else {
            reply(FocusLockCodec.encode(FocusLockResult.denied("Invalid host")))
            return
        }
        reply(FocusLockCodec.encode(schedule(.setCloudFilterHost, target: normalized)))
    }

    func setCloudFilterEnabled(_ enabled: Bool, passcode: String, reply: @escaping (Data) -> Void) {
        // Turning it ON is protection-increasing: immediate, no gate, no cooldown.
        if enabled {
            stateStore.mutate { state in
                state.cloudFilterEnabled = true
            }
            reapplyDNSIfEnforcing()
            onStateChanged()
            reply(FocusLockCodec.encode(FocusLockResult.ok))
            return
        }

        if let denial = authorize(passcode: passcode, action: "turn off the cloud filter") {
            reply(FocusLockCodec.encode(FocusLockResult.denied(denial)))
            return
        }
        guard stateStore.snapshot().cloudFilterEnabled else {
            reply(FocusLockCodec.encode(FocusLockResult.denied("The cloud filter is already off.")))
            return
        }
        reply(FocusLockCodec.encode(schedule(.disableCloudFilter)))
    }

    func setGuardianPasscode(newPasscode: String, currentPasscode: String, reply: @escaping (Data) -> Void) {
        let existing = stateStore.snapshot().guardianPasscode

        // Requesting removal of the passcode: protection-reducing, so it needs the current passcode
        // and then waits out the cooldown like any other removal.
        guard !newPasscode.isEmpty else {
            guard existing != nil else {
                reply(FocusLockCodec.encode(FocusLockResult.denied("No Guardian passcode is set.")))
                return
            }
            if let denial = authorize(passcode: currentPasscode, action: "remove the Guardian passcode") {
                reply(FocusLockCodec.encode(FocusLockResult.denied(denial)))
                return
            }
            reply(FocusLockCodec.encode(schedule(.clearPasscode)))
            return
        }

        guard newPasscode.count >= 6 else {
            reply(FocusLockCodec.encode(FocusLockResult.denied("Passcode must be at least 6 characters.")))
            return
        }

        // Setting the first passcode only ever adds a gate, so it's ungated -- but it is a
        // one-way door in practice (removing it costs the passcode plus a full cooldown), which is
        // the point. Rotating an existing one requires the current passcode.
        if existing != nil {
            if let denial = authorize(passcode: currentPasscode, action: "change the Guardian passcode") {
                reply(FocusLockCodec.encode(FocusLockResult.denied(denial)))
                return
            }
        }

        guard let record = PasscodeHash.make(passcode: newPasscode) else {
            reply(FocusLockCodec.encode(FocusLockResult.denied("Could not derive the passcode. Nothing was changed.")))
            return
        }
        stateStore.mutate { state in
            state.guardianPasscode = record
            state.passcodeConfigured = true
        }
        TamperReporter.report(
            type: existing == nil ? "passcode_set" : "passcode_changed",
            details: existing == nil ? "Guardian passcode configured" : "Guardian passcode rotated"
        )
        onStateChanged()
        reply(FocusLockCodec.encode(FocusLockResult(
            success: true,
            message: existing == nil
                ? "Guardian passcode set. Removals now require it, plus a \(Int(stateStore.snapshot().cooldownHours))h cooldown."
                : "Guardian passcode changed."
        )))
    }

    func setCooldownHours(_ hours: Double, passcode: String, reply: @escaping (Data) -> Void) {
        guard hours >= 0 else {
            reply(FocusLockCodec.encode(FocusLockResult.denied("Cooldown can't be negative.")))
            return
        }
        let clamped = min(hours, FocusLockConstants.maximumCooldownHours)
        let current = stateStore.snapshot().cooldownHours

        // Raising it (or leaving it alone) is protection-increasing: immediate, ungated.
        if clamped >= current {
            stateStore.mutate { state in
                state.cooldownHours = clamped
            }
            onStateChanged()
            reply(FocusLockCodec.encode(FocusLockResult.ok))
            return
        }

        if let denial = authorize(passcode: passcode, action: "lower the cooldown") {
            reply(FocusLockCodec.encode(FocusLockResult.denied(denial)))
            return
        }
        // Queued at the *current* cooldown, so shortening the wait is itself subject to the full
        // existing wait -- otherwise "set cooldown to 0" would be a one-step bypass of everything.
        reply(FocusLockCodec.encode(schedule(.lowerCooldownHours, target: String(clamped))))
    }

    func cancelPendingAction(id: String, reply: @escaping (Data) -> Void) {
        let existing = stateStore.snapshot().pendingActions.first { $0.id == id }
        guard let existing else {
            reply(FocusLockCodec.encode(FocusLockResult.denied("No pending action with that ID.")))
            return
        }
        stateStore.mutate { state in
            state.pendingActions.removeAll { $0.id == id }
        }
        TamperReporter.report(type: "pending_action_cancelled", details: existing.describedFully)
        onStateChanged()
        reply(FocusLockCodec.encode(FocusLockResult(success: true, message: "Cancelled: \(existing.describedFully)")))
    }

    /// Re-resolves and re-asserts DNS immediately (rather than waiting for the enforcement loop's
    /// 15s cadence) whenever the cloud filter host/toggle changes while DNS enforcement is
    /// already on -- otherwise a host edit wouldn't visibly take effect for up to 15s.
    private func reapplyDNSIfEnforcing() {
        let state = stateStore.snapshot()
        guard state.dnsEnforcementEnabled else { return }
        DNSEnforcer.apply(cloudHost: state.cloudFilterHost, cloudEnabled: state.cloudFilterEnabled)
    }

    func checkForUpdate(reply: @escaping (Data) -> Void) {
        // UpdateManager blocks (synchronous network I/O) -- dispatch off whatever queue XPC
        // delivered this call on so a slow/unreachable update host can't stall other XPC traffic.
        DispatchQueue.global().async {
            let host = self.stateStore.snapshot().cloudFilterHost
            let status = UpdateManager.checkForUpdate(host: host)
            reply(FocusLockCodec.encode(status))
        }
    }

    func installAvailableUpdate(passcode: String, reply: @escaping (Data) -> Void) {
        // Installing (as opposed to just checking) restarts the daemon/watchdog and swaps the
        // running app bundle -- gated the same as the other state-changing "remove/disable" calls.
        // Not cooldown-queued, though: see the protocol's doc comment for why an update is the one
        // gated action that shouldn't wait.
        if let denial = authorize(passcode: passcode, action: "install an update") {
            reply(FocusLockCodec.encode(UpdateInstallResult.rejected(denial)))
            return
        }
        DispatchQueue.global().async {
            let host = self.stateStore.snapshot().cloudFilterHost
            // Never trusts a manifest the caller might supply -- re-checks against the real host.
            switch UpdateManager.checkForUpdate(host: host) {
            case .upToDate:
                reply(FocusLockCodec.encode(UpdateInstallResult.rejected("Already up to date")))
            case .error(let message):
                reply(FocusLockCodec.encode(UpdateInstallResult.rejected("Update check failed: \(message)")))
            case .updateAvailable(let manifest):
                let result = UpdateManager.downloadVerifyAndInstall(manifest)
                reply(FocusLockCodec.encode(result))
                if case .installedPendingRestart = result {
                    // A couple of seconds' grace so this reply actually reaches the caller over
                    // the Mach port before the process that would send it exits.
                    DispatchQueue.global().asyncAfter(deadline: .now() + 2) {
                        UpdateManager.restartAfterInstall()
                    }
                }
            }
        }
    }
}
