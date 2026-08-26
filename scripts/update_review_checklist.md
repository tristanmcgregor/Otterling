# Otterling update review checklist

Live copy used by the release gate: `/var/lib/otterling/ci/checklist.md` (root-owned on the
update host). The tree copy in git is documentation only. The question that matters is always:
**does this diff let the phone run a version of Otterling with any of these protections
weakened, removed, or made conditional/bypassable?** Code style, unrelated bugs, and refactors are
not this checklist's concern -- only changes that could let the person being filtered escape it.

### Scope (what is production today)

Finished / shipping products in this monorepo:

- **Android Otterling** (`app/`)
- **macOS Otterling / FocusLock** (`macos/FocusLock/`)
- **Live filter host** (`filter-server/`) that both clients depend on

Other future sibling apps (if any are added later) stay unfinished until this checklist is
extended for them via the ratchet / `strengthen_checklist.sh`.

- Enforce every relevant section below against those production surfaces (and any shared code
  they call).
- **Do not FAIL** a release solely for changes confined to *other* unfinished client trees that
  are not listed above — accept those until they ship and the checklist covers them.
- A change that touches both an unfinished tree **and** production code is still fully reviewed
  for the production parts.

A **FAIL** on any single item below means the whole diff fails review, even if everything else
about it is fine and even if the change looks accidental/refactor-shaped. When in doubt, FAIL and
let a human sort it out -- a false FAIL costs a re-review; a false PASS ships a bypass to a
production phone.

## 0. Checklist ratchet (this file — check before anything else if the diff touches it)

The deny checklist itself must only get **stricter** over time. The live gate always uses the
pinned host copy (`/var/lib/otterling/ci/checklist.md`), not whatever is in the git tree.

- FAIL any diff to [`scripts/update_review_checklist.md`](update_review_checklist.md) (or any
  copy/rename of it) that **removes**, **narrows**, **softens**, or makes **optional/conditional**
  an existing requirement — including deleting a bullet, moving a must-keep into an allow-list,
  replacing “FAIL” with “warn”, or adding an exception that lets a previously forbidden bypass
  through.
- FAIL any diff that adds wording whose effect is to let the AI treat a protection as
  non-blocking (“prefer”, “nice to have”, “unless inconvenient”, etc.) for items that are
  currently hard FAILs.
- **Allowed** checklist diffs: new bullets that add protections; clarifying wording that does not
  reduce scope; fixing typos; pointing at renamed files **without** dropping the underlying rule.
- When in doubt whether a checklist edit is stronger or weaker, FAIL.
- Promoting a stronger checklist onto the host is a separate root-only step
  (`/var/lib/otterling/ci/strengthen_checklist.sh`); a release must never overwrite the pinned
  checklist with a weaker tree copy.

## 1. The update-verification chain itself (highest priority for app/server code)

This is the mechanism that makes every other item on this list actually matter. If this is
weakened, nothing else here matters, because a bypassed build could ship anyway.

- [`ApprovedUpdateManager.kt`](../app/src/main/java/app/otterling/updates/ApprovedUpdateManager.kt):
  `downloadVerifyAndInstall` must still (a) reject when `BuildConfig.RELEASE_CERT_SHA256` is
  blank, (b) reject on a SHA-256 mismatch, (c) reject when the downloaded APK's signing
  certificate fingerprint doesn't match the pinned one -- via `signingCertSha256`. FAIL any diff
  that removes a check, weakens a comparison (e.g. makes it case-sensitive-only in a way that
  trivially mismatches, or `startsWith` instead of exact match), hardcodes a bypass, or adds any
  code path that installs an APK without all three checks having passed.
- [`app/build.gradle.kts`](../app/build.gradle.kts): FAIL any diff that hardcodes a literal value
  for `RELEASE_CERT_SHA256` (or the release keystore path/passwords) directly in source instead of
  reading it from `System.getenv(...)`/`local.properties` -- that would let a contributor bake in
  their own trusted fingerprint instead of the real one CI's protected secrets provide.
