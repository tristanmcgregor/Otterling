import Foundation
import FocusLockShared

/// Extends `filter-server/dashboard/SERVER_DRIVEN_CONFIG_PLAN.md`-style dashboard control to the
/// Mac (that doc was originally written for the Android app -- see `DashboardConfigStore.kt` for
/// its equivalent). `fetch` pulls this Mac's `GET /dashboard-api/devices/<device_id>/settings`
/// record (including this device's own `rules`) and caches it; `fetchGlobalHabits` separately
/// pulls the fleet-wide habit library + live completion state from `GET /dashboard-api/habits`.
/// `reconcile` (below) applies the per-device settings against local state: blockedApps,
/// protectedApps, DNS/proxy/cloud-filter enforcement, the cloud filter host, and the removal
/// cooldown are all dashboard-driven. `rules` + the global habits cache are read separately by
/// `RuleBlockEnforcer` (a live, ephemeral, never-persisted enforcement layer -- see that file's
/// doc comment for why it's deliberately NOT part of `reconcile`/`blockedApps` at all). Not yet
/// wired: appBudgets/triggerWords (no Mac-side mechanism exists for these) and `guardianPasscode`
/// (deliberately excluded -- see this project's plan doc for why reusing the dashboard's Guardian
/// PIN as this Mac's local removal credential was rejected).
///
/// Host/token resolution and the fire-and-forget stderr logging style copy `TamperReporter`/
/// `AIReviewClient` exactly -- same filter-server, same credential, same "never let a network
/// hiccup crash or block the caller" stance.
enum DashboardConfigSync {
    private static let timeout: TimeInterval = 15

    /// Async, best-effort: dispatches the network round-trip and returns immediately so callers
    /// (`EnforcementLoop`'s tick) are never blocked on it. A failed fetch (network, non-200,
    /// undecodable body) leaves `stateStore`'s existing `dashboardConfigCache` untouched -- this
    /// must never wipe a last-known-good cache just because the network hiccuped, same fail-safe
    /// principle as everywhere else in this app (fail toward keeping the last-known state, not
    /// toward "unconfigured").
    static func fetch(stateStore: StateStore) {
        let host = nonEmpty(readTrimmed(FocusLockConstants.lockProfileHostPath))
            ?? FocusLockConstants.defaultLockProfileHost
        let token = nonEmpty(readTrimmed(FocusLockConstants.lockProfileTokenPath))
            ?? FocusLockConstants.defaultLockProfileToken
        guard !host.isEmpty, !token.isEmpty,
              let deviceID = TamperReporter.deviceID(), !deviceID.isEmpty,
              let url = URL(string: "https://\(host)/dashboard-api/devices/\(deviceID)/settings") else {
            FileHandle.standardError.write(
                "[dashboard-sync] skipped: no host/token provisioned, or device_id unavailable\n".data(using: .utf8)!
            )
            return
        }

        var request = URLRequest(url: url, timeoutInterval: timeout)
        request.httpMethod = "GET"
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")

        URLSession.shared.dataTask(with: request) { data, response, error in
            guard error == nil,
                  let http = response as? HTTPURLResponse, http.statusCode == 200,
                  let data else {
                FileHandle.standardError.write(
                    "[dashboard-sync] fetch failed: \(error?.localizedDescription ?? "non-200 response")\n".data(using: .utf8)!
                )
                return
            }
            guard let raw = try? JSONDecoder().decode(RawDashboardDeviceSettings.self, from: data) else {
                FileHandle.standardError.write(
                    "[dashboard-sync] fetch succeeded but the response body was undecodable\n".data(using: .utf8)!
                )
                return
            }
            let cache = DashboardDeviceSettingsCache(
                platform: raw.platform,
                updatedAt: raw.updatedAt,
                contentFilterEnabled: raw.vpnFilter?.enabled,
                blockedApps: (raw.blockedApps ?? []).map { $0.appId },
                protectedApps: (raw.protectedApps ?? []).map {
                    ProtectedApp(displayName: $0.displayName, executableName: $0.executableName, bundlePath: $0.bundlePath)
                },
                cooldownHours: raw.cooldownHours,
                proxyFilterEnabled: raw.proxyFilter?.enabled,
                proxyFilterForceViaFirewall: raw.proxyFilter?.forceViaFirewall,
                cloudFilterHost: raw.cloudFilterHost,
                cloudFilterEnabled: raw.cloudFilterEnabled,
                rules: (raw.rules ?? []).compactMap(parseRule)
            )
            stateStore.mutate { state in
                state.dashboardConfigCache = cache
                state.dashboardConfigLastFetchedAt = Date()
            }
            FileHandle.standardError.write("[dashboard-sync] fetched settings for device \(deviceID)\n".data(using: .utf8)!)
            reconcile(cache: cache, stateStore: stateStore)
        }.resume()
    }

