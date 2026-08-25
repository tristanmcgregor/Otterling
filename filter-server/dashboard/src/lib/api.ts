// Thin client for lockprofile_service.py's /dashboard-api/* routes (see that file's "Dashboard
// device settings" section for the server side of this contract). Same-origin requests only --
// Caddy sits in front of /dashboard-api/* with Basic Auth and injects the real bearer token
// itself (see filter-server/Caddyfile), so this file never handles or stores a token in
// production. In dev, vite.config.ts's proxy injects a token from a local-only env var instead.

const BASE = "/dashboard-api";

export interface Protections {
  safeMode: boolean;
  factoryReset: boolean;
  uninstallBlock: boolean;
  guestMode: boolean;
  usbDebugging: boolean;
}

// Fleet-wide baseline a brand-new device_id starts with -- see lockprofile_service.py's
// DEFAULT_TEMPLATE_PATH comment. Editing this only affects devices that haven't checked in yet;
// an already-configured device's own Settings screen is unaffected.
export interface DefaultSettings {
  protections: Protections;
  vpnFilter: { enabled: boolean };
  frictionDelay: { enabled: boolean; seconds: number };
}

export interface BypassApp {
  id: string;
  name: string;
}

export interface BlockedWebsite {
  domain: string;
  addedAt: number;
}

// Global, shared across every device (see lockprofile_service.py's HABITS_PATH) -- a rule on
// ANY device can reference a habit verified on a different one. doneToday/hasProof/verifiedAt are
// computed server-side from whichever device most recently reported this habit's completion.
//
// requiresProof: a completion report for this habit must include a photo (checked server-side,
// see lockprofile_service.py's HABIT_PROOFS_DIR) -- without it, a device holding nothing but the
// shared LOCKPROFILE_TOKEN (embedded in the shipped APK, extractable by the person being
// filtered) could otherwise fake ANY habit done and unlock every app any rule gates on it,
// fleet-wide, with a single request and zero evidence. hasProof reflects whether today's
// completion actually included one.
export interface Habit {
  id: string;
  name: string;
  requiresProof: boolean;
  doneToday: boolean;
  hasProof: boolean;
  verifiedAt: number | null;
}

// One entry from report_types.json's "types" map (see lockprofile_service.py's
// _load_report_types_file) -- source tells you where an event of this type actually comes from
// (mac daemon, this server itself, or the phone directly -- android-origin types never touch the
// server's own reporting path, they're suppressed entirely on-device by AlertReporter.kt).
export interface ReportType {
  enabled: boolean;
  source: "mac" | "server" | "android";
  description: string;
  // Guardian-editable override for this report's actual wording -- "" (the default) means "use
  // the built-in default message". `{details}` inside it is substituted with the event's own
  // details text server-side (mac/server-origin, via _send_ntfy_notification) or on-device
  // (android-origin, via AlertReporter.kt's formatBody) -- same placeholder, same substitution,
  // different place it happens depending on where that report type's message actually gets sent.
  customMessage: string;
}

export interface ReportTypesFile {
  types: Record<string, ReportType>;
}

export interface RuleSchedule {
  startTime?: string;
  endTime?: string;
  daysOfWeek?: number[];
}

export interface Rule {
  id: string;
  // "website" gates a domain (enforced via the phone's DNS filter -- see
  // HabitRuleManager.kt's isWebsiteCurrentlyBlocked) instead of suspending an app. Absent on
  // rules created before this field existed, which are always "app". appName holds the domain
  // itself for a website rule (server-side display label), so existing appName-only UI still
  // shows something sensible without checking targetType.
  targetType?: "app" | "website";
  appId: string;
  appName: string;
  websiteDomain?: string;
  requiredHabitIds: string[];
  schedule: RuleSchedule;
  dailyBudgetMinutes: number | null;
  createdAt: number;
}

export interface AppBudget {
  id: string;
  appId: string;
  appName: string;
  dailyLimitMinutes: number | null;
}

