import AppKit
import ApplicationServices
import FocusLockShared
import Foundation

/// FocusLockScanner -- the macOS equivalent of the phone's `FocusGuardAccessibilityService`
/// trigger-word scan. Runs as a per-user LaunchAgent (Accessibility only works inside a GUI login
/// session, and TCC Accessibility trust is per-user), walks the frontmost *browser* window's
/// accessibility tree every `scannerScanInterval` seconds, builds a lower-cased haystack of the
/// on-screen text (including the address-bar URL, which is just another text node in the tree), and
/// reports a `trigger_word_detected` event via `TamperReporter` when a word from the shared
/// `TriggerWords` list appears. Report-only, exactly like the phone -- it never blocks or closes
/// anything; the DNS/proxy content filter does the blocking.
///
/// Requires Accessibility permission (System Settings > Privacy & Security > Accessibility). Without
/// it, every cross-app AX read fails and scanning is a no-op; the scanner keeps re-checking so it
/// starts working the moment the Guardian grants it, no restart needed.
enum Scanner {
    /// Cap on nodes visited per scan -- mirrors the phone's `maxNodes` (180) reasoning: bound the
    /// walk so a huge DOM can't turn one tick into a multi-second tree crawl. A little higher than
    /// the phone because a desktop browser window legitimately has more chrome/nodes.
    static let maxNodes = 600

    /// Matches the phone's per-word+context debounce (GuardianAlertSettings.DEBOUNCE_MS, 10 min):
    /// don't re-report the same word in the same app while someone sits on the page.
    static let dedupInterval: TimeInterval = 10 * 60
    static var lastReported: [String: Date] = [:]

    /// So the "grant Accessibility" prompt is shown at most once per launch, not every tick.
    static var promptedForTrust = false

    static func tick() {
        guard ensureTrusted() else { return }
        guard let app = NSWorkspace.shared.frontmostApplication,
              let bundleID = app.bundleIdentifier,
              FocusLockConstants.browserBundleIdentifiers.contains(bundleID) else { return }

        let appElement = AXUIElementCreateApplication(app.processIdentifier)
        guard let window = focusedWindow(of: appElement) else { return }

        var texts: [String] = []
        collectText(from: window, into: &texts)
        guard !texts.isEmpty else { return }

        let haystack = texts.joined(separator: " ").lowercased()
        guard let word = TriggerWords.firstMatch(in: haystack) else { return }

        let appName = app.localizedName ?? bundleID
        report(word: word, appName: appName, bundleID: bundleID)
    }

    /// True once the process is Accessibility-trusted. Prompts the user exactly once per launch if
    /// not (the system shows its own "open System Settings" dialog); returns false meanwhile.
    private static func ensureTrusted() -> Bool {
        if AXIsProcessTrusted() { return true }
        if !promptedForTrust {
            promptedForTrust = true
            let options = [kAXTrustedCheckOptionPrompt.takeUnretainedValue(): true] as CFDictionary
            _ = AXIsProcessTrustedWithOptions(options)
            FileHandle.standardError.write(
                "[scanner] not Accessibility-trusted yet -- prompted; grant it in System Settings > Privacy & Security > Accessibility\n"
                    .data(using: .utf8)!
            )
        }
        return false
    }

    private static func focusedWindow(of appElement: AXUIElement) -> AXUIElement? {
        if let window = copyAttribute(appElement, kAXFocusedWindowAttribute) {
            return (window as! AXUIElement)
        }
        // Fall back to the main window if there's no explicitly focused one.
        if let window = copyAttribute(appElement, kAXMainWindowAttribute) {
            return (window as! AXUIElement)
        }
        return nil
    }

    /// Bounded breadth-first walk of the AX subtree, pulling any string-valued value/title/
    /// description off each node -- the desktop analogue of the phone's `collectNodeInfo` BFS.
    private static func collectText(from root: AXUIElement, into texts: inout [String]) {
        var queue: [AXUIElement] = [root]
        var visited = 0
        while !queue.isEmpty, visited < maxNodes {
            let node = queue.removeFirst()
            visited += 1

            for attribute in [kAXValueAttribute, kAXTitleAttribute, kAXDescriptionAttribute] {
                if let string = copyAttribute(node, attribute) as? String,
                   !string.isEmpty {
                    texts.append(string)
                }
            }

            if let children = copyAttribute(node, kAXChildrenAttribute) as? [AXUIElement] {
                queue.append(contentsOf: children)
            }
        }
    }

    private static func copyAttribute(_ element: AXUIElement, _ attribute: String) -> AnyObject? {
        var value: AnyObject?
        let error = AXUIElementCopyAttributeValue(element, attribute as CFString, &value)
        return error == .success ? value : nil
    }

    private static func report(word: String, appName: String, bundleID: String) {
        let key = "\(word)|\(bundleID)"
        let now = Date()
        if let last = lastReported[key], now.timeIntervalSince(last) < dedupInterval { return }
        lastReported[key] = now

        FileHandle.standardError.write("[scanner] trigger word '\(word)' seen in \(appName)\n".data(using: .utf8)!)
        TamperReporter.report(
            type: "trigger_word_detected",
            details: "\"\(word)\" seen in \(appName)"
        )
    }
}

// A LaunchAgent process needs a live run loop for URLSession callbacks (TamperReporter) and the
// scan timer. RunAtLoad + KeepAlive in the plist keeps it running; this just paces the scans.
let timer = Timer.scheduledTimer(withTimeInterval: FocusLockConstants.scannerScanInterval, repeats: true) { _ in
    Scanner.tick()
}
timer.tolerance = 0.5
RunLoop.main.add(timer, forMode: .common)
FileHandle.standardError.write("[scanner] started; scanning every \(FocusLockConstants.scannerScanInterval)s\n".data(using: .utf8)!)
RunLoop.main.run()