- There must be **no** UI or code path anywhere in the app that installs a local/arbitrary/
  unverified APK file (`Intent.ACTION_INSTALL_PACKAGE` on a user-picked file, a "developer mode"
  sideload toggle, etc.). `ApprovedUpdateManager` must remain the only path that ever calls
  `PackageInstaller`.
- Release must stay **off git**: FAIL any diff that re-adds a GitHub Actions (or similar)
  `sign-and-publish` / release-signing / update-host upload job. Signing + publish happen only via
  root-owned `/var/lib/otterling/ci/release.sh` (`sudo otterling-release`) after AI `VERDICT: PASS`
  against the pinned server checklist. The in-repo workflow may be advisory-only.
- That same `release.sh` must keep deploying **filter-server** onto the update host after PASS
  (not APK-only). FAIL any attempt to document or reintroduce a path that publishes the Android
  APK while skipping host `filter-server` deploy when that tree changed.
- [`UpdateManager.swift`](../macos/FocusLock/Sources/FocusLockHelperd/UpdateManager.swift):
  `downloadVerifyAndInstall` must still (a) reject when
  `FocusLockConstants.pinnedReviewAttestationPublicKey` is blank, (b) reject when
  `manifest.reviewAttestation` doesn't verify against that pinned key over the manifest's own
  `versionCode`/`versionName`/`sha256`/`gitSha`, (c) reject on a SHA-256 mismatch, (d) reject when
  the extracted bundle's code signature or Team Identifier doesn't match
  `pinnedUpdateTeamID` -- all four required, in addition to each other, not as alternatives. FAIL
  any diff that removes a check, weakens a comparison, hardcodes a bypass, or adds any code path
  that installs a `.app` without all four having passed.
- `attest_macos_release.sh`/`otterling-attest-macos` (root-owned, on the update host) must keep
  refusing to sign unless `/var/lib/otterling/updates/last_published_sha` names the git SHA being
  attested to -- that file is only ever written by `release.sh` after AI `VERDICT: PASS`. FAIL any
  diff that lets this script sign an arbitrary/caller-supplied git SHA, or that removes the
  requirement that `last_published_sha` exist before signing.

## 2. VPN lockdown (Android)

- [`VpnFilterManager.kt`](../app/src/main/java/app/otterling/content/VpnFilterManager.kt):
  `enable()`/`ensureActive()` must keep calling `setAlwaysOnVpnPackage(admin, packageName, true)`
  -- the `true` (lockdown) argument specifically. FAIL any diff that changes it to `false` or makes
  it conditional on anything other than Device Owner availability.
- [`VpnFilterService.kt`](../app/src/main/java/app/otterling/content/VpnFilterService.kt):
  the full default route (`addRoute("0.0.0.0", 0)`) and the `KNOWN_DOH_IPS` refusal list must
  remain. FAIL any diff that narrows the captured route or removes/empties that IP list.

## 3. Filter proxy fail-closed behavior (Android)

- [`TcpRelayManager.kt`](../app/src/main/java/app/otterling/content/TcpRelayManager.kt):
  when `proxyConfig.enabled` and the destination is port 80/443, a failed CONNECT (bad auth,
  proxy unreachable, non-2xx response) must still result in an RST with **no** fallback to a
  direct connection. FAIL any diff that adds an else-branch/fallback that connects directly to the
  real destination after a proxy failure.
- [`VpnFilterService.kt`](../app/src/main/java/app/otterling/content/VpnFilterService.kt):
  the QUIC (UDP/443) drop while the proxy is enabled must remain.
- [`CaCertInstaller.kt`](../app/src/main/java/app/otterling/content/CaCertInstaller.kt)
  and its call sites in `VpnFilterManager` (`enable()`/`ensureActive()`) must remain -- this is
  what makes the proxy's interception actually work at all; removing the call sites doesn't break
  anything visibly (HTTPS would just fail), so specifically check it's still wired up, not just
  that the file still exists.

