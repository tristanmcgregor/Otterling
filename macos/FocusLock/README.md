# Otterling (macOS)

An NSFW content filter for Intel Macs (built/tested on a Sequoia Hackintosh) that blocks adult
content system-wide 24/7 -- unconditionally, with no timer to wait out -- using a root-privileged
`LaunchDaemon` (internally still named `FocusLockHelperd`; see "Rebrand note" below) so the
blocking logic doesn't run as your own (revocable) user process. App/website blocking and app
protection (e.g. for an accountability app) are still here too, but content filtering is the
primary job.

There is no way for software alone to be un-bypassable by someone with admin rights on their own
Mac. Otterling supports two ways of dealing with that, and you can use either:

1. **Account split** (original model): a trusted person ("the Guardian") holds the one admin
   password, your day-to-day account is a Standard user, and the daemon checks the real uid of
   whoever calls it before honoring anything that removes a block. See
   [`GUARDIAN_SETUP.md`](GUARDIAN_SETUP.md) for that setup and its honest limits.
2. **Passcode** (no second account needed): you stay the only admin, and the daemon gates removals
   on a **Guardian passcode** you don't hold. Set it up with `otterlingctl set-passcode`.

Option 2 exists because on a single-admin machine the uid check in option 1 grants everything to
the very person it's meant to slow down. Be clear-eyed about what it buys: a local admin can always
`launchctl bootout` the daemon or delete the app, and the passcode doesn't stop that. What it
removes is the ability to drop a block **quietly** (the watchdog re-bootstraps the daemon and
`TamperReporter` files the event) — which is the failure mode self-imposed filtering actually runs
into. If nobody is on the other end of the tamper reports, this is a speed bump you've agreed to
respect, not a lock.

