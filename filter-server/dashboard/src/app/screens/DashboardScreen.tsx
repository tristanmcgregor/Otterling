import {
  Shield, Lock, Clock, Globe, CheckCircle, Bug, Plus, AlertTriangle, BarChart3, Wifi, ListChecks, Laptop,
} from "lucide-react";
import { cn, Card, Button, Pill } from "../components/ui";
import type { ActivityEvent, DeviceSettings, Habit, Rule } from "../../lib/api";
import type { Screen } from "../navigation";
import { AppIcon, StatTile } from "../components/shared";
import { describeSchedule, ruleTargetLabel } from "../utils/rules";

// ─── Dashboard ────────────────────────────────────────────────────────────────

export function DashboardScreen({
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
