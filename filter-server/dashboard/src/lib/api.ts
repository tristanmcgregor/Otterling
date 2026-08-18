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

export interface Habit {
  id: string;
  name: string;
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

export interface DeviceSettings {
  device_name: string;
  protections: Protections;
  vpnFilter: { enabled: boolean };
  vpnBypassApps: BypassApp[];
  blockedWebsites: BlockedWebsite[];
  frictionDelay: { enabled: boolean; seconds: number };
  habits: Habit[];
  rules: Rule[];
  appBudgets: AppBudget[];
  guardianEmail: string;
  updatedAt: number | null;
}

export interface DeviceSummary {
  device_id: string;
  device_name: string;
  updatedAt: number | null;
  alertCount24h: number;
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

  addHabit: (deviceId: string, name: string) =>
    request<{ habits: Habit[] }>(`/devices/${enc(deviceId)}/habits`, {
      method: "POST",
      body: JSON.stringify({ name }),
    }),
  removeHabit: (deviceId: string, id: string) =>
    request<{ habits: Habit[] }>(`/devices/${enc(deviceId)}/habits/${enc(id)}`, {
      method: "DELETE",
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

  setPin: (deviceId: string, pin: string) =>
    request<{ status: string }>(`/devices/${enc(deviceId)}/pin`, {
      method: "POST",
      body: JSON.stringify({ pin }),
    }),
};

export { ApiError };
