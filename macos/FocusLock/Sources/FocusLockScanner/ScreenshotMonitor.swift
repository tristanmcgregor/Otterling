import AppKit
import CoreGraphics
import FocusLockShared
import Foundation

/// The Mac equivalent of the phone's screenshot-based NSFW filter (the `visualFilterEnabled` loop
/// in `FocusGuardAccessibilityService.kt` + `ScreenshotUploader.kt`): periodically captures
/// whatever's on screen, uploads it to filter-server's `/screenshot-classify` (same route the
/// phone already uses -- see `ScreenshotUploader.swift`), and on an "nsfw" verdict force-quits the
/// offending app and keeps re-quitting it for a short window if it's relaunched.
///
/// Lives in `FocusLockScanner`, not `FocusLockHelperd`, for the same reason the trigger-word scan
/// does (see that file's doc comment): Screen Recording, like Accessibility, is TCC-granted
/// per-user and only usable inside a GUI login session -- a root LaunchDaemon has no session to
/// capture from and can never hold this permission at all.
///
/// The temporary block is enforced entirely in-process (an in-memory `[bundleID: Date]` map,
/// re-checked every scanner tick), not via XPC into the root daemon's persistent `blockedApps`
/// list -- `NSRunningApplication.forceTerminate()` only needs the same-user privilege this
/// per-user agent already has, and `FocusLockScanner` is a `KeepAlive` LaunchAgent expected to run
/// continuously, so this mirrors the phone's `AppSuspensionManager.blockTemporarily` closely enough
/// without adding new XPC surface for a short-lived, best-effort block. A scanner restart clears
/// it, same as `Scanner.lastReported` above has no persistence either.
///
/// Respects the guardian's `visualFilterEnabled`/`visualFilterIntervalSeconds` dashboard settings
/// via `DashboardVisualFilterSettings` -- the same server-side kill switch/interval the phone's
/// `captureScreenshotIfAllowed`/`visualFilterIntervalMillis` already honor.
enum ScreenshotMonitor {
    // Guards the three fields below: `tick()` (main run loop, via the scanner's Timer) and
    // ScreenshotUploader's URLSession completion handler (an arbitrary background queue) both
    // read/write them.
    private static let lock = NSLock()
    private static var lastCaptureAt = Date.distantPast
    private static var blockedUntil: [String: Date] = [:]
    private static var promptedForScreenRecording = false
    /// Consecutive "nsfw" verdicts per bundle ID since the last non-nsfw verdict (or app switch)
    /// -- mirrors the phone's `nsfwStreaks` in `FocusGuardAccessibilityService.kt`.
    private static var nsfwStreaks: [String: Int] = [:]

    static func tick() {
        enforceActiveBlocks()

        let settings = DashboardVisualFilterSettings.current()
        guard settings.enabled else { return }
        guard hasScreenRecordingAccess() else { return }

        lock.lock()
        let dueForCapture = Date().timeIntervalSince(lastCaptureAt) >= settings.intervalSeconds
        lock.unlock()
        guard dueForCapture else { return }

        guard let app = NSWorkspace.shared.frontmostApplication,
              let bundleID = app.bundleIdentifier,
              !FocusLockConstants.screenshotSkipBundleIdentifiers.contains(bundleID) else { return }

        lock.lock()
        lastCaptureAt = Date()
        // A streak only counts consecutive nsfw hits while the same app stays frontmost --
        // switching away (even briefly) starts the count fresh next time it's reopened. Only the
        // bundle ID that was just captured can have an in-progress streak (this loop only ever
        // targets the current frontmost app), so any switch clears it -- mirrors the phone's
        // onForegroundChanged clearing nsfwStreaks.
        if nsfwStreaks[bundleID] == nil {
            nsfwStreaks.removeAll()
        }
        lock.unlock()
        guard let imageData = captureScreenJPEG() else { return }
        classify(imageData: imageData, bundleID: bundleID, appName: app.localizedName ?? bundleID)
    }

    /// Re-checked every tick (cheap -- no capture, no network) so an app force-quit for an NSFW hit
    /// that gets relaunched within the block window is quit again immediately, rather than only at
    /// the next capture interval.
    private static func enforceActiveBlocks() {
        let now = Date()
        lock.lock()
        blockedUntil = blockedUntil.filter { $0.value > now }
        let currentlyBlocked = blockedUntil
        lock.unlock()
        guard let app = NSWorkspace.shared.frontmostApplication,
              let bundleID = app.bundleIdentifier,
              currentlyBlocked[bundleID] != nil else { return }
        app.forceTerminate()
    }

