import React, { useState, useEffect } from "react";
import {
  Shield, Lock, Clock, Globe, CheckCircle, Bug, RefreshCw,
  Plus, Smartphone, Settings as SettingsIcon, Camera, X,
  Home, ListChecks, Timer, AlertTriangle, Moon, Sun,
  Users, BarChart3, Check, ChevronRight, Wifi
} from "lucide-react";
import { cn, Card, Button, Switch, Pill } from "./components/ui";

type Screen = "Dashboard" | "Settings" | "Wizard" | "PhotoCapture" | "Friction" | "AccessibilityNag";

interface NavDef {
  id: Screen;
  label: string;
  icon: React.FC<{ className?: string }>;
  locked?: boolean;
}

const NAV: NavDef[] = [
  { id: "Dashboard", label: "Overview", icon: Home },
  { id: "Wizard", label: "Rules", icon: ListChecks },
  { id: "PhotoCapture", label: "Verify Habit", icon: Camera },
  { id: "Friction", label: "Delay Timer", icon: Timer },
  { id: "AccessibilityNag", label: "Accessibility", icon: AlertTriangle },
];

const ADMIN_NAV: NavDef[] = [
  { id: "Settings", label: "Settings", icon: SettingsIcon, locked: true },
];

export default function App() {
  const [screen, setScreen] = useState<Screen>("Dashboard");
  const [dark, setDark] = useState(true);
  const [pinOpen, setPinOpen] = useState(false);
  const [pinTarget, setPinTarget] = useState<Screen>("Settings");

  useEffect(() => {
    document.documentElement.classList.toggle("dark", dark);
  }, [dark]);

  const navigate = (s: Screen) => {
    const all = [...NAV, ...ADMIN_NAV];
    if (all.find((n) => n.id === s)?.locked) {
      setPinTarget(s);
      setPinOpen(true);
    } else {
      setScreen(s);
    }
  };

  const renderContent = () => {
    switch (screen) {
      case "Dashboard":    return <DashboardScreen onNavigate={navigate} />;
      case "Settings":     return <SettingsScreen onNavigate={navigate} />;
      case "Wizard":       return <HabitRuleWizard onNavigate={navigate} />;
      case "PhotoCapture": return <PhotoCaptureScreen onNavigate={navigate} />;
      case "Friction":     return <FrictionDelayScreen onNavigate={navigate} />;
      case "AccessibilityNag": return <AccessibilityNagScreen onNavigate={navigate} />;
    }
  };

  return (
    <div
      className="min-h-screen flex items-center justify-center p-6"
      style={{ background: "linear-gradient(140deg, #0d0c1d 0%, #1a1040 55%, #0d1a2e 100%)" }}
    >
      {/* macOS Window */}
      <div className="w-full max-w-[1320px] h-[820px] rounded-[20px] overflow-hidden shadow-[0_40px_120px_rgba(0,0,0,0.75)] border border-white/[0.07] flex flex-col bg-background text-on-background">

        {/* Title Bar */}
        <TitleBar dark={dark} onToggleDark={() => setDark(!dark)} />

        {/* Body */}
        <div className="flex flex-1 overflow-hidden">

          {/* Sidebar */}
          <Sidebar
            screen={screen}
            nav={NAV}
            adminNav={ADMIN_NAV}
            onNavigate={navigate}
          />

          {/* Main content */}
          <main className="flex-1 overflow-y-auto no-scrollbar bg-background">
            {renderContent()}
          </main>
        </div>
      </div>

      {/* PIN Modal Overlay */}
      {pinOpen && (
        <div className="fixed inset-0 z-50 bg-black/70 backdrop-blur-md flex items-center justify-center">
          <PinModal
            onSuccess={() => { setPinOpen(false); setScreen(pinTarget); }}
            onClose={() => setPinOpen(false)}
          />
        </div>
      )}
    </div>
  );
}

// ─── Chrome ──────────────────────────────────────────────────────────────────

function TitleBar({ dark, onToggleDark }: { dark: boolean; onToggleDark: () => void }) {
  return (
    <div className="h-11 flex items-center px-4 bg-surface/70 border-b border-outline-variant/30 shrink-0 select-none">
      <div className="flex items-center gap-1.5 group">
        <div className="w-3 h-3 rounded-full bg-[#FF5F56] flex items-center justify-center hover:brightness-90 transition-all cursor-default">
          <X className="w-[7px] h-[7px] text-[#820005] opacity-0 group-hover:opacity-100" strokeWidth={3} />
        </div>
        <div className="w-3 h-3 rounded-full bg-[#FFBD2E] hover:brightness-90 transition-all cursor-default" />
        <div className="w-3 h-3 rounded-full bg-[#27C93F] hover:brightness-90 transition-all cursor-default" />
      </div>

      <span className="flex-1 text-center text-[13px] font-medium text-on-surface-variant/60 pointer-events-none">
        Otterling — Family Habits & Device Control
      </span>

      <button
        onClick={onToggleDark}
        className="w-7 h-7 rounded-md flex items-center justify-center text-on-surface-variant hover:bg-surface-variant transition-colors"
        title="Toggle theme"
      >
        {dark ? <Sun className="w-[15px] h-[15px]" /> : <Moon className="w-[15px] h-[15px]" />}
      </button>
    </div>
  );
}

