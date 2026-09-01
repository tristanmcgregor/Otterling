import { useEffect, useState } from "react";
import { Plus, Trash2, X } from "lucide-react";
import { Card, Button } from "../components/ui";
import { api } from "../../lib/api";
import type { AppBudget, DeviceSettings } from "../../lib/api";
import type { Screen } from "../navigation";
import { SectionLabel, SettingsRow, TagList } from "../components/shared";

export function SettingsScreen({
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
          <SectionLabel>Visual Filtering</SectionLabel>
          <Card className="rounded-2xl space-y-0 divide-y divide-outline-variant/30">
            <SettingsRow
              title="Screenshot NSFW filtering"
              sub="Periodically uploads a screenshot of the foreground app to the server for classification; a positive match blocks that app for 15 minutes and alerts you"
              checked={settings.visualFilterEnabled}
              onChange={(v) => api.patchSettings(deviceId, { visualFilterEnabled: v }).then(onChanged)}
            />
            {settings.visualFilterEnabled && (
              <div className="py-3 px-1 flex items-center gap-3">
                <label className="text-sm text-on-surface-variant">Min. interval</label>
                <input
                  type="number"
                  min={15}
                  max={3600}
                  defaultValue={settings.visualFilterIntervalSeconds}
                  onBlur={(e) =>
                    api
                      .patchSettings(deviceId, { visualFilterIntervalSeconds: Number(e.target.value) || 60 })
                      .then(onChanged)
                  }
                  className="w-20 h-9 px-3 rounded-xl border border-outline bg-surface text-sm focus:outline-none focus:ring-2 focus:ring-primary"
                />
                <span className="text-xs text-on-surface-variant">seconds</span>
              </div>
            )}
            <div className="py-3 px-1">
              <a
                href="/screenshot-review/list"
                target="_blank"
                rel="noreferrer"
                className="text-xs text-primary underline"
              >
                View flagged screenshots
              </a>
            </div>
          </Card>
        </div>
        )}

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
