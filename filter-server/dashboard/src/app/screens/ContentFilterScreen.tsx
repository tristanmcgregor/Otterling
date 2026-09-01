import { useEffect, useState } from "react";
import { Wifi } from "lucide-react";
import { Card, Button, Pill } from "../components/ui";
import { api } from "../../lib/api";
import type { DeviceSettings } from "../../lib/api";
import { SectionLabel, SettingsRow, TagList } from "../components/shared";

// Shared, platform-conditional (Android: VPN tunnel + per-app bypass list; Mac: DNS enforcement +
// proxy enforcement + cloud filter resolver -- MitmExemptManager's bypass-app concept has no Mac
// equivalent, so that part only renders for Android).
export function ContentFilterScreen({
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
