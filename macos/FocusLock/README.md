# FocusLock

A self-control app for Intel Macs (built/tested on a Sequoia Hackintosh) that blocks apps and
websites 24/7 -- unconditionally, with no timer to wait out -- using a root-privileged
`LaunchDaemon` so the blocking logic doesn't run as your own (revocable) user process.

There is no way for software alone to be un-bypassable by someone with admin rights on their own
Mac. FocusLock's actual tamper resistance comes from an account split: a trusted person ("the
Guardian") holds the one admin password on the machine, your day-to-day account is a Standard
user, and the daemon checks the real uid of whoever calls it before honoring anything that removes
a block or ends a session early. See [`GUARDIAN_SETUP.md`](GUARDIAN_SETUP.md) for that setup and
its honest limits.

## How it works

```
FocusLock.app (GUI, runs as you)  --XPC-->  FocusLockHelperd (daemon, runs as root)
focuslockctl (CLI, runs as you)   --XPC-->        |
                                                   +--> /etc/hosts (site blocking)
                                                   +--> pf anchor (blocks DoH/DoT bypass)
                                                   +--> kills blocked processes on sight
                                                   +--> root-owned state file (you can't edit it directly)
```

- **`FocusLockHelperd`** is a `LaunchDaemon` registered via `SMAppService`, so it starts at boot,
  restarts if killed, and owns the actual block state (`/Library/Application Support/FocusLock/state.json`,
  root-only, `0600`).
- Everything you (or anyone) can do goes through the daemon's XPC interface. The daemon itself
  decides whether to honor a call: adding a block is always allowed; only *removing* one requires
  the caller's account to be in the `admin` group.
- **App blocking** scans running processes every few seconds (via `libproc`) and kills anything
  matching a blocked executable name -- continuously, for as long as it's on the list.
- **Site blocking** redirects blocked domains to `127.0.0.1` via `/etc/hosts`, plus a narrow `pf`
  anchor that blocks DNS-over-TLS and known public DoH resolver IPs so a browser can't sidestep
  the hosts redirect with its own encrypted DNS.
- **App protection** is the inverse of app blocking, for apps you want to be unable to get around
  rather than unable to run (e.g. an accountability app's reporting): the daemon locks the app
  bundle with the filesystem-level `schg` (system-immutable) flag, which only root can set or
  clear -- a Standard account can't touch it even with `sudo`, since it has no admin password to
  give `sudo` in the first place -- and relaunches the app within one enforcement tick if it's not
  running.
- There's no session or expiry: whatever's on the blocklist/protected list stays that way until
  the Guardian removes it.

## Requirements

- macOS 13+ (Ventura or later), Intel or Apple Silicon.
- Xcode (any version supporting your macOS release) signed in with your Apple ID, so you have a
  free "Apple Development" code-signing certificate. A paid Apple Developer Program membership is
  **not** required for this.
- Swift 5.10 toolchain (comes with Xcode / Command Line Tools).

## Building and installing

```bash
cd macos/FocusLock
security find-identity -v -p codesigning   # find your "Apple Development: ..." identity
./Scripts/build_app.sh "Apple Development: Your Name (TEAMID)"
```

This builds all four targets, assembles `/Applications/FocusLock.app` (with the daemon's
`LaunchDaemon` plist embedded under `Contents/Library/LaunchDaemons`), code-signs everything, and
installs `focuslockctl` to `/usr/local/bin`.

First launch:

```bash
open /Applications/FocusLock.app
```

macOS will show a notification that a background item was added. Approve it in
**System Settings > General > Login Items & Extensions** -- this is a one-time, per-machine
approval that `SMAppService` requires before the daemon can actually run. Relaunch the app
afterward; the daemon should show as running:

```bash
ps aux | grep FocusLockHelperd
```

## Using it

- Open `FocusLock.app` to see status and add/remove blocked apps and sites.
- `focuslockctl status` gives the same view from the terminal.
- Adding to the blocklist is always allowed from any account and takes effect immediately and
  permanently; removing an entry requires the Guardian admin account (see `GUARDIAN_SETUP.md`).

```bash
focuslockctl add-domain reddit.com
focuslockctl add-app "Steam" steam_osx
focuslockctl add-protected-app "Accountable2You" Accountable2You "/Applications/Accountable2You.app"
focuslockctl status
```

For a protected app, `executableName` is the actual binary inside `Contents/MacOS/` (usually,
but not always, the same as the app's display name -- check with `ls "/Applications/Accountable2You.app/Contents/MacOS/"`
if unsure), and `bundlePath` is the full path to the `.app` itself. The GUI's "+ Protect App..."
button fills both in for you from a file picker.

## Project layout

```
Package.swift
Sources/
  FocusLockShared/   Models, XPC protocol, constants, admin-group check, XPC client
  FocusLockHelperd/  The daemon: state store, XPC listener, enforcement loop
  FocusLock/         SwiftUI GUI app
  focuslockctl/       CLI, same XPC surface as the GUI
Scripts/build_app.sh  Build + assemble + codesign
GUARDIAN_SETUP.md      Account-split setup and its limits
```

## Known limitations

- Not distributed/notarized -- it's signed with a free development certificate for personal use
  on your own machine(s), not for distribution to others.
- The `pf` DoH/DoT blocking is a best-effort secondary layer, not exhaustive; a determined bypass
  via a VPN or a non-standard resolver port isn't specifically covered.
- App protection locks the bundle and keeps the process alive, but doesn't lock down the app's own
  granted permissions (Screen Recording, Accessibility, etc. in System Settings > Privacy &
  Security). Revoking those is a plain checkbox toggle for the app's own user and isn't something
  FocusLock currently detects or restores -- a real gap if the protected app depends on them.
- Protecting a path under `/System` (or anywhere else SIP already governs) doesn't get the `schg`
  lock -- `chflags` fails there with "Read-only file system" -- but SIP already prevents deletion
  on those paths anyway, so it's a non-issue in practice. This feature is meant for
  user-installed apps in `/Applications`.
- See `GUARDIAN_SETUP.md` for the deeper caveats (Recovery Mode, SIP, physical access) that no
  software running under an admin-controlled OS can fully close.
