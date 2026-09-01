import { useEffect, useState } from "react";
import { Card, Button, Switch, Pill } from "../components/ui";
import { api, ApiError } from "../../lib/api";
import type { DeviceSettings, ReportType, ReportTypesFile, WelcomeMessage } from "../../lib/api";
import { SectionLabel, TagList } from "../components/shared";

// ─── Accountability ────────────────────────────────────────────────────────────
// Partners (this device's phone-number list) is device-scoped -- see AccountabilityPartner's doc
// comment in lib/api.ts for how a dashboard add/remove reconciles onto the phone itself. Welcome
// Message and Reports below stay fleet-wide: one welcome text and one report-type enablement list
// shared by every device, not per-device settings.
export function AccountabilityScreen({
  deviceId, settings, onChanged,
}: {
  deviceId: string;
  settings: DeviceSettings | null;
  onChanged: () => void;
}) {
  return (
    <div className="p-7 max-w-[900px] space-y-6">
      <div>
        <h1 className="text-xl font-bold">Accountability</h1>
        <p className="text-sm text-on-surface-variant mt-0.5">
          What accountability partners see and hear from Otterling — who gets alerted, the
          one-time welcome text they get, and every kind of report/alert that can follow it.
        </p>
      </div>

      <div className="space-y-3">
        <SectionLabel>Partners</SectionLabel>
        <p className="text-xs text-on-surface-variant -mt-1">
          Phone numbers for the currently selected device ({settings?.device_name || deviceId || "no device selected"}).
          Adding one here sends it the welcome text below, same as adding it in the phone's own
          Settings; removing one here sends the removal text and stops further alerts. Takes
          effect on the phone within ~15 minutes (its next settings poll).
        </p>
        <AccountabilityPartnersPanel deviceId={deviceId} settings={settings} onChanged={onChanged} />
      </div>

      <div className="space-y-3">
        <SectionLabel>Welcome Message</SectionLabel>
        <p className="text-xs text-on-surface-variant -mt-1">
          Sent once, automatically, the first time a phone number is added as an accountability
          partner (from either the phone's own Settings or the Partners list above). Explains what
          the SMS suspicion tags mean, so a partner isn't guessing the first time a real alert
          arrives.
        </p>
        <WelcomeMessagePanel />
      </div>

      <div className="space-y-3">
        <div className="flex items-center justify-between gap-3">
          <SectionLabel>Reports</SectionLabel>
          <SendTestReportButton />
        </div>
        <p className="text-xs text-on-surface-variant -mt-1">
          Every kind of accountability report/alert Otterling can send, grouped by where it comes
          from. Turning one off fully suppresses it — no SMS, no local log entry, nothing recorded
          — so use this deliberately. A type not listed here (e.g. a newly added one) defaults to
          on. Takes effect immediately server-side; the phone picks up a change within ~15 minutes.
        </p>
        <ReportTypesPanel />
      </div>
    </div>
  );
}

function AccountabilityPartnersPanel({
  deviceId, settings, onChanged,
}: {
  deviceId: string;
  settings: DeviceSettings | null;
  onChanged: () => void;
}) {
  if (!deviceId) {
    return (
      <Card className="rounded-2xl">
        <p className="text-xs text-on-surface-variant">
          Select a device (top of the sidebar) to manage its accountability partners.
        </p>
      </Card>
    );
  }
  if (!settings) return <p className="text-xs text-on-surface-variant">Loading…</p>;
  if (settings.platform !== "android") {
    return (
      <Card className="rounded-2xl">
        <p className="text-xs text-on-surface-variant">
          Accountability partners are an Android-only feature (SMS is sent from the phone's own SIM).
        </p>
      </Card>
    );
  }
  return (
    <Card className="rounded-2xl">
      <TagList
        items={settings.accountabilityPartners.map((p) => ({ id: p.phone, label: p.phone }))}
        placeholder="+61..."
        onAdd={(phone) => api.addPartner(deviceId, phone).then(onChanged)}
        onRemove={(phone) => api.removePartner(deviceId, phone).then(onChanged)}
      />
    </Card>
  );
}

// Guardian-editable wording for the one-time welcome SMS (see AlertReporter.kt's
// sendWelcomeMessage) -- "" clears back to DEFAULT_WELCOME_MESSAGE, same convention as a report
// type's customMessage. The phone picks up a saved change within ~15 minutes (MacTamperPollWorker
// cadence), same as everything else in report_types.json.
function WelcomeMessagePanel() {
  const [data, setData] = useState<WelcomeMessage | null>(null);
  const [draft, setDraft] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    api.getWelcomeMessage()
      .then((d) => { setData(d); setDraft(d.message); })
      .catch(() => setError("Failed to load welcome message"));
  }, []);

  const save = (message: string) => {
    setSaving(true);
    setSaved(false);
    api.setWelcomeMessage(message)
      .then((d) => {
        setData(d);
        setDraft(d.message);
        setSaved(true);
        window.setTimeout(() => setSaved(false), 3000);
      })
      .catch(() => setError("Failed to save welcome message"))
      .finally(() => setSaving(false));
  };

  if (error) return <p className="text-xs text-error">{error}</p>;
  if (!data) return <p className="text-xs text-on-surface-variant">Loading…</p>;

  const dirty = draft !== data.message;

  return (
    <Card className="rounded-2xl space-y-2.5">
      <textarea
        value={draft}
        onChange={(e) => setDraft(e.target.value)}
        rows={8}
        maxLength={1000}
        className="w-full text-sm px-3 py-2.5 rounded-xl border border-outline bg-surface focus:outline-none focus:ring-2 focus:ring-primary resize-none"
      />
      <div className="flex items-center gap-2">
        <Button size="sm" className="h-8 px-3 text-xs" disabled={saving || !dirty} onClick={() => save(draft)}>
          {saving ? "Saving…" : "Save"}
        </Button>
        {!data.isDefault && (
          <Button
            variant="outlined"
            size="sm"
            className="h-8 px-3 text-xs"
            disabled={saving}
            onClick={() => save("")}
          >
            Reset to default
          </Button>
        )}
        {saved && <p className="text-[11px] text-on-surface-variant">Saved</p>}
        {data.isDefault && !dirty && (
          <p className="text-[11px] text-on-surface-variant/70">Using the built-in default wording</p>
        )}
      </div>
    </Card>
  );
}

