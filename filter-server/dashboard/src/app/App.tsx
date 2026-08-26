import React, { useEffect, useState } from "react";
import {
  Shield, Lock, Clock, Globe, CheckCircle, Bug, RefreshCw,
  Plus, Settings as SettingsIcon, X, Trash2, LogOut, Laptop,
  Home, ListChecks, AlertTriangle, Moon, Sun,
  BarChart3, Check, Wifi, ChevronDown, ShieldCheck,
} from "lucide-react";
import { cn, Card, Button, Switch, Pill } from "./components/ui";
import { api, ApiError, logout } from "../lib/api";
import type {
  DeviceSettings, DeviceSummary, ActivityEvent, Rule, RuleSchedule, RuleTargetApp, RuleTargetWebsite,
  AppBudget, ProtectedApp, Habit, ReportType, ReportTypesFile, DefaultSettings, Protections, DevicePlatform,
} from "../lib/api";

type Screen =
  | "Dashboard"
  | "BlockedApps"
  | "BlockedSites"
  | "ProtectedApps"
  | "ContentFilter"
  | "Settings"
  | "Habits"
  | "GlobalSettings"
  | "Wizard";

interface NavDef {
  id: Screen;
  label: string;
  icon: React.FC<{ className?: string }>;
}

// Mirrors the macOS app's own sidebar taxonomy (ContentView.swift's "Protect" group: Overview,
// Blocked Apps, Blocked Sites, Protected Apps, Content Filter) instead of cramming
// everything into one long Settings page -- each of these is now its own focused nav item/screen.
// Blocked Sites (Android-only, DNS/VPN-filter blocklist) and Protected Apps (macOS-only,
// filesystem-locked apps) are hidden for a platform that has no such concept, same as those
// sections used to be conditionally hidden inside the old single Settings page. `platform`
// undefined (still loading, or no device selected yet) shows the fuller Android-shaped set so the
// sidebar doesn't flicker empty before the first settings fetch resolves.
function deviceNav(platform: DevicePlatform | undefined): NavDef[] {
  const items: NavDef[] = [
    { id: "Dashboard", label: "Overview", icon: Home },
    { id: "BlockedApps", label: "Blocked Apps", icon: Lock },
  ];
  if (platform !== "macos") items.push({ id: "BlockedSites", label: "Blocked Sites", icon: Globe });
  if (platform === "macos") items.push({ id: "ProtectedApps", label: "Protected Apps", icon: ShieldCheck });
  items.push({ id: "ContentFilter", label: "Content Filter", icon: Wifi });
  items.push({ id: "Settings", label: "Settings", icon: SettingsIcon });
  return items;
}

// Fleet-wide, not scoped to whichever device is selected in the switcher above -- Guardian PIN,
// habit rules, HabitShare account, and the dashboard/review login passwords all live here instead
// of inside per-device Settings, where they used to be mixed in despite already being global data
// (see GlobalSettingsScreen's doc comment). The habit library itself has its own top-level "Habits"
// nav entry (see HabitsScreen) since it's a thing guardians check on its own, not just a setting.
// Its own nav section so it doesn't read as "a setting of whichever device happens to be selected
// right now".
const FLEET_NAV: NavDef[] = [
  { id: "Habits", label: "Habits", icon: CheckCircle },
  { id: "GlobalSettings", label: "Global Settings", icon: Globe },
];

const DEVICE_STORAGE_KEY = "otterling.deviceId";
const DAY_LABELS = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
const IDLE_LOGOUT_MS = 20 * 60 * 1000;