    /// Separate fetch, separate endpoint (`GET /dashboard-api/habits`, not per-device) -- the
    /// global habit library + live completion state shared across every device (see
    /// `GlobalHabit`). Called on the same cadence as `fetch` above (see `EnforcementLoop`), but
    /// independently: a failure here doesn't affect the per-device settings fetch or vice versa.
    /// Only ever read by `RuleBlockEnforcer`; nothing else here reconciles against it.
    static func fetchGlobalHabits(stateStore: StateStore) {
        let host = nonEmpty(readTrimmed(FocusLockConstants.lockProfileHostPath))
            ?? FocusLockConstants.defaultLockProfileHost
        let token = nonEmpty(readTrimmed(FocusLockConstants.lockProfileTokenPath))
            ?? FocusLockConstants.defaultLockProfileToken
        guard !host.isEmpty, !token.isEmpty,
              let url = URL(string: "https://\(host)/dashboard-api/habits") else {
            return
        }

        var request = URLRequest(url: url, timeoutInterval: timeout)
        request.httpMethod = "GET"
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")

        URLSession.shared.dataTask(with: request) { data, response, error in
            guard error == nil,
                  let http = response as? HTTPURLResponse, http.statusCode == 200,
                  let data else {
                FileHandle.standardError.write(
                    "[dashboard-sync] global habits fetch failed: \(error?.localizedDescription ?? "non-200 response")\n".data(using: .utf8)!
                )
                return
            }
            guard let raw = try? JSONDecoder().decode(RawGlobalHabits.self, from: data) else {
                FileHandle.standardError.write(
                    "[dashboard-sync] global habits fetch succeeded but the response body was undecodable\n".data(using: .utf8)!
                )
                return
            }
            let habits = raw.habits.map { GlobalHabit(id: $0.id, name: $0.name, doneToday: $0.doneToday ?? false) }
            stateStore.mutate { $0.globalHabitsCache = habits }
        }.resume()
    }

    private struct RawGlobalHabits: Decodable {
        let habits: [Item]
        struct Item: Decodable {
            let id: String
            let name: String
            let doneToday: Bool?
        }
    }

    /// "HH:MM" -> minutes since midnight, or nil if malformed -- mirrors Android's
    /// `HabitRuleManager.parseTimeToMinuteOfDay` exactly, same format the dashboard wizard writes.
    private static func parseTimeToMinuteOfDay(_ text: String) -> Int? {
        let parts = text.split(separator: ":")
        guard parts.count == 2, let hour = Int(parts[0]), let minute = Int(parts[1]),
              (0...23).contains(hour), (0...59).contains(minute) else {
            return nil
        }
        return hour * 60 + minute
    }