// Fires a TEST_REPORT event through the real alert pipeline (ntfy push + accountability-partner
// SMS relay) so a guardian can confirm it's wired up end-to-end without waiting for a real tamper
// event. Honors TEST_REPORT's own enabled toggle in the panel below like any other type.
function SendTestReportButton() {
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<string | null>(null);

  const handleClick = () => {
    setBusy(true);
    setResult(null);
    api.sendTestReport()
      .then((res) => setResult(res.sent ? "Test report sent" : "TEST_REPORT is disabled below"))
      .catch((err) => setResult(err instanceof ApiError ? err.message : "Couldn't reach the server"))
      .finally(() => {
        setBusy(false);
        window.setTimeout(() => setResult(null), 5000);
      });
  };

  return (
    <div className="flex items-center gap-2">
      {result && <p className="text-[11px] text-on-surface-variant">{result}</p>}
      <Button variant="text" size="sm" className="px-0 text-xs h-7" onClick={handleClick} disabled={busy}>
        {busy ? "Sending…" : "Send test report"}
      </Button>
    </div>
  );
}

function ReportTypesPanel() {
  const [data, setData] = useState<ReportTypesFile | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.getReportTypes().then(setData).catch(() => setError("Failed to load report types"));
  }, []);

  const toggle = (type: string, enabled: boolean) => {
    api.setReportTypeEnabled(type, enabled).then(setData).catch(() => setError(`Failed to update "${type}"`));
  };

  const setMessage = (type: string, customMessage: string) =>
    api.setReportTypeMessage(type, customMessage).then(setData).catch(() => {
      setError(`Failed to update "${type}"'s message`);
      throw new Error("failed"); // lets the row know the save didn't stick
    });

  const setSuspicion = (type: string, suspicion: ReportType["suspicion"]) => {
    api.setReportTypeSuspicion(type, suspicion).then(setData).catch(() => setError(`Failed to update "${type}"'s suspicion level`));
  };

  if (error) return <p className="text-xs text-error">{error}</p>;
  if (!data) return <p className="text-xs text-on-surface-variant">Loading…</p>;

  const bySource: Record<string, Array<[string, ReportType]>> = { android: [], mac: [], server: [] };
  for (const entry of Object.entries(data.types).sort(([a], [b]) => a.localeCompare(b))) {
    bySource[entry[1].source]?.push(entry);
  }
  const sourceLabels: Record<string, string> = {
    android: "Phone",
    mac: "macOS (FocusLock)",
    server: "Filter server",
  };

  return (
    <div className="space-y-4">
      {(["android", "mac", "server"] as const).map((source) => bySource[source].length > 0 && (
        <div key={source}>
          <p className="text-[11px] font-semibold uppercase tracking-wide text-on-surface-variant/70 mb-1 px-1">
            {sourceLabels[source]} ({bySource[source].length})
          </p>
          <Card className="rounded-2xl space-y-0 divide-y divide-outline-variant/30">
            {bySource[source].map(([type, info]) => (
              <ReportTypeRow
                key={type}
                type={type}
                info={info}
                onToggle={(v) => toggle(type, v)}
                onSaveMessage={(msg) => setMessage(type, msg)}
                onSetSuspicion={(level) => setSuspicion(type, level)}
              />
            ))}
          </Card>
        </div>
      ))}
    </div>
  );
}

