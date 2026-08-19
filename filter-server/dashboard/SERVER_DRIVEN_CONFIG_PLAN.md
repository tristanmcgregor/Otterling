# Plan: make the dashboard the single source of truth for phone configuration

**Status:** not started. Written 2026-08-19 as a handoff document for a dedicated session —
do not start this mid-session alongside other firefighting; it touches most of the Android
app's settings surface and needs room to test each phase properly.

## Goal

Right now `filter-server/dashboard/` (the web UI) writes to `device_settings.json` via
`/dashboard-api/*`, but **the Android app never reads any of it** — confirmed via full repo
search, zero references to `/dashboard-api` anywhere in `app/`. Every setting (blocked apps,
blocked websites, trigger words, habit rules, app budgets, VPN bypass list, protection
toggles) is still configured **on the phone itself**, in a mix of Room tables and
SharedPreferences, enforced by managers that reapply their own locally-stored "desired state"
every ~5 minutes.

The goal: the dashboard becomes the actual control surface. The phone pulls its config from
the server and enforces *that*, instead of (or in addition to, see "Local override" below)
whatever's stored on-device.

## Current state (as of 2026-08-19)

**Server side already has the storage + API**, in `filter-server/lockprofile_service.py`:
- `device_settings.json`, one record per `device_id`, schema in `_default_device_settings()`
- `/dashboard-api/devices`, `/dashboard-api/devices/<id>/settings` (GET/PATCH), plus
  list-endpoint CRUD for `websites`, `bypass-apps`, `habits`, `rules`, `app-budgets`
  (see `LIST_ENDPOINTS` dict)
- Auth: dashboard's own login (just built tonight — `/dashboard-auth/login`, session cookie,
  `DASHBOARD_USER`/`DASHBOARD_LOGIN_PASSWORD` in `.env`) gates the *browser* UI; Caddy injects
  the real `LOCKPROFILE_TOKEN` bearer for `/dashboard-api/*` server-side, so the dashboard's
  own JS never learns it. A device pulling its OWN settings would use that same
  `LOCKPROFILE_TOKEN` bearer, same as every other phone→server call already does
  (`MacTamperPollWorker`, `ReportConfigStore`).

**Android side has zero integration.** Every setting below is on-device only.

## Mapping: server schema field → current on-device equivalent

