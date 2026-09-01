import { Card } from "../components/ui";
import { api } from "../../lib/api";
import type { DeviceSettings } from "../../lib/api";
import type { Screen } from "../navigation";
import { PlatformNotAvailable, TagList } from "../components/shared";

// Android-only (VPN/DNS-filter blocklist -- see BlockedWebsite's doc comment in lib/api.ts). The
// Mac's equivalent is DNS enforcement, under Content Filter instead.
export function BlockedSitesScreen({
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