function Sidebar({
  screen, nav, adminNav, onNavigate,
}: {
  screen: Screen;
  nav: NavDef[];
  adminNav: NavDef[];
  onNavigate: (s: Screen) => void;
}) {
  return (
    <aside className="w-[224px] bg-surface border-r border-outline-variant/30 flex flex-col shrink-0">
      {/* Logo */}
      <div className="px-4 py-4 flex items-center gap-3">
        <div className="w-9 h-9 rounded-[12px] bg-primary flex items-center justify-center shadow-sm shrink-0">
          <Shield className="w-5 h-5 text-on-primary" />
        </div>
        <div>
          <p className="text-[15px] font-bold tracking-tight leading-tight">Otterling</p>
          <p className="text-[10px] text-on-surface-variant leading-tight">Family Safety</p>
        </div>
      </div>

      {/* Status badge */}
      <div className="mx-3 mb-3 px-3 py-2 rounded-xl bg-secondary-container/50 border border-secondary/20">
        <div className="flex items-center gap-1.5">
          <span className="w-1.5 h-1.5 rounded-full bg-secondary animate-pulse shrink-0" />
          <span className="text-[11px] font-semibold text-secondary">Protected</span>
        </div>
        <p className="text-[10px] text-on-surface-variant mt-0.5">5 of 6 protections active</p>
      </div>

      {/* Nav */}
      <nav className="px-2 flex-1 space-y-px">
        <p className="text-[9px] font-bold uppercase tracking-[0.12em] text-on-surface-variant/50 px-3 py-1.5">Monitor</p>
        {nav.map((item) => (
          <SidebarItem key={item.id} item={item} active={screen === item.id} onClick={() => onNavigate(item.id)} />
        ))}
        <div className="h-px bg-outline-variant/40 my-2" />
        <p className="text-[9px] font-bold uppercase tracking-[0.12em] text-on-surface-variant/50 px-3 py-1.5">Admin</p>
        {adminNav.map((item) => (
          <SidebarItem key={item.id} item={item} active={screen === item.id} onClick={() => onNavigate(item.id)} />
        ))}
      </nav>

      {/* User footer */}
      <div className="p-3 border-t border-outline-variant/30">
        <div className="flex items-center gap-2.5 px-2 py-1.5 rounded-lg hover:bg-surface-variant transition-colors cursor-default">
          <div className="w-7 h-7 rounded-full bg-primary-container flex items-center justify-center shrink-0">
            <span className="text-xs font-bold text-primary">P</span>
          </div>
          <div className="min-w-0">
            <p className="text-sm font-semibold truncate leading-tight">Parent</p>
            <p className="text-[10px] text-on-surface-variant truncate">parent@home.local</p>
          </div>
        </div>
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
      {item.locked && !active && <Lock className="w-3 h-3 opacity-40" />}
    </button>
  );
}

// ─── PIN Modal ────────────────────────────────────────────────────────────────

function PinModal({ onSuccess, onClose }: { onSuccess: () => void; onClose: () => void }) {
  const [pin, setPin] = useState("");
  const [error, setError] = useState(false);

  const handlePress = (num: string) => {
    if (pin.length >= 4) return;
    const next = pin + num;
    setPin(next);
    if (next.length === 4) {
      if (next === "1234") {
        setTimeout(onSuccess, 200);
      } else {
        setError(true);
        setTimeout(() => { setPin(""); setError(false); }, 900);
      }
    }
  };

  return (
    <Card className="w-[380px] rounded-3xl shadow-2xl p-8 border border-outline-variant/40">
      <div className="flex items-center justify-between mb-7">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 bg-primary-container rounded-full flex items-center justify-center">
            <Lock className="w-5 h-5 text-primary" />
          </div>
          <div>
            <h2 className="font-bold text-base leading-tight">Guardian Access</h2>
            <p className="text-xs text-on-surface-variant">Enter your 4-digit PIN</p>
          </div>
        </div>
        <button
          onClick={onClose}
          className="w-8 h-8 rounded-lg hover:bg-surface-variant flex items-center justify-center text-on-surface-variant transition-colors"
        >
          <X className="w-4 h-4" />
        </button>
      </div>

      {/* Dots */}
      <div className="flex gap-3 justify-center mb-6">
        {[0, 1, 2, 3].map((i) => (
          <div
            key={i}
            className={cn(
              "w-4 h-4 rounded-full transition-all duration-150",
              error ? "bg-error scale-110" : pin.length > i ? "bg-primary scale-110" : "bg-surface-variant"
            )}
          />
        ))}
      </div>

      {error && (
        <p className="text-center text-error text-sm mb-4 font-medium">Incorrect PIN — try again</p>
      )}

      {/* Numpad */}
      <div className="grid grid-cols-3 gap-2">
        {[1, 2, 3, 4, 5, 6, 7, 8, 9].map((num) => (
          <button
            key={num}
            onClick={() => handlePress(String(num))}
            className="h-14 rounded-2xl bg-surface-variant hover:bg-outline-variant/80 text-lg font-semibold transition-colors active:scale-95"
          >
            {num}
          </button>
        ))}
        <div />
        <button
          onClick={() => handlePress("0")}
          className="h-14 rounded-2xl bg-surface-variant hover:bg-outline-variant/80 text-lg font-semibold transition-colors active:scale-95"
        >
          0
        </button>
        <button
          onClick={() => setPin(pin.slice(0, -1))}
          className="h-14 rounded-2xl hover:bg-surface-variant transition-colors flex items-center justify-center text-on-surface-variant active:scale-95"
        >
          <X className="w-5 h-5" />
        </button>
      </div>

      <p className="text-center text-[11px] text-on-surface-variant/40 mt-5">Hint: 1234</p>
    </Card>
  );
}

