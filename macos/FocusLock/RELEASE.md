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
to it, and rebuild every install that should trust future updates. **This is one of two roots of
trust** -- see that constant's doc comment and `UpdateManager.swift`'s. Left empty, `UpdateManager`
refuses to install anything at all (fail closed), matching Android's stance for a build with no
`RELEASE_CERT_SHA256`.

**Generate the review-attestation keypair** (the other root of trust -- see below): on the AI-review
host (`/var/lib/otterling/ci`, root-owned), one time only:

```bash
sudo openssl genpkey -algorithm ed25519 -out /var/lib/otterling/ci/secrets/macos_review_attestation_ed25519
sudo chmod 600 /var/lib/otterling/ci/secrets/macos_review_attestation_ed25519
```

Then extract the raw public key as base64 (what you'll paste into
`FocusLockConstants.pinnedReviewAttestationPublicKey`):

```bash
sudo python3 -c '
from cryptography.hazmat.primitives import serialization
import base64
with open("/var/lib/otterling/ci/secrets/macos_review_attestation_ed25519", "rb") as f:
    priv = serialization.load_pem_private_key(f.read(), password=None)
raw = priv.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
print(base64.b64encode(raw).decode())
'
```

Set that value in `FocusLockConstants.pinnedReviewAttestationPublicKey` and rebuild every install
that should trust future updates, same as the Team ID pin above. Left empty, `UpdateManager`
likewise refuses to install anything (fail closed).

Why this second key exists: unlike Android, where the release host holds the actual APK signing
keystore (so "passed AI review" and "produced a trusted binary" are literally the same event), this
host has no Xcode/`codesign` and can't hold the Apple signing identity for macOS builds -- that
identity necessarily lives on whatever Mac runs `build_app.sh`. This keypair is a second,
independent signature that CAN live only on the review host, so the client can still verify "this
update corresponds to a git SHA the review host actually recorded as AI-reviewed" even though the
review host never touched the binary itself.

## Per-release

```bash
cd macos/FocusLock
./Scripts/build_app.sh "Apple Development: Your Name (TEAMID)"
ALLOW_NON_DEVELOPER_ID=1 ./Scripts/publish_release.sh   # versionCode/versionName no longer required
```