| Server field (`device_settings.json`) | On-device today | Storage | Enforced by |
|---|---|---|---|
| `vpnBypassApps` | `content/MitmExemptManager.kt` | SharedPrefs (`vpn_bypass_prefs`) | Read once per VPN tunnel (re)build in `VpnFilterService.runPacketLoop`, via `MitmExemptionPolicy` |
| `blockedWebsites` | `content/CustomBlocklistManager.kt` (parent-added; NOT `DomainBlocklistManager`, which is a bulk downloaded third-party feed, out of scope) | SharedPrefs (`custom_blocklist_prefs`) | Domain-only entries feed `DomainBlocklistManager.isBlocked`; path-prefix entries enforced via accessibility |
| `protections` (safeMode/factoryReset/uninstallBlock/guestMode/usbDebugging) | `restrictions/DeviceRestrictionsManager.kt` + `Restriction` enum | SharedPrefs "desired state" (`RestrictionPreferences`) + live DevicePolicyManager | `detectDriftAndReapply()`, called every 5 min from `ProtectionEnforcementService`'s loop |
| `rules` (app-unlock rules tied to habits) | `focus/HabitRuleManager.kt` + `HabitRule` entity | Room (`habit_rules` table) | `reapplyAll()` every 5 min (loop) + `evaluateTrigger()` live-fired from the 30s HabitShare sync |
| `appBudgets` | `focus/AppTimeBudgetManager.kt` + `AppTimeBudget` entity | Room (`app_time_budgets` table) | `BudgetEnforcer`, same reapply-loop pattern |
| `habits` | `DetectedHabit` entity — **this is scraped output, not authored config** (HabitTrackerScanner reads a 3rd-party app's accessibility tree) | Room (`detected_habits` table) | N/A — not something the dashboard should *write*, only read for display |
| *(no server field yet)* | Blocked apps — **two parallel systems**: `content/AppSuspensionManager.kt` (real block list, Room `blocked_apps` table) + `restrictions/PackageDisableStore.kt` (separate SharedPrefs exempt-tracking for the "undisable" UI) | Room + SharedPrefs | `AppSuspensionManager.reapplyAll()`, 5 min loop |
| *(no server field yet)* | Trigger words — `alerts/GuardianAlertSettings.kt`, single newline-delimited string, ~90 seeded defaults | SharedPrefs (`guardian_alert_settings`) | Read live (no cache) on every accessibility content-changed event in `FocusGuardAccessibilityService.checkTriggerWords` |

Two schema gaps to fill before those last two can move: **`blockedApps`** and
**`triggerWords`** don't exist in `device_settings.json` yet. Add them to
`_default_device_settings()` + `LIST_ENDPOINTS` (or a dedicated field for trigger words,
since that's a flat string list, not id-keyed items) before Phase 6/7 below.

## Design decisions to make BEFORE writing code

These affect every phase, so pin them down first rather than re-deciding per-phase:

1. **Server wins, always, or is there a local-override path?** The existing on-device
   managers already have "local override" semantics (e.g. `PackageDisableStore`'s exempt set,
   `RestrictionPreferences`'s desired-vs-live drift detection) specifically because a *parent*
   might deliberately turn something off temporarily. If the dashboard becomes authoritative,
   decide: does a local toggle in the phone's own Settings UI (if kept at all) get silently
   overwritten on the next pull? Get overwritten but ALERT (matches this project's existing
   "quiet bypass = same severity as tamper" philosophy — `TamperEventLogger`'s
   `RESTRICTION_DISABLED_BY_USER` etc.)? Or does the on-device Settings UI simply get removed
   for migrated items, leaving the dashboard as the only place to change them? Given the
   user's stated intent ("it will be where I control everything from"), the last option is
   probably right, but confirm before ripping out UI.

2. **`NEVER_EXEMPT_PACKAGES` (Chrome) must stay non-negotiable server-side too.**
   `MitmExemptManager.add()` hardcodes a veto against ever exempting Chrome from MITM. Once
   the dashboard can write `vpnBypassApps`, the SAME veto needs to exist in
   `lockprofile_service.py`'s validation for that list (a compromised or careless dashboard
   edit must not be able to defeat content filtering entirely) — don't just trust the
   Android client to re-reject it.

3. **Offline / fetch-failure behavior must fail toward MORE restrictive, never less** — same
   principle already established for DNS (`report_types.json` defaults unlisted types to
   *enabled*, not disabled) and the Chrome-only MITM's Cloudflare Family DNS fallback
   (unresolvable owner UID → strict path, not lenient). A failed settings pull should keep
   enforcing the last-known-good cached config, not fall back to "unconfigured = unblocked."

4. **`device_id` consistency.** Just fixed a mess tonight where the same physical Mac showed
   up under 3 different `device_id` values (UUID from the daemon, 2 different hostname
   variants from Fleet) in the alerts dashboard. Before wiring the phone to
   `/dashboard-api/devices/<id>/...`, confirm exactly what `device_id` the phone will use
   (likely `Settings.Secure.ANDROID_ID`, matching `DeviceLogUploader.kt`'s existing pattern —
   grep for how device_id is generated for `/alerts/tamper` today) and make sure it's the
   SAME id used consistently everywhere, so a guardian configuring "the phone" in the
   dashboard doesn't end up configuring a phantom duplicate device.

5. **Guardian PIN.** `guardianPinHash` already exists in the schema with a comment saying
   it's "metadata for a future phone-side sync, not a second web login" — figure out what
   this is actually FOR before building against it (a parent-bypass PIN entered on the phone
   itself, checked against a hash the dashboard sets? Needs its own small design pass).

## Proposed architecture

One new Android component, following the `ReportConfigStore.kt` pattern already built
tonight (simplest of the two existing patterns — GET-and-cache, best-effort, piggybacked on
an existing poll rather than a new dedicated WorkManager job):

- **`DashboardConfigStore.kt`** (new): `GET /dashboard-api/devices/<device_id>/settings`,
  bearer-token auth (reuse `MacTamperPollSettings`'s token, same server), cache the raw JSON
  (or parsed fields) into SharedPrefs or a small Room table. `refresh()` piggybacked onto
  `MacTamperPollWorker.doWork()` exactly like `ReportConfigStore.refresh()` already is —
  avoids adding a second periodic job fighting for WorkManager's 15-minute floor.
- Each existing manager (`MitmExemptManager`, `CustomBlocklistManager`,
  `DeviceRestrictionsManager`, `HabitRuleManager`, `AppTimeBudgetManager`, and eventually
  `AppSuspensionManager`/`GuardianAlertSettings`) gets a **new read path** that consults
  `DashboardConfigStore`'s cache instead of (or merged with, per decision #1 above) its own
  SharedPrefs/Room-stored desired-state, inside the SAME `reapplyAll()`-style methods that
  already run every 5 minutes — this reuses the existing reapply-loop/drift-detection
  machinery rather than building a second one.

## Phased rollout (do NOT attempt all at once)

Ordered by lowest risk / clearest existing single-source-of-truth shape first:

**Phase 0 — foundations.** `DashboardConfigStore.kt`, confirm `device_id` scheme (decision
#4), wire the pull into `MacTamperPollWorker`, no enforcement changes yet — just prove the
phone can fetch and cache its own `device_settings.json` record. Test: add a device via the
dashboard, confirm the phone's log shows a successful fetch.

**Phase 1 — VPN bypass apps.** Cleanest mapping (`vpnBypassApps` ↔ `MitmExemptManager`),
already a single flat set, already has the "must never allow Chrome" invariant defined on
both ends once #2 above is done server-side. Wire `MitmExemptManager.exemptPackages()` to
read from `DashboardConfigStore` (merged with or replacing the local seeded-defaults set,
per decision #1).

**Phase 2 — blocked websites.** `blockedWebsites` ↔ `CustomBlocklistManager`. Slightly more
complex (path-prefix entries need accessibility-side enforcement, not just DNS).

**Phase 3 — protection toggles.** `protections` ↔ `DeviceRestrictionsManager`. Higher
stakes (DevicePolicyManager-level restrictions) — get phases 1-2 solid first.

**Phase 4 — app budgets.** `appBudgets` ↔ `AppTimeBudgetManager`. Independent of the others,
can slot in anytime after Phase 0.

**Phase 5 — habit rules.** `rules` ↔ `HabitRuleManager`. Most complex mapping (multi-target
packages, day/time windows, habit-name matching) — do last among the ones with existing
schema support.

**Phase 6 — trigger words.** Needs a new schema field first (`triggerWords: string[]` or
similar) added to `_default_device_settings()`/`LIST_ENDPOINTS`, plus a new
`/dashboard-api/devices/<id>/trigger-words` route or reuse the generic list-endpoint
machinery. Maps to `GuardianAlertSettings.triggerWords()`.

**Phase 7 — blocked apps.** Also needs a new schema field (`blockedApps`). More design work
than the others: two parallel on-device systems (`AppSuspensionManager` +
`PackageDisableStore`) need reconciling into one server-driven model first.

**Phase 8 — UI cleanup.** Once a given setting is dashboard-driven and proven stable, decide
per decision #1 whether to remove/gray-out the corresponding on-device Settings screen, or
keep it as a read-only mirror of the server state.

## Cross-cutting reminders

- Every new phone→server call in this project so far reuses the same bearer-token pattern
  (`MacTamperPollSettings.token()`, `CloudFilterSettings.host()` for the target host) — don't
  invent a new auth scheme for this.
- The reapply-loop pattern (`ProtectionEnforcementService`'s 5-min cadence, calling each
  manager's `reapplyAll()`) is the existing "make cached config actually take effect and
  self-heal from drift" mechanism — the new dashboard-driven read path should plug into that,
  not add a parallel enforcement loop.
- Every existing local-override attempt already triggers a `TamperEventLogger` alert
  (`APP_UNBLOCKED_BY_USER`, `RESTRICTION_DISABLED_BY_USER`, etc.) — decide per decision #1
  whether a dashboard-overridden-then-locally-reverted setting should fire the SAME alerts,
  and keep that reporting intact regardless of where the "desired state" now comes from.