// ─── Dashboard ────────────────────────────────────────────────────────────────

function DashboardScreen({ onNavigate }: { onNavigate: (s: Screen) => void }) {
  return (
    <div className="p-7 space-y-5 max-w-[1080px]">
      {/* Header */}
      <div className="flex items-start justify-between">
        <div>
          <h1 className="text-2xl font-bold tracking-tight">Good morning, Parent</h1>
          <p className="text-sm text-on-surface-variant mt-0.5">Friday, August 15 · All children accounted for</p>
        </div>
        <div className="flex gap-2.5 mt-0.5">
          <Button variant="outlined" size="sm" className="gap-2">
            <Globe className="w-4 h-4" /> Block Web
          </Button>
          <Button size="sm" className="gap-2" onClick={() => onNavigate("Wizard")}>
            <Plus className="w-4 h-4" /> Add Rule
          </Button>
        </div>
      </div>

      {/* Stat row */}
      <div className="grid grid-cols-4 gap-3">
        <StatTile icon={Users} label="Children" value="2" sub="Active profiles" hue="primary" />
        <StatTile icon={ListChecks} label="Rules Active" value="6" sub="Enforced now" hue="secondary" />
        <StatTile icon={CheckCircle} label="Habits Done" value="3 / 5" sub="Today's progress" hue="tertiary" />
        <StatTile icon={Lock} label="Apps Blocked" value="1" sub="TikTok · Emma" hue="error" />
      </div>

      {/* Main grid */}
      <div className="grid grid-cols-5 gap-4">
        {/* Rules column */}
        <div className="col-span-3 space-y-3">
          <div className="flex items-center justify-between px-0.5">
            <h2 className="font-semibold text-base">Active Rules</h2>
            <button className="p-1.5 rounded-lg hover:bg-surface-variant text-on-surface-variant transition-colors">
              <RefreshCw className="w-3.5 h-3.5" />
            </button>
          </div>

          {/* Rule: TikTok */}
          <Card className="rounded-2xl space-y-3">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-3">
                <AppIcon name="TK" color="bg-[#ff004f]/10 text-[#ff004f]" />
                <div>
                  <p className="font-semibold leading-tight">TikTok</p>
                  <p className="text-xs text-on-surface-variant">Emma · Mon–Fri, 00:00–21:00</p>
                </div>
              </div>
              <Pill variant="error">Blocked</Pill>
            </div>
            <div className="h-px bg-outline-variant/30" />
            <div className="space-y-2">
              <HabitRow label="Morning Run" status="warning" statusText="Not done" />
              <HabitRow label="Read 10 pages" status="success" statusText="Done & verified" />
            </div>
            <div className="flex items-center gap-2 text-xs font-medium text-tertiary">
              <Lock className="w-3 h-3 shrink-0" />
              Blocked until all habit requirements are met
            </div>
          </Card>

          {/* Rule: Instagram */}
          <Card className="rounded-2xl space-y-3">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-3">
                <AppIcon name="IG" color="bg-pink-500/10 text-pink-500" />
                <div>
                  <p className="font-semibold leading-tight">Instagram</p>
                  <p className="text-xs text-on-surface-variant">Emma · Always</p>
                </div>
              </div>
              <Pill variant="success">Unlocked</Pill>
            </div>
            <div className="h-px bg-outline-variant/30" />
            <div className="flex items-center justify-between">
              <HabitRow label="Clean Room" status="default" statusText="Done, proof pending" />
              <Button variant="tonal" size="sm" onClick={() => onNavigate("PhotoCapture")}>
                Verify
              </Button>
            </div>
            <div className="flex items-center gap-2 text-xs font-medium text-secondary">
              <Clock className="w-3 h-3 shrink-0" />
              Unlocked for 12m 30s more
            </div>
          </Card>

          {/* Rule: YouTube */}
          <Card className="rounded-2xl space-y-3">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-3">
                <AppIcon name="YT" color="bg-red-500/10 text-red-500" />
                <div>
                  <p className="font-semibold leading-tight">YouTube</p>
                  <p className="text-xs text-on-surface-variant">Lucas · 30m daily budget</p>
                </div>
              </div>
              <Pill variant="warning">5m left</Pill>
            </div>
            <div>
              <div className="h-1.5 w-full bg-surface-variant rounded-full overflow-hidden">
                <div className="h-full bg-error rounded-full" style={{ width: "83%" }} />
              </div>
              <p className="text-xs text-error mt-1.5 font-medium">25m used of 30m budget</p>
            </div>
          </Card>
        </div>

        {/* Right column */}
        <div className="col-span-2 space-y-4">
          {/* App time */}
          <Card className="rounded-2xl">
            <h3 className="font-semibold text-sm mb-3.5 flex items-center gap-2">
              <BarChart3 className="w-4 h-4 text-primary" /> App Time Today
            </h3>
            <div className="space-y-3">
              <TimeBudgetRow app="Instagram" used={42} total={60} color="bg-primary" />
              <TimeBudgetRow app="YouTube" used={25} total={30} color="bg-error" warn />
              <TimeBudgetRow app="TikTok" used={0} total={45} color="bg-surface-variant" blocked />
              <TimeBudgetRow app="Snapchat" used={18} total={60} color="bg-primary" />
            </div>
          </Card>

          {/* Children */}
          <Card className="rounded-2xl">
            <h3 className="font-semibold text-sm mb-3.5 flex items-center gap-2">
              <Users className="w-4 h-4 text-primary" /> Children
            </h3>
            <div className="space-y-2">
              <ChildRow name="Emma" age={12} device="iPad Pro" online habits="3/4" />
              <ChildRow name="Lucas" age={9} device="iPhone 14" warn habits="0/1" />
            </div>
          </Card>

          {/* Activity log */}
          <Card className="rounded-2xl bg-surface-variant/20 border-none">
            <h3 className="text-sm font-semibold mb-2.5 flex items-center gap-2">
              <Bug className="w-3.5 h-3.5" /> Activity Log
            </h3>
            <div className="space-y-1.5" style={{ fontFamily: "var(--font-mono)" }}>
              {[
                ["10:42", "VPN tunnel established"],
                ["10:41", "Habit verification requested (id:4012)"],
                ["10:40", "com.zhiliaoapp.musically blocked"],
                ["10:38", "Emma completed Read 10 pages"],
                ["10:21", "Lucas opened YouTube — 25m used"],
              ].map(([time, msg]) => (
                <div key={time + msg} className="flex gap-2.5 text-[10px]">
                  <span className="text-on-surface-variant/40 shrink-0 tabular-nums">{time}</span>
                  <span className="text-on-surface-variant/80">{msg}</span>
                </div>
              ))}
            </div>
            <Button variant="text" size="sm" className="w-full mt-2 h-7 text-xs">
              Show all →
            </Button>
          </Card>
        </div>
      </div>
    </div>
  );
}

