import AppKit
import SwiftUI
import FocusLockShared

struct ContentView: View {
    @StateObject private var viewModel = FocusLockViewModel()

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            header
            Divider()
            appsSection
            Divider()
            domainsSection
            Spacer(minLength: 0)
        }
        .padding(20)
        .frame(width: 440, height: 560)
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

    private var header: some View {
        HStack(spacing: 12) {
            Image(systemName: "lock.shield.fill")
                .font(.system(size: 28))
                .foregroundStyle(isBlocking ? .red : .green)
            VStack(alignment: .leading, spacing: 2) {
                Text("FocusLock").font(.title3).bold()
                if isBlocking {
                    Text("Blocking active - 24/7 until removed by the Guardian")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                } else {
                    Text("Nothing blocked")
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