    /// Skips entries missing a package/executable name or a valid schedule -- the dashboard
    /// wizard always sets one (see `MacRule`'s doc comment), so a missing one here means an
    /// older/malformed entry, not a real windowless rule to somehow handle.
    private static func parseRule(_ raw: RawDashboardDeviceSettings.RuleItem) -> MacRule? {
        guard !raw.appId.isEmpty, let schedule = raw.schedule,
              let start = parseTimeToMinuteOfDay(schedule.startTime ?? ""),
              let end = parseTimeToMinuteOfDay(schedule.endTime ?? "") else {
            return nil
        }
        let daysOfWeek = Set((schedule.daysOfWeek ?? []).filter { (0...6).contains($0) })
        return MacRule(
            id: raw.id,
            executableName: raw.appId,
            requiredHabitIds: raw.requiredHabitIds ?? [],
            windowStartMinute: start,
            windowEndMinute: end,
            daysOfWeek: daysOfWeek.isEmpty ? Set(0...6) : daysOfWeek
        )
    }

    /// Applies the fetched config against local state. Additions (a blockedApps/protectedApps
    /// entry the dashboard has that the Mac doesn't yet, or a toggle going from off to on) apply
    /// immediately via `ImmediateActionApplier` -- exactly mirroring what the local, ungated
    /// `XPCService` add/enable handlers already let ANY local caller do. Removals/disables
    /// (something local the dashboard no longer lists or has turned off) are scheduled via
    /// `PendingActionScheduler` with `source: "dashboard"` -- same `PendingAction` +
    /// `cooldownHours` queue local removals use, but authorized by "this came from a
    /// bearer-authenticated GET of this Mac's own device_id settings" rather than a local
    /// passcode/admin-UID (see `PendingActionScheduler`'s doc comment for the full reasoning, and
    /// this project's plan doc for why that specific tradeoff was chosen over requiring local
    /// confirmation first).
    ///
    /// Scalar/boolean fields (`cooldownHours`, `proxyFilter`, `cloudFilterHost`,
    /// `cloudFilterEnabled`) are `nil` server-side until a guardian has explicitly interacted
    /// with that specific dashboard control at least once (see `_default_device_settings`'s
    /// comment) -- reconcile only ever acts on a non-nil value, so an untouched control can never
    /// coincidentally collide with the Mac's own local default and force a change nobody asked
    /// for.
    ///
    /// List fields (`blockedApps`, `protectedApps`) can't carry that same nil-by-default
    /// distinction (an empty list is indistinguishable from "guardian explicitly cleared this").
    /// Guarded instead by `dashboardManagedBlockedApps`/`dashboardManagedProtectedApps` on
    /// `FocusLockState`: reconcile only ever schedules REMOVAL for a name it previously added
    /// itself (tracked in those sets), never for an entry a guardian added locally via
    /// `focuslockctl`/the GUI, which reconcile simply doesn't recognize as its own and leaves
    /// alone. Without this, a local-only addition would look identical, on the very next sync, to
    /// "the dashboard no longer wants this" and get silently reverted by a system the guardian
    /// never even opened -- confirmed as a real, live bug in an earlier version of this function
    /// (see this project's plan doc). A name is added to the managed set the moment reconcile
    /// itself adds it, and dropped the moment reconcile schedules its removal (not only once that
    /// removal matures) -- if a guardian later re-adds the same name locally, it starts out
    /// unmanaged again, same as any other local-only entry.
    ///
    /// Diffs per-item, not per-list: a single sync tick can contain both an addition and a
    /// removal in the same list, and each needs independent routing.
    private static func reconcile(cache: DashboardDeviceSettingsCache, stateStore: StateStore) {
        guard cache.updatedAt != nil else { return }
        guard cache.platform == nil || cache.platform == "macos" else { return }

        var changed = false
        let state = stateStore.snapshot()

        // Blocked apps.
        let localBlockedNames = Set(state.blockedApps.map { $0.executableName })
        let desiredBlockedNames = Set(cache.blockedApps)

        for name in desiredBlockedNames.subtracting(localBlockedNames) {
            guard !AppBlockEnforcer.protectedExecutables.contains(name) else {
                FileHandle.standardError.write(
                    "[dashboard-sync] refused to block '\(name)': matches this app's own executable\n".data(using: .utf8)!
                )
                continue
            }
            // The dashboard has no separate display-name field for a Mac executable -- reuse the
            // executable name for both, matching what focuslockctl's own add-app CLI accepts.
            ImmediateActionApplier.addBlockedApp(BlockedApp(displayName: name, executableName: name), stateStore: stateStore)
            stateStore.mutate { $0.dashboardManagedBlockedApps.insert(name) }
            changed = true
        }
        // Only remove a name this sync itself previously added -- never a local-only entry.
        for name in localBlockedNames.subtracting(desiredBlockedNames).intersection(state.dashboardManagedBlockedApps) {
            let result = PendingActionScheduler.schedule(
                .removeBlockedApp, target: name, source: "dashboard", stateStore: stateStore, onScheduled: {}
            )
            if result.success {
                stateStore.mutate { $0.dashboardManagedBlockedApps.remove(name) }
                changed = true
            }
        }

        // Protected apps.
        let localProtectedNames = Set(state.protectedApps.map { $0.executableName })
        // uniquingKeysWith rather than uniqueKeysWithValues: the server doesn't dedupe
        // executableName within a list on its own add path, so a duplicate here must not crash
        // the daemon -- just keep the last entry.
        let desiredProtected = Dictionary(cache.protectedApps.map { ($0.executableName, $0) }, uniquingKeysWith: { _, last in last })

        for (name, app) in desiredProtected where !localProtectedNames.contains(name) {
            if !ImmediateActionApplier.addProtectedApp(app, stateStore: stateStore) {
                FileHandle.standardError.write(
                    "[dashboard-sync] could not protect '\(name)': no app bundle found at \(app.bundlePath)\n".data(using: .utf8)!
                )
            } else {
                stateStore.mutate { $0.dashboardManagedProtectedApps.insert(name) }
                changed = true
            }
        }
        for name in localProtectedNames.subtracting(Set(desiredProtected.keys)).intersection(state.dashboardManagedProtectedApps) {
            let result = PendingActionScheduler.schedule(
                .removeProtectedApp, target: name, source: "dashboard", stateStore: stateStore, onScheduled: {}
            )
            if result.success {
                stateStore.mutate { $0.dashboardManagedProtectedApps.remove(name) }
                changed = true
            }
        }

        // Content filter on/off maps only to dnsEnforcementEnabled -- see
        // DashboardDeviceSettingsCache's doc comment for why this is deliberately not a
        // DNS+proxy composite.
        if let desired = cache.contentFilterEnabled, desired != state.dnsEnforcementEnabled {
            if desired {
                ImmediateActionApplier.enableDNSEnforcement(stateStore: stateStore)
            } else {
                _ = PendingActionScheduler.schedule(
                    .disableDNSEnforcement, source: "dashboard", stateStore: stateStore, onScheduled: {}
                )
            }
            changed = true
        }

        // Cooldown hours: raising is protection-increasing (immediate, matches
        // XPCService.setCooldownHours' own ungated branch); lowering is itself gated, queued at
        // the CURRENT (higher) cooldown so shortening the wait can't be used to bypass itself.
        if let desired = cache.cooldownHours, desired != state.cooldownHours {
            if desired > state.cooldownHours {
                ImmediateActionApplier.raiseCooldownHours(desired, stateStore: stateStore)
                changed = true
            } else {
                let result = PendingActionScheduler.schedule(
                    .lowerCooldownHours, target: String(desired), source: "dashboard", stateStore: stateStore, onScheduled: {}
                )
                if result.success { changed = true }
            }
        }

        // Proxy enforcement: `forceViaFirewall` has no independent gate locally either (see
        // XPCService.enableProxyEnforcement -- it's only ever set together with enabling, in
        // either direction), so it's folded into the same immediate "enable" call rather than
        // reconciled separately.
        if let desiredEnabled = cache.proxyFilterEnabled {
            let desiredForce = cache.proxyFilterForceViaFirewall ?? false
            if desiredEnabled {
                if !state.proxyEnforcementEnabled || state.forceProxyViaFirewall != desiredForce {
                    ImmediateActionApplier.enableProxyEnforcement(forceViaFirewall: desiredForce, stateStore: stateStore)
                    changed = true
                }
            } else if state.proxyEnforcementEnabled {
                let result = PendingActionScheduler.schedule(
                    .disableProxyEnforcement, source: "dashboard", stateStore: stateStore, onScheduled: {}
                )
                if result.success { changed = true }
            }
        }

        // Cloud filter enabled/disabled -- which resolver DNS enforcement uses, independent of
        // whether DNS enforcement itself is on.
        if let desired = cache.cloudFilterEnabled, desired != state.cloudFilterEnabled {
            if desired {
                ImmediateActionApplier.enableCloudFilter(stateStore: stateStore)
            } else {
                _ = PendingActionScheduler.schedule(
                    .disableCloudFilter, source: "dashboard", stateStore: stateStore, onScheduled: {}
                )
            }
            changed = true
        }

        // Cloud filter host: unlike everything else above, XPCService.setCloudFilterHost is
        // ALWAYS gated regardless of direction (repointing is equivalent to defeating the filter
        // -- see that method's doc comment), so this never applies immediately even for what
        // looks like a "correction." Re-validated here rather than trusted from the server
        // payload's shape, same as every other value this daemon writes into DNS/pf config.
        if let desiredHost = cache.cloudFilterHost, !desiredHost.isEmpty, desiredHost != state.cloudFilterHost {
            let normalized = desiredHost.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            if HostnameValidator.isValidHostname(normalized) {
                let result = PendingActionScheduler.schedule(
                    .setCloudFilterHost, target: normalized, source: "dashboard", stateStore: stateStore, onScheduled: {}
                )
                if result.success { changed = true }
            } else {
                FileHandle.standardError.write(
                    "[dashboard-sync] refused invalid cloud filter host '\(desiredHost)'\n".data(using: .utf8)!
                )
            }
        }

        // Mirrors XPCService's onStateChanged -- an immediate re-tick so a dashboard-driven
        // addition (or a just-matured removal, on a future tick) takes effect right away rather
        // than waiting for EnforcementLoop's next 3s timer fire.
        if changed {
            EnforcementLoop.shared.reapplyNow()
        }
    }