// ─── Settings ────────────────────────────────────────────────────────────────

function SettingsScreen({ onNavigate }: { onNavigate: (s: Screen) => void }) {
  const [vpn, setVpn] = useState(true);
  const [safeMode, setSafeMode] = useState(true);
  const [factoryReset, setFactoryReset] = useState(true);
  const [uninstall, setUninstall] = useState(true);
  const [friction, setFriction] = useState(true);

  return (
    <div className="p-7 max-w-[820px] space-y-6">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">Settings</h1>
        <p className="text-sm text-on-surface-variant mt-0.5">Configure Otterling's protection and habit rules</p>
      </div>

      <div className="grid grid-cols-2 gap-4">
        {/* Protection */}
        <div className="space-y-3">
          <SectionLabel>Tamper Protection</SectionLabel>
          <Card className="rounded-2xl space-y-0 divide-y divide-outline-variant/30">
            <SettingsRow
              title="Block Safe Mode bypass"
              sub="Prevent circumventing rules via reboot"
              checked={safeMode}
              onChange={setSafeMode}
            />
            <SettingsRow
              title="Block factory reset"
              sub="Require PIN to wipe device"
              checked={factoryReset}
              onChange={setFactoryReset}
            />
            <SettingsRow
              title="Block app uninstall"
              sub="Require PIN to remove Otterling"
              checked={uninstall}
              onChange={setUninstall}
              danger
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
              checked={vpn}
              onChange={setVpn}
            />
            <div className="py-3 px-1">
              <div className="flex items-center gap-2 mb-2">
                <Wifi className="w-4 h-4 text-secondary" />
                <span className="text-sm font-medium">Filter Status</span>
                <Pill variant="success">Active</Pill>
              </div>
              <p className="text-xs text-on-surface-variant">VPN tunnel to filter.otterling.app · TLS 1.3</p>
              <Button variant="text" size="sm" className="px-0 mt-1 text-xs h-7">Manage bypass apps →</Button>
            </div>
          </Card>
        </div>

        {/* Habits */}
        <div className="space-y-3">
          <SectionLabel>Habits & Rules</SectionLabel>
          <Card className="rounded-2xl space-y-0 divide-y divide-outline-variant/30">
            <div className="py-3 px-1 flex items-center justify-between">
              <div>
                <p className="text-sm font-medium">Connected HabitShare</p>
                <p className="text-xs text-on-surface-variant">user@example.com</p>
              </div>
              <Button variant="outlined" size="sm">Unlink</Button>
            </div>
            <SettingsRow
              title="Friction delay"
              sub="Show countdown before unlocking apps"
              checked={friction}
              onChange={setFriction}
            />
            <div className="py-3 px-1">
              <Button variant="text" size="sm" className="px-0 text-xs h-7" onClick={() => onNavigate("Wizard")}>
                Add new rule →
              </Button>
            </div>
          </Card>
        </div>

        {/* PIN & Security */}
        <div className="space-y-3">
          <SectionLabel>PIN & Guardian Access</SectionLabel>
          <Card className="rounded-2xl space-y-0 divide-y divide-outline-variant/30">
            <div className="py-3 px-1 flex items-center justify-between">
              <div>
                <p className="text-sm font-medium">Guardian PIN</p>
                <p className="text-xs text-on-surface-variant">4-digit code · Set</p>
              </div>
              <Button variant="outlined" size="sm">Change PIN</Button>
            </div>
            <div className="py-3 px-1 flex items-center justify-between">
              <div>
                <p className="text-sm font-medium">PIN recovery email</p>
                <p className="text-xs text-on-surface-variant">parent@home.local</p>
              </div>
              <Button variant="text" size="sm" className="text-xs h-7">Edit</Button>
            </div>
            <div className="py-3 px-1">
              <p className="text-sm font-medium text-tertiary">3 failed PIN attempts today</p>
              <p className="text-xs text-on-surface-variant">Last at 10:14 AM</p>
            </div>
          </Card>
        </div>
      </div>

      <div className="flex gap-3 pt-2">
        <Button variant="outlined" size="sm" onClick={() => onNavigate("AccessibilityNag")}>
          Check Accessibility
        </Button>
        <Button variant="text" size="sm" onClick={() => onNavigate("Dashboard")}>
          ← Back to Overview
        </Button>
      </div>
    </div>
  );
}

