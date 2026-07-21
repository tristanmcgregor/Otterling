import AppKit
import SwiftUI
import FocusLockShared

struct ContentView: View {
    @StateObject private var viewModel = FocusLockViewModel()
    @State private var customHours: String = "1"

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            header
            Divider()
            sessionSection
            Divider()
            appsSection
            Divider()
            domainsSection
            Spacer(minLength: 0)
        }
        .padding(20)
        .frame(width: 440, height: 620)
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

    private var header: some View {
        HStack(spacing: 12) {
            Image(systemName: "lock.shield.fill")
                .font(.system(size: 28))
                .foregroundStyle(viewModel.state.isSessionActive ? .red : .green)
            VStack(alignment: .leading, spacing: 2) {
                Text("FocusLock").font(.title3).bold()
                if viewModel.state.isSessionActive {
                    Text("Blocking active - \(formattedRemaining) left")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                } else {
                    Text("Not blocking")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }
            Spacer()
        }
    }

    private var formattedRemaining: String {
        let seconds = Int(viewModel.state.remainingSeconds)
        let h = seconds / 3600
        let m = (seconds % 3600) / 60
        let s = seconds % 60
        return h > 0 ? String(format: "%dh %02dm", h, m) : String(format: "%dm %02ds", m, s)
    }

    private var sessionSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Session").font(.headline)
            HStack {
                Button("Start 1h") { viewModel.startSession(seconds: 3600) }
                Button("Start 4h") { viewModel.startSession(seconds: 4 * 3600) }
                Button("Start 8h") { viewModel.startSession(seconds: 8 * 3600) }
                Spacer()
            }
            HStack {
                TextField("Hours", text: $customHours)
                    .frame(width: 50)
                Button("Start / Extend") {
                    if let hours = Double(customHours), hours > 0 {
                        viewModel.startSession(seconds: hours * 3600)
                    }
                }
                Spacer()
                Button("End Session Early") { viewModel.endSessionEarly() }
                    .foregroundStyle(.red)
            }
            Text("Starting/extending is always allowed. Ending early requires the Guardian admin account.")
                .font(.caption)
                .foregroundStyle(.secondary)
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

    private func pickApp() {
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [.application]
        panel.allowsMultipleSelection = false
        panel.canChooseDirectories = false
        panel.prompt = "Block"
        guard panel.runModal() == .OK, let url = panel.url, let bundle = Bundle(url: url) else { return }

        let executableName = bundle.executableURL?.lastPathComponent
            ?? url.deletingPathExtension().lastPathComponent
        let displayName = (bundle.object(forInfoDictionaryKey: "CFBundleDisplayName") as? String)
            ?? (bundle.object(forInfoDictionaryKey: "CFBundleName") as? String)
            ?? url.deletingPathExtension().lastPathComponent

        let app = BlockedApp(displayName: displayName, executableName: executableName, bundleIdentifier: bundle.bundleIdentifier)
        viewModel.addApp(app)
    }
}