export default function App() {
  const [screen, setScreen] = useState<Screen>("Dashboard");
  const [dark, setDark] = useState(true);
  const [devices, setDevices] = useState<DeviceSummary[]>([]);
  const [deviceId, setDeviceId] = useState<string>(() => localStorage.getItem(DEVICE_STORAGE_KEY) || "");
  const [settings, setSettings] = useState<DeviceSettings | null>(null);
  const [activity, setActivity] = useState<ActivityEvent[]>([]);
  const [habits, setHabits] = useState<Habit[]>([]);
  const [rules, setRules] = useState<Rule[]>([]);
  const [editingRule, setEditingRule] = useState<Rule | null>(null);
  const [refreshToken, setRefreshToken] = useState(0);
  const [loadError, setLoadError] = useState<string | null>(null);
  const reload = () => setRefreshToken((t) => t + 1);
  const reloadHabits = () => api.getHabits().then((res) => setHabits(res.habits)).catch(() => setHabits([]));
  const reloadRules = () => api.listRules().then((res) => setRules(res.rules)).catch(() => setRules([]));

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

  // Global habit library -- not scoped to the selected device, unlike settings/activity below.
  useEffect(() => {
    reloadHabits();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [refreshToken]);

  // Global rule library -- likewise not scoped to the selected device (see lockprofile_service.py's
  // RULES_PATH). GET .../devices/<id>/settings still embeds the subset that applies to THIS
  // device (settings.rules) for the per-device summary; this is the full fleet-wide list, used by
  // GlobalSettingsScreen's Habit Rules section.
  useEffect(() => {
    reloadRules();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [refreshToken]);

  useEffect(() => {
    if (!deviceId) { setSettings(null); return; }
    api.getSettings(deviceId)
      .then((s) => { setSettings(s); setLoadError(null); })
      .catch((err) => {
        // A silent setSettings(null) here previously left the dashboard stuck on a bare
        // "Loading..." forever on failure (e.g. a device_id the server couldn't route), with no
        // way to tell a slow request from a broken one. Surfacing it as loadError instead gives
        // an ErrorScreen with a retry button.
        setSettings(null);
        setLoadError(err instanceof ApiError ? err.message : "Couldn't load this device's settings");
      });
  }, [deviceId, refreshToken]);

  useEffect(() => {
    if (!deviceId) { setActivity([]); return; }
    api.getActivity(deviceId).then((res) => setActivity(res.events.slice().reverse())).catch(() => setActivity([]));
  }, [deviceId, refreshToken]);

  // Auto-logout after IDLE_LOGOUT_MS of no interaction. The session cookie itself has no Max-Age
  // (see lockprofile_service.py's _set_dashboard_session_cookie) so it *should* clear when the
  // browser closes -- but Chrome/Firefox/Edge's "continue where you left off" restores session
  // cookies across a real restart regardless, which a Set-Cookie header can't override. An idle
  // timer is the only way to reliably end a session after a guardian actually walks away, so this
  // is the real logout mechanism; the session-cookie behavior is just a best-effort second layer.
  useEffect(() => {
    const lastActivity = { current: Date.now() };
    const bump = () => { lastActivity.current = Date.now(); };
    const events: (keyof WindowEventMap)[] = ["pointerdown", "keydown", "scroll", "wheel"];
    events.forEach((e) => window.addEventListener(e, bump, { passive: true }));
    const interval = window.setInterval(() => {
      if (Date.now() - lastActivity.current >= IDLE_LOGOUT_MS) {
        window.clearInterval(interval);
        logout().finally(() => window.location.reload());
      }
    }, 30_000);
    return () => {
      events.forEach((e) => window.removeEventListener(e, bump));
      window.clearInterval(interval);
    };
  }, []);

  const navigate = (s: Screen) => setScreen(s);
  const startNewRule = () => { setEditingRule(null); setScreen("Wizard"); };
  const startEditRule = (rule: Rule) => { setEditingRule(rule); setScreen("Wizard"); };

  const renderContent = () => {
    if (loadError) {
      return <ErrorScreen message={loadError} onRetry={reload} />;
    }
    // Global Settings and the rule Wizard are both fleet-wide, not scoped to a device, so they're
    // reachable even before any device has ever registered -- unlike every other screen below,
    // which needs one selected. A rule now names its own deviceIds (chosen inside the wizard
    // itself, see HabitRuleWizard's Devices step) instead of being tied to whichever device
    // happened to be selected in the sidebar when "Add Rule" was clicked.
    if (screen === "GlobalSettings") {
      return <GlobalSettingsScreen />;
    }
    if (screen === "Wizard") {
      return (
        <HabitRuleWizard
          devices={devices}
          settings={settings}
          habits={habits}
          editingRule={editingRule}
          onNavigate={navigate}
          onSaved={reload}
        />
      );
    }
    if (screen === "Habits") {
      return (
        <HabitsScreen
          habits={habits}
          onHabitsChanged={reloadHabits}
          rules={rules}
          devices={devices}
          onRulesChanged={reload}
          onAddRule={startNewRule}
          onEditRule={startEditRule}
        />
      );
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
            habits={habits}
            onNavigate={navigate}
          />
        );
      case "BlockedApps":
        return <BlockedAppsScreen deviceId={deviceId} settings={settings} onChanged={reload} />;
      case "BlockedSites":
        return <BlockedSitesScreen deviceId={deviceId} settings={settings} onChanged={reload} onNavigate={navigate} />;
      case "ProtectedApps":
        return <ProtectedAppsScreen deviceId={deviceId} settings={settings} onChanged={reload} onNavigate={navigate} />;
      case "ContentFilter":
        return <ContentFilterScreen deviceId={deviceId} settings={settings} onChanged={reload} />;
      case "Settings":
        return (
          <SettingsScreen
            deviceId={deviceId}
            settings={settings}
            onNavigate={navigate}
            onChanged={reload}
            onDeviceRemoved={() => { setDeviceId(""); reload(); }}
          />
        );
    }
  };

  return (
    <div className="min-h-screen flex bg-background text-on-background">
      <Sidebar
        screen={screen}
        nav={deviceNav(settings?.platform)}
        fleetNav={FLEET_NAV}
        onNavigate={navigate}
        devices={devices}
        deviceId={deviceId}
        onSelectDevice={setDeviceId}
        settings={settings}
        dark={dark}
        onToggleDark={() => setDark(!dark)}
        onLogout={() => logout().finally(() => window.location.reload())}
      />
      <main className="flex-1 overflow-y-auto no-scrollbar bg-background min-w-0">
        {renderContent()}
      </main>
    </div>
  );
}

// ─── Chrome ──────────────────────────────────────────────────────────────────

function Sidebar({
  screen, nav, fleetNav, onNavigate, devices, deviceId, onSelectDevice, settings, dark, onToggleDark, onLogout,
}: {
  screen: Screen;
  nav: NavDef[];
  fleetNav: NavDef[];
  onNavigate: (s: Screen) => void;
  devices: DeviceSummary[];
  deviceId: string;
  onSelectDevice: (id: string) => void;
  settings: DeviceSettings | null;
  dark: boolean;
  onToggleDark: () => void;
  onLogout: () => void;
}) {
  const isMac = settings?.platform === "macos";
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
      {settings && isMac && (
        <div className="mx-3 mb-3 px-3 py-2 rounded-xl bg-secondary-container/50 border border-secondary/20">
          <div className="flex items-center gap-1.5">
            <span className="w-1.5 h-1.5 rounded-full shrink-0 bg-secondary animate-pulse" />
            <span className="text-[11px] font-semibold text-secondary">Tamper monitoring active</span>
          </div>
          <p className="text-[10px] text-on-surface-variant mt-0.5">Managed locally by FocusLock</p>
        </div>
      )}
      {settings && !isMac && (
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

      <PollNowButton />

      {/* Nav */}
      <nav className="px-2 flex-1 space-y-px overflow-y-auto no-scrollbar">
        <p className="text-[9px] font-bold uppercase tracking-[0.12em] text-on-surface-variant/50 px-3 py-1.5">Protect</p>
        {nav.map((item) => (
          <SidebarItem key={item.id} item={item} active={screen === item.id} onClick={() => onNavigate(item.id)} />
        ))}
        <div className="h-px bg-outline-variant/40 my-2" />
        <p className="text-[9px] font-bold uppercase tracking-[0.12em] text-on-surface-variant/50 px-3 py-1.5">
          Fleet
        </p>
        {fleetNav.map((item) => (
          <SidebarItem key={item.id} item={item} active={screen === item.id} onClick={() => onNavigate(item.id)} />
        ))}
      </nav>

      <div className="p-3 border-t border-outline-variant/30 space-y-2">
        <button
          onClick={onLogout}
          className="w-full flex items-center gap-2.5 px-3 py-2 rounded-xl text-sm font-medium text-on-surface-variant hover:bg-error-container hover:text-error transition-colors"
        >
          <LogOut className="w-4 h-4 shrink-0" />
          <span className="flex-1 text-left">Log out</span>
        </button>
        <p className="text-[10px] text-on-surface-variant/60 leading-snug px-2">
          Signed in via this dashboard's own login. Auto logs out after 20 minutes idle.
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

// Wakes every registered phone via FCM right now (see api.pollNow) instead of waiting out
// MacTamperPollWorker's 15-minute WorkManager floor -- fleet-wide, not scoped to the selected
// device, so this lives in the Sidebar chrome rather than a per-device screen.
function PollNowButton() {
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<string | null>(null);

  const handleClick = () => {
    setBusy(true);
    setResult(null);
    api.pollNow()
      .then((res) => {
        if (!res.fcmConfigured) setResult("Push isn't configured on the server");
        else if (res.notified === 0) setResult("No phone has registered for push yet");
        else setResult(`Sent to ${res.notified} device${res.notified === 1 ? "" : "s"}`);
      })
      .catch((err) => setResult(err instanceof ApiError ? err.message : "Couldn't reach the server"))
      .finally(() => {
        setBusy(false);
        window.setTimeout(() => setResult(null), 5000);
      });
  };

  return (
    <div className="mx-3 mb-3">
      <button
        onClick={handleClick}
        disabled={busy}
        className="w-full flex items-center justify-center gap-2 h-9 rounded-xl border border-outline-variant/50 bg-surface-variant/40 text-sm font-medium text-on-surface hover:bg-surface-variant transition-colors disabled:opacity-60"
      >
        <RefreshCw className={cn("w-3.5 h-3.5", busy && "animate-spin")} />
        {busy ? "Polling..." : "Poll now"}
      </button>
      {result && <p className="text-[10px] text-on-surface-variant mt-1 text-center leading-snug">{result}</p>}
    </div>
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

// ─── Dashboard ────────────────────────────────────────────────────────────────

function DashboardScreen({
  deviceId, settings, activity, habits, onNavigate,
}: {
  deviceId: string;
  settings: DeviceSettings | null;
  activity: ActivityEvent[];
  habits: Habit[];
  onNavigate: (s: Screen) => void;
}) {
  if (!settings) {
    return <div className="p-7 text-sm text-on-surface-variant">Loading…</div>;
  }
  const goToRules = () => onNavigate("GlobalSettings");

  if (settings.platform === "macos") {
    const dnsOn = settings.vpnFilter.enabled;
    const hasBlocking = settings.blockedApps.length > 0 || settings.protectedApps.length > 0;
    const headline = dnsOn ? "Protected" : hasBlocking ? "Partially Protected" : "Setup Required";
    const subtitle = dnsOn
      ? "Content filter active"
      : hasBlocking
      ? "Content filter is off, but blocked/protected apps are still enforced"
      : "Content filter is off — turn it on from Settings";
    const statusHue = dnsOn ? "success" : hasBlocking ? "warning" : "error";

    return (
      <div className="p-7 space-y-5 max-w-[1080px]">
        {/* Header */}
        <div className="flex items-start justify-between">
          <div>
            <h1 className="text-2xl font-bold tracking-tight">{settings.device_name || deviceId}</h1>
            <p className="text-sm text-on-surface-variant mt-0.5">
              {new Date().toLocaleDateString(undefined, { weekday: "long", month: "long", day: "numeric" })}
            </p>
          </div>
          <Button size="sm" className="gap-2 mt-0.5" onClick={goToRules}>
            <Plus className="w-4 h-4" /> Add Rule
          </Button>
        </div>

        {/* Status card -- mirrors the local FocusLock app's own Overview status card */}
        <Card className="rounded-2xl">
          <div className="flex items-center gap-3.5">
            <div
              className={cn(
                "w-12 h-12 rounded-2xl flex items-center justify-center shrink-0",
                statusHue === "success" ? "bg-secondary-container/60 text-secondary"
                  : statusHue === "warning" ? "bg-tertiary-container/60 text-tertiary"
                  : "bg-error-container/60 text-error"
              )}
            >
              {statusHue === "success" ? <Shield className="w-6 h-6" /> : <AlertTriangle className="w-6 h-6" />}
            </div>
            <div>
              <p className="font-bold text-base leading-tight">{headline}</p>
              <p className="text-xs text-on-surface-variant mt-0.5">{subtitle}</p>
            </div>
          </div>
        </Card>

        {/* Stat row */}
        <div className="grid grid-cols-4 gap-3">
          <StatTile icon={Lock} label="Apps Blocked" value={String(settings.blockedApps.length)} sub="Killed on sight" hue="error" />
          <StatTile icon={Shield} label="Apps Protected" value={String(settings.protectedApps.length)} sub="Kept alive" hue="secondary" />
          <StatTile icon={ListChecks} label="Rules Active" value={String(settings.rules.length)} sub="Enforced now" hue="primary" />
          <StatTile
            icon={Wifi}
            label="Content Filter"
            value={dnsOn ? "On" : "Off"}
            sub={dnsOn ? "DNS enforced" : "Disabled"}
            hue={dnsOn ? "secondary" : "tertiary"}
          />
        </div>

        {/* Main grid */}
        <div className="grid grid-cols-5 gap-4">
          {/* Rules column -- same rendering as the Android dashboard; rules work identically for
              a Mac device (see HabitRuleWizard's platform-conditional app-identifier field). Read
              -only here: creating/editing/deleting a rule (including which devices it targets)
              happens in Global Settings now, since a rule is no longer tied to whichever device
              happens to be selected in the sidebar. */}
          <div className="col-span-3 space-y-3">
            <ActiveRulesSummary rules={settings.rules} habits={habits} onManage={goToRules} />
          </div>

          {/* Right column */}
          <div className="col-span-2 space-y-4">
            <Card className="rounded-2xl">
              <h3 className="font-semibold text-sm mb-1.5 flex items-center gap-2">
                <Laptop className="w-4 h-4 text-primary" /> Managed locally by FocusLock
              </h3>
              <p className="text-xs text-on-surface-variant leading-relaxed">
                Sudo-elevation gating and trigger-word reporting run locally and aren't
                configurable here. Habits, app budgets, and website blocking are phone-only
                features.
              </p>
            </Card>

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
          <Button size="sm" className="gap-2" onClick={goToRules}>
            <Plus className="w-4 h-4" /> Add Rule
          </Button>
        </div>
      </div>

      {/* Stat row */}
      <div className="grid grid-cols-4 gap-3">
        <StatTile icon={ListChecks} label="Rules Active" value={String(settings.rules.length)} sub="Enforced now" hue="primary" />
        <StatTile icon={CheckCircle} label="Habits" value={String(habits.length)} sub="Shared library" hue="secondary" />
        <StatTile icon={Lock} label="Blocked Sites" value={String(settings.blockedWebsites.length)} sub="Custom list" hue="error" />
        <StatTile icon={BarChart3} label="App Budgets" value={String(settings.appBudgets.length)} sub="Daily limits set" hue="tertiary" />
      </div>

      {/* Main grid */}
      <div className="grid grid-cols-5 gap-4">
        {/* Rules column -- read-only, see the mac branch's own comment on why. */}
        <div className="col-span-3 space-y-3">
          <ActiveRulesSummary rules={settings.rules} habits={habits} onManage={goToRules} />
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

// A rule can gate an app AND a website at once now (see api.ts's Rule doc comment) -- this is the
// one place that turns targetApps/targetWebsites into a single display label, reused everywhere a
// rule is rendered (DashboardScreen's read-only summary, GlobalSettingsScreen's editable list).
function ruleTargetLabel(rule: Rule): string {
  const names = [...rule.targetApps.map((a) => a.appName), ...rule.targetWebsites.map((w) => w.domain)];
  return names.length > 0 ? names.join(", ") : "(no target)";
}

function ruleDeviceLabel(rule: Rule, devices: DeviceSummary[]): string {
  if (rule.deviceIds.includes("all")) return "All devices";
  const names = rule.deviceIds.map((id) => devices.find((d) => d.device_id === id)?.device_name || id);
  return names.length > 0 ? names.join(", ") : "No devices";
}

// Read-only rule list shown on a device's own Dashboard -- creating/editing/deleting (including
// which devices a rule targets) happens in Global Settings now, since a rule is no longer tied to
// whichever device happens to be selected in the sidebar. This is just "what's currently active
// for THIS device" (settings.rules is already server-filtered to that, see
// lockprofile_service.py's _rules_for_device).
function ActiveRulesSummary({
  rules, habits, onManage,
}: {
  rules: Rule[];
  habits: Habit[];
  onManage: () => void;
}) {
  return (
    <>
      <div className="flex items-center justify-between px-0.5">
        <h2 className="font-semibold text-base">Active Rules</h2>
        <Button variant="text" size="sm" className="h-7 px-2 text-xs" onClick={onManage}>
          Manage in Global Settings →
        </Button>
      </div>

      {rules.length === 0 && (
        <Card className="rounded-2xl text-center py-8">
          <p className="text-sm text-on-surface-variant">No habit rules yet.</p>
          <Button size="sm" className="mt-3 gap-1.5" onClick={onManage}>
            <Plus className="w-3.5 h-3.5" /> Add your first rule
          </Button>
        </Card>
      )}

      {rules.map((rule) => (
        <Card key={rule.id} className="rounded-2xl space-y-3">
          <div className="flex items-center gap-3 min-w-0">
            <AppIcon name={ruleTargetLabel(rule).slice(0, 2).toUpperCase()} color="bg-primary/10 text-primary" />
            <div className="min-w-0">
              <div className="flex items-center gap-1.5 flex-wrap">
                <p className="font-semibold leading-tight truncate">{ruleTargetLabel(rule)}</p>
                {rule.targetWebsites.length > 0 && <Pill>Website</Pill>}
                {rule.targetApps.length > 0 && <Pill>App</Pill>}
              </div>
              <p className="text-xs text-on-surface-variant">{describeSchedule(rule.schedule)}</p>
            </div>
          </div>
          {rule.requiredHabitIds.length > 0 && (
            <>
              <div className="h-px bg-outline-variant/30" />
              <div className="flex flex-wrap gap-1.5">
                {rule.requiredHabitIds.map((hid) => {
                  const habit = habits.find((h) => h.id === hid);
                  return (
                    <Pill key={hid} variant={habit?.doneToday ? "success" : "default"}>
                      {habit ? habit.name : "Unknown habit"} {habit?.doneToday ? "✓" : ""}
                    </Pill>
                  );
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
    </>
  );
}

// ─── Settings ────────────────────────────────────────────────────────────────

// Shown by a platform-specific screen (Blocked Sites is Android-only, Protected Apps is
// macOS-only) when the currently-selected device is the other platform -- reachable if the
// guardian switches devices while parked on one of these screens, since `screen` state persists
// across the device switcher. Points back to Overview rather than leaving a dead-looking page up.
function PlatformNotAvailable({ message, onNavigate }: { message: string; onNavigate: (s: Screen) => void }) {
  return (
    <div className="p-7 max-w-[700px] space-y-3">
      <Card className="rounded-2xl text-center py-8">
        <p className="text-sm text-on-surface-variant">{message}</p>
        <Button size="sm" variant="text" className="mt-3" onClick={() => onNavigate("Dashboard")}>
          ← Back to Overview
        </Button>
      </Card>
    </div>
  );
}

function BlockedAppsScreen({
  deviceId, settings, onChanged,
}: {
  deviceId: string;
  settings: DeviceSettings | null;
  onChanged: () => void;
}) {
  if (!settings) return <div className="p-7 text-sm text-on-surface-variant">Loading…</div>;
  const isMac = settings.platform === "macos";
  return (
    <div className="p-7 max-w-[700px] space-y-3">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">Blocked Apps</h1>
        <p className="text-sm text-on-surface-variant mt-0.5">
          Fully blocks an app, all the time -- unlike a habit rule or time budget, there's no
          condition that unlocks it.
        </p>
      </div>
      <Card className="rounded-2xl">
        <p className="text-xs text-on-surface-variant mb-2">
          {isMac ? (
            <>
              Use the exact process/executable name as it appears in Activity Monitor (e.g.{" "}
              <code>Safari</code>) — not the app's display name or bundle identifier. Removing a
              block here applies immediately once the Guardian passcode authorizes it.
            </>
          ) : (
            <>Use the exact Android package name (e.g. <code>com.zhiliaoapp.musically</code>).</>
          )}
        </p>
        <TagList
          items={settings.blockedApps.map((a) => ({ id: a.appId, label: a.appId }))}
          placeholder={isMac ? "Safari" : "com.example.app"}
          onAdd={(appId) => api.addBlockedApp(deviceId, appId).then(onChanged)}
          onRemove={(appId) => api.removeBlockedApp(deviceId, appId).then(onChanged)}
        />
      </Card>
    </div>
  );
}

// Android-only (VPN/DNS-filter blocklist -- see BlockedWebsite's doc comment in lib/api.ts). The
// Mac's equivalent is DNS enforcement, under Content Filter instead.
function BlockedSitesScreen({
  deviceId, settings, onChanged, onNavigate,
}: {
  deviceId: string;
  settings: DeviceSettings | null;
  onChanged: () => void;
  onNavigate: (s: Screen) => void;
}) {
  if (!settings) return <div className="p-7 text-sm text-on-surface-variant">Loading…</div>;
  if (settings.platform === "macos") {
    return <PlatformNotAvailable message="Blocked Sites is an Android-only feature." onNavigate={onNavigate} />;
  }
  return (
    <div className="p-7 max-w-[700px] space-y-3">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">Blocked Sites</h1>
        <p className="text-sm text-on-surface-variant mt-0.5">
          Add-only — the device owner can never remove these.
        </p>
      </div>
      <Card className="rounded-2xl">
        <p className="text-xs text-on-surface-variant mb-2">
          A bare domain (e.g. <code>example.com</code>) blocks the whole site; a domain+path (e.g.{" "}
          <code>youtube.com/shorts</code>) blocks only that path.
        </p>
        <TagList
          items={settings.blockedWebsites.map((w) => ({ id: w.domain, label: w.domain }))}
          placeholder="example.com or youtube.com/shorts"
          onAdd={(domain) => api.addWebsite(deviceId, domain).then(onChanged)}
          onRemove={(domain) => api.removeWebsite(deviceId, domain).then(onChanged)}
        />
      </Card>
    </div>
  );
}

// macOS-only (filesystem-locked, undeletable apps -- see ProtectedApp's doc comment in
// lib/api.ts). No Android equivalent.
function ProtectedAppsScreen({
  deviceId, settings, onChanged, onNavigate,
}: {
  deviceId: string;
  settings: DeviceSettings | null;
  onChanged: () => void;
  onNavigate: (s: Screen) => void;
}) {
  if (!settings) return <div className="p-7 text-sm text-on-surface-variant">Loading…</div>;
  if (settings.platform !== "macos") {
    return <PlatformNotAvailable message="Protected Apps is a macOS-only feature." onNavigate={onNavigate} />;
  }
  return (
    <div className="p-7 max-w-[700px] space-y-3">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">Protected Apps</h1>
        <p className="text-sm text-on-surface-variant mt-0.5">
          Kept running and undeletable (filesystem-locked) instead of blocked — e.g. an
          accountability app whose reporting shouldn't be removable.
        </p>
      </div>
      <Card className="rounded-2xl">
        <p className="text-xs text-on-surface-variant mb-2">
          Use the exact executable name and the full path to the .app bundle.
        </p>
        <ProtectedAppList
          items={settings.protectedApps}
          onAdd={(app) => api.addProtectedApp(deviceId, app).then(onChanged)}
          onRemove={(executableName) => api.removeProtectedApp(deviceId, executableName).then(onChanged)}
        />
      </Card>
    </div>
  );
}

// Shared, platform-conditional (Android: VPN tunnel + per-app bypass list; Mac: DNS enforcement +
// proxy enforcement + cloud filter resolver -- MitmExemptManager's bypass-app concept has no Mac
// equivalent, so that part only renders for Android).
function ContentFilterScreen({
  deviceId, settings, onChanged,
}: {
  deviceId: string;
  settings: DeviceSettings | null;
  onChanged: () => void;
}) {
  const [cloudFilterHostDraft, setCloudFilterHostDraft] = useState("");

  useEffect(() => {
    setCloudFilterHostDraft(settings?.cloudFilterHost ?? "");
  }, [settings?.cloudFilterHost]);

  if (!settings) return <div className="p-7 text-sm text-on-surface-variant">Loading…</div>;
  const isMac = settings.platform === "macos";
  const patchVpnEnabled = (value: boolean) =>
    api.patchSettings(deviceId, { vpnFilter: { enabled: value } }).then(onChanged);

  return (
    <div className="p-7 max-w-[700px] space-y-6">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">Content Filter</h1>
        <p className="text-sm text-on-surface-variant mt-0.5">
          {isMac
            ? "Dashboard-driven -- applies immediately, gated by the Guardian passcode for anything protection-reducing."
            : "Blocks adult content globally across all apps."}
        </p>
      </div>

      <div className="space-y-3">
        <SectionLabel>{isMac ? "DNS Enforcement" : "Content Filter VPN"}</SectionLabel>
        <Card className="rounded-2xl space-y-0 divide-y divide-outline-variant/30">
          <SettingsRow
            title={isMac ? "Enable content filtering" : "Enable VPN Filter"}
            sub={isMac ? "Blocks adult content system-wide via DNS" : "Blocks adult content globally across all apps"}
            checked={settings.vpnFilter.enabled}
            onChange={patchVpnEnabled}
          />
          {!isMac && (
            <div className="py-3 px-1">
              <div className="flex items-center gap-2 mb-2">
                <Wifi className="w-4 h-4 text-secondary" />
                <span className="text-sm font-medium">Bypass apps</span>
                <Pill variant={settings.vpnFilter.enabled ? "success" : "default"}>
                  {settings.vpnFilter.enabled ? "Filter active" : "Filter off"}
                </Pill>
              </div>
              <p className="text-xs text-on-surface-variant mb-2">
                Apps allowed to skip the content filter. Use the exact Android package name (e.g.{" "}
                <code>com.google.android.youtube</code>), not the app's display name — the phone
                matches on this literally.
              </p>
              <TagList
                items={settings.vpnBypassApps.map((a) => ({ id: a.id, label: a.name }))}
                placeholder="com.example.app"
                onAdd={(name) => api.addBypassApp(deviceId, name).then(onChanged)}
                onRemove={(id) => api.removeBypassApp(deviceId, id).then(onChanged)}
              />
            </div>
          )}
        </Card>
      </div>

      {isMac && (
        <>
          <div className="space-y-3">
            <SectionLabel>Proxy Enforcement</SectionLabel>
            <Card className="rounded-2xl space-y-0 divide-y divide-outline-variant/30">
              <SettingsRow
                title="Route traffic through the filter proxy"
                sub="Extra layer beyond DNS filtering — needs the proxy CA/password already provisioned on the Mac"
                checked={settings.proxyFilter?.enabled ?? false}
                onChange={(v) => api.patchSettings(deviceId, { proxyFilter: { enabled: v } }).then(onChanged)}
              />
              <SettingsRow
                title="Force all traffic through the proxy"
                sub="Also blocks direct :80/:443 so non-proxy-aware apps can't bypass it"
                checked={settings.proxyFilter?.forceViaFirewall ?? false}
                onChange={(v) => api.patchSettings(deviceId, { proxyFilter: { forceViaFirewall: v } }).then(onChanged)}
              />
            </Card>
          </div>

          <div className="space-y-3">
            <SectionLabel>Cloud Filter</SectionLabel>
            <Card className="rounded-2xl space-y-0 divide-y divide-outline-variant/30">
              <SettingsRow
                title="Use the cloud filter as DNS resolver"
                sub="Off falls back to Cloudflare Family DNS"
                checked={settings.cloudFilterEnabled ?? true}
                onChange={(v) => api.patchSettings(deviceId, { cloudFilterEnabled: v }).then(onChanged)}
              />
              <div className="py-3 px-1 space-y-1.5">
                <div className="flex items-center gap-3">
                  <input
                    value={cloudFilterHostDraft}
                    onChange={(e) => setCloudFilterHostDraft(e.target.value)}
                    placeholder="vpn.bartholomew.help"
                    className="flex-1 h-9 px-3 rounded-xl border border-outline bg-surface text-sm focus:outline-none focus:ring-2 focus:ring-primary"
                  />
                  <Button
                    variant="text"
                    size="sm"
                    className="text-xs h-9"
                    onClick={() => api.patchSettings(deviceId, { cloudFilterHost: cloudFilterHostDraft }).then(onChanged)}
                  >
                    Save
                  </Button>
                </div>
                <p className="text-xs text-on-surface-variant">
                  Repointing the host always requires the Guardian passcode, even to "fix" it.
                </p>
              </div>
            </Card>
          </div>
        </>
      )}
    </div>
  );
}

function SettingsScreen({
  deviceId, settings, onNavigate, onChanged, onDeviceRemoved,
}: {
  deviceId: string;
  settings: DeviceSettings | null;
  onNavigate: (s: Screen) => void;
  onChanged: () => void;
  onDeviceRemoved: () => void;
}) {
  const [confirmRemove, setConfirmRemove] = useState(false);
  const [removing, setRemoving] = useState(false);
  const [nameDraft, setNameDraft] = useState("");

  useEffect(() => {
    setNameDraft(settings?.device_name ?? "");
  }, [settings?.device_name]);

  if (!settings) {
    return <div className="p-7 text-sm text-on-surface-variant">Loading…</div>;
  }

  const patchProtection = (key: keyof DeviceSettings["protections"], value: boolean) =>
    api.patchSettings(deviceId, { protections: { ...settings.protections, [key]: value } }).then(onChanged);

  const patchFriction = (updates: Partial<DeviceSettings["frictionDelay"]>) =>
    api.patchSettings(deviceId, { frictionDelay: { ...settings.frictionDelay, ...updates } }).then(onChanged);

  // Protections/app budgets/trigger words/friction delay are all Android-only (consumed
  // exclusively by DashboardConfigStore -- RestrictionPreferences.kt, AppTimeBudgetManager.kt,
  // GuardianAlertSettings.kt) -- nothing on the Mac reads dashboard-api, so showing those controls
  // for a macos device would save values that are never actually enforced anywhere.
  const isMac = settings.platform === "macos";

  return (
    <div className="p-7 max-w-[900px] space-y-6">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">Settings</h1>
        <p className="text-sm text-on-surface-variant mt-0.5">
          Everything else for {settings.device_name || deviceId} -- see Blocked Apps, Blocked
          Sites, Protected Apps, and Content Filter in the sidebar for the rest.
        </p>
      </div>

      <div className="columns-2 gap-4 [&>*]:mb-4 [&>*]:break-inside-avoid">
        <div className="space-y-3 [column-span:all]">
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

        {!isMac && (
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
        )}

        {!isMac && (
        <div className="space-y-3">
          <SectionLabel>App Time Budgets</SectionLabel>
          <Card className="rounded-2xl">
            <p className="text-xs text-on-surface-variant mb-2">
              Daily screen-time limit for a specific app. Use the exact Android package name (e.g.{" "}
              <code>com.zhiliaoapp.musically</code>), not the app's display name — the phone matches on this
              literally.
            </p>
            <AppBudgetList
              items={settings.appBudgets}
              onAdd={(budget) => api.addAppBudget(deviceId, budget).then(onChanged)}
              onRemove={(id) => api.removeAppBudget(deviceId, id).then(onChanged)}
            />
          </Card>
        </div>
        )}

        {!isMac && (
        <div className="space-y-3">
          <SectionLabel>Trigger Words</SectionLabel>
          <Card className="rounded-2xl">
            <p className="text-xs text-on-surface-variant mb-2">
              Words or phrases that trigger an accountability-partner SMS alert when typed or seen on screen, in
              addition to whatever's configured on the phone itself.
            </p>
            <TagList
              items={settings.triggerWords.map((t) => ({ id: t.word, label: t.word }))}
              placeholder="New trigger word…"
              onAdd={(word) => api.addTriggerWord(deviceId, word).then(onChanged)}
              onRemove={(word) => api.removeTriggerWord(deviceId, word).then(onChanged)}
            />
          </Card>
        </div>
        )}

        {/* Rules live entirely in Global Settings -- a rule names its own targets AND its own
            deviceIds (see api.ts's Rule doc comment), so it's no longer "this device's" setting
            the way it used to be. */}
        <div className="space-y-3">
          <SectionLabel>Rules</SectionLabel>
          <Card className="rounded-2xl">
            <p className="text-xs text-on-surface-variant mb-2">
              Habit rules (which apps/websites are gated, which devices they apply to) are managed
              from Global Settings now, not per-device.
            </p>
            <Button variant="text" size="sm" className="px-0 text-xs h-7" onClick={() => onNavigate("GlobalSettings")}>
              Manage rules →
            </Button>
          </Card>
        </div>

        {!isMac && (
        <div className="space-y-3">
          <SectionLabel>Friction Delay</SectionLabel>
          <Card className="rounded-2xl space-y-0 divide-y divide-outline-variant/30">
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
          </Card>
        </div>
        )}

        <div className="space-y-3 [column-span:all]">
          <SectionLabel>Diagnostics</SectionLabel>
          <Card className="rounded-2xl">
            <details>
              <summary className="text-sm font-medium cursor-pointer select-none">
                View raw settings JSON
              </summary>
              <p className="text-xs text-on-surface-variant mt-1 mb-2">
                Exactly what <code>GET /dashboard-api/devices/{deviceId}/settings</code> returns for
                this device -- useful for confirming what the phone is actually seeing when
                something looks out of sync.
              </p>
              <pre className="text-[11px] leading-snug bg-surface-variant/50 rounded-xl p-3 overflow-x-auto max-h-96 overflow-y-auto">
                {JSON.stringify(settings, null, 2)}
              </pre>
            </details>
          </Card>
        </div>
      </div>

      <div className="space-y-3">
        <SectionLabel>Danger Zone</SectionLabel>
        <Card className="rounded-2xl border-error/30">
          <div className="flex items-center justify-between gap-3">
            <div>
              <p className="text-sm font-medium">Remove this device</p>
              <p className="text-xs text-on-surface-variant">
                {settings.platform === "macos"
                  ? "Deletes this Mac's settings record from the dashboard. Tamper alert history is kept. If the Mac connects again, it'll reappear with default settings."
                  : `Turns off every protection on ${settings.device_name || deviceId} (VPN, restrictions, blocks) and offers to uninstall Otterling -- within seconds if the phone is online, or the next time it checks in otherwise. Tamper alert history is kept. This can't be undone from the dashboard.`}
              </p>
            </div>
            {confirmRemove ? (
              <div className="flex items-center gap-2 shrink-0">
                <Button variant="text" size="sm" disabled={removing} onClick={() => setConfirmRemove(false)}>
                  Cancel
                </Button>
                <Button
                  size="sm"
                  className="gap-1.5 bg-error text-on-error hover:bg-error/90"
                  disabled={removing}
                  onClick={async () => {
                    setRemoving(true);
                    try {
                      await api.removeDevice(deviceId);
                      onDeviceRemoved();
                    } finally {
                      setRemoving(false);
                    }
                  }}
                >
                  <Trash2 className="w-3.5 h-3.5" /> {removing ? "Removing…" : "Confirm removal"}
                </Button>
              </div>
            ) : (
              <Button variant="outlined" size="sm" className="shrink-0" onClick={() => setConfirmRemove(true)}>
                Remove device
              </Button>
            )}
          </div>
        </Card>
      </div>

      <div className="flex gap-3 pt-2">
        <Button variant="text" size="sm" onClick={() => onNavigate("Dashboard")}>
          ← Back to Overview
        </Button>
      </div>
    </div>
  );
}

// ─── Habits ────────────────────────────────────────────────────────────────────
// Fleet-wide (see api.ts's Habit doc comment), so this is its own top-level nav entry rather than
// nested inside per-device Settings or Global Settings -- guardians check "what habits exist,
// which are done today, which rules gate on them" often enough that it earns its own page instead
// of being one section among many on Global Settings.
function HabitsScreen({
  habits, onHabitsChanged, rules, devices, onRulesChanged, onAddRule, onEditRule,
}: {
  habits: Habit[];
  onHabitsChanged: () => void;
  rules: Rule[];
  devices: DeviceSummary[];
  onRulesChanged: () => void;
  onAddRule: () => void;
  onEditRule: (rule: Rule) => void;
}) {
  return (
    <div className="p-7 max-w-[900px] space-y-6">
      <div>
        <h1 className="text-xl font-bold">Habits</h1>
        <p className="text-sm text-on-surface-variant mt-0.5">
          Which habits exist, which are done today, and which rules gate on them --
          shared across every device on this account.
        </p>
      </div>

      <div className="space-y-3">
        <SectionLabel>Habit Library</SectionLabel>
        <Card className="rounded-2xl">
          <p className="text-xs text-on-surface-variant mb-2">
            Shared across every device on this account — not per-device. A habit checked off
            (and verified) on the phone can satisfy a rule on the Mac. Turn on "Requires proof"
            for a habit and the server will reject a completion report with no photo attached —
            without it, the device's own app-embedded token alone is enough to fake any habit
            done and unlock whatever it gates, fleet-wide.
          </p>
          <HabitLibraryList habits={habits} rules={rules} onChanged={onHabitsChanged} />
        </Card>
      </div>

      <div className="space-y-3">
        <div className="flex items-center justify-between">
          <SectionLabel>Habit Rules</SectionLabel>
          <Button size="sm" className="gap-1.5" onClick={onAddRule}>
            <Plus className="w-3.5 h-3.5" /> Add Rule
          </Button>
        </div>
        <p className="text-xs text-on-surface-variant -mt-1">
          Gates an app and/or a website behind required habits, on whichever device(s) you choose
          below — a single rule can target both an app and a website at once, and apply to one
          device, several, or "All devices".
        </p>
        <GlobalRulesList rules={rules} habits={habits} devices={devices} onChanged={onRulesChanged} onEdit={onEditRule} onAdd={onAddRule} />
      </div>
    </div>
  );
}

// ─── Global Settings ───────────────────────────────────────────────────────────
// Fleet-wide config, not scoped to whichever device happens to be selected in the sidebar's
// device switcher -- Guardian PIN was already global data (one PIN shared across every device)
// but used to live inside per-device SettingsScreen, which read as "a setting of this device"
// when it wasn't. HabitShare account is new here. The habit library and habit rules live in their
// own top-level Habits screen (see HabitsScreen) rather than here.
function GlobalSettingsScreen() {
  const [pinModalOpen, setPinModalOpen] = useState(false);
  const [pinStatus, setPinStatus] = useState<{ pin: string | null; updatedAt: number | null } | null>(null);
  const reloadPinStatus = () => api.getPin().then(setPinStatus).catch(() => setPinStatus(null));
  useEffect(() => { reloadPinStatus(); }, []);

  return (
    <div className="p-7 max-w-[900px] space-y-6">
      <div>
        <h1 className="text-xl font-bold">Global Settings</h1>
        <p className="text-sm text-on-surface-variant mt-0.5">
          Shared across every device on this account — not specific to whichever device is
          selected in the sidebar.
        </p>
      </div>

      <div className="space-y-3">
        <SectionLabel>Guardian PIN</SectionLabel>
        <Card className="rounded-2xl">
          <div className="flex items-center justify-between">
            <div>
              <div className="flex items-center gap-2">
                <p className="text-sm font-medium">Guardian PIN</p>
                <Pill variant={pinStatus?.pin ? "success" : "default"}>
                  {pinStatus?.pin ? "Set" : "Not set"}
                </Pill>
              </div>
              <p className="text-xs text-on-surface-variant">
                Shared across every Otterling device on this account — gates Settings on the phone,
                and also signs into this website and into /review (AI review history, device
                diagnostic logs). Changing it signs out every other /review session.
              </p>
            </div>
            <Button variant="outlined" size="sm" onClick={() => setPinModalOpen(true)}>Change PIN</Button>
          </div>
        </Card>
      </div>

      <div className="space-y-3">
        <SectionLabel>HabitShare Account</SectionLabel>
        <Card className="rounded-2xl">
          <p className="text-xs text-on-surface-variant mb-2">
            The HabitShare login every phone on this account uses to poll HabitShare's own
            servers directly for done/not-done status. Unlike the Guardian PIN, this doesn't gate
            anything Otterling enforces — it's a separate third-party account.
          </p>
          <HabitShareAccountCard />
        </Card>
      </div>

      <div className="space-y-3">
        <SectionLabel>Account Handoff</SectionLabel>
        <Card className="rounded-2xl">
          <HandoffLinkCard />
        </Card>
      </div>

      <div className="space-y-3">
        <SectionLabel>Report Types</SectionLabel>
        <p className="text-xs text-on-surface-variant -mt-1">
          Every kind of accountability report/alert Otterling can send, grouped by where it comes
          from. Turning one off fully suppresses it — no SMS, no local log entry, nothing recorded
          — so use this deliberately. A type not listed here (e.g. a newly added one) defaults to
          on. Takes effect immediately server-side; the phone picks up a change within ~15 minutes.
        </p>
        <ReportTypesPanel />
      </div>

      <div className="space-y-3">
        <SectionLabel>Default Protections for New Devices</SectionLabel>
        <p className="text-xs text-on-surface-variant -mt-1">
          What a brand-new device gets on its very first check-in, before you've touched its own
          Settings screen. Changing this only affects devices that haven't checked in yet — an
          already-configured device is untouched, same as changing this project's own hardcoded
          defaults used to require editing code for.
        </p>
        <DefaultSettingsPanel />
      </div>

      {pinModalOpen && (
        <div className="fixed inset-0 z-50 bg-black/70 backdrop-blur-md flex items-center justify-center">
          <SetPinModal
            onClose={() => setPinModalOpen(false)}
            onSave={async (pin) => {
              await api.setPin(pin);
              setPinModalOpen(false);
              reloadPinStatus();
            }}
          />
        </div>
      )}
    </div>
  );
}

// The fleet-wide rule library's editable list -- see api.ts's Rule doc comment for why a rule now
// carries its own targetApps/targetWebsites (either/both) and deviceIds instead of living inside
// one device's settings. This is the only place rules can be created/edited/deleted now; the
// per-device Dashboard just shows a read-only summary (see ActiveRulesSummary) and links here.
function GlobalRulesList({
  rules, habits, devices, onChanged, onEdit, onAdd,
}: {
  rules: Rule[];
  habits: Habit[];
  devices: DeviceSummary[];
  onChanged: () => void;
  onEdit: (rule: Rule) => void;
  onAdd: () => void;
}) {
  const [busyRuleId, setBusyRuleId] = useState<string | null>(null);

  const removeRule = async (id: string) => {
    setBusyRuleId(id);
    try {
      await api.removeRule(id);
      onChanged();
    } finally {
      setBusyRuleId(null);
    }
  };

  if (rules.length === 0) {
    return (
      <Card className="rounded-2xl text-center py-8">
        <p className="text-sm text-on-surface-variant">No habit rules yet.</p>
        <Button size="sm" className="mt-3 gap-1.5" onClick={onAdd}>
          <Plus className="w-3.5 h-3.5" /> Add your first rule
        </Button>
      </Card>
    );
  }

  return (
    <div className="space-y-2.5">
      {rules.map((rule) => (
        <Card key={rule.id} className="rounded-2xl space-y-3">
          <div className="flex items-center justify-between gap-3">
            <div className="flex items-center gap-3 min-w-0">
              <AppIcon name={ruleTargetLabel(rule).slice(0, 2).toUpperCase()} color="bg-primary/10 text-primary" />
              <div className="min-w-0">
                <div className="flex items-center gap-1.5 flex-wrap">
                  <p className="font-semibold leading-tight truncate">{ruleTargetLabel(rule)}</p>
                  {rule.targetApps.length > 0 && <Pill>{rule.targetApps.length} app{rule.targetApps.length === 1 ? "" : "s"}</Pill>}
                  {rule.targetWebsites.length > 0 && <Pill>{rule.targetWebsites.length} website{rule.targetWebsites.length === 1 ? "" : "s"}</Pill>}
                </div>
                <p className="text-xs text-on-surface-variant">{describeSchedule(rule.schedule)}</p>
                <p className="text-xs text-on-surface-variant flex items-center gap-1 mt-0.5">
                  <Laptop className="w-3 h-3 shrink-0" /> {ruleDeviceLabel(rule, devices)}
                </p>
              </div>
            </div>
            <div className="flex items-center gap-1.5 shrink-0">
              <Button variant="text" size="sm" className="h-7 px-2 text-xs" onClick={() => onEdit(rule)}>
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
                  const habit = habits.find((h) => h.id === hid);
                  return (
                    <Pill key={hid} variant={habit?.doneToday ? "success" : "default"}>
                      {habit ? habit.name : "Unknown habit"} {habit?.doneToday ? "✓" : ""}
                    </Pill>
                  );
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
  );
}

function ReportTypesPanel() {
  const [data, setData] = useState<ReportTypesFile | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.getReportTypes().then(setData).catch(() => setError("Failed to load report types"));
  }, []);

  const toggle = (type: string, enabled: boolean) => {
    api.setReportTypeEnabled(type, enabled).then(setData).catch(() => setError(`Failed to update "${type}"`));
  };

  const setMessage = (type: string, customMessage: string) =>
    api.setReportTypeMessage(type, customMessage).then(setData).catch(() => {
      setError(`Failed to update "${type}"'s message`);
      throw new Error("failed"); // lets the row know the save didn't stick
    });

  if (error) return <p className="text-xs text-error">{error}</p>;
  if (!data) return <p className="text-xs text-on-surface-variant">Loading…</p>;

  const bySource: Record<string, Array<[string, ReportType]>> = { android: [], mac: [], server: [] };
  for (const entry of Object.entries(data.types).sort(([a], [b]) => a.localeCompare(b))) {
    bySource[entry[1].source]?.push(entry);
  }
  const sourceLabels: Record<string, string> = {
    android: "Phone",
    mac: "macOS (FocusLock)",
    server: "Filter server",
  };

  return (
    <div className="space-y-4">
      {(["android", "mac", "server"] as const).map((source) => bySource[source].length > 0 && (
        <div key={source}>
          <p className="text-[11px] font-semibold uppercase tracking-wide text-on-surface-variant/70 mb-1 px-1">
            {sourceLabels[source]} ({bySource[source].length})
          </p>
          <Card className="rounded-2xl space-y-0 divide-y divide-outline-variant/30">
            {bySource[source].map(([type, info]) => (
              <ReportTypeRow
                key={type}
                type={type}
                info={info}
                onToggle={(v) => toggle(type, v)}
                onSaveMessage={(msg) => setMessage(type, msg)}
              />
            ))}
          </Card>
        </div>
      ))}
    </div>
  );
}

function ReportTypeRow({
  type, info, onToggle, onSaveMessage,
}: {
  type: string;
  info: ReportType;
  onToggle: (v: boolean) => void;
  onSaveMessage: (customMessage: string) => Promise<unknown>;
}) {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(info.customMessage || info.description);
  const [saving, setSaving] = useState(false);

  const save = async () => {
    setSaving(true);
    try {
      await onSaveMessage(draft.trim());
      setEditing(false);
    } catch {
      // onSaveMessage's caller already surfaced the error; leave the editor open with the draft
      // intact so the guardian doesn't lose what they typed.
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="py-3 px-1">
      <div className="flex items-center justify-between gap-4">
        <div className="min-w-0">
          <p className="text-sm font-medium">{type}</p>
          <p className="text-xs text-on-surface-variant mt-0.5 leading-tight">
            {info.customMessage || info.description}
          </p>
        </div>
        <Switch checked={info.enabled} onCheckedChange={onToggle} />
      </div>
      {!editing ? (
        <button
          className="mt-1.5 text-[11px] text-primary hover:underline"
          onClick={() => { setDraft(info.customMessage || info.description); setEditing(true); }}
        >
          {info.customMessage ? "Edit message" : "Customize message"}
        </button>
      ) : (
        <div className="mt-2 space-y-1.5">
          <textarea
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            placeholder="Leave blank to use the default wording. Use {details} to include what actually happened."
            rows={2}
            className="w-full text-xs px-3 py-2 rounded-xl border border-outline bg-surface focus:outline-none focus:ring-2 focus:ring-primary resize-none"
          />
          <div className="flex items-center gap-2">
            <Button size="sm" className="h-7 px-3 text-xs" disabled={saving} onClick={save}>
              {saving ? "Saving…" : "Save"}
            </Button>
            <Button
              variant="outlined"
              size="sm"
              className="h-7 px-3 text-xs"
              disabled={saving}
              onClick={() => setEditing(false)}
            >
              Cancel
            </Button>
          </div>
        </div>
      )}
    </div>
  );
}

function DefaultSettingsPanel() {
  const [data, setData] = useState<DefaultSettings | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [secondsDraft, setSecondsDraft] = useState("");

  useEffect(() => {
    api.getDefaultSettings()
      .then((d) => { setData(d); setSecondsDraft(String(d.frictionDelay.seconds)); })
      .catch(() => setError("Failed to load default settings"));
  }, []);

  const patchProtection = (key: keyof Protections, value: boolean) =>
    api.setDefaultSettings({ protections: { ...data!.protections, [key]: value } })
      .then(setData)
      .catch(() => setError(`Failed to update "${key}"`));

  if (error) return <p className="text-xs text-error">{error}</p>;
  if (!data) return <p className="text-xs text-on-surface-variant">Loading…</p>;

  return (
    <div className="space-y-3">
      <Card className="rounded-2xl space-y-0 divide-y divide-outline-variant/30">
        <SettingsRow
          title="Block Safe Mode bypass"
          sub="Prevent circumventing rules via reboot"
          checked={data.protections.safeMode}
          onChange={(v) => patchProtection("safeMode", v)}
        />
        <SettingsRow
          title="Block factory reset"
          sub="Require PIN to wipe device"
          checked={data.protections.factoryReset}
          onChange={(v) => patchProtection("factoryReset", v)}
        />
        <SettingsRow
          title="Block app uninstall"
          sub="Require PIN to remove Otterling"
          checked={data.protections.uninstallBlock}
          onChange={(v) => patchProtection("uninstallBlock", v)}
          danger
        />
        <SettingsRow
          title="Block guest mode"
          sub="Prevent switching to an unmanaged profile"
          checked={data.protections.guestMode}
          onChange={(v) => patchProtection("guestMode", v)}
        />
        <SettingsRow
          title="Block USB debugging"
          sub="Prevent ADB-based tampering"
          checked={data.protections.usbDebugging}
          onChange={(v) => patchProtection("usbDebugging", v)}
        />
        <SettingsRow
          title="Content filter (VPN)"
          sub="DNS/proxy filtering active from first check-in"
          checked={data.vpnFilter.enabled}
          onChange={(v) => api.setDefaultSettings({ vpnFilter: { enabled: v } }).then(setData).catch(() => setError("Failed to update content filter"))}
        />
        <SettingsRow
          title="Friction delay"
          sub="Short delay before an unapproved app opens"
          checked={data.frictionDelay.enabled}
          onChange={(v) =>
            api.setDefaultSettings({ frictionDelay: { ...data.frictionDelay, enabled: v } })
              .then(setData)
              .catch(() => setError("Failed to update friction delay"))
          }
        />
      </Card>
      {data.frictionDelay.enabled && (
        <Card className="rounded-2xl">
          <div className="flex items-center justify-between gap-3">
            <p className="text-sm font-medium">Friction delay length</p>
            <div className="flex items-center gap-2">
              <input
                type="number"
                min={1}
                value={secondsDraft}
                onChange={(e) => setSecondsDraft(e.target.value)}
                onBlur={() =>
                  api.setDefaultSettings({ frictionDelay: { ...data.frictionDelay, seconds: Number(secondsDraft) || 30 } })
                    .then(setData)
                    .catch(() => setError("Failed to update friction delay"))
                }
                className="w-20 h-9 px-3 rounded-xl border border-outline bg-surface text-sm focus:outline-none focus:ring-2 focus:ring-primary"
              />
              <span className="text-xs text-on-surface-variant">seconds</span>
            </div>
          </div>
        </Card>
      )}
    </div>
  );
}

function HabitShareAccountCard() {
  const [account, setAccount] = useState<{ username: string | null; password: string | null } | null>(null);
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState("");

  const reload = () => api.getHabitShareAccount().then(setAccount).catch(() => setAccount(null));
  useEffect(() => { reload(); }, []);

  const connect = async () => {
    if (!username.trim() || !password) return;
    setBusy(true);
    setStatus("");
    try {
      await api.setHabitShareAccount(username.trim(), password);
      setPassword("");
      setStatus("Connected.");
      reload();
    } catch (err) {
      setStatus(err instanceof ApiError ? err.message : "Couldn't reach the server");
    } finally {
      setBusy(false);
    }
  };

  const disconnect = async () => {
    setBusy(true);
    try {
      await api.removeHabitShareAccount();
      setStatus("Disconnected.");
      reload();
    } finally {
      setBusy(false);
    }
  };

  if (account?.username) {
    return (
      <div className="flex items-center justify-between">
        <div>
          <div className="flex items-center gap-2">
            <p className="text-sm font-medium">{account.username}</p>
            <Pill variant="success">Connected</Pill>
          </div>
          {status && <p className="text-xs text-on-surface-variant mt-0.5">{status}</p>}
        </div>
        <Button variant="outlined" size="sm" disabled={busy} onClick={disconnect}>Disconnect</Button>
      </div>
    );
  }

  return (
    <div className="space-y-2">
      <div className="flex items-center gap-2">
        <input
          value={username}
          onChange={(e) => setUsername(e.target.value)}
          placeholder="HabitShare username or email"
          className="flex-1 h-9 px-3 rounded-xl border border-outline bg-surface text-sm focus:outline-none focus:ring-2 focus:ring-primary"
        />
        <input
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          type="password"
          placeholder="Password"
          autoComplete="new-password"
          className="flex-1 h-9 px-3 rounded-xl border border-outline bg-surface text-sm focus:outline-none focus:ring-2 focus:ring-primary"
        />
        <Button variant="tonal" size="sm" disabled={busy || !username.trim() || !password} onClick={connect}>
          Connect
        </Button>
      </div>
      {status && <p className="text-xs text-on-surface-variant">{status}</p>}
    </div>
  );
}

// One-time account-handoff link (see lockprofile_service.py's HANDOFF_TOKEN_PATH comment) --
// generates a single-use, expiring link that lets whoever holds it set a BRAND NEW Guardian PIN
// without needing to know the current one. Meant for the one-time moment you're done setting
// this up and ready to hand the account to your guardian -- not an ongoing reset mechanism.
// Generating a new link invalidates whatever was generated before.
function HandoffLinkCard() {
  const [pending, setPending] = useState<{ pending: boolean; expiresAt: number | null } | null>(null);
  const [freshLink, setFreshLink] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState("");
  const [copied, setCopied] = useState(false);

  const reload = () => api.getHandoffLinkStatus().then(setPending).catch(() => setPending(null));
  useEffect(() => { reload(); }, []);

  const generate = async () => {
    setBusy(true);
    setStatus("");
    setCopied(false);
    try {
      const result = await api.generateHandoffLink();
      setFreshLink(`${window.location.origin}/handoff/?token=${result.token}`);
      setPending({ pending: true, expiresAt: result.expiresAt });
    } catch (err) {
      setStatus(err instanceof ApiError ? err.message : "Couldn't reach the server");
    } finally {
      setBusy(false);
    }
  };

  const cancel = async () => {
    setBusy(true);
    try {
      await api.cancelHandoffLink();
      setFreshLink(null);
      setPending({ pending: false, expiresAt: null });
    } catch (err) {
      setStatus(err instanceof ApiError ? err.message : "Couldn't reach the server");
    } finally {
      setBusy(false);
    }
  };

  const copy = () => {
    if (!freshLink) return;
    navigator.clipboard?.writeText(freshLink).then(() => {
      setCopied(true);
      window.setTimeout(() => setCopied(false), 3000);
    });
  };

  return (
    <div className="space-y-2">
      <div>
        <p className="text-sm font-medium">Account handoff link</p>
        <p className="text-xs text-on-surface-variant">
          A one-time link for when you're done setting this up -- send it to your guardian so
          they can set their own Guardian PIN. Works once, expires in 48 hours, and doesn't
          require knowing the current PIN. Generating a new link cancels any unused one.
        </p>
      </div>
      {freshLink ? (
        <div className="space-y-1.5">
          <div className="flex items-center gap-2">
            <input
              readOnly
              value={freshLink}
              onFocus={(e) => e.target.select()}
              className="flex-1 h-9 px-3 rounded-xl border border-outline bg-surface text-xs font-mono focus:outline-none focus:ring-2 focus:ring-primary"
            />
            <Button variant="tonal" size="sm" onClick={copy}>{copied ? "Copied" : "Copy"}</Button>
          </div>
          <p className="text-[11px] text-on-surface-variant">
            This is shown only once -- copy it now before leaving this page.
          </p>
        </div>
      ) : (
        <div className="flex items-center gap-2">
          <Button variant="tonal" size="sm" disabled={busy} onClick={generate}>
            {pending?.pending ? "Generate new link" : "Generate handoff link"}
          </Button>
          {pending?.pending && (
            <Button variant="outlined" size="sm" disabled={busy} onClick={cancel}>
              Cancel pending link
            </Button>
          )}
        </div>
      )}
      {pending?.pending && pending.expiresAt && !freshLink && (
        <p className="text-xs text-on-surface-variant">
          A link is pending, expires {new Date(pending.expiresAt * 1000).toLocaleString()}. It
          was only shown once when generated -- this is just a status check.
        </p>
      )}
      {status && <p className="text-xs text-error">{status}</p>}
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

function HabitLibraryList({ habits, rules, onChanged }: { habits: Habit[]; rules: Rule[]; onChanged: () => void }) {
  const [draft, setDraft] = useState("");
  const [draftRequiresProof, setDraftRequiresProof] = useState(false);
  const [busy, setBusy] = useState(false);
  const [viewingProofId, setViewingProofId] = useState<string | null>(null);
  const [importing, setImporting] = useState(false);
  const [importMessage, setImportMessage] = useState<string | null>(null);

  const submit = async () => {
    const name = draft.trim();
    if (!name) return;
    setBusy(true);
    try {
      await api.addHabit(name, draftRequiresProof);
      setDraft("");
      setDraftRequiresProof(false);
      onChanged();
    } finally {
      setBusy(false);
    }
  };

  // Pulls habit names from the connected HabitShare account (see HabitShareAccountCard above)
  // and creates a matching library entry for any not already here by name -- see
  // HabitCompletionReporter.kt's doc comment for why a name match is what actually makes
  // completion reporting work; this just saves retyping each one.
  const importFromHabitShare = async () => {
    setImporting(true);
    setImportMessage(null);
    try {
      const result = await api.importHabitsFromHabitShare();
      setImportMessage(
        result.imported > 0
          ? `Imported ${result.imported} new habit${result.imported === 1 ? "" : "s"}.`
          : "No new habits found -- everything in HabitShare is already in this list.",
      );
      onChanged();
    } catch (error) {
      setImportMessage(error instanceof ApiError ? error.message : "Import failed -- check the connected HabitShare account.");
    } finally {
      setImporting(false);
    }
  };

  return (
    <div className="space-y-2">
      <div className="flex items-center gap-2">
        <Button variant="outlined" size="sm" className="h-8 px-3 text-xs" disabled={importing} onClick={importFromHabitShare}>
          {importing ? "Importing…" : "Import from HabitShare"}
        </Button>
        {importMessage && <span className="text-xs text-on-surface-variant">{importMessage}</span>}
      </div>
      {habits.length > 0 && (
        <div className="space-y-1.5">
          {habits.map((h) => {
            const usedInRules = rules.filter((r) => r.requiredHabitIds.includes(h.id));
            return (
              <div
                key={h.id}
                className="flex flex-col gap-0.5 pl-3 pr-1.5 py-1.5 rounded-xl bg-surface-variant text-sm"
              >
                <div className="flex items-center gap-2">
                  <span className="flex-1 min-w-0 truncate">
                    {h.name}
                    {h.doneToday && <span className="text-secondary ml-1">✓</span>}
                  </span>
                  {h.doneToday && h.hasProof && (
                    <button
                      onClick={() => setViewingProofId(h.id)}
                      className="text-[11px] font-medium text-primary hover:underline shrink-0"
                    >
                      View proof
                    </button>
                  )}
                  {h.doneToday && (
                    <button
                      onClick={() => api.revokeHabitCompletion(h.id).then(onChanged)}
                      title="Revoke today's completion"
                      className="text-[11px] font-medium text-error hover:underline shrink-0"
                    >
                      Revoke
                    </button>
                  )}
                  <label className="flex items-center gap-1 text-[11px] text-on-surface-variant shrink-0">
                    <input
                      type="checkbox"
                      checked={h.requiresProof}
                      onChange={(e) => api.setHabitRequiresProof(h.id, e.target.checked).then(onChanged)}
                    />
                    Requires proof
                  </label>
                  <button
                    onClick={() => api.removeHabit(h.id).then(onChanged)}
                    className="w-5 h-5 rounded-full flex items-center justify-center hover:bg-error-container hover:text-error transition-colors shrink-0"
                  >
                    <X className="w-3 h-3" />
                  </button>
                </div>
                <p className="text-[11px] text-on-surface-variant truncate">
                  {usedInRules.length > 0
                    ? `Used in: ${usedInRules.map((r) => ruleTargetLabel(r)).join(", ")}`
                    : "Not used in any rule"}
                </p>
              </div>
            );
          })}
        </div>
      )}
      <div className="flex items-center gap-2">
        <input
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={(e) => { if (e.key === "Enter") submit(); }}
          placeholder="New habit…"
          className="flex-1 h-9 px-3 rounded-xl border border-outline bg-surface text-sm focus:outline-none focus:ring-2 focus:ring-primary"
        />
        <label className="flex items-center gap-1 text-[11px] text-on-surface-variant whitespace-nowrap">
          <input
            type="checkbox"
            checked={draftRequiresProof}
            onChange={(e) => setDraftRequiresProof(e.target.checked)}
          />
          Requires proof
        </label>
        <Button variant="tonal" size="sm" className="h-9 px-3" disabled={busy || !draft.trim()} onClick={submit}>
          <Plus className="w-3.5 h-3.5" />
        </Button>
      </div>
      {viewingProofId && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-8"
          onClick={() => setViewingProofId(null)}
        >
          <img
            src={api.habitProofUrl(viewingProofId)}
            alt="Habit completion proof"
            className="max-w-full max-h-full rounded-2xl shadow-xl"
          />
        </div>
      )}
    </div>
  );
}

function AppBudgetList({
  items, onAdd, onRemove,
}: {
  items: AppBudget[];
  onAdd: (budget: Partial<AppBudget>) => Promise<unknown>;
  onRemove: (id: string) => Promise<unknown>;
}) {
  const [appId, setAppId] = useState("");
  const [appName, setAppName] = useState("");
  const [minutes, setMinutes] = useState("");
  const [busy, setBusy] = useState(false);

  const submit = async () => {
    const id = appId.trim();
    if (!id) return;
    setBusy(true);
    try {
      await onAdd({
        appId: id,
        appName: appName.trim() || id,
        dailyLimitMinutes: minutes.trim() ? Number(minutes) : null,
      });
      setAppId("");
      setAppName("");
      setMinutes("");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="space-y-2">
      {items.length > 0 && (
        <div className="space-y-1.5">
          {items.map((b) => (
            <div
              key={b.id}
              className="flex items-center justify-between gap-2 pl-2.5 pr-1 py-1.5 rounded-xl bg-surface-variant text-on-surface-variant"
            >
              <span className="text-xs font-medium truncate">
                {b.appName}
                {b.dailyLimitMinutes != null ? ` — ${b.dailyLimitMinutes} min/day` : ""}
              </span>
              <button
                onClick={() => onRemove(b.id)}
                className="w-4 h-4 shrink-0 rounded-full flex items-center justify-center hover:bg-error-container hover:text-error transition-colors"
              >
                <X className="w-2.5 h-2.5" />
              </button>
            </div>
          ))}
        </div>
      )}
      <div className="space-y-2">
        <input
          value={appId}
          onChange={(e) => setAppId(e.target.value)}
          placeholder="com.example.app"
          className="w-full h-9 px-3 rounded-xl border border-outline bg-surface text-sm focus:outline-none focus:ring-2 focus:ring-primary"
        />
        <div className="flex items-center gap-2">
          <input
            value={appName}
            onChange={(e) => setAppName(e.target.value)}
            placeholder="Display name (optional)"
            className="flex-1 min-w-0 h-9 px-3 rounded-xl border border-outline bg-surface text-sm focus:outline-none focus:ring-2 focus:ring-primary"
          />
          <input
            type="number"
            min={1}
            value={minutes}
            onChange={(e) => setMinutes(e.target.value)}
            placeholder="min/day"
            className="w-24 shrink-0 h-9 px-3 rounded-xl border border-outline bg-surface text-sm focus:outline-none focus:ring-2 focus:ring-primary"
          />
          <Button variant="tonal" size="sm" className="h-9 px-3 shrink-0" disabled={busy || !appId.trim()} onClick={submit}>
            <Plus className="w-3.5 h-3.5" />
          </Button>
        </div>
      </div>
    </div>
  );
}

function ProtectedAppList({
  items, onAdd, onRemove,
}: {
  items: ProtectedApp[];
  onAdd: (app: { displayName: string; executableName: string; bundlePath: string }) => Promise<unknown>;
  onRemove: (executableName: string) => Promise<unknown>;
}) {
  const [displayName, setDisplayName] = useState("");
  const [executableName, setExecutableName] = useState("");
  const [bundlePath, setBundlePath] = useState("");
  const [busy, setBusy] = useState(false);

  const submit = async () => {
    const executable = executableName.trim();
    const path = bundlePath.trim();
    if (!executable || !path) return;
    setBusy(true);
    try {
      await onAdd({ displayName: displayName.trim() || executable, executableName: executable, bundlePath: path });
      setDisplayName("");
      setExecutableName("");
      setBundlePath("");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="space-y-2">
      {items.length > 0 && (
        <div className="space-y-1.5">
          {items.map((a) => (
            <div
              key={a.executableName}
              className="flex items-center justify-between gap-2 pl-2.5 pr-1 py-1.5 rounded-xl bg-surface-variant text-on-surface-variant"
            >
              <span className="text-xs font-medium truncate">
                {a.displayName} <span className="text-on-surface-variant/60">— {a.bundlePath}</span>
              </span>
              <button
                onClick={() => onRemove(a.executableName)}
                className="w-4 h-4 shrink-0 rounded-full flex items-center justify-center hover:bg-error-container hover:text-error transition-colors"
              >
                <X className="w-2.5 h-2.5" />
              </button>
            </div>
          ))}
        </div>
      )}
      <div className="flex items-center gap-2">
        <input
          value={displayName}
          onChange={(e) => setDisplayName(e.target.value)}
          placeholder="Display name (optional)"
          className="flex-1 min-w-0 h-9 px-3 rounded-xl border border-outline bg-surface text-sm focus:outline-none focus:ring-2 focus:ring-primary"
        />
        <input
          value={executableName}
          onChange={(e) => setExecutableName(e.target.value)}
          placeholder="Executable name"
          className="flex-1 min-w-0 h-9 px-3 rounded-xl border border-outline bg-surface text-sm focus:outline-none focus:ring-2 focus:ring-primary"
        />
        <input
          value={bundlePath}
          onChange={(e) => setBundlePath(e.target.value)}
          placeholder="/Applications/App.app"
          className="flex-1 min-w-0 h-9 px-3 rounded-xl border border-outline bg-surface text-sm focus:outline-none focus:ring-2 focus:ring-primary"
        />
        <Button
          variant="tonal" size="sm" className="h-9 px-3 shrink-0"
          disabled={busy || !executableName.trim() || !bundlePath.trim()}
          onClick={submit}
        >
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
            <p className="text-xs text-on-surface-variant">Applies to every device on this account</p>
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
  devices, settings, habits, editingRule, onNavigate, onSaved,
}: {
  devices: DeviceSummary[];
  settings: DeviceSettings | null;
  habits: Habit[];
  editingRule: Rule | null;
  onNavigate: (s: Screen) => void;
  onSaved: () => void;
}) {
  const [step, setStep] = useState(1);

  // Devices this rule applies to -- see api.ts's Rule doc comment. "All devices" is a sentinel
  // (deviceIds === ["all"]) rather than literally listing every device_id, so a rule stays
  // "everyone" even as devices are added/removed later.
  const [allDevices, setAllDevices] = useState(editingRule ? editingRule.deviceIds.includes("all") : true);
  const [selectedDeviceIds, setSelectedDeviceIds] = useState<string[]>(
    editingRule && !editingRule.deviceIds.includes("all") ? editingRule.deviceIds : []
  );

  // Targets: a rule can gate any mix of apps and websites at once now, not one-or-the-other.
  const [targetApps, setTargetApps] = useState<RuleTargetApp[]>(editingRule?.targetApps ?? []);
  const [targetWebsites, setTargetWebsites] = useState<RuleTargetWebsite[]>(editingRule?.targetWebsites ?? []);
  const [appQuery, setAppQuery] = useState("");
  const [customAppName, setCustomAppName] = useState("");
  const [customAppId, setCustomAppId] = useState("");
  const [websiteDraft, setWebsiteDraft] = useState("");

  const [selectedHabitIds, setSelectedHabitIds] = useState<string[]>(editingRule?.requiredHabitIds ?? []);
  const [newHabit, setNewHabit] = useState("");
  const [startTime, setStartTime] = useState(editingRule?.schedule.startTime ?? "00:00");
  const [endTime, setEndTime] = useState(editingRule?.schedule.endTime ?? "21:00");
  const [days, setDays] = useState<number[]>(editingRule?.schedule.daysOfWeek ?? [1, 2, 3, 4, 5]);
  const [budget, setBudget] = useState<string>(editingRule?.dailyBudgetMinutes != null ? String(editingRule.dailyBudgetMinutes) : "");
  const [saving, setSaving] = useState(false);

  const steps = [
    { n: 1, label: "Choose Devices", sub: "Who this rule applies to" },
    { n: 2, label: "Choose Targets", sub: "Apps and/or websites to gate" },
    { n: 3, label: "Require Habits", sub: "Must be completed to unlock" },
    { n: 4, label: "Set Schedule", sub: "When rule applies" },
  ];

  const devicesValid = allDevices || selectedDeviceIds.length > 0;
  const targetsValid = targetApps.length > 0 || targetWebsites.length > 0;

  const toggleDevice = (id: string) =>
    setSelectedDeviceIds((prev) => (prev.includes(id) ? prev.filter((d) => d !== id) : [...prev, id]));

  // Real installed apps this device reported (see api.ts's DeviceSettings.installedApps doc) --
  // preferred over the hardcoded COMMON_APPS fallback whenever a device has actually synced any,
  // since picking one of these fills in the exact id too, not just the display name. `settings` is
  // whichever device happens to be selected in the sidebar right now -- just a search convenience,
  // not a constraint on which device(s) the rule actually applies to (that's step 1, above).
  const installedApps = settings?.installedApps ?? [];
  const hasInstalledApps = installedApps.length > 0;
  const query = appQuery.trim().toLowerCase();
  const filteredInstalled = query
    ? installedApps.filter((a) => a.name.toLowerCase().includes(query) || a.id.toLowerCase().includes(query))
    : installedApps;
  const filteredCommon = COMMON_APPS.filter((a) => a.name.toLowerCase().includes(query));
  const isMac = settings?.platform === "macos";

  const addTargetApp = (appName: string, appId: string) => {
    if (!appName.trim() || !appId.trim()) return;
    setTargetApps((prev) => (prev.some((a) => a.appId === appId) ? prev : [...prev, { appName: appName.trim(), appId: appId.trim() }]));
    setAppQuery("");
    setCustomAppName("");
    setCustomAppId("");
  };
  const removeTargetApp = (appId: string) => setTargetApps((prev) => prev.filter((a) => a.appId !== appId));

  const addTargetWebsite = () => {
    const domain = websiteDraft.trim().toLowerCase();
    if (!domain) return;
    setTargetWebsites((prev) => (prev.some((w) => w.domain === domain) ? prev : [...prev, { domain }]));
    setWebsiteDraft("");
  };
  const removeTargetWebsite = (domain: string) => setTargetWebsites((prev) => prev.filter((w) => w.domain !== domain));

  const toggleHabit = (id: string) =>
    setSelectedHabitIds((prev) => (prev.includes(id) ? prev.filter((h) => h !== id) : [...prev, id]));

  const toggleDay = (day: number) =>
    setDays((prev) => (prev.includes(day) ? prev.filter((d) => d !== day) : [...prev, day].sort()));

  const addCustomHabit = async () => {
    const name = newHabit.trim();
    if (!name) return;
    const res = await api.addHabit(name);
    const created = res.habits[res.habits.length - 1];
    if (created) setSelectedHabitIds((prev) => [...prev, created.id]);
    setNewHabit("");
    onSaved();
  };

  const save = async () => {
    if (!devicesValid || !targetsValid) return;
    setSaving(true);
    const payload: Partial<Rule> = {
      targetApps,
      targetWebsites,
      deviceIds: allDevices ? ["all"] : selectedDeviceIds,
      requiredHabitIds: selectedHabitIds,
      schedule: { startTime, endTime, daysOfWeek: days },
      dailyBudgetMinutes: budget.trim() ? Number(budget) : null,
    };
    try {
      if (editingRule) {
        await api.updateRule(editingRule.id, payload);
      } else {
        await api.addRule(payload);
      }
      onSaved();
      onNavigate("GlobalSettings");
    } finally {
      setSaving(false);
    }
  };

  const canContinue = step === 1 ? devicesValid : step === 2 ? targetsValid : true;

  return (
    <div className="h-full flex flex-col">
      <div className="flex-1 flex overflow-hidden">
        {/* Step sidebar */}
        <div className="w-60 border-r border-outline-variant/30 p-6 shrink-0 flex flex-col">
          <h2 className="text-xl font-bold mb-1">{editingRule ? "Edit Rule" : "Create Rule"}</h2>
          <p className="text-xs text-on-surface-variant mb-6">Define habit requirements for app/website access</p>
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
                <h3 className="text-lg font-bold">Which device(s) enforce this rule?</h3>
                <p className="text-sm text-on-surface-variant mt-0.5">
                  Pick specific devices, or apply it fleet-wide so it stays in effect on any device
                  you add later too.
                </p>
              </div>
              <button
                onClick={() => setAllDevices(true)}
                className={cn(
                  "w-full flex items-center gap-3 p-3.5 rounded-xl border transition-all text-left",
                  allDevices ? "border-primary bg-primary/5" : "border-outline-variant/40 hover:bg-surface-variant"
                )}
              >
                <div className={cn("w-5 h-5 rounded-full border-2 flex items-center justify-center shrink-0", allDevices ? "border-primary bg-primary" : "border-outline")}>
                  {allDevices && <Check className="w-3 h-3 text-on-primary" />}
                </div>
                <div>
                  <p className="font-medium text-sm">All devices</p>
                  <p className="text-[11px] text-on-surface-variant">Applies to every device now and any registered later</p>
                </div>
              </button>
              <div>
                <button
                  onClick={() => setAllDevices(false)}
                  className={cn(
                    "w-full flex items-center gap-3 p-3.5 rounded-xl border transition-all text-left mb-2",
                    !allDevices ? "border-primary bg-primary/5" : "border-outline-variant/40 hover:bg-surface-variant"
                  )}
                >
                  <div className={cn("w-5 h-5 rounded-full border-2 flex items-center justify-center shrink-0", !allDevices ? "border-primary bg-primary" : "border-outline")}>
                    {!allDevices && <Check className="w-3 h-3 text-on-primary" />}
                  </div>
                  <p className="font-medium text-sm">Specific devices</p>
                </button>
                {!allDevices && (
                  devices.length === 0 ? (
                    <p className="text-sm text-on-surface-variant pl-8">No devices registered yet.</p>
                  ) : (
                    <div className="grid grid-cols-2 gap-2 pl-8">
                      {devices.map((d) => {
                        const sel = selectedDeviceIds.includes(d.device_id);
                        return (
                          <button
                            key={d.device_id}
                            onClick={() => toggleDevice(d.device_id)}
                            className={cn(
                              "flex items-center gap-3 p-3 rounded-xl border transition-all text-left",
                              sel ? "border-secondary bg-secondary-container/40" : "border-outline-variant/40 hover:bg-surface-variant"
                            )}
                          >
                            <div className={cn("w-5 h-5 rounded-full border-2 flex items-center justify-center shrink-0", sel ? "border-secondary bg-secondary" : "border-outline")}>
                              {sel && <Check className="w-3 h-3 text-on-secondary" />}
                            </div>
                            <span className="text-sm font-medium truncate">{d.device_name || d.device_id}</span>
                          </button>
                        );
                      })}
                    </div>
                  )
                )}
              </div>
            </div>
          )}

          {step === 2 && (
            <div className="max-w-xl space-y-6">
              <div>
                <h3 className="text-lg font-bold">What should be gated?</h3>
                <p className="text-sm text-on-surface-variant mt-0.5">
                  Add any mix of apps and websites -- all of them unlock together once the
                  required habits (next step) are done.
                </p>
              </div>

              {/* Apps */}
              <div className="space-y-2.5">
                <label className="text-sm font-semibold block">Apps</label>
                {targetApps.length > 0 && (
                  <div className="flex flex-wrap gap-1.5">
                    {targetApps.map((a) => (
                      <Pill key={a.appId}>
                        {a.appName}
                        <button onClick={() => removeTargetApp(a.appId)} className="ml-1.5 align-middle">
                          <X className="w-3 h-3 inline" />
                        </button>
                      </Pill>
                    ))}
                  </div>
                )}
                <input
                  type="text"
                  value={appQuery}
                  onChange={(e) => setAppQuery(e.target.value)}
                  placeholder={hasInstalledApps ? `Search apps installed on ${settings?.device_name || "a device"}…` : "Search common apps…"}
                  className="w-full h-11 px-4 rounded-xl border border-outline bg-surface text-sm focus:outline-none focus:ring-2 focus:ring-primary"
                />
                <div className="grid grid-cols-2 gap-2">
                  {(hasInstalledApps ? filteredInstalled.slice(0, 40) : filteredCommon).map((app) => (
                    <button
                      key={"id" in app ? app.id : app.name}
                      onClick={() => addTargetApp(app.name, "id" in app ? app.id : app.name)}
                      className="flex items-center gap-3 p-3 rounded-xl border border-outline-variant/40 hover:bg-surface-variant transition-all text-left min-w-0"
                    >
                      <div className={cn("w-8 h-8 rounded-lg flex items-center justify-center text-xs font-bold shrink-0", "color" in app ? app.color : "bg-primary/10 text-primary")}>
                        {app.name.slice(0, 2).toUpperCase()}
                      </div>
                      <div className="min-w-0">
                        <p className="font-medium text-sm truncate">{app.name}</p>
                        {"id" in app && <p className="text-[11px] text-on-surface-variant truncate">{app.id}</p>}
                      </div>
                    </button>
                  ))}
                </div>
                <div className="flex items-center gap-2 pt-1">
                  <input
                    type="text"
                    value={customAppName}
                    onChange={(e) => setCustomAppName(e.target.value)}
                    placeholder="Custom app name"
                    className="flex-1 h-9 px-3 rounded-xl border border-outline bg-surface text-sm focus:outline-none focus:ring-2 focus:ring-primary"
                  />
                  <input
                    type="text"
                    value={customAppId}
                    onChange={(e) => setCustomAppId(e.target.value)}
                    placeholder={isMac ? "Executable name" : "Package name"}
                    className="flex-1 h-9 px-3 rounded-xl border border-outline bg-surface text-sm focus:outline-none focus:ring-2 focus:ring-primary"
                  />
                  <Button variant="text" size="sm" className="gap-1.5 text-xs shrink-0" onClick={() => addTargetApp(customAppName, customAppId)}>
                    <Plus className="w-3.5 h-3.5" /> Add
                  </Button>
                </div>
                <p className="text-xs text-on-surface-variant">
                  {isMac
                    ? <>The exact process/executable name as it appears in Activity Monitor (e.g. <code>Steam</code>) -- not the display name or bundle identifier.</>
                    : <>The exact Android package name (e.g. <code>com.zhiliaoapp.musically</code>) -- the phone matches on this literally.</>}
                </p>
              </div>

              {/* Websites */}
              <div className="space-y-2.5">
                <label className="text-sm font-semibold block">Websites</label>
                {targetWebsites.length > 0 && (
                  <div className="flex flex-wrap gap-1.5">
                    {targetWebsites.map((w) => (
                      <Pill key={w.domain}>
                        {w.domain}
                        <button onClick={() => removeTargetWebsite(w.domain)} className="ml-1.5 align-middle">
                          <X className="w-3 h-3 inline" />
                        </button>
                      </Pill>
                    ))}
                  </div>
                )}
                <div className="flex items-center gap-2">
                  <input
                    type="text"
                    value={websiteDraft}
                    onChange={(e) => setWebsiteDraft(e.target.value)}
                    onKeyDown={(e) => { if (e.key === "Enter") addTargetWebsite(); }}
                    placeholder="youtube.com"
                    className="flex-1 h-11 px-4 rounded-xl border border-outline bg-surface text-sm focus:outline-none focus:ring-2 focus:ring-primary"
                  />
                  <Button variant="text" size="sm" className="gap-1.5 text-xs shrink-0" onClick={addTargetWebsite}>
                    <Plus className="w-3.5 h-3.5" /> Add
                  </Button>
                </div>
                <p className="text-xs text-on-surface-variant">
                  Blocks this domain and its subdomains via DNS -- same enforcement as a domain in
                  Blocked Websites, just conditional on the habit(s) below instead of always-on.
                </p>
              </div>
            </div>
          )}

          {step === 3 && (
            <div className="max-w-xl space-y-4">
              <div>
                <h3 className="text-lg font-bold">Required habits</h3>
                <p className="text-sm text-on-surface-variant mt-0.5">
                  Select which habits must be done before {targetApps.map((a) => a.appName).concat(targetWebsites.map((w) => w.domain)).join(", ") || "the target(s)"} unlock.
                </p>
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

          {step === 4 && (
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
        <Button variant="text" size="sm" onClick={() => (step > 1 ? setStep(step - 1) : onNavigate("GlobalSettings"))}>
          {step > 1 ? "← Back" : "Cancel"}
        </Button>
        <div className="flex items-center gap-3">
          <div className="flex gap-1.5">
            {[1, 2, 3, 4].map((n) => (
              <div key={n} className={cn("w-1.5 h-1.5 rounded-full transition-all", step >= n ? "bg-primary" : "bg-surface-variant")} />
            ))}
          </div>
          <Button
            size="sm"
            disabled={!canContinue || saving}
            onClick={() => (step < 4 ? setStep(step + 1) : save())}
          >
            {saving ? "Saving…" : step < 4 ? "Continue →" : "Save Rule"}
          </Button>
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
