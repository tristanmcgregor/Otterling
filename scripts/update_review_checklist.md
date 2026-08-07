# Otterling update review checklist

Live copy used by the release gate: `/var/lib/otterling/ci/checklist.md` (root-owned on the
update host). The tree copy in git is documentation only. The question that matters is always:
**does this diff let the phone run a version of Otterling with any of these protections
weakened, removed, or made conditional/bypassable?** Code style, unrelated bugs, and refactors are
not this checklist's concern -- only changes that could let the person being filtered escape it.

A **FAIL** on any single item below means the whole diff fails review, even if everything else
about it is fine and even if the change looks accidental/refactor-shaped. When in doubt, FAIL and
let a human sort it out -- a false FAIL costs a re-review; a false PASS ships a bypass to a
production phone.

## 1. The update-verification chain itself (highest priority -- check this first)

This is the mechanism that makes every other item on this list actually matter. If this is
weakened, nothing else here matters, because a bypassed build could ship anyway.

- [`ApprovedUpdateManager.kt`](../app/src/main/java/au/com/tbmcgregor/bwparker/familyguard/updates/ApprovedUpdateManager.kt):
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

## 2. VPN lockdown (Android)

- [`VpnFilterManager.kt`](../app/src/main/java/au/com/tbmcgregor/bwparker/familyguard/content/VpnFilterManager.kt):
  `enable()`/`ensureActive()` must keep calling `setAlwaysOnVpnPackage(admin, packageName, true)`
  -- the `true` (lockdown) argument specifically. FAIL any diff that changes it to `false` or makes
  it conditional on anything other than Device Owner availability.
- [`VpnFilterService.kt`](../app/src/main/java/au/com/tbmcgregor/bwparker/familyguard/content/VpnFilterService.kt):
  the full default route (`addRoute("0.0.0.0", 0)`) and the `KNOWN_DOH_IPS` refusal list must
  remain. FAIL any diff that narrows the captured route or removes/empties that IP list.

## 3. Filter proxy fail-closed behavior (Android)

- [`TcpRelayManager.kt`](../app/src/main/java/au/com/tbmcgregor/bwparker/familyguard/content/TcpRelayManager.kt):
  when `proxyConfig.enabled` and the destination is port 80/443, a failed CONNECT (bad auth,
  proxy unreachable, non-2xx response) must still result in an RST with **no** fallback to a
  direct connection. FAIL any diff that adds an else-branch/fallback that connects directly to the
  real destination after a proxy failure.
- [`VpnFilterService.kt`](../app/src/main/java/au/com/tbmcgregor/bwparker/familyguard/content/VpnFilterService.kt):
  the QUIC (UDP/443) drop while the proxy is enabled must remain.
- [`CaCertInstaller.kt`](../app/src/main/java/au/com/tbmcgregor/bwparker/familyguard/content/CaCertInstaller.kt)
  and its call sites in `VpnFilterManager` (`enable()`/`ensureActive()`) must remain -- this is
  what makes the proxy's interception actually work at all; removing the call sites doesn't break
  anything visibly (HTTPS would just fail), so specifically check it's still wired up, not just
  that the file still exists.

## 4. Local content blocklists

- [`DomainBlocklistManager.kt`](../app/src/main/java/au/com/tbmcgregor/bwparker/familyguard/content/DomainBlocklistManager.kt):
  `DEFAULT_SOURCES` must not be emptied, and `isBlocked()` must not be short-circuited to always
  return `false` or made to ignore the loaded domain set.
- `filter-server/mitm_nsfw_addon.py`: the block-on-domain-match / block-on-path-pattern / block-
  on-title-keyword logic and the deny cache must remain intact and reachable from `request()`/
  `response()`. FAIL any diff that makes `NsfwFilter` a no-op (e.g. removing it from `addons = [...]`,
  or making `_block` never get called).

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

## 7. General red flags (any file)

- Any new `adb`/shell-invoked debug backdoor, hidden broadcast action, or exported component that
  lets a non-Guardian account disable protections, matching the existing "always allowed to add,
  Guardian-only to remove/disable" asymmetry -- FAIL anything that adds a way to remove/disable
  without that gate.
- Any change to `applicationId`/package name, or to the Device Admin/VPN/accessibility service
  class names referenced by `AndroidManifest.xml`, without an equally thorough migration plan --
  these are load-bearing for existing enrolled devices.
- Obfuscated, minified-looking, or otherwise hard-to-read code introduced specifically in one of
  the files above, where the rest of the diff is unrelated -- treat unexplained complexity in a
  security-relevant file as a reason to slow down and FAIL pending human review, even if you can't
  pin down exactly what it does.

## Allow list (do not FAIL on these alone)

- Refactors, renames, or comment/doc changes in files *not* listed above.
- New features that don't touch anything in sections 1-6.
- Version bumps (`versionCode`/`versionName` in `app/build.gradle.kts`) -- expected on every
  release.
- UI copy changes that don't alter the underlying enforcement logic.