    /// True once Screen-Recording-trusted. Prompts at most once per launch, matching `Scanner`'s
    /// `ensureTrusted()` pattern for Accessibility -- keeps re-checking so this starts working the
    /// moment the Guardian grants it, no restart needed.
    private static func hasScreenRecordingAccess() -> Bool {
        if CGPreflightScreenCaptureAccess() { return true }
        if !promptedForScreenRecording {
            promptedForScreenRecording = true
            _ = CGRequestScreenCaptureAccess()
            FileHandle.standardError.write(
                "[scanner] not Screen-Recording-trusted yet -- prompted; grant it in System Settings > Privacy & Security > Screen Recording\n"
                    .data(using: .utf8)!
            )
        }
        return false
    }

    /// Whole on-screen content, not one isolated app surface -- `CGWindowListCreateImage` keyed to
    /// a single window still needs a window-list walk to find that window's current bounds, and the
    /// phone side already classifies "whatever's on screen" for the foreground app rather than
    /// isolating one surface, so this keeps both platforms' semantics aligned. Downscaled/
    /// JPEG-compressed the same way `FocusGuardAccessibilityService.downscale` is on the phone.
    private static func captureScreenJPEG() -> Data? {
        guard let cgImage = CGWindowListCreateImage(
            .null, .optionOnScreenOnly, kCGNullWindowID, [.boundsIgnoreFraming, .bestResolution]
        ) else {
            return nil
        }
        let rep = NSBitmapImageRep(cgImage: cgImage)
        let maxDimension = FocusLockConstants.screenshotMaxDimension
        let largestSide = Double(max(cgImage.width, cgImage.height))
        let scale = largestSide > maxDimension ? maxDimension / largestSide : 1.0
        let finalRep: NSBitmapImageRep
        if scale < 1.0 {
            let targetSize = NSSize(width: Double(cgImage.width) * scale, height: Double(cgImage.height) * scale)
            finalRep = resize(rep, to: targetSize) ?? rep
        } else {
            finalRep = rep
        }
        return finalRep.representation(
            using: .jpeg,
            properties: [.compressionFactor: FocusLockConstants.screenshotJPEGCompressionFactor]
        )
    }

    private static func resize(_ source: NSBitmapImageRep, to size: NSSize) -> NSBitmapImageRep? {
        guard size.width >= 1, size.height >= 1,
              let target = NSBitmapImageRep(
                  bitmapDataPlanes: nil, pixelsWide: Int(size.width), pixelsHigh: Int(size.height),
                  bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
                  colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0
              ) else { return nil }
        target.size = size

        NSGraphicsContext.saveGraphicsState()
        defer { NSGraphicsContext.restoreGraphicsState() }
        guard let context = NSGraphicsContext(bitmapImageRep: target) else { return nil }
        NSGraphicsContext.current = context
        source.draw(in: NSRect(origin: .zero, size: size))
        return target
    }

    private static func classify(imageData: Data, bundleID: String, appName: String) {
        guard let deviceID = TamperReporter.deviceID() else { return }
        ScreenshotUploader.upload(deviceID: deviceID, packageName: bundleID, imageData: imageData) { result in
            guard case .success(let value) = result, value.classification == "nsfw" else {
                lock.lock()
                nsfwStreaks.removeValue(forKey: bundleID)
                lock.unlock()
                return
            }

            lock.lock()
            let streak = (nsfwStreaks[bundleID] ?? 0) + 1
            nsfwStreaks[bundleID] = streak
            lock.unlock()
            guard streak >= FocusLockConstants.nsfwBlockTriggerCount else {
                FileHandle.standardError.write("[scanner] NSFW screenshot flagged in \(appName) (\(streak)/\(FocusLockConstants.nsfwBlockTriggerCount)); not blocking yet\n".data(using: .utf8)!)
                return
            }
            lock.lock()
            nsfwStreaks.removeValue(forKey: bundleID)
            lock.unlock()

            // Prefer the server-computed deadline (keeps the duration dashboard-tunable without a
            // rebuild, same reasoning as the phone's handleCapturedScreenshot) over the hardcoded
            // default; clamp for phone/Mac/server clock skew, mirroring MIN_NSFW_BLOCK_MILLIS.
            let blockSeconds = value.blockUntilMillis
                .map { max(FocusLockConstants.minNsfwBlockSeconds, $0 / 1000.0 - Date().timeIntervalSince1970) }
                ?? FocusLockConstants.defaultNsfwBlockSeconds
            lock.lock()
            blockedUntil[bundleID] = Date().addingTimeInterval(blockSeconds)
            lock.unlock()

            if let app = NSWorkspace.shared.runningApplications.first(where: { $0.bundleIdentifier == bundleID }) {
                app.forceTerminate()
            }

            let minutes = Int((blockSeconds / 60).rounded())
            FileHandle.standardError.write("[scanner] NSFW content detected \(FocusLockConstants.nsfwBlockTriggerCount) times in a row in \(appName); blocked for \(minutes) minutes\n".data(using: .utf8)!)
            TamperReporter.report(
                type: "NSFW_SCREENSHOT_DETECTED",
                details: "NSFW content detected \(FocusLockConstants.nsfwBlockTriggerCount) times in a row in \(appName); blocked for \(minutes) minutes"
            )
        }
    }
}
