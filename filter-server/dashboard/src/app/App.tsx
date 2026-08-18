import React, { useEffect, useState } from "react";
import {
  Shield, Lock, Clock, Globe, CheckCircle, Bug, RefreshCw,
  Plus, Settings as SettingsIcon, Camera, X, Trash2,
  Home, ListChecks, Timer, AlertTriangle, Moon, Sun,
  BarChart3, Check, Wifi, ChevronDown,
} from "lucide-react";
import { cn, Card, Button, Switch, Pill } from "./components/ui";
import { api, ApiError } from "../lib/api";
import type {
  DeviceSettings, DeviceSummary, ActivityEvent, Rule, RuleSchedule,
} from "../lib/api";

type Screen = "Dashboard" | "Settings" | "Wizard" | "PhotoCapture" | "Friction" | "AccessibilityNag";

interface NavDef {
  id: Screen;
  label: string;
  icon: React.FC<{ className?: string }>;
}

const NAV: NavDef[] = [
  { id: "Dashboard", label: "Overview", icon: Home },
  { id: "Settings", label: "Settings", icon: SettingsIcon },
];

const PREVIEW_NAV: NavDef[] = [
  { id: "Wizard", label: "Rules", icon: ListChecks },
  { id: "PhotoCapture", label: "Verify Habit", icon: Camera },
  { id: "Friction", label: "Delay Timer", icon: Timer },
  { id: "AccessibilityNag", label: "Accessibility", icon: AlertTriangle },
];

const DEVICE_STORAGE_KEY = "otterling.deviceId";
const DAY_LABELS = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];

export default function App() {
  const [screen, setScreen] = useState<Screen>("Dashboard");
  const [dark, setDark] = useState(true);
  const [devices, setDevices] = useState<DeviceSummary[]>([]);
  const [deviceId, setDeviceId] = useState<string>(() => localStorage.getItem(DEVICE_STORAGE_KEY) || "");
  const [settings, setSettings] = useState<DeviceSettings | null>(null);
  const [activity, setActivity] = useState<ActivityEvent[]>([]);
  const [editingRule, setEditingRule] = useState<Rule | null>(null);
  const [refreshToken, setRefreshToken] = useState(0);
  const [loadError, setLoadError] = useState<string | null>(null);
  const reload = () => setRefreshToken((t) => t + 1);

  useEffect(() => {
    document.documentElement.classList.toggle("dark", dark);
  }, [dark]);

  useEffect(() => {
    api.listDevices()
      .then((res) => {
        setDevices(res.devices);
        setLoadError(null);
        if (!deviceId && res.devices.length > 0) setDeviceId(res.devices[0].device_id);
      })
      .catch((err) => setLoadError(err instanceof ApiError ? err.message : "Couldn't reach the server"));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [refreshToken]);

  useEffect(() => {
    if (deviceId) localStorage.setItem(DEVICE_STORAGE_KEY, deviceId);
  }, [deviceId]);

  useEffect(() => {
    if (!deviceId) { setSettings(null); return; }
    api.getSettings(deviceId).then(setSettings).catch(() => setSettings(null));
  }, [deviceId, refreshToken]);

  useEffect(() => {
    if (!deviceId) { setActivity([]); return; }
    api.getActivity(deviceId).then((res) => setActivity(res.events.slice().reverse())).catch(() => setActivity([]));
  }, [deviceId, refreshToken]);

  const navigate = (s: Screen) => setScreen(s);
  const startNewRule = () => { setEditingRule(null); setScreen("Wizard"); };
  const startEditRule = (rule: Rule) => { setEditingRule(rule); setScreen("Wizard"); };

  const renderContent = () => {
    if (loadError) {
      return <ErrorScreen message={loadError} onRetry={reload} />;
    }
    if (!deviceId) {
      return <NoDeviceScreen devices={devices} />;
    }
    switch (screen) {
      case "Dashboard":
        return (
          <DashboardScreen
            deviceId={deviceId}
            settings={settings}
            activity={activity}
            onNavigate={navigate}
            onAddRule={startNewRule}
            onEditRule={startEditRule}
            onReload={reload}
          />
        );
      case "Settings":
        return (
          <SettingsScreen
            deviceId={deviceId}
            settings={settings}
            onNavigate={navigate}
            onAddRule={startNewRule}
            onChanged={reload}
          />
        );
      case "Wizard":
        return (
          <HabitRuleWizard
            deviceId={deviceId}
            settings={settings}
            editingRule={editingRule}
            onNavigate={navigate}
            onSaved={reload}
          />
        );
      case "PhotoCapture":
        return <PhotoCaptureScreen onNavigate={navigate} />;
      case "Friction":
        return <FrictionDelayScreen onNavigate={navigate} seconds={settings?.frictionDelay.seconds ?? 30} />;
      case "AccessibilityNag":
        return <AccessibilityNagScreen onNavigate={navigate} />;
    }
  };

  return (
    <div className="min-h-screen flex bg-background text-on-background">
      <Sidebar
        screen={screen}
        nav={NAV}
        previewNav={PREVIEW_NAV}
        onNavigate={navigate}
        devices={devices}
        deviceId={deviceId}
        onSelectDevice={setDeviceId}
        settings={settings}
        dark={dark}
        onToggleDark={() => setDark(!dark)}
      />
      <main className="flex-1 overflow-y-auto no-scrollbar bg-background min-w-0">
        {renderContent()}
      </main>
    </div>
  );
}

// ─── Chrome ──────────────────────────────────────────────────────────────────

function Sidebar({
  screen, nav, previewNav, onNavigate, devices, deviceId, onSelectDevice, settings, dark, onToggleDark,
}: {
  screen: Screen;
  nav: NavDef[];
  previewNav: NavDef[];
  onNavigate: (s: Screen) => void;
  devices: DeviceSummary[];
  deviceId: string;
  onSelectDevice: (id: string) => void;
  settings: DeviceSettings | null;
  dark: boolean;
  onToggleDark: () => void;
}) {
  const protectionsOn = settings ? Object.values(settings.protections).filter(Boolean).length : 0;
  const protectionsTotal = settings ? Object.keys(settings.protections).length : 0;

  return (
    <aside className="w-[240px] bg-surface border-r border-outline-variant/30 flex flex-col shrink-0 h-screen sticky top-0">
      {/* Logo */}
      <div className="px-4 py-4 flex items-center gap-3">
        <div className="w-9 h-9 rounded-[12px] bg-primary flex items-center justify-center shadow-sm shrink-0">
          <Shield className="w-5 h-5 text-on-primary" />
        </div>
        <div className="min-w-0 flex-1">
          <p className="text-[15px] font-bold tracking-tight leading-tight">Otterling</p>
          <p className="text-[10px] text-on-surface-variant leading-tight">Family Safety</p>
        </div>
        <button
          onClick={onToggleDark}
          className="w-7 h-7 rounded-md flex items-center justify-center text-on-surface-variant hover:bg-surface-variant transition-colors shrink-0"
          title="Toggle theme"
        >
          {dark ? <Sun className="w-[15px] h-[15px]" /> : <Moon className="w-[15px] h-[15px]" />}
        </button>
      </div>

      {/* Device switcher */}
      <div className="mx-3 mb-3">
        <label className="relative block">
          <select
            value={deviceId}
            onChange={(e) => onSelectDevice(e.target.value)}
            className="w-full h-10 pl-3 pr-8 rounded-xl border border-outline-variant/50 bg-surface-variant/40 text-sm font-medium appearance-none focus:outline-none focus:ring-2 focus:ring-primary"
          >
            {devices.length === 0 && <option value="">No devices yet</option>}
            {devices.map((d) => (
              <option key={d.device_id} value={d.device_id}>
                {d.device_name || d.device_id}
              </option>
            ))}
          </select>
          <ChevronDown className="w-3.5 h-3.5 absolute right-3 top-1/2 -translate-y-1/2 text-on-surface-variant pointer-events-none" />
        </label>
      </div>

      {/* Status badge */}
      {settings && (
        <div className="mx-3 mb-3 px-3 py-2 rounded-xl bg-secondary-container/50 border border-secondary/20">
          <div className="flex items-center gap-1.5">
            <span className={cn("w-1.5 h-1.5 rounded-full shrink-0", protectionsOn === protectionsTotal ? "bg-secondary animate-pulse" : "bg-tertiary")} />
            <span className="text-[11px] font-semibold text-secondary">
              {protectionsOn === protectionsTotal ? "Protected" : "Setup required"}
            </span>
          </div>
          <p className="text-[10px] text-on-surface-variant mt-0.5">
            {protectionsOn} of {protectionsTotal} protections active
          </p>
        </div>
      )}

      {/* Nav */}
      <nav className="px-2 flex-1 space-y-px overflow-y-auto no-scrollbar">
        <p className="text-[9px] font-bold uppercase tracking-[0.12em] text-on-surface-variant/50 px-3 py-1.5">Manage</p>
        {nav.map((item) => (
          <SidebarItem key={item.id} item={item} active={screen === item.id} onClick={() => onNavigate(item.id)} />
        ))}
        <div className="h-px bg-outline-variant/40 my-2" />
        <p className="text-[9px] font-bold uppercase tracking-[0.12em] text-on-surface-variant/50 px-3 py-1.5">
          Phone previews
        </p>
        {previewNav.map((item) => (
          <SidebarItem key={item.id} item={item} active={screen === item.id} onClick={() => onNavigate(item.id)} />
        ))}
      </nav>

      <div className="p-3 border-t border-outline-variant/30">
        <p className="text-[10px] text-on-surface-variant/60 leading-snug px-2">
          Signed in via this dashboard's own login.
        </p>
      </div>
    </aside>
  );
}

function SidebarItem({ item, active, onClick }: { item: NavDef; active: boolean; onClick: () => void }) {
  const Icon = item.icon;
  return (
    <button
      onClick={onClick}
      className={cn(
        "w-full flex items-center gap-2.5 px-3 py-2 rounded-xl text-sm font-medium transition-all",
        active
          ? "bg-primary text-on-primary shadow-sm"
          : "text-on-surface-variant hover:bg-surface-variant hover:text-on-surface"
      )}
    >
      <Icon className="w-4 h-4 shrink-0" />
      <span className="flex-1 text-left">{item.label}</span>
    </button>
  );
}

function NoDeviceScreen({ devices }: { devices: DeviceSummary[] }) {
  return (
    <div className="h-full flex items-center justify-center p-8">
      <div className="max-w-sm text-center space-y-2">
        <Smartphone className="w-10 h-10 mx-auto text-on-surface-variant/40" />
        <h2 className="text-lg font-bold">No devices yet</h2>
        <p className="text-sm text-on-surface-variant">
          {devices.length === 0
            ? "Once a phone reports in (or you configure settings for one), it'll show up here."
            : "Pick a device from the sidebar to get started."}
        </p>
      </div>
    </div>
  );
}

function Smartphone(props: { className?: string }) {
  // Small local icon to avoid pulling another lucide import just for the empty state.
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} className={props.className}>
      <rect x="6" y="2" width="12" height="20" rx="2" />
      <line x1="11" y1="18" x2="13" y2="18" />
    </svg>
  );
}

