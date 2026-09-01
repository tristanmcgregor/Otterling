import { useEffect, useState } from "react";
import {
  Shield, AlertTriangle, LogOut, RefreshCw, Moon, Sun, ChevronDown,
} from "lucide-react";
import { cn, Button } from "./components/ui";
import { api, ApiError, logout } from "../lib/api";
import type { DeviceSettings, DeviceSummary, ActivityEvent, Rule, Habit } from "../lib/api";
import { type Screen, type NavDef, deviceNav, FLEET_NAV } from "./navigation";
import { DashboardScreen } from "./screens/DashboardScreen";
import { BlockedAppsScreen } from "./screens/BlockedAppsScreen";
import { BlockedSitesScreen } from "./screens/BlockedSitesScreen";
import { ProtectedAppsScreen } from "./screens/ProtectedAppsScreen";
import { ContentFilterScreen } from "./screens/ContentFilterScreen";
import { SettingsScreen } from "./screens/SettingsScreen";
import { HabitsScreen } from "./screens/HabitsScreen";
import { GlobalSettingsScreen } from "./screens/GlobalSettingsScreen";
import { AccountabilityScreen } from "./screens/AccountabilityScreen";
import { HabitRuleWizard } from "./screens/HabitRuleWizard";

const DEVICE_STORAGE_KEY = "otterling.deviceId";
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
    if (screen === "Accountability") {
      return <AccountabilityScreen deviceId={deviceId} settings={settings} onChanged={reload} />;
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