const SUSPICION_LEVELS: Array<{ value: ReportType["suspicion"]; label: string; pill: "error" | "warning" | "default" }> = [
  { value: "high", label: "High", pill: "error" },
  { value: "medium", label: "Medium", pill: "warning" },
  { value: "low", label: "Low", pill: "default" },
];

function ReportTypeRow({
  type, info, onToggle, onSaveMessage, onSetSuspicion,
}: {
  type: string;
  info: ReportType;
  onToggle: (v: boolean) => void;
  onSaveMessage: (customMessage: string) => Promise<unknown>;
  onSetSuspicion: (level: ReportType["suspicion"]) => void;
}) {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(info.customMessage || info.description);
  const [saving, setSaving] = useState(false);

  const save = async () => {
    setSaving(true);
    try {
      await onSaveMessage(draft.trim());
      setEditing(false);
    } catch {
      // onSaveMessage's caller already surfaced the error; leave the editor open with the draft
      // intact so the guardian doesn't lose what they typed.
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="py-3 px-1">
      <div className="flex items-center justify-between gap-4">
        <div className="min-w-0">
          <p className="text-sm font-medium">{type}</p>
          <p className="text-xs text-on-surface-variant mt-0.5 leading-tight">
            {info.customMessage || info.description}
          </p>
        </div>
        <Switch checked={info.enabled} onCheckedChange={onToggle} />
      </div>
      <div className="flex items-center gap-1.5 mt-1.5">
        {SUSPICION_LEVELS.map((level) => (
          <button key={level.value} onClick={() => onSetSuspicion(level.value)}>
            <Pill variant={info.suspicion === level.value ? level.pill : "default"}>
              <span className={info.suspicion === level.value ? "font-semibold" : "opacity-50"}>
                {level.label}
              </span>
            </Pill>
          </button>
        ))}
      </div>
      {!editing ? (
        <button
          className="mt-1.5 text-[11px] text-primary hover:underline"
          onClick={() => { setDraft(info.customMessage || info.description); setEditing(true); }}
        >
          {info.customMessage ? "Edit message" : "Customize message"}
        </button>
      ) : (
        <div className="mt-2 space-y-1.5">
          <textarea
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            placeholder="Leave blank to use the default wording. Use {details} to include what actually happened."
            rows={2}
            className="w-full text-xs px-3 py-2 rounded-xl border border-outline bg-surface focus:outline-none focus:ring-2 focus:ring-primary resize-none"
          />
          <div className="flex items-center gap-2">
            <Button size="sm" className="h-7 px-3 text-xs" disabled={saving} onClick={save}>
              {saving ? "Saving…" : "Save"}
            </Button>
            <Button
              variant="outlined"
              size="sm"
              className="h-7 px-3 text-xs"
              disabled={saving}
              onClick={() => setEditing(false)}
            >
              Cancel
            </Button>
          </div>
        </div>
      )}
    </div>
  );
}