// ─── Wizard ───────────────────────────────────────────────────────────────────

function HabitRuleWizard({ onNavigate }: { onNavigate: (s: Screen) => void }) {
  const [step, setStep] = useState(1);
  const [selectedApp, setSelectedApp] = useState("");

  const steps = [
    { n: 1, label: "Choose App", sub: "Select target application" },
    { n: 2, label: "Require Habits", sub: "Must be completed to unlock" },
    { n: 3, label: "Set Schedule", sub: "When rule applies" },
  ];

  const apps = [
    { name: "TikTok", color: "bg-[#ff004f]/10 text-[#ff004f]" },
    { name: "Instagram", color: "bg-pink-500/10 text-pink-500" },
    { name: "YouTube", color: "bg-red-500/10 text-red-500" },
    { name: "Snapchat", color: "bg-yellow-400/10 text-yellow-600" },
    { name: "BeReal", color: "bg-black/10 text-on-surface" },
    { name: "Discord", color: "bg-indigo-500/10 text-indigo-500" },
  ];

  return (
    <div className="h-full flex flex-col">
      <div className="flex-1 flex overflow-hidden">
        {/* Step sidebar */}
        <div className="w-60 border-r border-outline-variant/30 p-6 shrink-0 flex flex-col">
          <h2 className="text-xl font-bold mb-1">Create Rule</h2>
          <p className="text-xs text-on-surface-variant mb-6">Define habit requirements for app access</p>
          <div className="space-y-2">
            {steps.map((s) => (
              <div
                key={s.n}
                className={cn(
                  "flex items-start gap-3 p-3 rounded-xl transition-colors",
                  step === s.n && "bg-primary/10"
                )}
              >
                <div
                  className={cn(
                    "w-6 h-6 rounded-full flex items-center justify-center text-xs font-bold shrink-0 mt-0.5 transition-all",
                    step > s.n
                      ? "bg-secondary text-on-secondary"
                      : step === s.n
                      ? "bg-primary text-on-primary"
                      : "bg-surface-variant text-on-surface-variant"
                  )}
                >
                  {step > s.n ? <Check className="w-3 h-3" /> : s.n}
                </div>
                <div>
                  <p
                    className={cn(
                      "text-sm font-semibold leading-tight",
                      step === s.n
                        ? "text-primary"
                        : step > s.n
                        ? "text-secondary"
                        : "text-on-surface-variant"
                    )}
                  >
                    {s.label}
                  </p>
                  <p className="text-xs text-on-surface-variant mt-0.5">{s.sub}</p>
                </div>
              </div>
            ))}
          </div>

          {selectedApp && (
            <div className="mt-auto pt-4 border-t border-outline-variant/30">
              <p className="text-[10px] font-bold uppercase tracking-wider text-on-surface-variant/50 mb-2">Preview</p>
              <div className="p-3 bg-surface-variant/40 rounded-xl">
                <p className="text-sm font-semibold">{selectedApp}</p>
                <p className="text-xs text-on-surface-variant mt-0.5">
                  {step >= 2 ? "Habits required" : "App selected"}
                </p>
              </div>
            </div>
          )}
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
                placeholder="Search installed apps..."
                className="w-full h-11 px-4 rounded-xl border border-outline bg-surface text-sm focus:outline-none focus:ring-2 focus:ring-primary"
              />
              <div className="grid grid-cols-2 gap-2">
                {apps.map((app) => (
                  <button
                    key={app.name}
                    onClick={() => { setSelectedApp(app.name); setStep(2); }}
                    className={cn(
                      "flex items-center gap-3 p-3.5 rounded-xl border transition-all text-left",
                      selectedApp === app.name
                        ? "border-primary bg-primary/5"
                        : "border-outline-variant/40 hover:bg-surface-variant"
                    )}
                  >
                    <div className={cn("w-9 h-9 rounded-lg flex items-center justify-center text-xs font-bold", app.color)}>
                      {app.name.slice(0, 2)}
                    </div>
                    <span className="font-medium text-sm">{app.name}</span>
                  </button>
                ))}
              </div>
            </div>
          )}

          {step === 2 && (
            <div className="max-w-xl space-y-4">
              <div>
                <h3 className="text-lg font-bold">Required habits</h3>
                <p className="text-sm text-on-surface-variant mt-0.5">Select which habits must be done before {selectedApp || "the app"} unlocks.</p>
              </div>
              <div className="grid grid-cols-2 gap-2">
                {[
                  { name: "Morning Run", sel: false },
                  { name: "Read 10 pages", sel: true },
                  { name: "Clean Room", sel: false },
                  { name: "Homework", sel: true },
                  { name: "Brush Teeth", sel: false },
                  { name: "Practice Piano", sel: false },
                ].map((h) => (
                  <div
                    key={h.name}
                    className={cn(
                      "flex items-center gap-3 p-3.5 rounded-xl border cursor-pointer transition-all",
                      h.sel ? "border-secondary bg-secondary-container/40" : "border-outline-variant/40 hover:bg-surface-variant"
                    )}
                  >
                    <div className={cn(
                      "w-5 h-5 rounded-full border-2 flex items-center justify-center shrink-0",
                      h.sel ? "border-secondary bg-secondary" : "border-outline"
                    )}>
                      {h.sel && <Check className="w-3 h-3 text-on-secondary" />}
                    </div>
                    <span className="text-sm font-medium">{h.name}</span>
                  </div>
                ))}
              </div>
              <div className="pt-2">
                <Button variant="text" size="sm" className="gap-1.5 text-xs">
                  <Plus className="w-3.5 h-3.5" /> Add custom habit
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
                    <input type="time" defaultValue="00:00" className="flex-1 h-10 px-3 rounded-xl border border-outline bg-surface text-sm focus:outline-none focus:ring-2 focus:ring-primary" />
                    <span className="text-sm text-on-surface-variant">to</span>
                    <input type="time" defaultValue="21:00" className="flex-1 h-10 px-3 rounded-xl border border-outline bg-surface text-sm focus:outline-none focus:ring-2 focus:ring-primary" />
                  </div>
                </div>
                <div>
                  <label className="text-sm font-semibold block mb-2.5">Days of week</label>
                  <div className="flex gap-2">
                    {["M", "T", "W", "T", "F", "S", "S"].map((d, i) => (
                      <div
                        key={i}
                        className={cn(
                          "w-10 h-10 rounded-full flex items-center justify-center text-sm font-semibold cursor-pointer transition-colors select-none",
                          i < 5 ? "bg-primary text-on-primary" : "bg-surface-variant text-on-surface-variant hover:bg-outline-variant"
                        )}
                      >
                        {d}
                      </div>
                    ))}
                  </div>
                </div>
                <div>
                  <label className="text-sm font-semibold block mb-2">Daily time budget (optional)</label>
                  <div className="flex items-center gap-3">
                    <input type="number" defaultValue={60} min={0} max={480} className="w-24 h-10 px-3 rounded-xl border border-outline bg-surface text-sm focus:outline-none focus:ring-2 focus:ring-primary" />
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
        <Button
          variant="text"
          size="sm"
          onClick={() => (step > 1 ? setStep(step - 1) : onNavigate("Dashboard"))}
        >
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
            onClick={() => (step < 3 ? setStep(step + 1) : onNavigate("Dashboard"))}
            disabled={step === 1 && !selectedApp}
          >
            {step < 3 ? "Continue →" : "Save Rule"}
          </Button>
        </div>
      </div>
    </div>
  );
}

