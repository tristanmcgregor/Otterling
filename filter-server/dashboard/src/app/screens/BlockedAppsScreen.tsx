import { Card } from "../components/ui";
import { api } from "../../lib/api";
import type { DeviceSettings } from "../../lib/api";
import { TagList } from "../components/shared";

export function BlockedAppsScreen({
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