function ErrorScreen({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <div className="h-full flex items-center justify-center p-8">
      <div className="max-w-sm text-center space-y-3">
        <AlertTriangle className="w-10 h-10 mx-auto text-error" />
        <h2 className="text-lg font-bold">Couldn't load the dashboard</h2>
        <p className="text-sm text-on-surface-variant">{message}</p>
        <Button size="sm" onClick={onRetry}>Try again</Button>
      </div>
    </div>
  );
}

function PreviewBanner({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex items-start gap-2.5 px-4 py-2.5 bg-tertiary-container/40 border-b border-tertiary/20 text-xs text-on-surface-variant">
      <AlertTriangle className="w-3.5 h-3.5 text-tertiary shrink-0 mt-0.5" />
      <span>{children}</span>
    </div>
  );
}

// ─── Dashboard ────────────────────────────────────────────────────────────────

function DashboardScreen({
  deviceId, settings, activity, onNavigate, onAddRule, onEditRule, onReload,
}: {
  deviceId: string;
  settings: DeviceSettings | null;
  activity: ActivityEvent[];
  onNavigate: (s: Screen) => void;
  onAddRule: () => void;
  onEditRule: (rule: Rule) => void;
  onReload: () => void;
}) {
  const [busyRuleId, setBusyRuleId] = useState<string | null>(null);

  if (!settings) {
    return <div className="p-7 text-sm text-on-surface-variant">Loading…</div>;
  }

  const removeRule = async (id: string) => {
    setBusyRuleId(id);
    try {
      await api.removeRule(deviceId, id);
      onReload();
    } finally {
      setBusyRuleId(null);
    }
  };

  return (
    <div className="p-7 space-y-5 max-w-[1080px]">
      {/* Header */}
      <div className="flex items-start justify-between">
        <div>
          <h1 className="text-2xl font-bold tracking-tight">
            {settings.device_name || deviceId}
          </h1>
          <p className="text-sm text-on-surface-variant mt-0.5">
            {new Date().toLocaleDateString(undefined, { weekday: "long", month: "long", day: "numeric" })}
          </p>
        </div>
        <div className="flex gap-2.5 mt-0.5">
          <Button variant="outlined" size="sm" className="gap-2" onClick={() => onNavigate("Settings")}>
            <Globe className="w-4 h-4" /> Block Website
          </Button>
          <Button size="sm" className="gap-2" onClick={onAddRule}>
            <Plus className="w-4 h-4" /> Add Rule
          </Button>
        </div>
      </div>

      {/* Stat row */}
      <div className="grid grid-cols-4 gap-3">
        <StatTile icon={ListChecks} label="Rules Active" value={String(settings.rules.length)} sub="Enforced now" hue="primary" />
        <StatTile icon={CheckCircle} label="Habits" value={String(settings.habits.length)} sub="Configured" hue="secondary" />
        <StatTile icon={Lock} label="Blocked Sites" value={String(settings.blockedWebsites.length)} sub="Custom list" hue="error" />
        <StatTile icon={BarChart3} label="App Budgets" value={String(settings.appBudgets.length)} sub="Daily limits set" hue="tertiary" />
      </div>

      {/* Main grid */}
      <div className="grid grid-cols-5 gap-4">
        {/* Rules column */}
        <div className="col-span-3 space-y-3">
          <div className="flex items-center justify-between px-0.5">
            <h2 className="font-semibold text-base">Active Rules</h2>
            <button
              onClick={onReload}
              className="p-1.5 rounded-lg hover:bg-surface-variant text-on-surface-variant transition-colors"
            >
              <RefreshCw className="w-3.5 h-3.5" />
            </button>
          </div>

          {settings.rules.length === 0 && (
            <Card className="rounded-2xl text-center py-8">
              <p className="text-sm text-on-surface-variant">No habit rules yet.</p>
              <Button size="sm" className="mt-3 gap-1.5" onClick={onAddRule}>
                <Plus className="w-3.5 h-3.5" /> Add your first rule
              </Button>
            </Card>
          )}

          {settings.rules.map((rule) => (
            <Card key={rule.id} className="rounded-2xl space-y-3">
              <div className="flex items-center justify-between gap-3">
                <div className="flex items-center gap-3 min-w-0">
                  <AppIcon name={(rule.appName || "?").slice(0, 2).toUpperCase()} color="bg-primary/10 text-primary" />
                  <div className="min-w-0">
                    <p className="font-semibold leading-tight truncate">{rule.appName}</p>
                    <p className="text-xs text-on-surface-variant">{describeSchedule(rule.schedule)}</p>
                  </div>
                </div>
                <div className="flex items-center gap-1.5 shrink-0">
                  <Button variant="text" size="sm" className="h-7 px-2 text-xs" onClick={() => onEditRule(rule)}>
                    Edit
                  </Button>
                  <button
                    onClick={() => removeRule(rule.id)}
                    disabled={busyRuleId === rule.id}
                    className="w-7 h-7 rounded-lg flex items-center justify-center text-on-surface-variant hover:bg-error-container hover:text-error transition-colors disabled:opacity-50"
                  >
                    <Trash2 className="w-3.5 h-3.5" />
                  </button>
                </div>
              </div>
              {rule.requiredHabitIds.length > 0 && (
                <>
                  <div className="h-px bg-outline-variant/30" />
                  <div className="flex flex-wrap gap-1.5">
                    {rule.requiredHabitIds.map((hid) => {
                      const habit = settings.habits.find((h) => h.id === hid);
                      return <Pill key={hid}>{habit ? habit.name : "Unknown habit"}</Pill>;
                    })}
                  </div>
                </>
              )}
              {rule.dailyBudgetMinutes != null && (
                <div className="flex items-center gap-2 text-xs font-medium text-tertiary">
                  <Clock className="w-3 h-3 shrink-0" /> {rule.dailyBudgetMinutes}m daily budget
                </div>
              )}
            </Card>
          ))}
        </div>

        {/* Right column */}
        <div className="col-span-2 space-y-4">
          {/* App budgets */}
          <Card className="rounded-2xl">
            <h3 className="font-semibold text-sm mb-3.5 flex items-center gap-2">
              <BarChart3 className="w-4 h-4 text-primary" /> App Time Budgets
            </h3>
            {settings.appBudgets.length === 0 ? (
              <p className="text-xs text-on-surface-variant">No daily limits set yet.</p>
            ) : (
              <div className="space-y-3">
                {settings.appBudgets.map((b) => (
                  <div key={b.id} className="flex items-center justify-between text-xs">
                    <span className="font-medium">{b.appName}</span>
                    <span className="text-on-surface-variant tabular-nums">
                      {b.dailyLimitMinutes != null ? `${b.dailyLimitMinutes}m / day` : "No limit"}
                    </span>
                  </div>
                ))}
              </div>
            )}
          </Card>

          {/* Blocked websites */}
          <Card className="rounded-2xl">
            <h3 className="font-semibold text-sm mb-3.5 flex items-center gap-2">
              <Globe className="w-4 h-4 text-primary" /> Blocked Websites
            </h3>
            {settings.blockedWebsites.length === 0 ? (
              <p className="text-xs text-on-surface-variant">No custom websites blocked.</p>
            ) : (
              <div className="flex flex-wrap gap-1.5">
                {settings.blockedWebsites.map((w) => (
                  <Pill key={w.domain}>{w.domain}</Pill>
                ))}
              </div>
            )}
          </Card>

          {/* Activity log */}
          <Card className="rounded-2xl bg-surface-variant/20 border-none">
            <h3 className="text-sm font-semibold mb-2.5 flex items-center gap-2">
              <Bug className="w-3.5 h-3.5" /> Activity Log
            </h3>
            {activity.length === 0 ? (
              <p className="text-xs text-on-surface-variant">No logs captured yet.</p>
            ) : (
              <div className="space-y-1.5" style={{ fontFamily: "var(--font-mono)" }}>
                {activity.slice(0, 8).map((e) => (
                  <div key={e.id} className="flex gap-2.5 text-[10px]">
                    <span className="text-on-surface-variant/40 shrink-0 tabular-nums">
                      {new Date(e.reported_at * 1000).toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" })}
                    </span>
                    <span className="text-on-surface-variant/80 truncate">{e.details || e.type}</span>
                  </div>
                ))}
              </div>
            )}
          </Card>
        </div>
      </div>
    </div>
  );
}

function describeSchedule(schedule: RuleSchedule): string {
  if (!schedule || (!schedule.startTime && !schedule.daysOfWeek)) return "Always";
  const days = schedule.daysOfWeek && schedule.daysOfWeek.length > 0 && schedule.daysOfWeek.length < 7
    ? schedule.daysOfWeek.slice().sort().map((d) => DAY_LABELS[d]).join(", ")
    : "Every day";
  const window = schedule.startTime && schedule.endTime ? `${schedule.startTime}–${schedule.endTime}` : "";
  return [days, window].filter(Boolean).join(", ");
}

// ─── Settings ────────────────────────────────────────────────────────────────

function SettingsScreen({
  deviceId, settings, onNavigate, onAddRule, onChanged,
}: {
  deviceId: string;
  settings: DeviceSettings | null;
  onNavigate: (s: Screen) => void;
  onAddRule: () => void;
  onChanged: () => void;
}) {
  const [pinModalOpen, setPinModalOpen] = useState(false);
  const [emailDraft, setEmailDraft] = useState("");
  const [nameDraft, setNameDraft] = useState("");

  useEffect(() => {
    setEmailDraft(settings?.guardianEmail ?? "");
    setNameDraft(settings?.device_name ?? "");
  }, [settings?.guardianEmail, settings?.device_name]);

  if (!settings) {
    return <div className="p-7 text-sm text-on-surface-variant">Loading…</div>;
  }

  const patchProtection = (key: keyof DeviceSettings["protections"], value: boolean) =>
    api.patchSettings(deviceId, { protections: { ...settings.protections, [key]: value } }).then(onChanged);

  const patchVpnEnabled = (value: boolean) =>
    api.patchSettings(deviceId, { vpnFilter: { enabled: value } }).then(onChanged);

  const patchFriction = (updates: Partial<DeviceSettings["frictionDelay"]>) =>
    api.patchSettings(deviceId, { frictionDelay: { ...settings.frictionDelay, ...updates } }).then(onChanged);

  return (
    <div className="p-7 max-w-[900px] space-y-6">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">Settings</h1>
        <p className="text-sm text-on-surface-variant mt-0.5">
          Configure protection and habit rules for {settings.device_name || deviceId}
        </p>
      </div>

      <div className="grid grid-cols-2 gap-4">
        {/* Device name */}
        <div className="space-y-3 col-span-2">
          <SectionLabel>Device</SectionLabel>
          <Card className="rounded-2xl">
            <div className="flex items-center gap-3">
              <input
                value={nameDraft}
                onChange={(e) => setNameDraft(e.target.value)}
                placeholder={deviceId}
                className="flex-1 h-10 px-3 rounded-xl border border-outline bg-surface text-sm focus:outline-none focus:ring-2 focus:ring-primary"
              />
              <Button
                size="sm"
                onClick={() => api.patchSettings(deviceId, { device_name: nameDraft }).then(onChanged)}
              >
                Save
              </Button>
            </div>
          </Card>
        </div>

        {/* Protection */}
        <div className="space-y-3">
          <SectionLabel>Tamper Protection</SectionLabel>
          <Card className="rounded-2xl space-y-0 divide-y divide-outline-variant/30">
            <SettingsRow
              title="Block Safe Mode bypass"
              sub="Prevent circumventing rules via reboot"
              checked={settings.protections.safeMode}
              onChange={(v) => patchProtection("safeMode", v)}
            />
            <SettingsRow
              title="Block factory reset"
              sub="Require PIN to wipe device"
              checked={settings.protections.factoryReset}
              onChange={(v) => patchProtection("factoryReset", v)}
            />
            <SettingsRow
              title="Block app uninstall"
              sub="Require PIN to remove Otterling"
              checked={settings.protections.uninstallBlock}
              onChange={(v) => patchProtection("uninstallBlock", v)}
              danger
            />
            <SettingsRow
              title="Block guest mode"
              sub="Prevent switching to an unmanaged profile"
              checked={settings.protections.guestMode}
              onChange={(v) => patchProtection("guestMode", v)}
            />
            <SettingsRow
              title="Block USB debugging"
              sub="Prevent ADB-based tampering"
              checked={settings.protections.usbDebugging}
              onChange={(v) => patchProtection("usbDebugging", v)}
            />
          </Card>
        </div>

        {/* VPN & Content */}
        <div className="space-y-3">
          <SectionLabel>Content Filter VPN</SectionLabel>
          <Card className="rounded-2xl space-y-0 divide-y divide-outline-variant/30">
            <SettingsRow
              title="Enable VPN Filter"
              sub="Blocks adult content globally across all apps"
              checked={settings.vpnFilter.enabled}
              onChange={patchVpnEnabled}
            />
            <div className="py-3 px-1">
              <div className="flex items-center gap-2 mb-2">
                <Wifi className="w-4 h-4 text-secondary" />
                <span className="text-sm font-medium">Bypass apps</span>
                <Pill variant={settings.vpnFilter.enabled ? "success" : "default"}>
                  {settings.vpnFilter.enabled ? "Filter active" : "Filter off"}
                </Pill>
              </div>
              <p className="text-xs text-on-surface-variant mb-2">Apps allowed to skip the content filter.</p>
              <TagList
                items={settings.vpnBypassApps.map((a) => ({ id: a.id, label: a.name }))}
                placeholder="App name…"
                onAdd={(name) => api.addBypassApp(deviceId, name).then(onChanged)}
                onRemove={(id) => api.removeBypassApp(deviceId, id).then(onChanged)}
              />
            </div>
          </Card>
        </div>

        {/* Blocked websites */}
        <div className="space-y-3">
          <SectionLabel>Blocked Websites</SectionLabel>
          <Card className="rounded-2xl">
            <p className="text-xs text-on-surface-variant mb-2">Add-only — the device owner can never remove these.</p>
            <TagList
              items={settings.blockedWebsites.map((w) => ({ id: w.domain, label: w.domain }))}
              placeholder="example.com"
              onAdd={(domain) => api.addWebsite(deviceId, domain).then(onChanged)}
              onRemove={(domain) => api.removeWebsite(deviceId, domain).then(onChanged)}
            />
          </Card>
        </div>

        {/* Habits */}
        <div className="space-y-3">
          <SectionLabel>Habits & Rules</SectionLabel>
          <Card className="rounded-2xl space-y-0 divide-y divide-outline-variant/30">
            <div className="py-3 px-1">
              <p className="text-sm font-medium mb-2">Habit library</p>
              <TagList
                items={settings.habits.map((h) => ({ id: h.id, label: h.name }))}
                placeholder="New habit…"
                onAdd={(name) => api.addHabit(deviceId, name).then(onChanged)}
                onRemove={(id) => api.removeHabit(deviceId, id).then(onChanged)}
              />
            </div>
            <SettingsRow
              title="Friction delay"
              sub="Show countdown before unlocking apps"
              checked={settings.frictionDelay.enabled}
              onChange={(v) => patchFriction({ enabled: v })}
            />
            {settings.frictionDelay.enabled && (
              <div className="py-3 px-1 flex items-center gap-3">
                <label className="text-sm text-on-surface-variant">Delay</label>
                <input
                  type="number"
                  min={5}
                  max={300}
                  defaultValue={settings.frictionDelay.seconds}
                  onBlur={(e) => patchFriction({ seconds: Number(e.target.value) || 30 })}
                  className="w-20 h-9 px-3 rounded-xl border border-outline bg-surface text-sm focus:outline-none focus:ring-2 focus:ring-primary"
                />
                <span className="text-xs text-on-surface-variant">seconds</span>
              </div>
            )}
            <div className="py-3 px-1">
              <Button variant="text" size="sm" className="px-0 text-xs h-7" onClick={onAddRule}>
                Add new rule →
              </Button>
            </div>
          </Card>
        </div>

        {/* PIN & Security */}
        <div className="space-y-3">
          <SectionLabel>Guardian Access</SectionLabel>
          <Card className="rounded-2xl space-y-0 divide-y divide-outline-variant/30">
            <div className="py-3 px-1 flex items-center justify-between">
              <div>
                <p className="text-sm font-medium">Guardian PIN</p>
                <p className="text-xs text-on-surface-variant">Used for local unlock on the child's phone</p>
              </div>
              <Button variant="outlined" size="sm" onClick={() => setPinModalOpen(true)}>Change PIN</Button>
            </div>
            <div className="py-3 px-1 flex items-center gap-3">
              <input
                type="email"
                value={emailDraft}
                onChange={(e) => setEmailDraft(e.target.value)}
                placeholder="Recovery email"
                className="flex-1 h-9 px-3 rounded-xl border border-outline bg-surface text-sm focus:outline-none focus:ring-2 focus:ring-primary"
              />
              <Button
                variant="text"
                size="sm"
                className="text-xs h-9"
                onClick={() => api.patchSettings(deviceId, { guardianEmail: emailDraft }).then(onChanged)}
              >
                Save
              </Button>
            </div>
          </Card>
        </div>
      </div>

      <div className="flex gap-3 pt-2">
        <Button variant="outlined" size="sm" onClick={() => onNavigate("AccessibilityNag")}>
          Preview accessibility screen
        </Button>
        <Button variant="text" size="sm" onClick={() => onNavigate("Dashboard")}>
          ← Back to Overview
        </Button>
      </div>

      {pinModalOpen && (
        <div className="fixed inset-0 z-50 bg-black/70 backdrop-blur-md flex items-center justify-center">
          <SetPinModal
            onClose={() => setPinModalOpen(false)}
            onSave={async (pin) => {
              await api.setPin(deviceId, pin);
              setPinModalOpen(false);
            }}
          />
        </div>
      )}
    </div>
  );
}

function TagList({
  items, placeholder, onAdd, onRemove,
}: {
  items: { id: string; label: string }[];
  placeholder: string;
  onAdd: (value: string) => Promise<unknown>;
  onRemove: (id: string) => Promise<unknown>;
}) {
  const [draft, setDraft] = useState("");
  const [busy, setBusy] = useState(false);

  const submit = async () => {
    const value = draft.trim();
    if (!value) return;
    setBusy(true);
    try {
      await onAdd(value);
      setDraft("");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="space-y-2">
      {items.length > 0 && (
        <div className="flex flex-wrap gap-1.5">
          {items.map((item) => (
            <span
              key={item.id}
              className="inline-flex items-center gap-1 pl-2.5 pr-1 py-1 rounded-full text-xs font-medium bg-surface-variant text-on-surface-variant"
            >
              {item.label}
              <button
                onClick={() => onRemove(item.id)}
                className="w-4 h-4 rounded-full flex items-center justify-center hover:bg-error-container hover:text-error transition-colors"
              >
                <X className="w-2.5 h-2.5" />
              </button>
            </span>
          ))}
        </div>
      )}
      <div className="flex items-center gap-2">
        <input
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={(e) => { if (e.key === "Enter") submit(); }}
          placeholder={placeholder}
          className="flex-1 h-9 px-3 rounded-xl border border-outline bg-surface text-sm focus:outline-none focus:ring-2 focus:ring-primary"
        />
        <Button variant="tonal" size="sm" className="h-9 px-3" disabled={busy || !draft.trim()} onClick={submit}>
          <Plus className="w-3.5 h-3.5" />
        </Button>
      </div>
    </div>
  );
}

function SetPinModal({ onClose, onSave }: { onClose: () => void; onSave: (pin: string) => Promise<void> }) {
  const [pin, setPin] = useState("");
  const [confirm, setConfirm] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const submit = async () => {
    if (pin.length !== 4) { setError("PIN must be 4 digits"); return; }
    if (pin !== confirm) { setError("PINs don't match"); return; }
    setSaving(true);
    setError(null);
    try {
      await onSave(pin);
    } catch {
      setError("Couldn't save — try again");
    } finally {
      setSaving(false);
    }
  };

  return (
    <Card className="w-[380px] rounded-3xl shadow-2xl p-8 border border-outline-variant/40">
      <div className="flex items-center justify-between mb-6">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 bg-primary-container rounded-full flex items-center justify-center">
            <Lock className="w-5 h-5 text-primary" />
          </div>
          <div>
            <h2 className="font-bold text-base leading-tight">Change Guardian PIN</h2>
            <p className="text-xs text-on-surface-variant">Used for local unlock on the phone</p>
          </div>
        </div>
        <button
          onClick={onClose}
          className="w-8 h-8 rounded-lg hover:bg-surface-variant flex items-center justify-center text-on-surface-variant transition-colors"
        >
          <X className="w-4 h-4" />
        </button>
      </div>
      <div className="space-y-3">
        <input
          type="password"
          inputMode="numeric"
          maxLength={4}
          value={pin}
          onChange={(e) => setPin(e.target.value.replace(/\D/g, ""))}
          placeholder="New 4-digit PIN"
          className="w-full h-11 px-4 rounded-xl border border-outline bg-surface text-sm tracking-[0.5em] focus:outline-none focus:ring-2 focus:ring-primary"
        />
        <input
          type="password"
          inputMode="numeric"
          maxLength={4}
          value={confirm}
          onChange={(e) => setConfirm(e.target.value.replace(/\D/g, ""))}
          placeholder="Confirm PIN"
          className="w-full h-11 px-4 rounded-xl border border-outline bg-surface text-sm tracking-[0.5em] focus:outline-none focus:ring-2 focus:ring-primary"
        />
        {error && <p className="text-error text-sm font-medium">{error}</p>}
        <Button className="w-full" disabled={saving} onClick={submit}>
          {saving ? "Saving…" : "Save PIN"}
        </Button>
      </div>
    </Card>
  );
}

// ─── Wizard ───────────────────────────────────────────────────────────────────

const COMMON_APPS = [
  { name: "TikTok", color: "bg-[#ff004f]/10 text-[#ff004f]" },
  { name: "Instagram", color: "bg-pink-500/10 text-pink-500" },
  { name: "YouTube", color: "bg-red-500/10 text-red-500" },
  { name: "Snapchat", color: "bg-yellow-400/10 text-yellow-600" },
  { name: "BeReal", color: "bg-black/10 text-on-surface" },
  { name: "Discord", color: "bg-indigo-500/10 text-indigo-500" },
];

function HabitRuleWizard({
  deviceId, settings, editingRule, onNavigate, onSaved,
}: {
  deviceId: string;
  settings: DeviceSettings | null;
  editingRule: Rule | null;
  onNavigate: (s: Screen) => void;
  onSaved: () => void;
}) {
  const [step, setStep] = useState(1);
  const [appQuery, setAppQuery] = useState("");
  const [selectedApp, setSelectedApp] = useState(editingRule?.appName ?? "");
  const [selectedHabitIds, setSelectedHabitIds] = useState<string[]>(editingRule?.requiredHabitIds ?? []);
  const [newHabit, setNewHabit] = useState("");
  const [startTime, setStartTime] = useState(editingRule?.schedule.startTime ?? "00:00");
  const [endTime, setEndTime] = useState(editingRule?.schedule.endTime ?? "21:00");
  const [days, setDays] = useState<number[]>(editingRule?.schedule.daysOfWeek ?? [1, 2, 3, 4, 5]);
  const [budget, setBudget] = useState<string>(editingRule?.dailyBudgetMinutes != null ? String(editingRule.dailyBudgetMinutes) : "");
  const [saving, setSaving] = useState(false);

  const steps = [
    { n: 1, label: "Choose App", sub: "Select target application" },
    { n: 2, label: "Require Habits", sub: "Must be completed to unlock" },
    { n: 3, label: "Set Schedule", sub: "When rule applies" },
  ];

  const filteredApps = COMMON_APPS.filter((a) => a.name.toLowerCase().includes(appQuery.toLowerCase()));
  const habits = settings?.habits ?? [];

  const toggleHabit = (id: string) =>
    setSelectedHabitIds((prev) => (prev.includes(id) ? prev.filter((h) => h !== id) : [...prev, id]));

  const toggleDay = (day: number) =>
    setDays((prev) => (prev.includes(day) ? prev.filter((d) => d !== day) : [...prev, day].sort()));

  const addCustomHabit = async () => {
    const name = newHabit.trim();
    if (!name) return;
    const res = await api.addHabit(deviceId, name);
    const created = res.habits[res.habits.length - 1];
    if (created) setSelectedHabitIds((prev) => [...prev, created.id]);
    setNewHabit("");
    onSaved();
  };

  const save = async () => {
    if (!selectedApp) return;
    setSaving(true);
    const payload = {
      appName: selectedApp,
      requiredHabitIds: selectedHabitIds,
      schedule: { startTime, endTime, daysOfWeek: days },
      dailyBudgetMinutes: budget.trim() ? Number(budget) : null,
    };
    try {
      if (editingRule) {
        await api.updateRule(deviceId, editingRule.id, payload);
      } else {
        await api.addRule(deviceId, payload);
      }
      onSaved();
      onNavigate("Dashboard");
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="h-full flex flex-col">
      <div className="flex-1 flex overflow-hidden">
        {/* Step sidebar */}
        <div className="w-60 border-r border-outline-variant/30 p-6 shrink-0 flex flex-col">
          <h2 className="text-xl font-bold mb-1">{editingRule ? "Edit Rule" : "Create Rule"}</h2>
          <p className="text-xs text-on-surface-variant mb-6">Define habit requirements for app access</p>
          <div className="space-y-2">
            {steps.map((s) => (
              <div key={s.n} className={cn("flex items-start gap-3 p-3 rounded-xl transition-colors", step === s.n && "bg-primary/10")}>
                <div
                  className={cn(
                    "w-6 h-6 rounded-full flex items-center justify-center text-xs font-bold shrink-0 mt-0.5 transition-all",
                    step > s.n ? "bg-secondary text-on-secondary" : step === s.n ? "bg-primary text-on-primary" : "bg-surface-variant text-on-surface-variant"
                  )}
                >
                  {step > s.n ? <Check className="w-3 h-3" /> : s.n}
                </div>
                <div>
                  <p className={cn("text-sm font-semibold leading-tight", step === s.n ? "text-primary" : step > s.n ? "text-secondary" : "text-on-surface-variant")}>
                    {s.label}
                  </p>
                  <p className="text-xs text-on-surface-variant mt-0.5">{s.sub}</p>
                </div>
              </div>
            ))}
          </div>
        </div>

        {/* Step content */}
        <div className="flex-1 p-7 overflow-y-auto no-scrollbar">
          {step === 1 && (
            <div className="max-w-xl space-y-4">
              <div>
                <h3 className="text-lg font-bold">Which app should be gated?</h3>
                <p className="text-sm text-on-surface-variant mt-0.5">The child must complete their habits before accessing this app.</p>
              </div>
              <input
                type="text"
                value={appQuery}
                onChange={(e) => setAppQuery(e.target.value)}
                placeholder="Search or type a custom app name…"
                className="w-full h-11 px-4 rounded-xl border border-outline bg-surface text-sm focus:outline-none focus:ring-2 focus:ring-primary"
              />
              <div className="grid grid-cols-2 gap-2">
                {filteredApps.map((app) => (
                  <button
                    key={app.name}
                    onClick={() => { setSelectedApp(app.name); setStep(2); }}
                    className={cn(
                      "flex items-center gap-3 p-3.5 rounded-xl border transition-all text-left",
                      selectedApp === app.name ? "border-primary bg-primary/5" : "border-outline-variant/40 hover:bg-surface-variant"
                    )}
                  >
                    <div className={cn("w-9 h-9 rounded-lg flex items-center justify-center text-xs font-bold", app.color)}>
                      {app.name.slice(0, 2)}
                    </div>
                    <span className="font-medium text-sm">{app.name}</span>
                  </button>
                ))}
                {appQuery.trim() && filteredApps.length === 0 && (
                  <button
                    onClick={() => { setSelectedApp(appQuery.trim()); setStep(2); }}
                    className="flex items-center gap-3 p-3.5 rounded-xl border border-dashed border-outline-variant/60 hover:bg-surface-variant transition-all text-left col-span-2"
                  >
                    <Plus className="w-4 h-4 text-primary" />
                    <span className="font-medium text-sm">Use "{appQuery.trim()}"</span>
                  </button>
                )}
              </div>
            </div>
          )}

          {step === 2 && (
            <div className="max-w-xl space-y-4">
              <div>
                <h3 className="text-lg font-bold">Required habits</h3>
                <p className="text-sm text-on-surface-variant mt-0.5">Select which habits must be done before {selectedApp || "the app"} unlocks.</p>
              </div>
              {habits.length === 0 ? (
                <p className="text-sm text-on-surface-variant">No habits yet — add one below.</p>
              ) : (
                <div className="grid grid-cols-2 gap-2">
                  {habits.map((h) => {
                    const sel = selectedHabitIds.includes(h.id);
                    return (
                      <button
                        key={h.id}
                        onClick={() => toggleHabit(h.id)}
                        className={cn(
                          "flex items-center gap-3 p-3.5 rounded-xl border transition-all text-left",
                          sel ? "border-secondary bg-secondary-container/40" : "border-outline-variant/40 hover:bg-surface-variant"
                        )}
                      >
                        <div className={cn("w-5 h-5 rounded-full border-2 flex items-center justify-center shrink-0", sel ? "border-secondary bg-secondary" : "border-outline")}>
                          {sel && <Check className="w-3 h-3 text-on-secondary" />}
                        </div>
                        <span className="text-sm font-medium">{h.name}</span>
                      </button>
                    );
                  })}
                </div>
              )}
              <div className="flex items-center gap-2 pt-2">
                <input
                  value={newHabit}
                  onChange={(e) => setNewHabit(e.target.value)}
                  onKeyDown={(e) => { if (e.key === "Enter") addCustomHabit(); }}
                  placeholder="Add custom habit…"
                  className="flex-1 h-9 px-3 rounded-xl border border-outline bg-surface text-sm focus:outline-none focus:ring-2 focus:ring-primary"
                />
                <Button variant="text" size="sm" className="gap-1.5 text-xs" onClick={addCustomHabit}>
                  <Plus className="w-3.5 h-3.5" /> Add
                </Button>
              </div>
            </div>
          )}

          {step === 3 && (
            <div className="max-w-xl space-y-5">
              <div>
                <h3 className="text-lg font-bold">When does this rule apply?</h3>
                <p className="text-sm text-on-surface-variant mt-0.5">Set the time window and days for this rule.</p>
              </div>
              <Card className="rounded-2xl space-y-5">
                <div>
                  <label className="text-sm font-semibold block mb-2">Active time window</label>
                  <div className="flex items-center gap-3">
                    <input type="time" value={startTime} onChange={(e) => setStartTime(e.target.value)} className="flex-1 h-10 px-3 rounded-xl border border-outline bg-surface text-sm focus:outline-none focus:ring-2 focus:ring-primary" />
                    <span className="text-sm text-on-surface-variant">to</span>
                    <input type="time" value={endTime} onChange={(e) => setEndTime(e.target.value)} className="flex-1 h-10 px-3 rounded-xl border border-outline bg-surface text-sm focus:outline-none focus:ring-2 focus:ring-primary" />
                  </div>
                </div>
                <div>
                  <label className="text-sm font-semibold block mb-2.5">Days of week</label>
                  <div className="flex gap-2">
                    {["S", "M", "T", "W", "T", "F", "S"].map((d, i) => (
                      <button
                        key={i}
                        onClick={() => toggleDay(i)}
                        className={cn(
                          "w-10 h-10 rounded-full flex items-center justify-center text-sm font-semibold cursor-pointer transition-colors select-none",
                          days.includes(i) ? "bg-primary text-on-primary" : "bg-surface-variant text-on-surface-variant hover:bg-outline-variant"
                        )}
                      >
                        {d}
                      </button>
                    ))}
                  </div>
                </div>
                <div>
                  <label className="text-sm font-semibold block mb-2">Daily time budget (optional)</label>
                  <div className="flex items-center gap-3">
                    <input
                      type="number"
                      value={budget}
                      onChange={(e) => setBudget(e.target.value)}
                      min={0}
                      max={480}
                      className="w-24 h-10 px-3 rounded-xl border border-outline bg-surface text-sm focus:outline-none focus:ring-2 focus:ring-primary"
                    />
                    <span className="text-sm text-on-surface-variant">minutes per day</span>
                  </div>
                </div>
              </Card>
            </div>
          )}
        </div>
      </div>

      {/* Wizard footer */}
      <div className="border-t border-outline-variant/30 px-7 py-4 flex items-center justify-between bg-surface/40">
        <Button variant="text" size="sm" onClick={() => (step > 1 ? setStep(step - 1) : onNavigate("Dashboard"))}>
          {step > 1 ? "← Back" : "Cancel"}
        </Button>
        <div className="flex items-center gap-3">
          <div className="flex gap-1.5">
            {[1, 2, 3].map((n) => (
              <div key={n} className={cn("w-1.5 h-1.5 rounded-full transition-all", step >= n ? "bg-primary" : "bg-surface-variant")} />
            ))}
          </div>
          <Button
            size="sm"
            disabled={(step === 1 && !selectedApp) || saving}
            onClick={() => (step < 3 ? setStep(step + 1) : save())}
          >
            {saving ? "Saving…" : step < 3 ? "Continue →" : "Save Rule"}
          </Button>
        </div>
      </div>
    </div>
  );
}

// ─── Photo Capture (phone-side preview only) ──────────────────────────────────

function PhotoCaptureScreen({ onNavigate }: { onNavigate: (s: Screen) => void }) {
  return (
    <div className="h-full flex flex-col">
      <PreviewBanner>
        Preview only — this is what your child's phone shows when proving a habit. Photo capture and
        AI matching happen on-device; nothing here is enforced from the browser.
      </PreviewBanner>
      <div className="flex-1 flex">
        <div className="flex-1 bg-neutral-950 relative flex items-center justify-center">
          <div className="absolute inset-10 border border-dashed border-white/15 rounded-3xl" />
          <div className="flex flex-col items-center text-white/30">
            <Camera className="w-16 h-16 mb-3" />
            <p className="text-sm">Camera preview</p>
          </div>
        </div>
        <div className="w-80 border-l border-outline-variant/30 bg-background flex flex-col">
          <div className="p-6 border-b border-outline-variant/30">
            <h2 className="text-xl font-bold">Prove it: [habit name]</h2>
            <p className="text-sm text-on-surface-variant mt-1">Photo must match the reference to unlock apps.</p>
          </div>
          <div className="p-6 flex-1 flex flex-col gap-5">
            <div>
              <p className="text-[10px] font-bold uppercase tracking-wider text-on-surface-variant/60 mb-2">Reference Photo</p>
              <div className="h-40 bg-surface-variant rounded-2xl flex items-center justify-center text-on-surface-variant/40">
                <div className="text-center">
                  <Camera className="w-8 h-8 mx-auto mb-1" />
                  <p className="text-xs">Reference image</p>
                </div>
              </div>
            </div>
            <div className="text-xs text-on-surface-variant space-y-1.5 bg-surface-variant/30 rounded-xl p-3">
              <p className="font-semibold text-on-surface mb-1">Tips for a good match:</p>
              <p>· Same angle and distance as the reference</p>
              <p>· Good lighting, avoid harsh shadows</p>
              <p>· Make sure the whole area is visible</p>
            </div>
          </div>
          <div className="p-6 pt-0">
            <Button variant="text" className="w-full" onClick={() => onNavigate("Dashboard")}>
              ← Back to Overview
            </Button>
          </div>
        </div>
      </div>
    </div>
  );
}

// ─── Friction Delay (phone-side preview only) ─────────────────────────────────

function FrictionDelayScreen({ onNavigate, seconds }: { onNavigate: (s: Screen) => void; seconds: number }) {
  const [timeLeft, setTimeLeft] = useState(seconds);

  useEffect(() => setTimeLeft(seconds), [seconds]);

  useEffect(() => {
    if (timeLeft > 0) {
      const t = setTimeout(() => setTimeLeft((p) => p - 1), 1000);
      return () => clearTimeout(t);
    }
  }, [timeLeft]);

  const pct = seconds > 0 ? ((seconds - timeLeft) / seconds) * 100 : 100;

  return (
    <div className="h-full flex flex-col">
      <PreviewBanner>
        Preview only — this is what your child's phone shows before opening a "mindful" app. The
        {" "}{seconds}s delay reflects your current Friction Delay setting; nothing here is enforced from the browser.
      </PreviewBanner>
      <div className="flex-1 flex items-center justify-center bg-secondary-container/30">
        <div className="max-w-sm w-full px-8 text-center">
          <h1 className="text-4xl font-bold text-secondary mb-3">Pause.</h1>
          <p className="text-base text-on-secondary-container/80 leading-relaxed mb-12">
            Is opening this app the best use of your time right now?
          </p>
          <div className="relative w-36 h-36 mx-auto mb-12">
            <svg className="w-full h-full -rotate-90" viewBox="0 0 144 144">
              <circle cx="72" cy="72" r="66" fill="none" stroke="currentColor" strokeWidth="6" className="text-secondary/15" />
              <circle
                cx="72" cy="72" r="66" fill="none" stroke="currentColor" strokeWidth="6" strokeLinecap="round"
                strokeDasharray={Math.PI * 2 * 66}
                strokeDashoffset={Math.PI * 2 * 66 * (1 - pct / 100)}
                className="text-secondary transition-all duration-1000 ease-linear"
              />
            </svg>
            <div className="absolute inset-0 flex items-center justify-center">
              {timeLeft > 0 ? (
                <span className="text-4xl font-light tabular-nums text-on-secondary-container">{timeLeft}</span>
              ) : (
                <CheckCircle className="w-12 h-12 text-secondary" />
              )}
            </div>
          </div>
          <div className="space-y-3">
            <Button
              className={cn("w-full transition-all", timeLeft === 0 ? "bg-secondary text-on-secondary hover:bg-secondary/90" : "bg-secondary/30 text-secondary cursor-not-allowed")}
              disabled={timeLeft > 0}
              onClick={() => onNavigate("Dashboard")}
            >
              {timeLeft > 0 ? `Wait ${timeLeft}s...` : "Continue to app →"}
            </Button>
            <Button variant="text" className="w-full text-on-secondary-container/60" onClick={() => onNavigate("Dashboard")}>
              Close without opening
            </Button>
          </div>
        </div>
      </div>
    </div>
  );
}

// ─── Accessibility Nag (phone-side preview only) ──────────────────────────────

function AccessibilityNagScreen({ onNavigate }: { onNavigate: (s: Screen) => void }) {
  return (
    <div className="h-full flex flex-col">
      <PreviewBanner>
        Preview only — this is the un-dismissable nag your child's phone shows if the required
        accessibility service gets turned off. It's tied to the phone's own OS state; nothing here
        is enforced from the browser.
      </PreviewBanner>
      <div className="flex-1 flex items-center justify-center p-8">
        <div className="max-w-2xl w-full">
          <div className="grid grid-cols-2 gap-8 items-start">
            <div className="flex flex-col items-start">
              <div className="w-16 h-16 bg-error-container rounded-2xl flex items-center justify-center mb-5">
                <Shield className="w-8 h-8 text-error" />
              </div>
              <h1 className="text-2xl font-bold text-error mb-2">Protection Disabled</h1>
              <p className="text-sm text-on-surface-variant leading-relaxed mb-6">
                The required accessibility service was turned off. Otterling cannot monitor or block apps until it is re-enabled.
              </p>
              <div className="p-4 bg-error-container/40 rounded-2xl border border-error/20 w-full mb-6">
                <div className="flex items-center gap-2 text-error font-semibold text-sm mb-1">
                  <AlertTriangle className="w-4 h-4" /> Children are unprotected
                </div>
                <p className="text-xs text-on-surface-variant">All rules and habits are currently bypassed.</p>
              </div>
              <Button variant="text" size="sm" className="w-full mt-2" onClick={() => onNavigate("Dashboard")}>
                ← Back to Overview
              </Button>
            </div>
            <div>
              <h2 className="font-semibold text-base mb-4">How to re-enable protection</h2>
              <div className="space-y-3">
                {[
                  { n: 1, title: "Open System Settings", sub: 'Tap the gear icon → "Accessibility"' },
                  { n: 2, title: "Find \"Otterling\"", sub: "Scroll the list until you see the Otterling entry" },
                  { n: 3, title: "Turn the toggle ON", sub: "You may need to authenticate to confirm" },
                ].map((s) => (
                  <div key={s.n} className="flex items-start gap-3 p-3.5 bg-surface rounded-2xl border border-outline-variant/30">
                    <div className="w-6 h-6 rounded-full bg-primary-container flex items-center justify-center shrink-0 mt-0.5">
                      <span className="text-xs font-bold text-primary">{s.n}</span>
                    </div>
                    <div>
                      <p className="text-sm font-semibold">{s.title}</p>
                      <p className="text-xs text-on-surface-variant mt-0.5">{s.sub}</p>
                    </div>
                  </div>
                ))}
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

// ─── Shared helpers ───────────────────────────────────────────────────────────

function StatTile({
  icon: Icon, label, value, sub, hue,
}: {
  icon: React.FC<{ className?: string }>;
  label: string;
  value: string;
  sub: string;
  hue: "primary" | "secondary" | "tertiary" | "error";
}) {
  const colors = {
    primary: "bg-primary-container/60 text-primary",
    secondary: "bg-secondary-container/60 text-secondary",
    tertiary: "bg-tertiary-container/60 text-tertiary",
    error: "bg-error-container/60 text-error",
  };
  return (
    <Card className="rounded-2xl p-4 space-y-2">
      <div className={cn("w-8 h-8 rounded-xl flex items-center justify-center", colors[hue])}>
        <Icon className="w-4 h-4" />
      </div>
      <div>
        <p className="text-xl font-bold leading-none">{value}</p>
        <p className="text-xs font-semibold text-on-surface mt-1">{label}</p>
        <p className="text-[10px] text-on-surface-variant mt-0.5">{sub}</p>
      </div>
    </Card>
  );
}

function AppIcon({ name, color }: { name: string; color: string }) {
  return (
    <div className={cn("w-8 h-8 rounded-lg flex items-center justify-center text-xs font-bold shrink-0", color)}>
      {name}
    </div>
  );
}

function SectionLabel({ children }: { children: React.ReactNode }) {
  return <p className="text-xs font-bold uppercase tracking-wider text-primary px-0.5">{children}</p>;
}

function SettingsRow({
  title, sub, checked, onChange, danger,
}: {
  title: string; sub: string; checked: boolean; onChange: (v: boolean) => void; danger?: boolean;
}) {
  return (
    <div className="py-3 px-1 flex items-center justify-between gap-4">
      <div className="min-w-0">
        <p className={cn("text-sm font-medium", danger && "text-error")}>{title}</p>
        <p className="text-xs text-on-surface-variant mt-0.5 leading-tight">{sub}</p>
      </div>
      <Switch checked={checked} onCheckedChange={onChange} />
    </div>
  );
}