// ─── Photo Capture ────────────────────────────────────────────────────────────

function PhotoCaptureScreen({ onNavigate }: { onNavigate: (s: Screen) => void }) {
  const [state, setState] = useState<"capture" | "checking" | "match" | "nomatch">("capture");

  const simulate = () => {
    setState("checking");
    setTimeout(() => setState(Math.random() > 0.4 ? "match" : "nomatch"), 2000);
  };

  return (
    <div className="h-full flex">
      {/* Camera view */}
      <div className="flex-1 bg-neutral-950 relative flex items-center justify-center">
        <div className="absolute inset-10 border border-dashed border-white/15 rounded-3xl" />
        <div className="absolute top-4 left-4 right-4 flex items-center justify-between">
          <div className="px-3 py-1 bg-black/40 backdrop-blur rounded-full">
            <span className="text-white text-xs font-medium flex items-center gap-1.5">
              <span className="w-1.5 h-1.5 rounded-full bg-red-500 animate-pulse" />
              LIVE
            </span>
          </div>
        </div>

        {state === "checking" ? (
          <div className="flex flex-col items-center text-white">
            <RefreshCw className="w-10 h-10 animate-spin mb-3 text-primary" />
            <p className="text-sm font-medium">Analyzing photo...</p>
            <p className="text-xs text-white/50 mt-1">Comparing with reference image</p>
          </div>
        ) : state === "match" ? (
          <div className="flex flex-col items-center">
            <div className="w-20 h-20 bg-secondary/20 border-2 border-secondary rounded-full flex items-center justify-center mb-4">
              <CheckCircle className="w-10 h-10 text-secondary" />
            </div>
            <p className="text-white text-xl font-bold">Match confirmed</p>
          </div>
        ) : state === "nomatch" ? (
          <div className="flex flex-col items-center">
            <div className="w-20 h-20 bg-red-500/20 border-2 border-red-400 rounded-full flex items-center justify-center mb-4">
              <X className="w-10 h-10 text-red-400" />
            </div>
            <p className="text-white text-xl font-bold">No match</p>
          </div>
        ) : (
          <div className="flex flex-col items-center text-white/30">
            <Camera className="w-16 h-16 mb-3" />
            <p className="text-sm">Camera preview</p>
          </div>
        )}

        {/* Corner guides */}
        {state === "capture" && (
          <>
            <div className="absolute top-10 left-10 w-8 h-8 border-t-2 border-l-2 border-white/40 rounded-tl-xl" />
            <div className="absolute top-10 right-10 w-8 h-8 border-t-2 border-r-2 border-white/40 rounded-tr-xl" />
            <div className="absolute bottom-10 left-10 w-8 h-8 border-b-2 border-l-2 border-white/40 rounded-bl-xl" />
            <div className="absolute bottom-10 right-10 w-8 h-8 border-b-2 border-r-2 border-white/40 rounded-br-xl" />
          </>
        )}
      </div>

      {/* Right panel */}
      <div className="w-80 border-l border-outline-variant/30 bg-background flex flex-col">
        <div className="p-6 border-b border-outline-variant/30">
          <h2 className="text-xl font-bold">Prove it: Clean Room</h2>
          <p className="text-sm text-on-surface-variant mt-1">Photo must match the reference to unlock apps.</p>
        </div>

        <div className="p-6 flex-1 flex flex-col gap-5">
          {/* Reference photo */}
          <div>
            <p className="text-[10px] font-bold uppercase tracking-wider text-on-surface-variant/60 mb-2">Reference Photo</p>
            <div className="h-40 bg-surface-variant rounded-2xl flex items-center justify-center text-on-surface-variant/40">
              <div className="text-center">
                <Camera className="w-8 h-8 mx-auto mb-1" />
                <p className="text-xs">Reference image</p>
              </div>
            </div>
          </div>

          {/* Tips */}
          {state === "capture" && (
            <div className="text-xs text-on-surface-variant space-y-1.5 bg-surface-variant/30 rounded-xl p-3">
              <p className="font-semibold text-on-surface mb-1">Tips for a good match:</p>
              <p>· Same angle and distance as the reference</p>
              <p>· Good lighting, avoid harsh shadows</p>
              <p>· Make sure the whole area is visible</p>
            </div>
          )}

          {state === "match" && (
            <div className="flex items-start gap-3 p-4 bg-secondary-container/50 rounded-2xl border border-secondary/20">
              <CheckCircle className="w-5 h-5 text-secondary shrink-0 mt-0.5" />
              <div>
                <p className="font-semibold text-secondary">Verified!</p>
                <p className="text-xs text-on-surface-variant mt-0.5">Apps will be unlocked immediately.</p>
              </div>
            </div>
          )}

          {state === "nomatch" && (
            <div className="flex items-start gap-3 p-4 bg-error-container/50 rounded-2xl border border-error/20">
              <X className="w-5 h-5 text-error shrink-0 mt-0.5" />
              <div>
                <p className="font-semibold text-error">No match</p>
                <p className="text-xs text-on-surface-variant mt-0.5">Photo doesn't match the reference. Try again.</p>
              </div>
            </div>
          )}
        </div>

        {/* Actions */}
        <div className="p-6 pt-0 space-y-2.5">
          {state === "capture" && (
            <>
              <Button className="w-full gap-2" onClick={simulate}>
                <Camera className="w-4 h-4" /> Take Photo
              </Button>
              <Button variant="text" className="w-full" onClick={() => onNavigate("Dashboard")}>
                Not now
              </Button>
            </>
          )}
          {state === "checking" && (
            <Button className="w-full" disabled>
              <RefreshCw className="w-4 h-4 mr-2 animate-spin" /> Analyzing...
            </Button>
          )}
          {state === "match" && (
            <Button className="w-full bg-secondary text-on-secondary hover:bg-secondary/90" onClick={() => onNavigate("Dashboard")}>
              Continue to apps →
            </Button>
          )}
          {state === "nomatch" && (
            <>
              <Button className="w-full" onClick={() => setState("capture")}>
                Retake Photo
              </Button>
              <Button variant="text" className="w-full" onClick={() => onNavigate("Dashboard")}>
                Ask parent for help
              </Button>
            </>
          )}
        </div>
      </div>
    </div>
  );
}

