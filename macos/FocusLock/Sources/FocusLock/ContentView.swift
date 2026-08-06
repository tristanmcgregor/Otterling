import AppKit
import SwiftUI
import FocusLockShared

struct ContentView: View {
    @StateObject private var viewModel = FocusLockViewModel()

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            header
            Divider()
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    appsSection
                    Divider()
                    domainsSection
                    Divider()
                    protectedAppsSection
                    Divider()
                    dnsSection
                }
            }
        }
        .padding(20)
        .frame(width: 440, height: 860)
        .onAppear { viewModel.startPolling() }
        .alert(
            "Action denied",
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

    private var isBlocking: Bool {
        !viewModel.state.blockedApps.isEmpty || !viewModel.state.blockedDomains.isEmpty
    }

    private var isProtecting: Bool {
        !viewModel.state.protectedApps.isEmpty
    }

    private var isEnforcingDNS: Bool {
        viewModel.state.dnsEnforcementEnabled
    }

    private var header: some View {
        HStack(spacing: 12) {
            Image(systemName: "lock.shield.fill")
                .font(.system(size: 28))
                .foregroundStyle((isBlocking || isProtecting || isEnforcingDNS) ? .red : .green)
            VStack(alignment: .leading, spacing: 2) {
                Text("Otterling").font(.title3).bold()
                if isEnforcingDNS {
                    Text("NSFW filter active - only the Guardian can undo this")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                } else if isBlocking || isProtecting {
                    Text("Active 24/7 - only the Guardian can undo this")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                } else {
                    Text("Content filter off")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }
            Spacer()
        }
    }

    private var appsSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("Blocked Apps").font(.headline)
                Spacer()
                Button("+ Add App...") { pickApp() }
            }
            if viewModel.state.blockedApps.isEmpty {
                Text("No apps blocked").font(.caption).foregroundStyle(.secondary)
            } else {
                List(viewModel.state.blockedApps) { app in
                    HStack {
                        Text(app.displayName)
                        Spacer()
                        Button {
                            viewModel.removeApp(app.executableName)
                        } label: {
                            Image(systemName: "minus.circle")
                        }
                        .buttonStyle(.plain)
                        .foregroundStyle(.red)
                    }
                }
                .frame(height: 130)
            }
        }
    }

    private var domainsSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Blocked Websites").font(.headline)
            HStack {
                TextField("example.com", text: $viewModel.newDomainText)
                    .onSubmit { viewModel.addDomain() }
                Button("Add") { viewModel.addDomain() }
            }
            if viewModel.state.blockedDomains.isEmpty {
                Text("No sites blocked").font(.caption).foregroundStyle(.secondary)
            } else {
                List(viewModel.state.blockedDomains, id: \.self) { domain in
                    HStack {
                        Text(domain)
                        Spacer()
                        Button {
                            viewModel.removeDomain(domain)
                        } label: {
                            Image(systemName: "minus.circle")
                        }
                        .buttonStyle(.plain)
                        .foregroundStyle(.red)
                    }
                }
                .frame(height: 130)
            }
        }
    }

    private var protectedAppsSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("Protected Apps").font(.headline)
                Spacer()
                Button("+ Protect App...") { pickProtectedApp() }
            }
            Text("Optional: keeps selected apps from being quit or deleted -- relaunched automatically and locked against removal. Not required for content filtering; useful for e.g. an accountability app you don't want to be able to get around.")
                .font(.caption)
                .foregroundStyle(.secondary)
            if viewModel.state.protectedApps.isEmpty {
                Text("No apps protected").font(.caption).foregroundStyle(.secondary)
            } else {
                List(viewModel.state.protectedApps) { app in
                    HStack {
                        Text(app.displayName)
                        Spacer()
                        Button {
                            viewModel.removeProtectedApp(app.executableName)
                        } label: {
                            Image(systemName: "minus.circle")
                        }
                        .buttonStyle(.plain)
                        .foregroundStyle(.red)
                    }
                }
                .frame(height: 130)
            }
        }
    }

    private var dnsSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Content filter (NSFW)").font(.headline)
            Text("Points system DNS at your own cloud filter server (Canopy-style AdGuard Home) as the primary content filter, and blocks alternate/DoH/DoT resolvers so it can't be sidestepped by switching DNS providers. Falls back to Cloudflare's filtered DNS if the cloud filter is off or its host can't be resolved. A downloaded local adult-domain hosts list is applied unconditionally either way, regardless of this toggle.")
                .font(.caption)
                .foregroundStyle(.secondary)
            HStack {
                if viewModel.state.dnsEnforcementEnabled {
                    Label("Enforced (cloud + local adult lists)", systemImage: "checkmark.shield.fill")
                        .foregroundStyle(.green)
                    Spacer()
                    Button("Disable...") { viewModel.disableDNSEnforcement() }
                        .foregroundStyle(.red)
                } else {
                    Label("Not enforced", systemImage: "shield.slash")
                        .foregroundStyle(.secondary)
                    Spacer()
                    Button("Enable") { viewModel.enableDNSEnforcement() }
                }
            }

            Divider()

            Toggle(isOn: Binding(
                get: { viewModel.state.cloudFilterEnabled },
                set: { viewModel.setCloudFilterEnabled($0) }
            )) {
                Text("Use cloud filter server").font(.subheadline)
            }
            HStack {
                TextField("bartholomew.help", text: $viewModel.cloudFilterHostText)
                    .onSubmit { viewModel.saveCloudFilterHost() }
                Button("Save") { viewModel.saveCloudFilterHost() }
            }
            HStack {
                Button("Test filter server") { viewModel.testCloudFilterReachability() }
                    .disabled(viewModel.cloudFilterTesting)
                if let result = viewModel.cloudFilterTestResult {
                    Text(result).font(.caption).foregroundStyle(.secondary)
                }
            }
        }
    }

    private func pickApp() {
        guard let (executableName, displayName, _) = pickAppBundle(prompt: "Block") else { return }
        let app = BlockedApp(displayName: displayName, executableName: executableName)
        viewModel.addApp(app)
    }

    private func pickProtectedApp() {
        guard let (executableName, displayName, bundlePath) = pickAppBundle(prompt: "Protect") else { return }
        let app = ProtectedApp(displayName: displayName, executableName: executableName, bundlePath: bundlePath)
        viewModel.addProtectedApp(app)
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
