import { useState } from "react";
import { Check, Plus, X } from "lucide-react";
import { cn, Button, Card, Pill } from "../components/ui";
import { api } from "../../lib/api";
import type {
  DeviceSettings, DeviceSummary, Habit, Rule, RuleTargetApp, RuleTargetWebsite,
} from "../../lib/api";
import type { Screen } from "../navigation";

// ─── Wizard ───────────────────────────────────────────────────────────────────

const COMMON_APPS = [
  { name: "TikTok", color: "bg-[#ff004f]/10 text-[#ff004f]" },
  { name: "Instagram", color: "bg-pink-500/10 text-pink-500" },
  { name: "YouTube", color: "bg-red-500/10 text-red-500" },
  { name: "Snapchat", color: "bg-yellow-400/10 text-yellow-600" },
  { name: "BeReal", color: "bg-black/10 text-on-surface" },
  { name: "Discord", color: "bg-indigo-500/10 text-indigo-500" },
];

export function HabitRuleWizard({
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
  // A rule with an empty requiredHabitIds list blocks unconditionally for its whole scheduled
  // window (see lockprofile_service.py's _currently_blocked_website_domains) -- exactly what a
  // curfew-style rule wants, but it means this step's own defaults (00:00-21:00, Mon-Fri) would
  // ALSO impose an all-day block on top of a pure daily-budget rule if left untouched, defeating
  // the point of "just cap it at N minutes, any time." This toggle lets a guardian opt out of the
  // schedule condition entirely -- checked, `save` sends an empty schedule, which
  // _currently_blocked_website_domains' `start is None or end is None` check already skips, so
  // only the habit/budget condition(s) apply. Defaults to whatever the rule being edited already
  // has (no stored startTime means a prior save already used this).
  const [noSchedule, setNoSchedule] = useState(editingRule ? !editingRule.schedule.startTime : false);
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
      schedule: noSchedule ? {} : { startTime, endTime, daysOfWeek: days },
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
                <label className="flex items-start gap-2.5 cursor-pointer">
                  <input
                    type="checkbox"
                    checked={noSchedule}
                    onChange={(e) => setNoSchedule(e.target.checked)}
                    className="mt-0.5 w-4 h-4 rounded border-outline accent-primary"
                  />
                  <span>
                    <span className="text-sm font-semibold block">No schedule restriction</span>
                    <span className="text-xs text-on-surface-variant">
                      Applies at any time, any day -- use this for a pure daily time budget with no time-of-day window.
                      {selectedHabitIds.length === 0 && " Without a required habit, leaving this unchecked blocks the target for the entire window below, every time it's in effect."}
                    </span>
                  </span>
                </label>
                <div className={cn(noSchedule && "opacity-40 pointer-events-none")}>
                  <label className="text-sm font-semibold block mb-2">Active time window</label>
                  <div className="flex items-center gap-3">
                    <input type="time" value={startTime} onChange={(e) => setStartTime(e.target.value)} className="flex-1 h-10 px-3 rounded-xl border border-outline bg-surface text-sm focus:outline-none focus:ring-2 focus:ring-primary" />
                    <span className="text-sm text-on-surface-variant">to</span>
                    <input type="time" value={endTime} onChange={(e) => setEndTime(e.target.value)} className="flex-1 h-10 px-3 rounded-xl border border-outline bg-surface text-sm focus:outline-none focus:ring-2 focus:ring-primary" />
                  </div>
                </div>
                <div className={cn(noSchedule && "opacity-40 pointer-events-none")}>
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
