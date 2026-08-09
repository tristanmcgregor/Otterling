# Otterling (macOS)

An NSFW content filter for Intel Macs (built/tested on a Sequoia Hackintosh) that blocks adult
content system-wide 24/7 -- unconditionally, with no timer to wait out -- using a root-privileged
`LaunchDaemon` (internally still named `FocusLockHelperd`; see "Rebrand note" below) so the
blocking logic doesn't run as your own (revocable) user process. App/website blocking and app
protection (e.g. for an accountability app) are still here too, but content filtering is the
primary job.

There is no way for software alone to be un-bypassable by someone with admin rights on their own
Mac. Otterling's actual tamper resistance comes from an account split: a trusted person ("the
Guardian") holds the one admin password on the machine, your day-to-day account is a Standard
user, and the daemon checks the real uid of whoever calls it before honoring anything that removes
a block. See [`GUARDIAN_SETUP.md`](GUARDIAN_SETUP.md) for that setup and its honest limits.

**Rebrand note**: the product is now called Otterling (matching the Android app's pivot to the
same name); the internal executable/bundle/Mach-service names (`FocusLock`, `FocusLockHelperd`,
`focuslockctl`, `au.com.tbmcgregor.bwparker.focuslock*`) were deliberately left unchanged so an
existing install isn't orphaned by the rename -- see [`Scripts/build_app.sh`](Scripts/build_app.sh).

## How it works

```
Otterling.app (GUI, runs as you) --XPC-->  FocusLockHelperd (daemon, runs as root)
focuslockctl (CLI, runs as you)  --XPC-->        |
                                                  +--> /etc/hosts (manual sites + downloaded adult-domain list)
                                                  +--> system DNS (cloud filter server, or Cloudflare Family fallback)
                                                  +--> pf anchor (blocks DoH/DoT bypass; allowlists the cloud filter host)
                                                  +--> kills blocked processes on sight
                                                  +--> root-owned state file (you can't edit it directly)
                                                  +--> LockProfileGuard: watches the lock profile,
                                                  |    reports removal to filter-server /alerts/tamper
                                                  +--> checked every ~20s by --> FocusLockWatchdog
                                                       (separate LaunchDaemon; re-bootstraps
                                                       FocusLockHelperd if it's ever unloaded,
                                                       reports the recovery)
```

A configuration profile (installed via `Scripts/install_lock_profile.command`, see
[`GUARDIAN_SETUP.md`](GUARDIAN_SETUP.md) §6) adds a tamper *tripwire*, not a removal lock: it moves
DNS onto a profile-managed encrypted resolver that can't be hand-edited via
`networksetup`/System Settings without removing the whole profile first, and
`LockProfileGuard`/`FocusLockWatchdog` report it if that profile or the daemon itself disappears.
It is **not** effective against a local admin account trying to remove it -- macOS honors an
admin's own password over the profile's `RemovalPasscode`. Read `GUARDIAN_SETUP.md` §6 before
assuming otherwise.

- **`FocusLockHelperd`** is a `LaunchDaemon` registered via `SMAppService`, so it starts at boot,
  restarts if killed, and owns the actual block state (`/Library/Application Support/FocusLock/state.json`,
  root-only, `0600`).
- Everything you (or anyone) can do goes through the daemon's XPC interface. The daemon itself
  decides whether to honor a call: adding a block (or turning content filtering *on*) is always
  allowed; only *removing* one, or turning content filtering *off*, requires the caller's account
  to be in the `admin` group.