export interface TriggerWord {
  word: string;
  addedAt: number;
}

// appId's meaning is platform-dependent: an Android package name (e.g. com.example.app) for an
// "android" device, or a process/executable name (e.g. "Safari", matching what AppBlockEnforcer.swift
// matches running processes on, not a bundle identifier or display name) for a "macos" device.
export interface BlockedApp {
  appId: string;
  addedAt: number;
}

// macos-only: an app kept running and undeletable (filesystem-locked via schg), the inverse of
// BlockedApp -- see ProtectedApp in macos/FocusLock/Sources/FocusLockShared/Models.swift.
export interface ProtectedApp {
  displayName: string;
  executableName: string;
  bundlePath: string;
  addedAt: number;
}

// Computed server-side from device_id shape (see lockprofile_service.py's _detect_platform) --
// not stored, not client-settable. protections/vpnBypassApps/blockedWebsites/rules/habits/
// appBudgets/triggerWords are Android-only (consumed exclusively by the Android app's
// DashboardConfigStore); vpnFilter/blockedApps are shared with platform-dependent meaning (see
// their own doc comments); protectedApps/cooldownHours/proxyFilter/cloudFilterHost/
// cloudFilterEnabled are macos-only (consumed by DashboardConfigSync.swift). The dashboard UI
// uses this field to show/hide each section per selected device.
export type DevicePlatform = "macos" | "android";

export interface DeviceSettings {
  device_name: string;
  platform: DevicePlatform;
  protections: Protections;
  vpnFilter: { enabled: boolean };
  vpnBypassApps: BypassApp[];
  blockedWebsites: BlockedWebsite[];
  frictionDelay: { enabled: boolean; seconds: number };
  // habits is NOT here -- moved to the global library, see api.getHabits() below.
  rules: Rule[];
  appBudgets: AppBudget[];
  triggerWords: TriggerWord[];
  blockedApps: BlockedApp[];
  updatedAt: number | null;
  // macos-only -- null means "no opinion yet" (see lockprofile_service.py's
  // _default_device_settings comment). The dashboard UI only shows these controls when
  // platform === "macos"; a null value there just means the guardian hasn't touched that
  // control yet.
  protectedApps: ProtectedApp[];
  cooldownHours: number | null;
  proxyFilter: { enabled: boolean; forceViaFirewall: boolean } | null;
  cloudFilterHost: string | null;
  cloudFilterEnabled: boolean | null;
  // Self-reported by the device (see lockprofile_service.py's installed-apps route and Android's
  // InstalledAppsReporter.kt / macOS's InstalledAppScanner.swift) -- id is a package name
  // (Android) or executable name (macOS), matching what blockedApps/rules/etc. expect. Empty
  // until the device's next sync cycle after this feature shipped, or if it's never synced at all.
  installedApps: { id: string; name: string }[];
  // Self-reported by the device (see lockprofile_service.py's app-info route and Android's
  // AppVersionReporter.kt) -- all fields null until the device's next sync cycle after this
  // feature shipped, or if it's never synced at all.
  appVersion: { versionName: string | null; versionCode: number | null; reportedAt: number | null };
}

export interface DeviceSummary {
  device_id: string;
  device_name: string;
  updatedAt: number | null;
  alertCount24h: number;
  platform: DevicePlatform;
  appVersion: { versionName: string | null; versionCode: number | null; reportedAt: number | null };
}

export interface ActivityEvent {
  id: number;
  device_id: string;
  device_name?: string;
  type: string;
  details: string;
  reported_at: number;
  received_at: number;
}

class ApiError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const res = await fetch(BASE + path, {
    ...init,
    headers: { "Content-Type": "application/json", ...(init.headers || {}) },
  });
  if (!res.ok) {
    let message = res.statusText;
    try {
      const body = await res.json();
      message = body.error || message;
    } catch {
      // body wasn't JSON -- keep statusText
    }
    throw new ApiError(res.status, message);
  }
  if (res.status === 204) return undefined as unknown as T;
  return (await res.json()) as T;
}

