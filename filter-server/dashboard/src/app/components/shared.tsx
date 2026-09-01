import React, { useState } from "react";
import { Plus, X } from "lucide-react";
import { cn, Card, Button, Switch } from "./ui";
import type { Screen } from "../navigation";

// Shown by a platform-specific screen (Blocked Sites is Android-only, Protected Apps is
// macOS-only) when the currently-selected device is the other platform -- reachable if the
// guardian switches devices while parked on one of these screens, since `screen` state persists
// across the device switcher. Points back to Overview rather than leaving a dead-looking page up.
export function PlatformNotAvailable({ message, onNavigate }: { message: string; onNavigate: (s: Screen) => void }) {
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

export function TagList({
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

export function StatTile({
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

export function AppIcon({ name, color }: { name: string; color: string }) {
  return (
    <div className={cn("w-8 h-8 rounded-lg flex items-center justify-center text-xs font-bold shrink-0", color)}>
      {name}
    </div>
  );
}

export function SectionLabel({ children }: { children: React.ReactNode }) {
  return <p className="text-xs font-bold uppercase tracking-wider text-primary px-0.5">{children}</p>;
}

export function SettingsRow({
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