// ─── Friction Delay ───────────────────────────────────────────────────────────

function FrictionDelayScreen({ onNavigate }: { onNavigate: (s: Screen) => void }) {
  const [timeLeft, setTimeLeft] = useState(5);

  useEffect(() => {
    if (timeLeft > 0) {
      const t = setTimeout(() => setTimeLeft((p) => p - 1), 1000);
      return () => clearTimeout(t);
    }
  }, [timeLeft]);

  const pct = ((5 - timeLeft) / 5) * 100;

  return (
    <div className="h-full flex items-center justify-center bg-secondary-container/30">
      <div className="max-w-sm w-full px-8 text-center">
        <h1 className="text-4xl font-bold text-secondary mb-3" style={{ fontFamily: "var(--font-sans)" }}>
          Pause.
        </h1>
        <p className="text-base text-on-secondary-container/80 leading-relaxed mb-12">
          Is opening this app the best use of your time right now?
        </p>

        {/* Ring */}
        <div className="relative w-36 h-36 mx-auto mb-12">
          <svg className="w-full h-full -rotate-90" viewBox="0 0 144 144">
            <circle cx="72" cy="72" r="66" fill="none" stroke="currentColor" strokeWidth="6" className="text-secondary/15" />
            <circle
              cx="72" cy="72" r="66"
              fill="none"
              stroke="currentColor"
              strokeWidth="6"
              strokeLinecap="round"
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
            className={cn(
              "w-full transition-all",
              timeLeft === 0
                ? "bg-secondary text-on-secondary hover:bg-secondary/90"
                : "bg-secondary/30 text-secondary cursor-not-allowed"
            )}
            disabled={timeLeft > 0}
            onClick={() => onNavigate("Dashboard")}
          >
            {timeLeft > 0 ? `Wait ${timeLeft}s...` : "Continue to app →"}
          </Button>
          <Button variant="text" className="w-full text-on-secondary-container/60" onClick={() => onNavigate("Dashboard")}>
            Close without opening
          </Button>
        </div>

        <p className="text-xs text-on-secondary-container/40 mt-8">
          This pause is set by your parent via Otterling
        </p>
      </div>
    </div>
  );
}

// ─── Accessibility Nag ────────────────────────────────────────────────────────