const enc = encodeURIComponent;

export const api = {
  listDevices: () => request<{ devices: DeviceSummary[] }>("/devices"),
  removeDevice: (deviceId: string) =>
    request<{ status: string }>(`/devices/${enc(deviceId)}`, { method: "DELETE" }),

  getSettings: (deviceId: string) => request<DeviceSettings>(`/devices/${enc(deviceId)}/settings`),

  patchSettings: (deviceId: string, updates: Partial<DeviceSettings>) =>
    request<DeviceSettings>(`/devices/${enc(deviceId)}/settings`, {
      method: "PATCH",
      body: JSON.stringify(updates),
    }),

  getActivity: (deviceId: string, sinceId = 0) =>
    request<{ events: ActivityEvent[]; max_id: number }>(
      `/devices/${enc(deviceId)}/activity?since_id=${sinceId}`
    ),

  addWebsite: (deviceId: string, domain: string) =>
    request<{ blockedWebsites: BlockedWebsite[] }>(`/devices/${enc(deviceId)}/websites`, {
      method: "POST",
      body: JSON.stringify({ domain }),
    }),
  removeWebsite: (deviceId: string, domain: string) =>
    request<{ blockedWebsites: BlockedWebsite[] }>(
      `/devices/${enc(deviceId)}/websites/${enc(domain)}`,
      { method: "DELETE" }
    ),

  addBypassApp: (deviceId: string, name: string) =>
    request<{ vpnBypassApps: BypassApp[] }>(`/devices/${enc(deviceId)}/bypass-apps`, {
      method: "POST",
      body: JSON.stringify({ name }),
    }),
  removeBypassApp: (deviceId: string, id: string) =>
    request<{ vpnBypassApps: BypassApp[] }>(
      `/devices/${enc(deviceId)}/bypass-apps/${enc(id)}`,
      { method: "DELETE" }
    ),

  // Global, shared across every device -- NOT scoped under /devices/<id>, see Habit's doc comment.
  getHabits: () => request<{ habits: Habit[] }>("/habits"),
  addHabit: (name: string, requiresProof = false) =>
    request<{ habits: Habit[] }>("/habits", {
      method: "POST",
      body: JSON.stringify({ name, requiresProof }),
    }),
  removeHabit: (id: string) =>
    request<{ habits: Habit[] }>(`/habits/${enc(id)}`, { method: "DELETE" }),
  setHabitRequiresProof: (id: string, requiresProof: boolean) =>
    request<{ habits: Habit[] }>(`/habits/${enc(id)}`, {
      method: "PATCH",
      body: JSON.stringify({ requiresProof }),
    }),
  // Revokes just today's completion (e.g. a suspicious one spotted in the Activity Log) without
  // deleting the habit itself -- see lockprofile_service.py's HABIT_PROOFS_DIR comment.
  revokeHabitCompletion: (id: string) =>
    request<{ habits: Habit[] }>(`/habits/${enc(id)}/complete`, { method: "DELETE" }),
  // Logs into the connected HabitShare account server-side and creates a library entry (by name,
  // skipping any already present) for each habit found there -- see lockprofile_service.py's
  // _fetch_habitshare_habit_names for why this is safe to do without the phone being involved.
  importHabitsFromHabitShare: () =>
    request<{ habits: Habit[]; imported: number }>("/habits/import-from-habitshare", { method: "POST" }),
  // Browser-session-authed image fetch (not JSON) -- callers build an <img src> from this rather
  // than calling it directly; see the GET /dashboard-api/habits/<id>/proof route.
  habitProofUrl: (id: string) => `${BASE}/habits/${enc(id)}/proof`,

  // Guardian-only -- deliberately a different route from the phone-reachable /report-config (see
  // lockprofile_service.py's route handler comment for why those must never be merged). Toggles
  // an EXISTING type's enabled flag only; there's no add/remove here on purpose.
  getReportTypes: () => request<ReportTypesFile>("/report-types"),
  setReportTypeEnabled: (type: string, enabled: boolean) =>
    request<ReportTypesFile>(`/report-types/${enc(type)}`, {
      method: "PATCH",
      body: JSON.stringify({ enabled }),
    }),
  // "" clears back to the built-in default wording -- see ReportType.customMessage's comment.
  setReportTypeMessage: (type: string, customMessage: string) =>
    request<ReportTypesFile>(`/report-types/${enc(type)}`, {
      method: "PATCH",
      body: JSON.stringify({ customMessage }),
    }),

  // Guardian-only. GET returns the effective values (template merged onto the hardcoded
  // fallback), so the UI can show real toggle states even before the guardian has ever touched
  // this template -- see lockprofile_service.py's route comment.
  getDefaultSettings: () => request<DefaultSettings>("/default-settings"),
  setDefaultSettings: (updates: Partial<DefaultSettings>) =>
    request<DefaultSettings>("/default-settings", {
      method: "PATCH",
      body: JSON.stringify(updates),
    }),

  addRule: (deviceId: string, rule: Partial<Rule>) =>
    request<{ rules: Rule[] }>(`/devices/${enc(deviceId)}/rules`, {
      method: "POST",
      body: JSON.stringify(rule),
    }),
  updateRule: (deviceId: string, id: string, updates: Partial<Rule>) =>
    request<{ rules: Rule[] }>(`/devices/${enc(deviceId)}/rules/${enc(id)}`, {
      method: "PATCH",
      body: JSON.stringify(updates),
    }),
  removeRule: (deviceId: string, id: string) =>
    request<{ rules: Rule[] }>(`/devices/${enc(deviceId)}/rules/${enc(id)}`, {
      method: "DELETE",
    }),

  addAppBudget: (deviceId: string, budget: Partial<AppBudget>) =>
    request<{ appBudgets: AppBudget[] }>(`/devices/${enc(deviceId)}/app-budgets`, {
      method: "POST",
      body: JSON.stringify(budget),
    }),
  removeAppBudget: (deviceId: string, id: string) =>
    request<{ appBudgets: AppBudget[] }>(
      `/devices/${enc(deviceId)}/app-budgets/${enc(id)}`,
      { method: "DELETE" }
    ),

  addTriggerWord: (deviceId: string, word: string) =>
    request<{ triggerWords: TriggerWord[] }>(`/devices/${enc(deviceId)}/trigger-words`, {
      method: "POST",
      body: JSON.stringify({ word }),
    }),
  removeTriggerWord: (deviceId: string, word: string) =>
    request<{ triggerWords: TriggerWord[] }>(
      `/devices/${enc(deviceId)}/trigger-words/${enc(word)}`,
      { method: "DELETE" }
    ),

  addBlockedApp: (deviceId: string, appId: string) =>
    request<{ blockedApps: BlockedApp[] }>(`/devices/${enc(deviceId)}/blocked-apps`, {
      method: "POST",
      body: JSON.stringify({ appId }),
    }),
  removeBlockedApp: (deviceId: string, appId: string) =>
    request<{ blockedApps: BlockedApp[] }>(
      `/devices/${enc(deviceId)}/blocked-apps/${enc(appId)}`,
      { method: "DELETE" }
    ),

  // macos-only. Removal is delayed by the Mac's own cooldown, unlike removeBlockedApp above --
  // see PendingActionScheduler.swift.
  addProtectedApp: (deviceId: string, app: Omit<ProtectedApp, "addedAt">) =>
    request<{ protectedApps: ProtectedApp[] }>(`/devices/${enc(deviceId)}/protected-apps`, {
      method: "POST",
      body: JSON.stringify(app),
    }),
  removeProtectedApp: (deviceId: string, executableName: string) =>
    request<{ protectedApps: ProtectedApp[] }>(
      `/devices/${enc(deviceId)}/protected-apps/${enc(executableName)}`,
      { method: "DELETE" }
    ),

  // One shared PIN for the whole fleet, not per-device -- every phone syncs the same value from
  // here (see lockprofile_service.py's GUARDIAN_PIN_PATH).
  getPin: () => request<{ pin: string | null; updatedAt: number | null }>("/pin"),
  setPin: (pin: string) =>
    request<{ pin: string; updatedAt: number }>("/pin", {
      method: "POST",
      body: JSON.stringify({ pin }),
    }),

  // Wakes every registered phone via FCM right now instead of waiting out
  // MacTamperPollWorker's 15-minute WorkManager floor (see lockprofile_service.py's
  // _send_fcm_wake). notified is how many tokens the push actually went to -- 0 with
  // fcmConfigured: true usually means no phone has registered a token yet.
  pollNow: () =>
    request<{ status: string; notified: number; fcmConfigured: boolean }>("/poll-now", {
      method: "POST",
    }),
  // Same idea as pollNow() but scoped to one device -- see lockprofile_service.py's
  // _fcm_tokens_for_device. notified can legitimately be 0 for a real, working device whose
  // token predates per-device FCM association (its next app launch backfills it) -- that's not
  // an error, it just means this device syncs on the normal ~15-minute floor instead of instantly.
  pollDeviceNow: (deviceId: string) =>
    request<{ status: string; notified: number }>(`/devices/${enc(deviceId)}/poll-now`, {
      method: "POST",
    }),

  // One shared HabitShare login for the whole fleet -- the phone polls HabitShare's own servers
  // directly with it. Unlike the PIN, knowing this doesn't unlock anything Otterling enforces, so
  // (see lockprofile_service.py's HABITSHARE_ACCOUNT_PATH) the full credential round-trips here.
  getHabitShareAccount: () =>
    request<{ username: string | null; password: string | null; updatedAt: number | null }>("/habitshare-account"),
  setHabitShareAccount: (username: string, password: string) =>
    request<{ username: string; password: string; updatedAt: number }>("/habitshare-account", {
      method: "POST",
      body: JSON.stringify({ username, password }),
    }),
  removeHabitShareAccount: () =>
    request<{ username: null; password: null; updatedAt: number }>("/habitshare-account", { method: "DELETE" }),

  // Changes the one shared guardian login password (used for both this dashboard and /review).
  // Requires the current password -- see lockprofile_service.py's dashboard-password route.
  // Also invalidates every existing /review session, since that session's HMAC key IS this
  // password.
  setDashboardPassword: (currentPassword: string, newPassword: string) =>
    request<{ status: string }>("/dashboard-password", {
      method: "POST",
      body: JSON.stringify({ currentPassword, newPassword }),
    }),

  // One-time account-handoff link -- see lockprofile_service.py's HANDOFF_TOKEN_PATH comment.
  // GET reports pending-link status only (no token -- see that route's own comment for why); the
  // token itself is only ever returned once, from the POST that creates it.
  getHandoffLinkStatus: () => request<{ pending: boolean; expiresAt: number | null }>("/handoff-link"),
  generateHandoffLink: () =>
    request<{ token: string; expiresAt: number }>("/handoff-link", { method: "POST" }),
  cancelHandoffLink: () => request<{ status: string }>("/handoff-link", { method: "DELETE" }),
};

// Not under BASE ("/dashboard-api") -- this is one of the two /dashboard-auth/* routes Caddy lets
// through without the forward_auth session gate (the gate itself lives behind this route). See
// lockprofile_service.py's _handle_dashboard_logout.
export function logout(): Promise<void> {
  return fetch("/dashboard-auth/logout", { method: "POST" }).then(() => undefined);
}

export { ApiError };