## 4. Local content blocklists

- [`DomainBlocklistManager.kt`](../app/src/main/java/app/otterling/content/DomainBlocklistManager.kt):
  `DEFAULT_SOURCES` must not be emptied, and `isBlocked()` must not be short-circuited to always
  return `false` or made to ignore the loaded domain set.
- `filter-server/mitm_nsfw_addon.py`: the block-on-domain-match / block-on-path-pattern / block-
  on-title-keyword logic and the deny cache must remain intact and reachable from `request()`/
  `response()`. FAIL any diff that makes `NsfwFilter` a no-op (e.g. removing it from `addons = [...]`,
  or making `_block` never get called).
  Narrowing the deny-cache's key scope (e.g. host → host+path) or shortening its TTL is **not**
  automatically a FAIL, provided the domain-list / path-pattern / title-keyword checks in
  `request()` / `response()` still execute unconditionally on every request regardless of cache
  state. Only FAIL if the actual detection logic — not just the cache — stops running or stops
  calling `_block` / `_respond_blocked`.

## 5. Device Owner / tamper resistance

- Restriction application (`DeviceRestrictionsManager`, `RestrictionEnforcementWorker`) and
  tamper-event logging (`TamperEventLogger`, `AccessibilityGuard`) must not be removed or
  disabled by default.
- `DeviceAdminReceiverImpl.onEnabled`/`onDisableRequested` must keep re-applying
  restrictions/logging the disable attempt -- don't allow a diff that turns either into a no-op.
- `AndroidManifest.xml`: the `DeviceAdminReceiverImpl`/`FocusGuardAccessibilityService`/
  `VpnFilterService` component declarations and their required permissions/intent-filters must not
  be removed or narrowed in a way that breaks enrollment or enforcement.

## 6. Guardian alerting

- `GuardianAlertSettings`, `AlertReporter`, `SmsPermissionGranter`: SMS alerting to the Guardian
  number must not be gutted (e.g. `report()` becoming a no-op, or the debounce/cap logic being
  replaced with something that silently drops all alerts).

## 6b. macOS Otterling / FocusLock (finished client)

Treat `macos/FocusLock/` as a production parental-control client. FAIL diffs that weaken its
enforcement or Guardian asymmetry:

- [`XPCService.swift`](../macos/FocusLock/Sources/FocusLockHelperd/XPCService.swift) /
  [`XPCProtocol.swift`](../macos/FocusLock/Sources/FocusLockShared/XPCProtocol.swift): removing a
  block, disabling DNS enforcement, or clearing protection must still require the caller to be in
  the `admin` group (Guardian). Adding blocks / enabling filtering may stay open to the standard
  user. FAIL any diff that lets a non-admin disable or remove protections.
- [`DNSEnforcer.swift`](../macos/FocusLock/Sources/FocusLockHelperd/DNSEnforcer.swift) +
  [`PFBlocker.swift`](../macos/FocusLock/Sources/FocusLockHelperd/PFBlocker.swift): DNS enforcement
  and the pf DoH/DoT bypass block must remain reachable and not become no-ops when enforcement is
  on. FAIL emptying the DoH IP list or skipping pf activation whenever site blocking or DNS
  enforcement is enabled.
- [`AdultBlocklistManager.swift`](../macos/FocusLock/Sources/FocusLockHelperd/AdultBlocklistManager.swift)
  + hosts application: the always-on adult-domain hosts layer must not be short-circuited to never
  apply.
- [`AppBlockEnforcer.swift`](../macos/FocusLock/Sources/FocusLockHelperd/AppBlockEnforcer.swift) /
  [`EnforcementLoop.swift`](../macos/FocusLock/Sources/FocusLockHelperd/EnforcementLoop.swift):
  blocked apps must still be killed on sight; protected apps must still be relaunched / `schg`
  locked unless the Guardian clears them.