function AccessibilityNagScreen({ onNavigate }: { onNavigate: (s: Screen) => void }) {
  return (
    <div className="h-full flex items-center justify-center p-8">
      <div className="max-w-2xl w-full">
        <div className="grid grid-cols-2 gap-8 items-start">
          {/* Left: Status */}
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
                <AlertTriangle className="w-4 h-4" />
                Children are unprotected
              </div>
              <p className="text-xs text-on-surface-variant">All rules and habits are currently bypassed.</p>
            </div>
            <Button
              className="w-full h-12 bg-error text-on-error hover:bg-error/90"
              onClick={() => onNavigate("Dashboard")}
            >
              Re-enable in System Settings
            </Button>
            <Button variant="text" size="sm" className="w-full mt-2" onClick={() => onNavigate("Dashboard")}>
              Dismiss for now
            </Button>
          </div>

          {/* Right: Fix steps */}
          <div>
            <h2 className="font-semibold text-base mb-4">How to re-enable protection</h2>
            <div className="space-y-3">
              {[
                { n: 1, title: "Open System Settings", sub: 'Click the Apple  menu → "System Settings"' },
                { n: 2, title: "Navigate to Privacy & Security", sub: 'Then select "Accessibility" from the left sidebar' },
                { n: 3, title: 'Find "Otterling"', sub: "Scroll the list until you see the Otterling entry" },
                { n: 4, title: "Turn the toggle ON", sub: "You may need to authenticate with Touch ID or your password" },
              ].map((step) => (
                <div key={step.n} className="flex items-start gap-3 p-3.5 bg-surface rounded-2xl border border-outline-variant/30">
                  <div className="w-6 h-6 rounded-full bg-primary-container flex items-center justify-center shrink-0 mt-0.5">
                    <span className="text-xs font-bold text-primary">{step.n}</span>
                  </div>
                  <div>
                    <p className="text-sm font-semibold">{step.title}</p>
                    <p className="text-xs text-on-surface-variant mt-0.5">{step.sub}</p>
                  </div>
                </div>
              ))}
            </div>

            <div className="mt-4 p-3.5 bg-surface-variant/30 rounded-2xl">
              <p className="text-xs text-on-surface-variant">
                <span className="font-semibold">Still stuck?</span>{" "}
                Contact support at help.otterling.app or restart the Otterling daemon from Settings.
              </p>
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

function HabitRow({
  label,
  status,
  statusText,
  action,
}: {
  label: string;
  status: "success" | "warning" | "default";
  statusText: string;
  action?: React.ReactNode;
}) {
  return (
    <div className="flex items-center justify-between gap-3">
      <div className="flex items-center gap-2 min-w-0">
        <div className={cn(
          "w-4 h-4 rounded-full flex items-center justify-center shrink-0",
          status === "success" ? "bg-secondary" : status === "warning" ? "bg-tertiary" : "bg-surface-variant"
        )}>
          {status === "success" && <Check className="w-2.5 h-2.5 text-on-secondary" />}
        </div>
        <span className="text-sm truncate">{label}</span>
      </div>
      <div className="flex items-center gap-2 shrink-0">
        <Pill variant={status === "success" ? "success" : status === "warning" ? "warning" : "default"}>
          {statusText}
        </Pill>
        {action}
      </div>
    </div>
  );
}

function TimeBudgetRow({
  app, used, total, color, warn, blocked,
}: {
  app: string; used: number; total: number; color: string; warn?: boolean; blocked?: boolean;
}) {
  const pct = blocked ? 0 : Math.min((used / total) * 100, 100);
  return (
    <div>
      <div className="flex items-center justify-between text-xs mb-1">
        <span className="font-medium">{app}</span>
        <span className={cn("tabular-nums", blocked ? "text-on-surface-variant/50" : warn ? "text-error font-semibold" : "")}>
          {blocked ? "Blocked" : `${used}m / ${total}m`}
        </span>
      </div>
      <div className="h-1.5 w-full bg-surface-variant rounded-full overflow-hidden">
        <div className={cn("h-full rounded-full transition-all", color)} style={{ width: `${pct}%` }} />
      </div>
    </div>
  );
}

function ChildRow({
  name, age, device, online, warn, habits,
}: {
  name: string; age: number; device: string; online?: boolean; warn?: boolean; habits: string;
}) {
  return (
    <div className="flex items-center gap-3 p-2.5 rounded-xl hover:bg-surface-variant/30 transition-colors">
      <div className={cn(
        "w-8 h-8 rounded-full flex items-center justify-center text-xs font-bold shrink-0",
        warn ? "bg-tertiary-container text-tertiary" : "bg-primary-container text-primary"
      )}>
        {name[0]}
      </div>
      <div className="flex-1 min-w-0">
        <div className="flex items-center gap-1.5">
          <p className="text-sm font-semibold">{name}</p>
          <span className="text-[10px] text-on-surface-variant">{age}y</span>
          <span className={cn("w-1.5 h-1.5 rounded-full ml-0.5", online ? "bg-secondary" : warn ? "bg-tertiary" : "bg-outline")} />
        </div>
        <p className="text-[10px] text-on-surface-variant truncate">{device} · {habits} habits today</p>
      </div>
      <ChevronRight className="w-3.5 h-3.5 text-on-surface-variant/40 shrink-0" />
    </div>
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
  return (
    <p className="text-xs font-bold uppercase tracking-wider text-primary px-0.5">{children}</p>
  );
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
