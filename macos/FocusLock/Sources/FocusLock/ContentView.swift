import AppKit
import SwiftUI
import FocusLockShared

// Restyled to the Otterling desktop design (see Theme.swift): a macOS-window layout with a left
// sidebar (logo + live "Protected" badge + nav) and a card-based content area, Material-3 palette,
// status pills, light + dark. All behaviour and every viewModel binding is unchanged -- this is a
// visual reskin over the same daemon-backed functionality.

struct ContentView: View {
    @StateObject private var viewModel = FocusLockViewModel()
    @State private var screen: Screen = .overview

    enum Screen: String, CaseIterable, Identifiable {
        case overview, apps, sites, protectedApps, filter, security, updates, terminal, assistant, multiUser
        var id: String { rawValue }

        var title: String {
            switch self {
            case .overview: return "Overview"
            case .apps: return "Blocked Apps"
            case .sites: return "Blocked Sites"
            case .protectedApps: return "Protected Apps"
            case .filter: return "Content Filter"
            case .security: return "Guardian & Cooldown"
            case .updates: return "App Updates"
            case .terminal: return "Sudo Terminal"
            case .assistant: return "AI Assistant"
            case .multiUser: return "Protect Another User"
            }
        }

        var icon: String {
            switch self {
            case .overview: return "square.grid.2x2.fill"
            case .apps: return "xmark.app.fill"
            case .sites: return "globe"
            case .protectedApps: return "lock.app.dashed"
            case .filter: return "shield.lefthalf.filled"
            case .security: return "key.fill"
            case .updates: return "arrow.triangle.2.circlepath"
            case .terminal: return "terminal.fill"
            case .assistant: return "sparkles"
            case .multiUser: return "person.2.fill"
            }
        }

        var group: String {
            switch self {
            case .security: return "Guardian"
            case .terminal, .assistant, .multiUser: return "Elevation Broker"
            default: return "Protect"
            }
        }
    }

