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

export interface BypassApp {
  id: string;
  name: string;
}

export interface BlockedWebsite {
  domain: string;
  addedAt: number;
}

// Global, shared across every device (see lockprofile_service.py's HABITS_PATH) -- a rule on
// ANY device can reference a habit verified on a different one. doneToday/verifiedAt are
// computed server-side from whichever device most recently reported this habit's completion.
export interface Habit {
  id: string;
  name: string;
  doneToday: boolean;
  verifiedAt: number | null;
}

export interface RuleSchedule {
  startTime?: string;
  endTime?: string;
  daysOfWeek?: number[];
}

export interface Rule {
  id: string;
  appId: string;
  appName: string;
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
  guardianEmail: string;
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
}

export interface DeviceSummary {
  device_id: string;
  device_name: string;
  updatedAt: number | null;
  alertCount24h: number;
  platform: DevicePlatform;
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
  addHabit: (name: string) =>
    request<{ habits: Habit[] }>("/habits", {
      method: "POST",
      body: JSON.stringify({ name }),
    }),
  removeHabit: (id: string) =>
    request<{ habits: Habit[] }>(`/habits/${enc(id)}`, { method: "DELETE" }),

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
};

// Not under BASE ("/dashboard-api") -- this is one of the two /dashboard-auth/* routes Caddy lets
// through without the forward_auth session gate (the gate itself lives behind this route). See
// lockprofile_service.py's _handle_dashboard_logout.
export function logout(): Promise<void> {
  return fetch("/dashboard-auth/logout", { method: "POST" }).then(() => undefined);
}

export { ApiError };