- [`AdminGroupCheck.swift`](../macos/FocusLock/Sources/FocusLockShared/AdminGroupCheck.swift): FAIL
  any bypass that treats every caller as Guardian/admin.

## 7. General red flags (production surfaces)

Applies to Android `app/`, macOS `macos/FocusLock/`, and live `filter-server/` (and any shared
code those production surfaces call). Other unfinished client trees alone are covered by the
allow list below.

- Any new `adb`/shell-invoked debug backdoor, hidden broadcast action, or exported component that
  lets a non-Guardian account disable protections, matching the existing "always allowed to add,
  Guardian-only to remove/disable" asymmetry -- FAIL anything that adds a way to remove/disable
  without that gate. On macOS, also FAIL new XPC/CLI entry points that disable protections without
  the admin-group check.
- Any change to `applicationId`/package name, or to the Device Admin/VPN/accessibility service
  class names referenced by `AndroidManifest.xml`, without an equally thorough migration plan --
  these are load-bearing for existing enrolled devices. On macOS, FAIL renaming the LaunchDaemon /
  Mach service identifiers in a way that orphans existing installs without a migration plan.
- Obfuscated, minified-looking, or otherwise hard-to-read code introduced specifically in one of
  the files above, where the rest of the diff is unrelated -- treat unexplained complexity in a
  security-relevant file as a reason to slow down and FAIL pending human review, even if you can't
  pin down exactly what it does.

### Config UI vs enforcement (do not confuse these)

The checklist cares about **runtime enforcement**, not every settings screen that once configured
it.

- FAIL if the DIFF deletes or no-ops the **manager / service / worker** that actually enforces a
  protection (e.g. emptying `VpnFilterService`, making `AppSuspensionManager.reapplyAll()` a
  no-op, removing `PrivateDnsFilterManager` while VPN still relies on it to suppress conflicting
  Private DNS).
- Do **not** FAIL solely because a Guardian **settings composable / button** for an unused or
  superseded feature is removed, when the underlying enforcement classes remain and a supported
  path still provides the protection (example: removing the old Device-Owner **Private DNS
  "Content Filtering"** settings UI while the cloud content-filter **VPN** remains the live
  adult-content path, and `PrivateDnsFilterManager` is kept for VPN conflict handling).
- If commit messages include `AI-REVIEW:` lines (see below), treat them as author intent about
  unused/superseded UI — still verify the DIFF matches that claim (enforcement not deleted).

## Allow list (do not FAIL on these alone)

- Changes that stay entirely inside *other* unfinished / not-yet-listed future client directories
  (not `app/`, not `macos/FocusLock/`, not `filter-server/`). Accept those until they ship and
  this checklist is extended.
- Refactors, renames, or comment/doc changes in files *not* listed above.
- New features that don't touch anything in sections 1–6b (for production Android / macOS /
  filter-server).
- Version bumps (`versionCode`/`versionName` in `app/build.gradle.kts`) -- expected on every
  release when an APK is rebuilt.
- UI copy changes that don't alter the underlying enforcement logic.
- Removing unused / superseded **configuration UI** when enforcement code remains reachable and
  a supported replacement path still provides the same class of protection (see "Config UI vs
  enforcement" above).

## Commit messages for the AI gate (`AI-REVIEW:`)

Authors should put intent the reviewer must read in the commit body as one or more lines:

```text
AI-REVIEW: Removing unused Private DNS settings UI; VPN cloud filter is the live path.
AI-REVIEW: Enforcement managers unchanged; AppSuspensionManager still used by ProtectionController.
```

The release prompt includes `git log` for the cumulative range. The AI must read those
`AI-REVIEW:` lines and not invent a FAIL that contradicts both the DIFF and the stated intent,
while still FAILing if the DIFF actually guts enforcement.