**Version bumping is now automatic.** `build_app.sh` checks whether anything under `macos/` changed
since the last local publish (tracked in `.release/last_published_*`, written by
`publish_release.sh` after a successful publish) and, if so, bumps
`FocusLockConstants.appVersionCode` and `Scripts/version.txt` itself before building -- no more
manually editing `Constants.swift` and remembering to keep it in sync with the build. No macOS
changes since the last publish means no bump (re-running the build for an unrelated release just
reuses the existing version). `publish_release.sh` then reads `versionCode`/`versionName` straight
back out of those two files if you don't pass them explicitly, so what gets published always
matches what was just built. Passing `<versionCode> <versionName>` explicitly to either script
still works and overrides this (see `OTTERLING_VERSION_CODE`/`OTTERLING_VERSION_NAME` in
`build_app.sh`'s own comments -- that's what the build-agent path uses, to pin the version the
review host already decided rather than rely on this repo's git history).

`publish_release.sh`:
- Refuses to run if the built app's Team Identifier doesn't match `pinnedUpdateTeamID` -- catches
  signing with the wrong identity (or forgetting to update the pin) before it goes anywhere near a
  server, since a published build no installed copy will ever trust is a wasted release.
- Zips the app (`ditto`, preserving resource forks/attributes) and computes its SHA-256.
- Without `--attestation`, prints the exact command to run on the review host and stops -- it does
  **not** write a manifest, since one without `reviewAttestation` is dead weight no install will
  ever trust.

On the review host, get the attestation (needs root; this is the actual AI-review gate -- it
refuses unless `/var/lib/otterling/updates/last_published_sha` names a reviewed commit):

```bash
sudo otterling-attest-macos --sha256 <sha256 from publish_release.sh's output> \
  --version-code <versionCode> --version-name <versionName>
```

This prints `{"gitSha": "...", "reviewAttestation": "..."}`. Re-run `publish_release.sh` with that
JSON to actually write the manifest:

```bash
./Scripts/publish_release.sh <versionCode> <versionName> --attestation '<paste JSON here>'
```

This writes `macos-manifest.json` + the zip to `macos/FocusLock/.release/`, with `gitSha` and
`reviewAttestation` filled in from what the host signed.

Then, manually (this script has no access to your server and doesn't attempt it):

1. Edit the written `macos-manifest.json`: replace `${UPDATE_HOST}` with your real host (matching
   `filter-server/.env`'s `UPDATE_HOST`, e.g. `vpn.bartholomew.help`).
2. Copy both files onto the update host's `/var/lib/otterling/updates/` (however you already manage
   that host -- `scp`/`rsync`/etc.).

Caddy already serves that whole directory at `https://<host>/updates/` (same block that serves
Android's `manifest.json`) -- nothing else to configure. Existing installs pick up the new version
on their next hourly automatic check, or immediately via `otterlingctl check-update` /
the GUI's "Check for update" button, followed by "Install update" (or automatically, on the hourly
check -- see below).

## What happens on the Mac when an update installs

`UpdateManager` (daemon-side, `Sources/FocusLockHelperd/UpdateManager.swift`) verifies, in order:
the manifest's `reviewAttestation` signature against the locally pinned
`pinnedReviewAttestationPublicKey` (before downloading anything), then SHA-256 of the download,
then the extracted bundle's code signature (`codesign --verify --deep --strict`) **and** that its
Team Identifier matches the locally pinned `pinnedUpdateTeamID` -- all required. Only then does it
atomically swap the new bundle into
`/Applications/Otterling.app`, restart the watchdog LaunchDaemon (a separate job, needs telling
explicitly) so it picks up its own new binary, and exit -- `KeepAlive=true` relaunches the daemon
itself immediately from the freshly-installed binary.

A live foreground SwiftUI process can't replace its own in-memory binary mid-run, so after a
manual "Install update" click, `FocusLockViewModel.relaunchAfterUpdate()` instead spawns a brand
new instance of the now-updated `/Applications/Otterling.app` (a few seconds after the daemon's own
restart, so the new instance's first status poll doesn't race a daemon that's still mid-restart)
and quits the old one -- from the user's seat, "Install update" restarts everything. This is still
different from Android's fully silent APK swap (the OS handles that for a background app; a
foreground macOS app has no equivalent), but at least doesn't leave a stale GUI window around. The
*automatic* hourly check (`UpdateCheckLoop`, daemon-side, below) has no such GUI-relaunch step --
if Otterling.app happens to be open when that silent install runs, its window keeps showing the old
build number until it's quit and reopened by hand.

Automatic checks run hourly (`UpdateCheckLoop`, daemon-side) and install silently (same code path
as a manual "Install update" click) -- the only user-visible effect of a fully automatic
install is the daemon/watchdog restarting (a few seconds of no active DNS/pf reassertion; the
*existing* hosts/DNS/pf state isn't touched by the restart, just not *re-applied* until the new
process's first tick).

## Closing the CI gap

The `reviewAttestation` mechanism above (see "One-time setup") closes the *verification* half of
this gap without a macOS build agent: the client already refuses any update whose manifest isn't
signed by a key that only exists on the review host, and that host only signs a git SHA it already
recorded as AI-reviewed. What it does **not** close is the *build* half -- a human still has to
locally build, sign, and run `publish_release.sh`/`otterling-attest-macos` by hand for every
release; nothing stops that human from building from a stale or locally-modified checkout that
happens to share a git SHA with something that *was* reviewed (the attestation checks the SHA
matches, not that the bytes it's attesting to were literally produced from that source). Real
build-time parity with Android (AI-review-gated, fully automated build-and-publish on push) would
still need:

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

### Automated macOS builds (implemented)

The gap above is now closed using the household's own Mac as the build agent, running under a
**separate, isolated macOS user account** created just for this (not the account used for daily
use) -- see `HANDOVER.md` for why account separation, not a second physical machine, was the
accepted trade-off here. This is weaker than a fully separate CI host (a local admin with physical
access to the Mac can still reach the build-agent account), but strictly better than the fully
manual status quo, and every actual trust decision still happens only on the Linux review host --
this account can build and upload bytes, but cannot itself sign a `reviewAttestation` or publish
anything the client would accept.

How it works, end to end:

1. `release.sh` (Linux host), after a cumulative `VERDICT: PASS`, checks whether the reviewed diff
   touched `macos/` paths. If so, it writes `/var/lib/otterling/ci/macos-pending-build.json`
   (`{"gitSha", "versionCode", "versionName", "claimed": false}`) -- a single pending-job file, not
   a queue, so multiple pushes while the agent is offline coalesce to just the latest.
2. `Scripts/build_agent_poll.sh`, run every ~15 minutes by a LaunchDaemon under the isolated
   account (see `build_agent.launchd.plist.example`), polls `GET /ci/pending-macos-build` with a
   narrowly-scoped `MACOS_BUILD_AGENT_TOKEN` bearer (deliberately NOT `LOCKPROFILE_TOKEN` -- a
   compromised build agent should not also be able to impersonate a phone/Mac device). If a job is
   pending, it does a clean `git clone` + `checkout --force --detach <gitSha>` into a throwaway
   temp directory -- never a persistent local checkout -- and verifies the resulting `HEAD` matches
   the requested SHA exactly before building anything.
3. `Scripts/build_agent_build_and_upload.sh` unlocks a dedicated build keychain (never the
   account's login keychain), runs `build_app.sh --keychain <path> "<identity>"` (see that script's
   own doc comment for the new non-interactive-signing flag), optionally notarizes if
   `NOTARY_PROFILE` is configured, zips via `ditto`, and `POST`s the raw zip bytes plus
   `X-Git-Sha`/`X-Version-Code`/`X-Version-Name`/`X-Codesign-Team-Id` headers to
   `/ci/macos-build-result`.
4. The Linux host (`webhook_server.py`'s `/ci/macos-build-result` handler) independently computes
   the SHA-256 of the bytes it actually received -- **it never trusts a client-reported hash for
   anything security-relevant** -- validates the claimed `gitSha` matches the still-pending job,
   invokes the existing, unmodified `otterling-attest-macos` with that host-computed hash, and (on
   success) writes `macos-manifest.json` + the zip straight into
   `/var/lib/otterling/updates/` via a new `publish_macos_from_upload.sh`. `attest_macos_release.sh`
   itself, and the private attestation key, are completely unchanged by any of this.
5. Once the upload succeeds, `build_agent_poll.sh` calls `Scripts/build_agent_sync_version.sh`,
   which does a separate, fresh clone of `origin/main`, patches
   `Sources/FocusLockShared/Constants.swift`'s `appVersionCode` and `Scripts/version.txt` to the
   version that was just published (no-op if they already match), and pushes that as its own commit
   -- retrying through a rebase if `main` moved in the meantime. This is what used to be a manual
   step (see commits `7555c01`, `c5bd389`, `874b1c9`, all reconciling this repo's committed version
   files back to whatever the build agent had actually shipped); it's deliberately non-fatal to the
   overall poll run if it fails (the build already published successfully by this point), but it
   logs loudly so a failure doesn't go unnoticed -- check `~/.otterling-build-agent/logs/` for a
   "WARNING: version-bump commit/push failed" line if `git log` on `main` doesn't show the expected
   sync commit after a build. Requires `GITHUB_CLONE_TOKEN` to have **Contents: Read and write**
   (not just Read-only) on this repo -- see `build_agent.env.example`.

One-time setup on the build-agent account: run `Scripts/setup_build_agent.sh <path-to-your.p12>`.
It creates the dedicated signing keychain, generates and stores its unlock password, imports the
certificate, auto-detects the resulting signing identity string (preferring a Developer ID
Application identity if present, but accepting the free "Apple Development" one too, matching this
project's already-documented stance above and `publish_release.sh`'s own `ALLOW_NON_DEVELOPER_ID`
precedent for the manual path -- the actual trust check is SHA-256 + pinned Team ID either way),
writes `~/.otterling-build-agent/config.env` (prompting for the Otterling host, GitHub repo, and
`MACOS_BUILD_AGENT_TOKEN` -- generate that token once with `openssl rand -hex 32` and set the same
value in the Linux host's `secrets.env`), and installs the LaunchDaemon. The one thing it cannot do
for you is obtain *some* code-signing certificate in the first place and export it as a `.p12`
(Keychain Access -> My Certificates -> Export) -- there's no way to script around a human holding
that credential, but for most single-developer setups this is just the free identity Xcode already
generated the first time you signed in with an Apple ID, no paid Developer Program membership
needed. The script is idempotent (safe to re-run) and its own header comment documents exactly what
it automates vs. what it can't. `build_agent.env.example` / `build_agent.launchd.plist.example` are
still here as reference for anyone who'd rather do the equivalent steps by hand.

## Gotcha: pinning the wrong Team ID

When filling in `FocusLockConstants.pinnedUpdateTeamID`, use the `TeamIdentifier=` line from
`codesign -dv --verbose=4 <app>` -- not the parenthesized suffix in `security find-identity`'s
display name. At least one "Apple Development" personal-team certificate showed a different value
in each place (identity name `"... (C438Q9HAHP)"`, but every build it produced actually carried
`TeamIdentifier=D4XJKWV7GY`). Trusting the display name silently made every build fail its own
pinned-Team-ID check.