**Naming note**: the product is Otterling (matching the Android app's pivot to the same name). The
bundle / Mach-service / LaunchDaemon / profile identifiers are all under `app.otterling*` (e.g.
`app.otterling`, `app.otterling.helperd`, `app.otterling.watchdog`, `app.otterling.lockprofile`).
The internal Swift target and executable names (`FocusLock`, `FocusLockHelperd`, `FocusLockWatchdog`,
`otterlingctl`) are just code symbols and keep their historical spelling -- renaming them would be
churn with no user-facing effect, and the tamper policy matches on the *process* name `FocusLockHelperd`,
which is one of them. See [`Scripts/build_app.sh`](Scripts/build_app.sh).

## How it works

```
Otterling.app (GUI, runs as you) --XPC-->  FocusLockHelperd (daemon, runs as root)
otterlingctl (CLI, runs as you)  --XPC-->        |
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
  allowed and immediate. *Removing* one, or turning content filtering *off*, has to clear the
  **Guardian passcode** if one is set (otherwise it falls back to the `admin`-group check, so
  existing installs behave exactly as before until they opt in) -- a correct passcode applies the
  change immediately.
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
- There's no session or expiry: whatever's on the blocklist/protected list stays that way until a
  removal is authorised, and DNS enforcement defaults to **on** for a fresh install (an existing
  install upgrading from an older build keeps whatever it already had).
- **App updates**: `UpdateManager` checks an update manifest hourly (and on demand via the GUI or
  `otterlingctl check-update`/`install-update`) and, on a newer version, verifies SHA-256 + a
  pinned code-signing Team Identifier before installing -- same trust chain as the Android app's
  `ApprovedUpdateManager`. See [`RELEASE.md`](RELEASE.md) for publishing a release (a manual/local
  step for now -- no macOS build agent in the existing CI pipeline).
- **Dashboard-driven configuration**: `DashboardConfigSync` (in `FocusLockHelperd`) polls the
  guardian dashboard's `/dashboard-api/devices/<id>/settings` every ~60s and reconciles blocked
  apps, protected apps, DNS/proxy/cloud-filter enforcement, and the cloud filter host against it --
  the same web console the Android app already reads its own config from (see
  `filter-server/dashboard/`). Both additions/enables and removals/disables from the dashboard
  apply immediately -- removals are authorized by possession of the server's bearer token instead
  of the local passcode (see `DashboardConfigSync.reconcile`'s doc comment for why). The Guardian
  passcode itself is never dashboard-settable. A local-only change made via this GUI/CLI (never
  touched from the dashboard) is left alone by dashboard sync -- it only ever removes something it
  added itself.

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
installs `otterlingctl` to `/usr/local/bin`.

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
- `otterlingctl status` gives the same view from the terminal.
- Adding to the blocklist is always allowed from any account and takes effect immediately and
  permanently; removing an entry requires the Guardian passcode (or, if none is set, the Guardian
  admin account — see `GUARDIAN_SETUP.md`) and applies immediately once authorised.
- Set up the passcode gate with `otterlingctl set-passcode` (prompts; never takes the passcode as
  an argument, since `ps` can expose another process's argv).
- After completing `GUARDIAN_SETUP.md` steps 1-4, run `Scripts/install_lock_profile.command` once
  (while logged in as the Guardian) to set up the lock-profile tripwire -- see `GUARDIAN_SETUP.md`
  §6 for exactly what it does and doesn't protect against before relying on it.
- `otterlingctl check-update` / `otterlingctl install-update` (or the GUI's "App updates" section)
  check/install against whatever `RELEASE.md`'s publish process last published -- automatic hourly
  checks use the exact same path.
- The downloaded adult-domain hosts list is applied automatically and unconditionally -- there's
  nothing to add for baseline NSFW blocking. Point DNS enforcement at your own cloud filter server
  (see [`filter-server/README.md`](../../filter-server/README.md)) for stronger, always-current
  category filtering:

```bash
otterlingctl set-filter-host vpn.bartholomew.help
otterlingctl enable-dns
otterlingctl add-domain reddit.com
otterlingctl add-app "Steam" steam_osx
otterlingctl add-protected-app "Safari" Safari "/Applications/Safari.app"
otterlingctl status

# Close the single-admin hole: gate removals on a secret rather than on being admin.
otterlingctl set-passcode          # prompts for the new passcode twice

# Removals now require the passcode and apply immediately once authorised.
otterlingctl remove-domain reddit.com   # prompts for the passcode
```

For a protected app, `executableName` is the actual binary inside `Contents/MacOS/` (usually,
but not always, the same as the app's display name -- check with `ls "/Applications/Safari.app/Contents/MacOS/"`
if unsure), and `bundlePath` is the full path to the `.app` itself. The GUI's "+ Protect App..."
button fills both in for you from a file picker.

## Project layout

```
Package.swift
Sources/
  FocusLockShared/    Models, XPC protocol, constants, admin-group check, PasscodeHash
                       (PBKDF2-SHA256), XPC client, cloud filter reachability probe,
                       TamperReporter (shared by the daemon and the watchdog)
  FocusLockHelperd/   The daemon: state store, XPC listener (the gate lives in XPCService),
                       ImmediateActionApplier, enforcement loop, DNS/pf/hosts enforcers, adult
                       blocklist manager, LockProfileGuard, UpdateManager, UpdateCheckLoop
Tests/
  FocusLockSharedTests/  Passcode verification + the encode/decode split that keeps the digest on
                       disk but out of every getStatus reply
  FocusLockWatchdog/  Independent LaunchDaemon: re-bootstraps FocusLockHelperd if unloaded
  FocusLock/          SwiftUI GUI app
  otterlingctl/       CLI, same XPC surface as the GUI
Scripts/build_app.sh              Build + assemble + codesign (both LaunchDaemons)
Scripts/install_lock_profile.py   Provisions + hands off the lock profile to System Settings
Scripts/publish_release.sh        Packages a signed build into a release manifest + zip
GUARDIAN_SETUP.md      Account-split setup and its limits
RELEASE.md             Publishing an update -- signing identity, manifest, the CI gap
```

## Known limitations

- Not distributed/notarized -- it's signed with a free development certificate for personal use
  on your own machine(s), not for distribution to others.
- The lock profile / `LockProfileGuard` / `FocusLockWatchdog` (see above) are a detection layer,
  not a removal lock -- an admin account can always remove the profile (with their own password,
  not the `RemovalPasscode`) or unload both LaunchDaemons with `sudo launchctl bootout`. They make
  that get reported instead of silent; they don't and can't prevent it. See `GUARDIAN_SETUP.md` §6.
- **The passcode gate is enforced by the daemon, not by the OS.** It stops removals
  through the app, the CLI, and the XPC surface generally -- it does nothing about an admin with a
  terminal, who can unload both LaunchDaemons, delete `/Applications/Otterling.app`, or edit
  `/etc/hosts` directly, none of which goes through XPC at all. Combined with the watchdog and
  `TamperReporter` that becomes loud rather than impossible. **The tamper reports are the load-bearing
  part** -- `/alerts/tamper` fans out to ntfy push + the phone's `/alerts/poll` → SMS relay (see
  `filter-server/lockprofile_service.py`'s module docstring and `MacTamperPollWorker.kt`), so a
  report does reach the accountability partner, not just a log file.
- Passcode brute-forcing over XPC is bounded by PBKDF2 (210k iterations) plus an exponential
  backoff after three consecutive failures. The backoff is in-memory, so it resets if the daemon
  restarts -- restarting the daemon is itself watchdog-reported, but it does mean a short passcode
  is worth avoiding.
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
- **App updates have no CI automation** -- see `RELEASE.md`'s "Closing the CI gap": the existing
  AI-gated release host is Linux-only and can't build/sign a macOS `.app`, so publishing a release
  is a manual/local step (`Scripts/publish_release.sh` + copying the output to the update host
  yourself), not push-triggered like the Android app's releases. The on-device trust chain
  (SHA-256 + pinned code-signing Team ID, refuses to install with no pin configured) is real and
  independent of that gap -- it's the *publishing* automation that's missing, not the verification.