- **Content filtering (NSFW), two layers**:
  1. **Local, always-on**: a downloaded adult-domain hosts list (`AdultBlocklistManager`, same two
     sources as the Android app -- StevenBlack porn-only + The Blocklist Project's porn list),
     merged with your manual site blocklist and redirected to `127.0.0.1` via `/etc/hosts`. Applied
     unconditionally, regardless of the DNS enforcement toggle below.
  2. **Cloud, primary, opt-in**: DNS enforcement points every network service's DNS at a
     configurable cloud filter server -- a Canopy-style AdGuard Home deployment, default
     `vpn.bartholomew.help`, see [`filter-server/README.md`](../../filter-server/README.md) at the repo
     root -- falling back to Cloudflare Family (`1.1.1.3`/`1.0.0.3`) if the cloud filter is off or
     its host can't currently be resolved.
  A narrow `pf` anchor blocks DNS-over-TLS and known public DoH resolver IPs (and explicitly
  allows the cloud filter host's own resolved IPs on :53) so a browser can't sidestep either layer
  with its own encrypted DNS.
- **App blocking** scans running processes every few seconds (via `libproc`) and kills anything
  matching a blocked executable name -- continuously, for as long as it's on the list.
- **App protection** (optional, not required for content filtering) is the inverse of app
  blocking, for apps you want to be unable to get around rather than unable to run (e.g. an
  accountability app's reporting): the daemon locks the app bundle with the filesystem-level
  `schg` (system-immutable) flag, which only root can set or clear -- a Standard account can't
  touch it even with `sudo`, since it has no admin password to give `sudo` in the first place --
  and relaunches the app within one enforcement tick if it's not running.
- There's no session or expiry: whatever's on the blocklist/protected list stays that way until
  the Guardian removes it, and DNS enforcement defaults to **on** for a fresh install (an existing
  install upgrading from an older build keeps whatever it already had).

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

This builds all four targets, assembles `/Applications/Otterling.app` (with the daemon's
`LaunchDaemon` plist embedded under `Contents/Library/LaunchDaemons`), code-signs everything, and
installs `focuslockctl` to `/usr/local/bin`.

First launch:

```bash
open /Applications/Otterling.app
```

macOS will show a notification that a background item was added. Approve it in
**System Settings > General > Login Items & Extensions** -- this is a one-time, per-machine
approval that `SMAppService` requires before the daemon can actually run. Relaunch the app
afterward; the daemon should show as running:

```bash
ps aux | grep FocusLockHelperd
```

## Using it

- Open `Otterling.app` to see status, configure the cloud filter server, and add/remove blocked
  apps and sites.
- `focuslockctl status` gives the same view from the terminal.
- Adding to the blocklist is always allowed from any account and takes effect immediately and
  permanently; removing an entry requires the Guardian admin account (see `GUARDIAN_SETUP.md`).
- After completing `GUARDIAN_SETUP.md` steps 1-4, run `Scripts/install_lock_profile.command` once
  (while logged in as the Guardian) to set up the lock-profile tripwire -- see `GUARDIAN_SETUP.md`
  §6 for exactly what it does and doesn't protect against before relying on it.
- The downloaded adult-domain hosts list is applied automatically and unconditionally -- there's
  nothing to add for baseline NSFW blocking. Point DNS enforcement at your own cloud filter server
  (see [`filter-server/README.md`](../../filter-server/README.md)) for stronger, always-current
  category filtering:

```bash
focuslockctl set-filter-host vpn.bartholomew.help
focuslockctl enable-dns
focuslockctl add-domain reddit.com
focuslockctl add-app "Steam" steam_osx
focuslockctl add-protected-app "Safari" Safari "/Applications/Safari.app"
focuslockctl status
```

For a protected app, `executableName` is the actual binary inside `Contents/MacOS/` (usually,
but not always, the same as the app's display name -- check with `ls "/Applications/Safari.app/Contents/MacOS/"`
if unsure), and `bundlePath` is the full path to the `.app` itself. The GUI's "+ Protect App..."
button fills both in for you from a file picker.

## Project layout

```
Package.swift
Sources/
  FocusLockShared/    Models, XPC protocol, constants, admin-group check, XPC client, cloud filter
                       reachability probe, TamperReporter (shared by the daemon and the watchdog)
  FocusLockHelperd/   The daemon: state store, XPC listener, enforcement loop, DNS/pf/hosts
                       enforcers, adult blocklist manager, LockProfileGuard
  FocusLockWatchdog/  Independent LaunchDaemon: re-bootstraps FocusLockHelperd if unloaded
  FocusLock/          SwiftUI GUI app
  focuslockctl/       CLI, same XPC surface as the GUI
Scripts/build_app.sh              Build + assemble + codesign (both LaunchDaemons)
Scripts/install_lock_profile.py   Provisions + hands off the lock profile to System Settings
GUARDIAN_SETUP.md      Account-split setup and its limits
```

## Known limitations

- Not distributed/notarized -- it's signed with a free development certificate for personal use
  on your own machine(s), not for distribution to others.
- The lock profile / `LockProfileGuard` / `FocusLockWatchdog` (see above) are a detection layer,
  not a removal lock -- an admin account can always remove the profile (with their own password,
  not the `RemovalPasscode`) or unload both LaunchDaemons with `sudo launchctl bootout`. They make
  that get reported instead of silent; they don't and can't prevent it. See `GUARDIAN_SETUP.md` §6.
- **No VPN lockdown** (unlike the Android app's always-on, locked-down `VpnFilterService`): macOS
  enforcement here is DNS + `/etc/hosts` + `pf`, all of which a VPN app can route around by sending
  its own DNS/traffic through an encrypted tunnel outside the system resolver entirely. `pf`'s
  DoH/DoT blocking is a best-effort secondary layer against browser-level bypass, not a general VPN
  block. Closing this gap would need a Network Extension (Packet Tunnel / Content Filter
  provider) to capture and filter all traffic system-wide the way the Android VPN does -- out of
  scope for this pass.
- App protection locks the bundle and keeps the process alive, but doesn't lock down the app's own
  granted permissions (Screen Recording, Accessibility, etc. in System Settings > Privacy &
  Security). Revoking those is a plain checkbox toggle for the app's own user and isn't something
  Otterling currently detects or restores -- a real gap if the protected app depends on them.
- Protecting a path under `/System` (or anywhere else SIP already governs) doesn't get the `schg`
  lock -- `chflags` fails there with "Read-only file system" -- but SIP already prevents deletion
  on those paths anyway, so it's a non-issue in practice. This feature is meant for
  user-installed apps in `/Applications`.
- See `GUARDIAN_SETUP.md` for the deeper caveats (Recovery Mode, SIP, physical access) that no
  software running under an admin-controlled OS can fully close.