    var body: some View {
        HStack(spacing: 0) {
            sidebar
            main
        }
        .frame(width: 940, height: 680)
        .background(Otter.background)
        .onAppear { viewModel.startPolling() }
        .alert(
            "Otterling",
            isPresented: Binding(
                get: { viewModel.errorMessage != nil },
                set: { if !$0 { viewModel.errorMessage = nil } }
            )
        ) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(viewModel.errorMessage ?? "")
        }
    }

    // MARK: Derived status

    private var isBlocking: Bool {
        !viewModel.state.blockedApps.isEmpty || !viewModel.state.blockedDomains.isEmpty
    }
    private var isProtecting: Bool { !viewModel.state.protectedApps.isEmpty }
    private var isEnforcingDNS: Bool { viewModel.state.dnsEnforcementEnabled }
    private var isProtected: Bool { isBlocking || isProtecting || isEnforcingDNS }

    /// (title, hue) for the top-line status shown in the sidebar badge and the overview card. A live
    /// VPN routes around the filter entirely (GAP-03), so it overrides the "Protected" headline.
    private var statusHeadline: (String, PillVariant) {
        if viewModel.state.vpnActive { return ("Filter bypassed", .error) }
        if !isProtected { return ("Setup required", .warning) }
        return ("Protected", .success)
    }

    private var cooldownDescription: String {
        let hours = viewModel.state.cooldownHours
        if hours <= 0 { return "no time (cooldown off)" }
        return hours == hours.rounded() ? "\(Int(hours))h" : String(format: "%.1fh", hours)
    }

    /// Doesn't claim "only the Guardian can undo this" when no passcode is set -- on a single-admin
    /// machine that would overstate the protection.
    private var undoDescription: String {
        guard viewModel.state.passcodeConfigured else { return "not gated yet — no passcode set" }
        return viewModel.state.cooldownHours > 0
            ? "undoing needs the passcode + \(cooldownDescription)"
            : "undoing needs the passcode"
    }

    // MARK: - Sidebar

    private var sidebar: some View {
        VStack(alignment: .leading, spacing: 0) {
            // Logo lockup
            HStack(spacing: 10) {
                IconTile(systemImage: "shield.lefthalf.filled", hue: .info, size: 36)
                VStack(alignment: .leading, spacing: 1) {
                    Text("Otterling").font(.system(size: 15, weight: .bold))
                    Text("Family Safety").font(.system(size: 10)).foregroundStyle(Otter.onSurfaceVariant)
                }
            }
            .padding(.horizontal, 16).padding(.top, 16).padding(.bottom, 12)

            // Live status badge
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Circle()
                        .fill(statusHeadline.1.fg)
                        .frame(width: 6, height: 6)
                    Text(statusHeadline.0)
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(statusHeadline.1.fg)
                }
                Text(statusSummary).font(.system(size: 10)).foregroundStyle(Otter.onSurfaceVariant)
            }
            .padding(.horizontal, 12).padding(.vertical, 9)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(statusHeadline.1.bg.opacity(0.5))
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .padding(.horizontal, 12).padding(.bottom, 10)

            // Nav
            ScrollView {
                VStack(alignment: .leading, spacing: 1) {
                    navHeader("Protect")
                    ForEach(Screen.allCases.filter { $0.group == "Protect" }) { navItem($0) }
                    navHeader("Guardian")
                    ForEach(Screen.allCases.filter { $0.group == "Guardian" }) { navItem($0) }
                    navHeader("Elevation Broker")
                    ForEach(Screen.allCases.filter { $0.group == "Elevation Broker" }) { navItem($0) }
                }
                .padding(.horizontal, 8)
            }

            Spacer(minLength: 0)

            // Footer: build + theme note
            Divider().overlay(Otter.outlineVariant.opacity(0.4))
            Text("Build \(FocusLockConstants.appVersionCode)")
                .font(.system(size: 10))
                .foregroundStyle(Otter.onSurfaceVariant)
                .padding(.horizontal, 16).padding(.vertical, 10)
        }
        .frame(width: 212)
        .background(Otter.surface)
        .overlay(alignment: .trailing) {
            Rectangle().fill(Otter.outlineVariant.opacity(0.4)).frame(width: 1)
        }
    }

    private var statusSummary: String {
        if isEnforcingDNS { return "NSFW filter enforced" }
        if isBlocking || isProtecting { return "Blocking active 24/7" }
        return "No protection active"
    }

    private func navHeader(_ text: String) -> some View {
        Text(text.uppercased())
            .font(.system(size: 9, weight: .bold))
            .tracking(1.2)
            .foregroundStyle(Otter.onSurfaceVariant.opacity(0.7))
            .padding(.horizontal, 12).padding(.top, 10).padding(.bottom, 4)
    }

    private func navItem(_ item: Screen) -> some View {
        let active = screen == item
        return Button {
            screen = item
        } label: {
            HStack(spacing: 10) {
                Image(systemName: item.icon).font(.system(size: 13)).frame(width: 16)
                Text(item.title).font(.system(size: 13, weight: .medium))
                Spacer(minLength: 0)
            }
            .foregroundStyle(active ? Otter.onPrimary : Otter.onSurfaceVariant)
            .padding(.horizontal, 12).padding(.vertical, 7)
            .background(active ? Otter.primary : Color.clear)
            .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    // MARK: - Main content

    private var main: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                header
                // Ordered by urgency: a live filter bypass first, then the missing tripwire. Both
                // show on every screen so they can't be missed.
                if viewModel.state.vpnActive {
                    vpnActiveWarningBanner
                }
                if !viewModel.state.lockProfileInstalled {
                    lockProfileWarningBanner
                }
                switch screen {
                case .overview: overviewScreen
                case .apps: appsScreen
                case .sites: sitesScreen
                case .protectedApps: protectedAppsScreen
                case .filter: filterScreen
                case .security: securityScreen
                case .updates: updatesScreen
                case .terminal: terminalScreen
                case .assistant: assistantScreen
                case .multiUser: multiUserScreen
                }
            }
            .padding(24)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .background(Otter.background)
    }

    private var header: some View {
        HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: 2) {
                Text(screen.title).font(.system(size: 24, weight: .bold))
                Text(headerSubtitle).font(.system(size: 13)).foregroundStyle(Otter.onSurfaceVariant)
            }
            Spacer()
            if screen == .overview {
                Button { screen = .sites } label: { Label("Block Site", systemImage: "globe") }
                    .buttonStyle(OtterOutlined())
                Button { screen = .apps } label: { Label("Block App", systemImage: "plus") }
                    .buttonStyle(OtterFilled())
            }
        }
    }

    private var headerSubtitle: String {
        switch screen {
        case .overview: return "Your protection status at a glance"
        case .apps: return "Apps killed on sight while listed — add-only, removal is gated"
        case .sites: return "Domains redirected to nowhere via /etc/hosts"
        case .protectedApps: return "Apps kept alive and undeletable"
        case .filter: return "System-wide NSFW DNS filtering"
        case .security: return "The secret and the wait that gate every removal"
        case .updates: return "Signed, verified in-app updates"
        case .terminal: return "Every command goes through the denylist → allowlist → AI-review broker -- inert until this account is Standard"
        case .assistant: return "Describe what you need in plain language -- the AI translates it, then every resulting command still goes through the same broker"
        case .multiUser: return "Push the trigger-word scanner into a different local account's session"
        }
    }

    // MARK: Overview

    private var overviewScreen: some View {
        VStack(alignment: .leading, spacing: 18) {
            // Big status card
            Card {
                HStack(spacing: 14) {
                    IconTile(systemImage: statusHeadline.1 == .success ? "checkmark.shield.fill" : "shield.slash",
                             hue: statusHeadline.1, size: 48)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(statusHeadline.0)
                            .font(.system(size: 18, weight: .bold))
                            .foregroundStyle(statusHeadline.1.fg)
                        Text(isEnforcingDNS ? "NSFW filter active · \(undoDescription)"
                             : (isBlocking || isProtecting) ? "Active 24/7 · \(undoDescription)"
                             : "Content filter is off. Turn it on from Content Filter.")
                            .font(.system(size: 13))
                            .foregroundStyle(Otter.onSurfaceVariant)
                    }
                    Spacer()
                }
            }

            // Stat tiles
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 12), count: 4), spacing: 12) {
                StatTile(systemImage: "globe", value: "\(viewModel.state.blockedDomains.count)",
                         label: "Sites Blocked", sub: "Manual list", hue: .info)
                StatTile(systemImage: "xmark.app.fill", value: "\(viewModel.state.blockedApps.count)",
                         label: "Apps Blocked", sub: "Killed on sight", hue: .info)
                StatTile(systemImage: "lock.app.dashed", value: "\(viewModel.state.protectedApps.count)",
                         label: "Apps Protected", sub: "Kept alive", hue: .success)
                StatTile(systemImage: viewModel.state.passcodeConfigured ? "key.fill" : "key.slash",
                         value: viewModel.state.passcodeConfigured ? "Set" : "None",
                         label: "Passcode", sub: viewModel.state.passcodeConfigured ? "Cooldown \(cooldownDescription)" : "Not gated",
                         hue: viewModel.state.passcodeConfigured ? .success : .warning)
            }

            if !viewModel.state.pendingActions.isEmpty {
                pendingActionsCard
            }
        }
    }

    /// Scheduled-but-not-yet-applied changes. Cancelling needs no passcode, so it's the easiest
    /// thing to reach.
    private var pendingActionsCard: some View {
        Card {
            HStack {
                SectionLabel(text: "Pending Changes")
                Spacer()
                Pill(text: "\(viewModel.state.pendingActions.count) scheduled", variant: .warning)
            }
            Text("Authorised, waiting out the cooldown. Cancel needs no passcode.")
                .font(.system(size: 11)).foregroundStyle(Otter.onSurfaceVariant)
            ForEach(viewModel.state.pendingActions.sorted { $0.effectiveAt < $1.effectiveAt }) { action in
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(action.describedFully).font(.system(size: 13, weight: .medium))
                        Text(action.isMature()
                             ? "Due now — applies within seconds"
                             : "Takes effect \(action.effectiveAt.formatted(date: .abbreviated, time: .shortened))")
                            .font(.system(size: 11)).foregroundStyle(Otter.onSurfaceVariant)
                    }
                    Spacer()
                    Button("Cancel") { viewModel.cancelPendingAction(action.id) }
                        .buttonStyle(OtterTonal(variant: .warning))
                }
                .padding(10)
                .background(Otter.tertiaryContainer.opacity(0.35))
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
            }
        }
    }

    // MARK: Blocked apps

    private var appsScreen: some View {
        Card {
            HStack {
                SectionLabel(text: "Blocked Apps")
                Spacer()
                Button { pickApp() } label: { Label("Add App", systemImage: "plus") }
                    .buttonStyle(OtterFilled())
            }
            if viewModel.state.blockedApps.isEmpty {
                emptyState("No apps blocked", "Blocked apps are killed the instant they launch, continuously.")
            } else {
                VStack(spacing: 6) {
                    ForEach(viewModel.state.blockedApps) { app in
                        listRow(icon: "xmark.app.fill", hue: .error, title: app.displayName,
                                subtitle: app.executableName) {
                            viewModel.removeApp(app.executableName)
                        }
                    }
                }
            }
        }
    }

    // MARK: Blocked sites

    private var sitesScreen: some View {
        Card {
            SectionLabel(text: "Blocked Websites")
            HStack(spacing: 8) {
                TextField("example.com", text: $viewModel.newDomainText)
                    .textFieldStyle(.plain)
                    .padding(.horizontal, 12).padding(.vertical, 8)
                    .background(Otter.surfaceVariant.opacity(0.5))
                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                    .onSubmit { viewModel.addDomain() }
                Button("Add") { viewModel.addDomain() }.buttonStyle(OtterFilled())
            }
            if viewModel.state.blockedDomains.isEmpty {
                emptyState("No sites blocked", "Added domains are redirected to 127.0.0.1 in /etc/hosts.")
            } else {
                VStack(spacing: 6) {
                    ForEach(viewModel.state.blockedDomains, id: \.self) { domain in
                        listRow(icon: "globe", hue: .info, title: domain, subtitle: nil) {
                            viewModel.removeDomain(domain)
                        }
                    }
                }
            }
        }
    }

    // MARK: Protected apps

    private var protectedAppsScreen: some View {
        Card {
            HStack {
                SectionLabel(text: "Protected Apps")
                Spacer()
                Button { pickProtectedApp() } label: { Label("Protect App", systemImage: "plus") }
                    .buttonStyle(OtterFilled())
            }
            Text("Keeps selected apps from being quit or deleted — relaunched automatically and locked against removal. Optional; useful for an accountability app you don't want to be able to get around.")
                .font(.system(size: 11)).foregroundStyle(Otter.onSurfaceVariant)
            if viewModel.state.protectedApps.isEmpty {
                emptyState("No apps protected", "Protected apps are relaunched within seconds and locked with the system-immutable flag.")
            } else {
                VStack(spacing: 6) {
                    ForEach(viewModel.state.protectedApps) { app in
                        listRow(icon: "lock.app.dashed", hue: .success, title: app.displayName,
                                subtitle: app.bundlePath) {
                            viewModel.removeProtectedApp(app.executableName)
                        }
                    }
                }
            }
        }
    }

    // MARK: Content filter

    private var filterScreen: some View {
        VStack(alignment: .leading, spacing: 16) {
            Card {
                HStack {
                    SectionLabel(text: "DNS Enforcement")
                    Spacer()
                    if viewModel.state.dnsEnforcementEnabled {
                        Pill(text: "Enforced", variant: .success)
                    } else {
                        Pill(text: "Off", variant: .neutral)
                    }
                }
                Text("Points system DNS at your cloud filter (Canopy-style AdGuard Home) and blocks alternate/DoH/DoT resolvers so it can't be sidestepped. Falls back to Cloudflare's filtered DNS if the cloud filter is off or unreachable. A downloaded local adult-domain hosts list is always applied regardless of this toggle.")
                    .font(.system(size: 11)).foregroundStyle(Otter.onSurfaceVariant)
                HStack {
                    if viewModel.state.dnsEnforcementEnabled {
                        Label("Cloud + local adult lists active", systemImage: "checkmark.shield.fill")
                            .font(.system(size: 13)).foregroundStyle(Otter.secondary)
                        Spacer()
                        Button("Disable…") { viewModel.disableDNSEnforcement() }
                            .buttonStyle(OtterTonal(variant: .error))
                    } else {
                        Label("Not enforced", systemImage: "shield.slash")
                            .font(.system(size: 13)).foregroundStyle(Otter.onSurfaceVariant)
                        Spacer()
                        Button("Enable") { viewModel.enableDNSEnforcement() }.buttonStyle(OtterFilled())
                    }
                }
            }

            Card {
                SectionLabel(text: "Cloud Filter Server")
                Toggle(isOn: Binding(
                    get: { viewModel.state.cloudFilterEnabled },
                    set: { viewModel.setCloudFilterEnabled($0) }
                )) {
                    Text("Use cloud filter server").font(.system(size: 13, weight: .medium))
                }
                .toggleStyle(.switch)
                .tint(Otter.primary)

                HStack(spacing: 8) {
                    TextField("vpn.bartholomew.help", text: $viewModel.cloudFilterHostText)
                        .textFieldStyle(.plain)
                        .padding(.horizontal, 12).padding(.vertical, 8)
                        .background(Otter.surfaceVariant.opacity(0.5))
                        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                        .onSubmit { viewModel.saveCloudFilterHost() }
                    Button("Save") { viewModel.saveCloudFilterHost() }.buttonStyle(OtterOutlined())
                }
                HStack {
                    Button("Test filter server") { viewModel.testCloudFilterReachability() }
                        .buttonStyle(OtterText())
                        .disabled(viewModel.cloudFilterTesting)
                    if let result = viewModel.cloudFilterTestResult {
                        Text(result).font(.system(size: 11)).foregroundStyle(Otter.onSurfaceVariant)
                    }
                }
            }
        }
    }

    // MARK: Security (passcode + cooldown)

    private var securityScreen: some View {
        VStack(alignment: .leading, spacing: 16) {
            Card {
                HStack {
                    SectionLabel(text: "Guardian Passcode")
                    Spacer()
                    Pill(text: viewModel.state.passcodeConfigured ? "Set" : "Not set",
                         variant: viewModel.state.passcodeConfigured ? .success : .warning)
                }
                if viewModel.state.passcodeConfigured {
                    Text("Removals need the passcode, then wait out the \(cooldownDescription) cooldown.")
                        .font(.system(size: 12)).foregroundStyle(Otter.onSurfaceVariant)
                } else {
                    Text("No passcode set. Removals fall back to the admin-group check, which this account passes — nothing is actually gated. Set one and give it to someone else, or store it where you can't reach it on impulse.")
                        .font(.system(size: 12)).foregroundStyle(Otter.tertiary)
                }

                SecureField("Guardian passcode (to authorise a change)", text: $viewModel.passcodeText)
                    .textFieldStyle(.plain)
                    .padding(.horizontal, 12).padding(.vertical, 8)
                    .background(Otter.surfaceVariant.opacity(0.5))
                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))

                Divider().overlay(Otter.outlineVariant.opacity(0.4))

                Text(viewModel.state.passcodeConfigured ? "Change passcode" : "Set passcode")
                    .font(.system(size: 12, weight: .semibold))
                SecureField("New passcode (min 6 characters)", text: $viewModel.newPasscodeText)
                    .textFieldStyle(.plain)
                    .padding(.horizontal, 12).padding(.vertical, 8)
                    .background(Otter.surfaceVariant.opacity(0.5))
                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                SecureField("Confirm new passcode", text: $viewModel.confirmPasscodeText)
                    .textFieldStyle(.plain)
                    .padding(.horizontal, 12).padding(.vertical, 8)
                    .background(Otter.surfaceVariant.opacity(0.5))
                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                HStack {
                    Button(viewModel.state.passcodeConfigured ? "Change Passcode" : "Set Passcode") {
                        viewModel.setGuardianPasscode()
                    }
                    .buttonStyle(OtterFilled())
                    if let status = viewModel.passcodeStatusText {
                        Text(status).font(.system(size: 11)).foregroundStyle(Otter.onSurfaceVariant)
                    }
                }
            }

            Card {
                SectionLabel(text: "Removal Cooldown")
                HStack(spacing: 8) {
                    Text("Hours").font(.system(size: 12)).foregroundStyle(Otter.onSurfaceVariant)
                    TextField("24", text: $viewModel.cooldownHoursText)
                        .textFieldStyle(.plain)
                        .frame(width: 70)
                        .padding(.horizontal, 12).padding(.vertical, 8)
                        .background(Otter.surfaceVariant.opacity(0.5))
                        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                    Button("Save") { viewModel.saveCooldownHours() }.buttonStyle(OtterOutlined())
                    Spacer()
                }
                Text("Raising the cooldown is instant. Lowering it needs the passcode and waits out the current cooldown first.")
                    .font(.system(size: 11)).foregroundStyle(Otter.onSurfaceVariant)
            }
        }
    }

    // MARK: Updates

    private var updatesScreen: some View {
        Card {
            SectionLabel(text: "App Updates")
            Text("Checked automatically every hour; this is the same check, run on demand. A verified update's SHA-256 and code-signing Team ID must both match before anything installs.")
                .font(.system(size: 11)).foregroundStyle(Otter.onSurfaceVariant)
            HStack(spacing: 8) {
                Pill(text: "Build \(FocusLockConstants.appVersionCode)", variant: .info)
                Spacer()
                Button("Check for update") { viewModel.checkForUpdate() }
                    .buttonStyle(OtterOutlined())
                    .disabled(viewModel.updateChecking || viewModel.updateInstalling)
                if viewModel.updateAvailable {
                    Button("Install update") { viewModel.installAvailableUpdate() }
                        .buttonStyle(OtterFilled())
                        .disabled(viewModel.updateInstalling)
                }
            }
            if !viewModel.updateStatusText.isEmpty {
                Text(viewModel.updateStatusText).font(.system(size: 11)).foregroundStyle(Otter.onSurfaceVariant)
            }
        }
    }

    // MARK: - Sudo terminal

    private var terminalScreen: some View {
        Card {
            SectionLabel(text: "Elevated Command Broker")
            Text("Decision pipeline: hardcoded denylist → hardcoded allowlist → AI review (fails closed on any error or ambiguity). Every decision, approved or denied, is reported to your accountability partner. Inert until this account is converted to Standard -- normal sudo still works right now.")
                .font(.system(size: 11)).foregroundStyle(Otter.onSurfaceVariant)

            ScrollView {
                VStack(alignment: .leading, spacing: 8) {
                    if viewModel.terminalLog.isEmpty {
                        emptyState("No commands run yet", "Type a command below and press Run.")
                    }
                    ForEach(viewModel.terminalLog) { entry in
                        terminalEntryView(entry)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .frame(height: 320)
            .padding(10)
            .background(Color.black.opacity(0.85))
            .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))

            HStack(spacing: 8) {
                TextField("command, e.g. brew install wget", text: $viewModel.terminalCommandText)
                    .font(.system(size: 12, design: .monospaced))
                    .textFieldStyle(.roundedBorder)
                    .onSubmit { viewModel.runTerminalCommand() }
                TextField("reason (optional)", text: $viewModel.terminalReasonText)
                    .font(.system(size: 12))
                    .textFieldStyle(.roundedBorder)
                    .frame(width: 180)
                Button("Run") { viewModel.runTerminalCommand() }
                    .buttonStyle(OtterFilled())
                    .disabled(viewModel.terminalRunning || viewModel.terminalCommandText.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
    }

    private func terminalEntryView(_ entry: FocusLockViewModel.TerminalEntry) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text("$ \(entry.command)")
                .font(.system(size: 12, design: .monospaced))
                .foregroundStyle(.white)
            Text("\(entry.result.approved ? "APPROVED" : "DENIED") (\(entry.result.source)): \(entry.result.explanation)")
                .font(.system(size: 11, design: .monospaced))
                .foregroundStyle(entry.result.approved ? Color.green : Color.red)
            if let stdout = entry.result.stdout, !stdout.isEmpty {
                Text(stdout).font(.system(size: 11, design: .monospaced)).foregroundStyle(.white.opacity(0.85))
            }
            if let stderr = entry.result.stderr, !stderr.isEmpty {
                Text(stderr).font(.system(size: 11, design: .monospaced)).foregroundStyle(.orange)
            }
        }
        .padding(.bottom, 4)
    }

    // MARK: - AI Assistant

    private var assistantScreen: some View {
        Card {
            SectionLabel(text: "AI Assistant")
            Text("Describe what you need in plain language. The assistant only translates it into shell command(s) -- it never executes anything itself. Each resulting command is then run through the exact same broker as the terminal above, one at a time.")
                .font(.system(size: 11)).foregroundStyle(Otter.onSurfaceVariant)

            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    if viewModel.assistantLog.isEmpty {
                        emptyState("No requests yet", "Try something like \"install wget\".")
                    }
                    ForEach(viewModel.assistantLog) { entry in
                        assistantEntryView(entry)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .frame(height: 320)
            .padding(10)
            .background(Color.black.opacity(0.85))
            .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))

            HStack(spacing: 8) {
                TextField("e.g. install wget", text: $viewModel.assistantRequestText)
                    .font(.system(size: 12))
                    .textFieldStyle(.roundedBorder)
                    .onSubmit { viewModel.runAssistantRequest() }
                Button("Send") { viewModel.runAssistantRequest() }
                    .buttonStyle(OtterFilled())
                    .disabled(viewModel.assistantRunning || viewModel.assistantRequestText.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
    }

    private func assistantEntryView(_ entry: FocusLockViewModel.AssistantEntry) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("> \(entry.request)").font(.system(size: 12, design: .monospaced)).foregroundStyle(.white)
            Text(entry.result.translationExplanation)
                .font(.system(size: 11)).foregroundStyle(.white.opacity(0.6))
            if entry.result.steps.isEmpty {
                Text("(no commands to run)").font(.system(size: 11, design: .monospaced)).foregroundStyle(.orange)
            }
            ForEach(Array(entry.result.steps.enumerated()), id: \.offset) { _, step in
                VStack(alignment: .leading, spacing: 2) {
                    Text("$ \(step.command)").font(.system(size: 12, design: .monospaced)).foregroundStyle(.white)
                    Text("\(step.result.approved ? "APPROVED" : "DENIED") (\(step.result.source)): \(step.result.explanation)")
                        .font(.system(size: 11, design: .monospaced))
                        .foregroundStyle(step.result.approved ? Color.green : Color.red)
                    if let stdout = step.result.stdout, !stdout.isEmpty {
                        Text(stdout).font(.system(size: 11, design: .monospaced)).foregroundStyle(.white.opacity(0.85))
                    }
                }
                .padding(.leading, 12)
            }
        }
        .padding(.bottom, 4)
    }

    // MARK: - Multi-user

    private var multiUserScreen: some View {
        Card {
            SectionLabel(text: "Protect Another User")
            Text("Pushes the trigger-word scanner into a different local account's session, for an admin protecting a separate Standard account rather than the single-account self-accountability model. That user needs an active login session right now, and will see one unavoidable Accessibility permission prompt themselves the next time they're at their desktop -- macOS requires that click, it can't be automated. The DNS-floor profile still needs installing separately under their own login.")
                .font(.system(size: 11)).foregroundStyle(Otter.onSurfaceVariant)
            HStack(spacing: 8) {
                TextField("macOS username", text: $viewModel.protectUsernameText)
                    .textFieldStyle(.roundedBorder)
                Button("Install Scanner") { viewModel.protectUser(viewModel.protectUsernameText) }
                    .buttonStyle(OtterFilled())
                    .disabled(viewModel.protectUsernameText.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            if let status = viewModel.protectUserStatusText {
                Text(status).font(.system(size: 11)).foregroundStyle(Otter.onSurfaceVariant)
            }
        }
    }

    // MARK: - Shared row helpers

    private func listRow(icon: String, hue: PillVariant, title: String, subtitle: String?, remove: @escaping () -> Void) -> some View {
        HStack(spacing: 10) {
            IconTile(systemImage: icon, hue: hue, size: 30)
            VStack(alignment: .leading, spacing: 1) {
                Text(title).font(.system(size: 13, weight: .medium)).foregroundStyle(Otter.onSurface)
                if let subtitle {
                    Text(subtitle).font(.system(size: 10)).foregroundStyle(Otter.onSurfaceVariant).lineLimit(1)
                }
            }
            Spacer()
            Button(action: remove) {
                Image(systemName: "minus.circle.fill").font(.system(size: 16)).foregroundStyle(Otter.error)
            }
            .buttonStyle(.plain)
        }
        .padding(8)
        .background(Otter.surfaceVariant.opacity(0.35))
        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
    }

    private func emptyState(_ title: String, _ sub: String) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(title).font(.system(size: 13, weight: .semibold)).foregroundStyle(Otter.onSurfaceVariant)
            Text(sub).font(.system(size: 11)).foregroundStyle(Otter.onSurfaceVariant.opacity(0.8))
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.vertical, 8)
    }

    /// `LockProfileGuard` (daemon-side) reports this within ~15s of the profile disappearing;
    /// surfacing it here is what makes it visible. See GUARDIAN_SETUP.md §5.
    private var lockProfileWarningBanner: some View {
        warningBanner(
            icon: "exclamationmark.triangle.fill",
            title: "Lock profile not installed",
            message: "The DNS floor and removal tripwire from GUARDIAN_SETUP.md are missing. Run Scripts/install_lock_profile.command to set it up.",
            variant: .warning
        )
    }

    /// `VPNGuard` (daemon-side) reports `vpn_active` to the partner when this goes true; the banner
    /// is the on-machine half. A VPN routes around the DNS floor + hosts + pf entirely, so this is
    /// framed as the filter being *bypassed right now*, not a mere setup nag -- hence the error hue.
    private var vpnActiveWarningBanner: some View {
        warningBanner(
            icon: "network.badge.shield.half.filled",
            title: "VPN active — content filter bypassed",
            message: "Traffic is routing through a VPN tunnel, which sidesteps the DNS floor, hosts file, and firewall. The filter can't see or block anything while this is up. Your accountability partner has been notified.",
            variant: .error
        )
    }

    private func warningBanner(icon: String, title: String, message: String, variant: PillVariant) -> some View {
        let hue = variant.fg
        return HStack(alignment: .top, spacing: 10) {
            Image(systemName: icon).foregroundStyle(hue)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.system(size: 13, weight: .semibold))
                Text(message)
                    .font(.system(size: 11)).foregroundStyle(Otter.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer()
        }
        .padding(12)
        .background(variant.bg.opacity(0.7))
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .stroke(hue.opacity(0.3), lineWidth: 1)
        )
    }

    // MARK: - App pickers (unchanged behaviour)

    private func pickApp() {
        guard let (executableName, displayName, _) = pickAppBundle(prompt: "Block") else { return }
        viewModel.addApp(BlockedApp(displayName: displayName, executableName: executableName))
    }

    private func pickProtectedApp() {
        guard let (executableName, displayName, bundlePath) = pickAppBundle(prompt: "Protect") else { return }
        viewModel.addProtectedApp(ProtectedApp(displayName: displayName, executableName: executableName, bundlePath: bundlePath))
    }

    private func pickAppBundle(prompt: String) -> (executableName: String, displayName: String, bundlePath: String)? {
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [.application]
        panel.allowsMultipleSelection = false
        panel.canChooseDirectories = false
        panel.prompt = prompt
        guard panel.runModal() == .OK, let url = panel.url, let bundle = Bundle(url: url) else { return nil }

        let executableName = bundle.executableURL?.lastPathComponent
            ?? url.deletingPathExtension().lastPathComponent
        let displayName = (bundle.object(forInfoDictionaryKey: "CFBundleDisplayName") as? String)
            ?? (bundle.object(forInfoDictionaryKey: "CFBundleName") as? String)
            ?? url.deletingPathExtension().lastPathComponent
        return (executableName, displayName, url.path)
    }
}
