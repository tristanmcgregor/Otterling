import { useState } from "react";
import { Plus, X } from "lucide-react";
import { Card, Button } from "../components/ui";
import { api } from "../../lib/api";
import type { DeviceSettings, ProtectedApp } from "../../lib/api";
import type { Screen } from "../navigation";
import { PlatformNotAvailable } from "../components/shared";

// macOS-only (filesystem-locked, undeletable apps -- see ProtectedApp's doc comment in
// lib/api.ts). No Android equivalent.
export function ProtectedAppsScreen({
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
