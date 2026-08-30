import Foundation
import FocusLockShared

/// Implements the daemon side of FocusLockXPCProtocol, and is where the gate lives.
///
/// Every protection-reducing call (removing a block, disabling DNS enforcement, clearing a
/// protection) requires the caller to be in the admin (Guardian) group -- this is unconditional and
/// never bypassed. When a Guardian passcode is also set, it is required *in addition* to admin-group
/// membership. The two layers cover different deployments: with the classic Guardian-account split
/// (daily account Standard, separate admin account) the admin-group check alone already gates
/// removals; on a machine where the daily user is the only admin, that check is satisfied
/// automatically, so the partner-held passcode is what actually gates a removal. Either way a
/// Standard account can never remove or disable protections, while adding blocks / enabling
/// filtering stays open to any caller.
///
/// Clearing the gate applies the change immediately via `ImmediateActionApplier`.
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

        // §6b invariant, never bypassed: a protection-reducing call ALWAYS requires the caller to
        // be in the admin (Guardian) group. On a machine with the classic Guardian-account split
        // this is the gate; on a single-admin machine the daily user satisfies it automatically, so
        // the passcode below becomes the meaningful barrier -- but the admin check is still enforced
        // either way, so a Standard account can never remove/disable protections.
        guard isCallerAdmin() else {
            return "Only the Guardian admin account can \(action)."
        }

        // No passcode configured: admin-group membership is the whole gate (prior behaviour).
        guard let record = state.guardianPasscode else {
            return nil
        }

        // Passcode configured: admin-group AND a correct passcode are both required (defence in
        // depth).
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

    /// Audit trail for an authorized, immediately-applied protection-reducing change -- see
    /// `TamperReporter`.
    private func reportRemoval(_ description: String) {
        TamperReporter.report(type: "protection_removed", details: "\(description) (source: local_admin)")
    }

    func getStatus(reply: @escaping (Data?) -> Void) {
        var state = stateStore.snapshot()
        // Overlaid, not persisted -- see the field's doc comment on FocusLockState.
        state.lockProfileInstalled = LockProfileGuard.lastKnownState
        state.vpnActive = VPNGuard.lastKnownState
        state.daemonVersionCode = FocusLockConstants.appVersionCode
        // Strips the passcode digest before it crosses the wire -- getStatus is ungated, so
        // anything left in here is readable by every account on the machine.
        reply(FocusLockCodec.encode(state.redactedForStatus()))
    }

    func addBlockedApp(_ appJSON: Data, reply: @escaping (Data) -> Void) {
        guard let app = FocusLockCodec.decode(BlockedApp.self, from: appJSON) else {
            reply(FocusLockCodec.encode(FocusLockResult.denied("Invalid app payload")))
            return
        }
        ImmediateActionApplier.addBlockedApp(app, stateStore: stateStore)
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
        ImmediateActionApplier.removeBlockedApp(executableName, stateStore: stateStore)
        reportRemoval("Unblock app \(executableName)")
        onStateChanged()
        reply(FocusLockCodec.encode(FocusLockResult.ok))
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
        ImmediateActionApplier.removeBlockedDomain(normalized, stateStore: stateStore)
        reportRemoval("Unblock site \(normalized)")
        onStateChanged()
        reply(FocusLockCodec.encode(FocusLockResult.ok))
    }

    func addProtectedApp(_ appJSON: Data, reply: @escaping (Data) -> Void) {
        guard let app = FocusLockCodec.decode(ProtectedApp.self, from: appJSON) else {
            reply(FocusLockCodec.encode(FocusLockResult.denied("Invalid app payload")))
            return
        }
        guard ImmediateActionApplier.addProtectedApp(app, stateStore: stateStore) else {
            reply(FocusLockCodec.encode(FocusLockResult.denied("No app bundle found at \(app.bundlePath)")))
            return
        }
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
        ImmediateActionApplier.removeProtectedApp(executableName, stateStore: stateStore)
        reportRemoval("Stop protecting app \(executableName)")
        onStateChanged()
        reply(FocusLockCodec.encode(FocusLockResult.ok))
    }

    func enableDNSEnforcement(reply: @escaping (Data) -> Void) {
        ImmediateActionApplier.enableDNSEnforcement(stateStore: stateStore)
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
        ImmediateActionApplier.disableDNSEnforcement(stateStore: stateStore)
        reportRemoval("Turn off DNS enforcement")
        onStateChanged()
        reply(FocusLockCodec.encode(FocusLockResult.ok))
    }

    func enableProxyEnforcement(forceViaFirewall: Bool, reply: @escaping (Data) -> Void) {
        stateStore.mutate { state in
            state.proxyEnforcementEnabled = true
            state.forceProxyViaFirewall = forceViaFirewall
        }
        // Apply immediately rather than waiting for the enforcement loop's proxy cadence. The result
        // is advisory here (ProxyEnforcer is fail-open); the loop re-asserts it every tick regardless.
        let state = stateStore.snapshot()
        let active = ProxyEnforcer.apply(host: state.proxyHost, port: state.proxyPort, enabled: true)
        onStateChanged()
        if active {
            reply(FocusLockCodec.encode(FocusLockResult(
                success: true,
                message: forceViaFirewall
                    ? "Proxy enforcement on, with firewall force-through. All web traffic now goes through the filter."
                    : "Proxy enforcement on. Browser traffic now goes through the filter."
            )))
        } else {
            // Not an error -- the toggle is saved and the loop will pick it up once the proxy is
            // reachable/provisioned -- but tell the caller why nothing changed yet.
            reply(FocusLockCodec.encode(FocusLockResult(
                success: true,
                message: "Proxy enforcement is enabled but not active yet: the mitmproxy wasn't reachable, or no proxy password is provisioned. Run Scripts/setup_mac_proxy.command, then it activates automatically. (Web access is unaffected until then.)"
            )))
        }
    }

    func disableProxyEnforcement(passcode: String, reply: @escaping (Data) -> Void) {
        if let denial = authorize(passcode: passcode, action: "disable proxy enforcement") {
            reply(FocusLockCodec.encode(FocusLockResult.denied(denial)))
            return
        }
        guard stateStore.snapshot().proxyEnforcementEnabled else {
            reply(FocusLockCodec.encode(FocusLockResult.denied("Proxy enforcement is already off.")))
            return
        }
        ImmediateActionApplier.disableProxyEnforcement(stateStore: stateStore)
        reportRemoval("Turn off proxy enforcement")
        onStateChanged()
        reply(FocusLockCodec.encode(FocusLockResult.ok))
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
        ImmediateActionApplier.setCloudFilterHost(normalized, stateStore: stateStore)
        reportRemoval("Repoint cloud filter to \(normalized)")
        onStateChanged()
        reply(FocusLockCodec.encode(FocusLockResult.ok))
    }

    func setCloudFilterEnabled(_ enabled: Bool, passcode: String, reply: @escaping (Data) -> Void) {
        // Turning it ON is protection-increasing: immediate, no gate.
        if enabled {
            ImmediateActionApplier.enableCloudFilter(stateStore: stateStore)
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
        ImmediateActionApplier.disableCloudFilter(stateStore: stateStore)
        reportRemoval("Turn off the cloud filter")
        onStateChanged()
        reply(FocusLockCodec.encode(FocusLockResult.ok))
    }

    func setGuardianPasscode(newPasscode: String, currentPasscode: String, reply: @escaping (Data) -> Void) {
        let existing = stateStore.snapshot().guardianPasscode

        // Requesting removal of the passcode: protection-reducing, so it needs the current
        // passcode, same as any other removal.
        guard !newPasscode.isEmpty else {
            guard existing != nil else {
                reply(FocusLockCodec.encode(FocusLockResult.denied("No Guardian passcode is set.")))
                return
            }
            if let denial = authorize(passcode: currentPasscode, action: "remove the Guardian passcode") {
                reply(FocusLockCodec.encode(FocusLockResult.denied(denial)))
                return
            }
            ImmediateActionApplier.clearPasscode(stateStore: stateStore)
            reportRemoval("Remove the Guardian passcode")
            onStateChanged()
            reply(FocusLockCodec.encode(FocusLockResult.ok))
            return
        }

        guard newPasscode.count >= 6 else {
            reply(FocusLockCodec.encode(FocusLockResult.denied("Passcode must be at least 6 characters.")))
            return
        }

        // Rotating an existing passcode requires the current one (see `authorize`).
        //
        // Setting the FIRST one requires admin group membership but no passcode (there is none to
        // present yet). It used to require nothing at all, on the reasoning that adding a gate
        // where none existed is protection-increasing. That reasoning has a hole: once a passcode
        // is set it *replaces* the admin-group check as the authorization boundary for every
        // removal (see this protocol's doc comment), so whoever sets it first holds the only
        // credential that can weaken anything. An unprivileged local process claiming it before
        // the accountability partner ran `otterlingctl set-passcode` would capture the boundary and
        // lock the partner out -- inverting the whole design. Requiring admin for the first set
        // costs a Guardian nothing (they are admin under both deployment models) and closes it.
        if existing != nil {
            if let denial = authorize(passcode: currentPasscode, action: "change the Guardian passcode") {
                reply(FocusLockCodec.encode(FocusLockResult.denied(denial)))
                return
            }
        } else if !isCallerAdmin() {
            TamperReporter.report(
                type: "passcode_set_refused",
                details: "A non-admin caller tried to set the first Guardian passcode."
            )
            reply(FocusLockCodec.encode(FocusLockResult.denied(
                "Only the Guardian admin account can set the first Guardian passcode."
            )))
            return
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
                ? "Guardian passcode set. Removals now require it."
                : "Guardian passcode changed."
        )))
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

    func installAvailableUpdate(reply: @escaping (Data) -> Void) {
        // Unlike the other state-changing calls (remove/disable), installing an update doesn't
        // reduce protection -- UpdateManager's own trust chain (SHA-256 + pinned codesign Team ID +
        // AI-review attestation) is what's actually gating what code can run here, independent of
        // who's asking. So this deliberately skips `authorize()`: no admin-group requirement, no
        // Guardian passcode -- any account whose process can reach this XPC service at all (see
        // XPCPeerValidator, the actual security boundary here) can install an already-verified
        // update.
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

    func protectUser(username: String, reply: @escaping (Data) -> Void) {
        switch UserScannerInstaller.install(forUsername: username) {
        case .success(let message):
            reply(FocusLockCodec.encode(FocusLockResult(success: true, message: message)))
        case .failure(let error):
            reply(FocusLockCodec.encode(FocusLockResult.denied(error.description)))
        }
    }

    func requestElevatedCommand(_ requestJSON: Data, reply: @escaping (Data) -> Void) {
        guard let request = FocusLockCodec.decode(ElevatedCommandRequest.self, from: requestJSON) else {
            reply(FocusLockCodec.encode(ElevatedCommandResult(
                approved: false, source: "error", explanation: "Malformed request"
            )))
            return
        }
        // SudoBroker.handle blocks on the AI-review network round-trip (tier 3) -- off the XPC
        // connection's own thread so a slow/unreachable reviewer doesn't stall the whole daemon.
        DispatchQueue.global().async {
            let result = SudoBroker.handle(command: request.command, reason: request.reason)
            reply(FocusLockCodec.encode(result))
        }
    }

    /// Hard ceilings on the agent loop in `requestAssistantAction` below -- enforced here,
    /// independent of anything the translator returns. See `AIAssistantClient.swift`'s doc comment
    /// for why: nothing else stops a propose-execute-observe cycle from looping forever.
    private static let maxAssistantRounds = 6
    private static let maxAssistantSteps = 20
    /// Keeps each round's follow-up prompt bounded -- this is user-controlled command
    /// output being folded back into a network request, not something to let grow unbounded.
    private static let maxAssistantTranscriptChars = 2000

    func requestAssistantAction(_ requestJSON: Data, reply: @escaping (Data) -> Void) {
        guard let request = FocusLockCodec.decode(AssistantRequest.self, from: requestJSON) else {
            reply(FocusLockCodec.encode(AssistantActionResult(translationExplanation: "Malformed request", steps: [], stopReason: "error")))
            return
        }
        DispatchQueue.global().async {
            reply(FocusLockCodec.encode(Self.runAssistantAgentLoop(originalRequest: request.request)))
        }
    }

    /// Translate -> run each command through `SudoBroker` (exactly like a manually-typed command)
    /// -> fold the real result back into the next `translate()` call -> repeat, until the
    /// translator says nothing more is needed or a cap above is hit. See `AIAssistantClient.swift`'s
    /// doc comment for the full design note this implements.
    private static func runAssistantAgentLoop(originalRequest: String) -> AssistantActionResult {
        var steps: [AssistantStep] = []
        var transcript = ""
        var firstExplanation = ""
        var stopReason = "done"

        roundLoop: for round in 0..<maxAssistantRounds {
            let prompt = round == 0
                ? originalRequest
                : followUpPrompt(originalRequest: originalRequest, transcript: transcript)
            let (commands, explanation) = AIAssistantClient.translate(request: prompt)
            if round == 0 { firstExplanation = explanation }

            guard !commands.isEmpty else {
                // Round 0 producing nothing is a translation failure/ambiguity (see `explanation`).
                // A later round producing nothing is the normal happy path: the translator was
                // shown everything run so far and decided the original request is satisfied.
                stopReason = round == 0 ? "no_commands" : "done"
                break
            }

            for (index, command) in commands.enumerated() {
                guard steps.count < maxAssistantSteps else {
                    stopReason = "max_steps"
                    break roundLoop
                }
                // Each command goes through the SAME broker pipeline a manually-typed command
                // does -- see AIAssistantClient.swift's doc comment for why there's no shortcut
                // here, even inside a multi-round agent loop.
                let result = SudoBroker.handle(command: command, reason: "AI Assistant: \"\(originalRequest)\"")
                steps.append(AssistantStep(
                    command: command,
                    result: result,
                    roundExplanation: index == 0 ? explanation : nil
                ))
                transcript += "$ \(command)\n\(result.approved ? "approved" : "denied") (\(result.source)): \(result.explanation)\n"
                if let stdout = result.stdout, !stdout.isEmpty {
                    transcript += "stdout: \(truncateForTranscript(stdout))\n"
                }
                if let stderr = result.stderr, !stderr.isEmpty {
                    transcript += "stderr: \(truncateForTranscript(stderr))\n"
                }
            }

            if round == maxAssistantRounds - 1 { stopReason = "max_rounds" }
        }

        return AssistantActionResult(translationExplanation: firstExplanation, steps: steps, stopReason: stopReason)
    }

    private static func followUpPrompt(originalRequest: String, transcript: String) -> String {
        """
        Continuing an in-progress admin task. The original request was: "\(originalRequest)"

        Command(s) run so far and their real outcomes:
        \(transcript)
        If that fully accomplishes the original request, or a denial/failure means it cannot go \
        further, respond with no commands. Otherwise return only the next command(s) still needed.
        """
    }

    private static func truncateForTranscript(_ text: String) -> String {
        text.count > maxAssistantTranscriptChars
            ? String(text.prefix(maxAssistantTranscriptChars)) + "…(truncated)"
            : text
    }

    func killSwitch(reply: @escaping (Data) -> Void) {
        FileHandle.standardError.write("[killswitch] activated -- turning off the whole app, not just filtering\n".data(using: .utf8)!)
        TamperReporter.report(type: "kill_switch_activated", details: "Emergency stop invoked -- whole app disabled, daemons unloading.")

        let preKillState = stateStore.snapshot()
        // Snapshot BEFORE flipping anything, so `restoreFromKillSwitch` can put DNS/proxy back
        // exactly as they were (e.g. the proxy might already have been off) rather than defaulting
        // everything back on just because it can. Best-effort: a failed write still lets the kill
        // switch itself proceed (this must never be the thing that blocks an emergency stop), it
        // just means restore later falls back to sensible defaults instead of exact prior state.
        let snapshot = KillSwitchSnapshot(
            dnsEnforcementEnabled: preKillState.dnsEnforcementEnabled,
            proxyEnforcementEnabled: preKillState.proxyEnforcementEnabled,
            forceProxyViaFirewall: preKillState.forceProxyViaFirewall
        )
        if let data = try? JSONEncoder().encode(snapshot) {
            try? data.write(to: URL(fileURLWithPath: FocusLockConstants.killSwitchSnapshotPath))
        }

        // Persist the disable, not just the live network settings -- if this process somehow
        // survives the bootout attempts below (e.g. a stuck SMAppService/BTM registration under a
        // workaround label `launchctl bootout` doesn't know about -- a real, recurring issue on this
        // project), its OWN next enforcement tick must not silently re-assert what this just cleared.
        // Confirmed necessary by hand: an earlier version of this only cleared live settings, and
        // the still-running loop reasserted DNS within one tick. `protectionEnabled = false` is the
        // broader "whole app off" switch `EnforcementLoop.reapplyNow` checks first, on top of the
        // individual DNS/proxy flags -- see that field's doc comment.
        stateStore.mutate { state in
            state.dnsEnforcementEnabled = false
            state.proxyEnforcementEnabled = false
            state.forceProxyViaFirewall = false
            state.protectionEnabled = false
        }
        onStateChanged()

        // Serialized onto EnforcementLoop's own queue so this is guaranteed to run AFTER any tick
        // already in flight or queued right now -- see `runExclusive`'s doc comment for the exact
        // race this closes (a stale tick's own un-synchronized ProxyEnforcer.apply silently
        // reapplying the proxy after this teardown, confirmed live on 2026-08-18).
        EnforcementLoop.shared.runExclusive {
            DNSEnforcer.remove()
            ProxyEnforcer.remove()
            PFBlocker.apply(active: false)
            ProcessRunner.runSilently("/sbin/pfctl", ["-d"])
        }

        // Trigger-word scanner runs as a per-user LaunchAgent, not a system LaunchDaemon, so it
        // isn't covered by the daemon/watchdog bootout below -- unload it separately for whichever
        // user is actually logged in right now. Best-effort: no console session (locked/logged-out)
        // just means nothing to unload.
        if let uid = consoleUserUID() {
            ProcessRunner.runSilently("/bin/launchctl", [
                "bootout", "gui/\(uid)/\(FocusLockConstants.scannerBundleIdentifier)",
            ])
        }
        // Quit the GUI app if it's running -- "turn off the whole app" means the menu bar/window
        // app stops too, not just the background daemons.
        ProcessRunner.runSilently("/usr/bin/killall", ["FocusLock"])

        reply(FocusLockCodec.encode(FocusLockResult(
            success: true,
            message: "Whole app disabled: DNS/proxy/pf cleared, scanner and GUI app stopped. Unloading daemons now. Run `otterlingctl restore` to undo."
        )))

        // Delay so the reply above actually reaches the caller over the Mach port before this
        // process exits -- same reasoning as installAvailableUpdate's restart delay. Tries BOTH the
        // normal SMAppService-managed label and the direct-launchd workaround label this project has
        // needed before (see GUARDIAN_SETUP.md/session notes on SMAppService/BTM getting stuck) --
        // whichever one isn't actually registered just fails harmlessly, so trying both is strictly
        // safer than guessing which scheme is currently active.
        DispatchQueue.global().asyncAfter(deadline: .now() + 1) {
            for label in [FocusLockConstants.watchdogBundleIdentifier, "\(FocusLockConstants.watchdogBundleIdentifier).direct"] {
                ProcessRunner.runSilently("/bin/launchctl", ["bootout", "system/\(label)"])
            }
            for label in [FocusLockConstants.helperBundleIdentifier, "\(FocusLockConstants.helperBundleIdentifier).direct"] {
                ProcessRunner.runSilently("/bin/launchctl", ["bootout", "system/\(label)"])
            }
            // Last resort: if this process is somehow still alive after both bootout attempts
            // (neither label matched what's actually registered), just kill it directly. The
            // persisted state change above already means it won't reassert anything even if this
            // races or fails too.
            kill(getpid(), SIGKILL)
        }
    }

    /// See `FocusLockXPCProtocol.restoreFromKillSwitch`'s doc comment for the full picture. This
    /// only ever runs on a daemon that's already back up (`otterlingctl restore` re-bootstraps it
    /// first, since `killSwitch` unloads it and there's no XPC connection to call this over until
    /// then) -- so unlike `killSwitch`, this doesn't need to touch launchd itself at all.
    func restoreFromKillSwitch(reply: @escaping (Data) -> Void) {
        let snapshotURL = URL(fileURLWithPath: FocusLockConstants.killSwitchSnapshotPath)
        guard let data = try? Data(contentsOf: snapshotURL),
              let snapshot = try? JSONDecoder().decode(KillSwitchSnapshot.self, from: data) else {
            // No snapshot: either the kill switch was never actually triggered, or restore already
            // ran once. Still make sure protection isn't stuck off, but there's nothing to restore
            // DNS/proxy to specifically -- leave those exactly as `state.json` already has them.
            stateStore.mutate { state in state.protectionEnabled = true }
            onStateChanged()
            FileHandle.standardError.write("[killswitch] restore: no snapshot found, protection re-enabled with existing DNS/proxy settings\n".data(using: .utf8)!)
            reply(FocusLockCodec.encode(FocusLockResult(success: true, message: "No kill-switch snapshot found -- protection re-enabled, DNS/proxy settings unchanged.")))
            return
        }

        stateStore.mutate { state in
            state.dnsEnforcementEnabled = snapshot.dnsEnforcementEnabled
            state.proxyEnforcementEnabled = snapshot.proxyEnforcementEnabled
            state.forceProxyViaFirewall = snapshot.forceProxyViaFirewall
            state.protectionEnabled = true
        }
        onStateChanged()
        try? FileManager.default.removeItem(at: snapshotURL)

        TamperReporter.report(type: "kill_switch_restored", details: "Protection re-enabled after emergency stop.")
        FileHandle.standardError.write("[killswitch] restored: protection re-enabled from snapshot\n".data(using: .utf8)!)
        reply(FocusLockCodec.encode(FocusLockResult(success: true, message: "Protection re-enabled. DNS/proxy restored to their pre-kill-switch settings.")))
    }

    /// Root-safe console-user lookup (no session to inherit a `$USER`/`whoami` from, since the
    /// daemon runs headless) -- same `stat -f %u /dev/console` approach `LockProfileGuard` uses,
    /// just returning the uid directly since that's all `launchctl bootout gui/<uid>/...` needs.
    private func consoleUserUID() -> uid_t? {
        let output = ProcessRunner.runCapturingStdout("/usr/bin/stat", ["-f", "%u", "/dev/console"])
        guard let uid = UInt32(output.trimmingCharacters(in: .whitespacesAndNewlines)), uid != 0 else { return nil }
        return uid
    }
}
