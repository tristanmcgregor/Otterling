# Releasing an Otterling macOS update

Mirrors the Android app's update model (manifest + SHA-256 + pinned signing identity, see
`filter-server/updates/README.md` and `app/src/main/java/app/otterling/updates/
ApprovedUpdateManager.kt`) as closely as macOS allows. The one real difference, and the reason this
doc exists separately: **there is no automated build-and-publish pipeline for macOS yet.** The
existing AI-gated release host (`filter-server/SELF_LOCKOUT.md`) is Linux-only -- Android SDK, no
Xcode, no `codesign` -- and structurally cannot build or sign a `.app` bundle. Every release
described here is a manual, local process until that changes (see "Closing the CI gap" below).

## One-time setup

This project uses the **free "Apple Development" identity** (the same one `README.md`'s normal
build instructions already use) for releases too, not a paid Developer ID -- a deliberate choice
for a personal, single-family setup, made with the trade-off below understood:

- The actual trust check (SHA-256 + pinned Team Identifier) works exactly the same either way -- a
  free "Apple Development" identity has a perfectly stable Team ID too (Xcode's free "Personal
  Team"), so this is not a weaker check.
- `UpdateManager` downloads over `URLSession`, which -- unlike a browser -- doesn't set the
  `com.apple.quarantine` attribute Gatekeeper's signature/notarization assessment keys off, and
  launchd starting its own `Program` historically hasn't gone through the same check a Finder
  double-click does. So this update path most likely just works with a free identity.
- The trade-off, stated honestly: "Developer ID Application" is the certificate class Apple
  actually built for software downloaded and run outside Xcode/the App Store (only paying Developer
  Program members can get one). Using "Apple Development" instead means depending on "Gatekeeper
  doesn't happen to check this particular code path" rather than a guarantee -- Apple has tightened
  this exact behavior before (more permissive on Mojave, less so from Catalina on) and could again.
  If a future macOS update ever starts blocking this, switching to a paid Developer ID identity
  (see "Closing the CI gap" below) is the fix, not a code change here.

`publish_release.sh` only warns, doesn't block, when the signing identity isn't Developer ID --
pass `ALLOW_NON_DEVELOPER_ID=1` to proceed (see "Per-release" below). It still hard-refuses a
genuinely ad-hoc/unsigned build (no Team Identifier at all) and any build whose Team ID doesn't
match your pinned value.

**Pin your Team ID**: find it with `security find-identity -v -p codesigning` (the parenthesized
suffix after your "Apple Development" certificate's name -- Xcode's free "Personal Team" assigns
one same as a paid account) or in Keychain Access under the certificate's Organizational Unit
field, then set `FocusLockConstants.pinnedUpdateTeamID` in `Sources/FocusLockShared/Constants.swift`
to it, and rebuild every install that should trust future updates. **This is the actual root of
trust** -- see that constant's doc comment and `UpdateManager.swift`'s. Left empty, `UpdateManager`
refuses to install anything at all (fail closed), matching Android's stance for a build with no
`RELEASE_CERT_SHA256`.

## Per-release

```bash
cd macos/FocusLock
./Scripts/build_app.sh "Apple Development: Your Name (TEAMID)"
ALLOW_NON_DEVELOPER_ID=1 ./Scripts/publish_release.sh <versionCode> <versionName>   # e.g. 2 "0.2"
```

`publish_release.sh`:
- Refuses to run if the built app's Team Identifier doesn't match `pinnedUpdateTeamID` -- catches
  signing with the wrong identity (or forgetting to update the pin) before it goes anywhere near a
  server, since a published build no installed copy will ever trust is a wasted release.
- Zips the app (`ditto`, preserving resource forks/attributes) and computes its SHA-256.
- Writes `macos-manifest.json` + the zip to `macos/FocusLock/.release/`.

Then, manually (this script has no access to your server and doesn't attempt it):

1. Edit the written `macos-manifest.json`: replace `${UPDATE_HOST}` with your real host (matching
   `filter-server/.env`'s `UPDATE_HOST`, e.g. `vpn.bartholomew.help`).
2. Copy both files onto the update host's `/var/lib/otterling/updates/` (however you already manage
   that host -- `scp`/`rsync`/etc.).

Caddy already serves that whole directory at `https://<host>/updates/` (same block that serves
Android's `manifest.json`) -- nothing else to configure. Existing installs pick up the new version
on their next hourly automatic check, or immediately via `focuslockctl check-update` /
the GUI's "Check for update" button, followed by "Install update" (or automatically, on the hourly
check -- see below).

## What happens on the Mac when an update installs

`UpdateManager` (daemon-side, `Sources/FocusLockHelperd/UpdateManager.swift`) verifies, in order:
SHA-256 of the download, then the extracted bundle's code signature
(`codesign --verify --deep --strict`) **and** that its Team Identifier matches the locally pinned
`pinnedUpdateTeamID` -- both required. Only then does it atomically swap the new bundle into
`/Applications/Otterling.app`, restart the watchdog LaunchDaemon (a separate job, needs telling
explicitly) so it picks up its own new binary, and exit -- `KeepAlive=true` relaunches the daemon
itself immediately from the freshly-installed binary.

The GUI app is not force-relaunched (there's no clean way to make a live foreground SwiftUI process
replace itself mid-run) -- after a successful install its status just reads "restarting the filter
daemon now"; quit and reopen Otterling.app to pick up the new GUI binary too. This is intentionally
different from Android's fully silent APK swap, which the OS handles for a background app; nothing
analogous exists for a running foreground macOS app.

Automatic checks run hourly (`UpdateCheckLoop`, daemon-side) and install silently (same code path
as a manual "Install update" click) -- the only user-visible effect of a fully automatic
install is the daemon/watchdog restarting (a few seconds of no active DNS/pf reassertion; the
*existing* hosts/DNS/pf state isn't touched by the restart, just not *re-applied* until the new
process's first tick).

## Closing the CI gap

To get real parity with Android (AI-review-gated, fully automated build-and-publish on push), this
pipeline would need:

- **A macOS build agent** added to the release flow -- either a GitHub Actions `macos-latest`
  runner, or a real Mac added as a self-hosted runner. Recall `.github/workflows/*` is currently
  policy-restricted to advisory-only (see `filter-server/SELF_LOCKOUT.md`, "What git must not do")
  -- actual signing/publishing happens only on the AI-gated self-hosted host, which is Linux. A
  macOS runner would need to plug into that same gate (build on the Mac runner, but still require
  the same cumulative AI review before anything gets signed/published), not bypass it.
- **CI-embeddable code-signing**: the Developer ID identity + private key would need to live in CI
  secrets (e.g. a `.p12` export, imported into a temporary keychain per build) rather than an
  interactively-selected identity in a developer's login keychain, which is what `build_app.sh`
  assumes today.
- Likely **notarization** (`xcrun notarytool submit ... --wait` + stapling) so Gatekeeper doesn't
  warn on a downloaded, non-App-Store build -- not required for `UpdateManager`'s own trust chain
  (SHA-256 + Team ID already cover that), but relevant to the human clicking through the install.

None of that is set up here -- it's a real, separate infrastructure decision (get a Mac CI runner,
manage a second set of signing secrets), not something to start blind as part of this pass.