    /// Only the fields Phase 1/2 need, decoded with every key optional (`decodeIfPresent`-
    /// equivalent via `Bool?`/`String?`/optional nested types) so new or Android-only fields the
    /// server adds later never break Mac decoding. Deliberately NOT a re-declaration of the full
    /// server schema -- see `lockprofile_service.py`'s `_default_device_settings` for everything
    /// this intentionally ignores (rules, habits, appBudgets, triggerWords, blockedWebsites,
    /// protections, etc. -- all Android-only today, see the plan doc's "deferred" section).
    private struct RawDashboardDeviceSettings: Decodable {
        let platform: String?
        let updatedAt: Double?
        let vpnFilter: VPNFilter?
        let blockedApps: [BlockedAppItem]?
        let protectedApps: [ProtectedAppItem]?
        let cooldownHours: Double?
        let proxyFilter: ProxyFilter?
        let cloudFilterHost: String?
        let cloudFilterEnabled: Bool?
        let rules: [RuleItem]?

        struct VPNFilter: Decodable { let enabled: Bool? }
        struct BlockedAppItem: Decodable { let appId: String }
        struct ProtectedAppItem: Decodable { let displayName: String; let executableName: String; let bundlePath: String }
        struct ProxyFilter: Decodable { let enabled: Bool?; let forceViaFirewall: Bool? }
        struct RuleItem: Decodable {
            let id: String
            let appId: String
            let requiredHabitIds: [String]?
            let schedule: Schedule?
            struct Schedule: Decodable {
                let startTime: String?
                let endTime: String?
                let daysOfWeek: [Int]?
            }
        }
    }

    private static func readTrimmed(_ path: String) -> String? {
        guard let data = FileManager.default.contents(atPath: path) else { return nil }
        return String(data: data, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func nonEmpty(_ value: String?) -> String? {
        guard let value, !value.isEmpty else { return nil }
        return value
    }
}
