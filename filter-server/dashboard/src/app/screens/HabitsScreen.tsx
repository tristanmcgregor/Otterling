import { useState } from "react";
import { Clock, Laptop, Plus, Trash2, X } from "lucide-react";
import { Card, Button, Pill } from "../components/ui";
import { api, ApiError } from "../../lib/api";
import type { DeviceSummary, Habit, Rule } from "../../lib/api";
import { AppIcon, SectionLabel } from "../components/shared";
import { describeSchedule, ruleDeviceLabel, ruleTargetLabel } from "../utils/rules";

// ─── Habits ────────────────────────────────────────────────────────────────────
// Fleet-wide (see api.ts's Habit doc comment), so this is its own top-level nav entry rather than
// nested inside per-device Settings or Global Settings -- guardians check "what habits exist,
// which are done today, which rules gate on them" often enough that it earns its own page instead
// of being one section among many on Global Settings.
export function HabitsScreen({
  habits, onHabitsChanged, rules, devices, onRulesChanged, onAddRule, onEditRule,
}: {
  habits: Habit[];
  onHabitsChanged: () => void;
  rules: Rule[];
  devices: DeviceSummary[];
  onRulesChanged: () => void;
  onAddRule: () => void;
  onEditRule: (rule: Rule) => void;
}) {
  return (
    <div className="p-7 max-w-[900px] space-y-6">
      <div>
        <h1 className="text-xl font-bold">Habits</h1>
        <p className="text-sm text-on-surface-variant mt-0.5">
          Which habits exist, which are done today, and which rules gate on them --
          shared across every device on this account.
        </p>
      </div>

      <div className="space-y-3">
        <SectionLabel>Habit Library</SectionLabel>
        <Card className="rounded-2xl">
          <p className="text-xs text-on-surface-variant mb-2">
            Shared across every device on this account — not per-device. A habit checked off
            (and verified) on the phone can satisfy a rule on the Mac. Turn on "Requires proof"
            for a habit and the server will reject a completion report with no photo attached —
            without it, the device's own app-embedded token alone is enough to fake any habit
            done and unlock whatever it gates, fleet-wide.
          </p>
          <HabitLibraryList habits={habits} rules={rules} onChanged={onHabitsChanged} />
        </Card>
      </div>

      <div className="space-y-3">
        <div className="flex items-center justify-between">
          <SectionLabel>Habit Rules</SectionLabel>
          <Button size="sm" className="gap-1.5" onClick={onAddRule}>
            <Plus className="w-3.5 h-3.5" /> Add Rule
          </Button>
        </div>
        <p className="text-xs text-on-surface-variant -mt-1">
          Gates an app and/or a website behind required habits, on whichever device(s) you choose
          below — a single rule can target both an app and a website at once, and apply to one
          device, several, or "All devices".
        </p>
        <GlobalRulesList rules={rules} habits={habits} devices={devices} onChanged={onRulesChanged} onEdit={onEditRule} onAdd={onAddRule} />
      </div>
    </div>
  );
}

// The fleet-wide rule library's editable list -- see api.ts's Rule doc comment for why a rule now
// carries its own targetApps/targetWebsites (either/both) and deviceIds instead of living inside
// one device's settings. This is the only place rules can be created/edited/deleted now; the
// per-device Dashboard just shows a read-only summary (see ActiveRulesSummary) and links here.
function GlobalRulesList({
  rules, habits, devices, onChanged, onEdit, onAdd,
}: {
  rules: Rule[];
  habits: Habit[];
  devices: DeviceSummary[];
  onChanged: () => void;
  onEdit: (rule: Rule) => void;
  onAdd: () => void;
}) {
  const [busyRuleId, setBusyRuleId] = useState<string | null>(null);

  const removeRule = async (id: string) => {
    setBusyRuleId(id);
    try {
      await api.removeRule(id);
      onChanged();
    } finally {
      setBusyRuleId(null);
    }
  };

  if (rules.length === 0) {
    return (
      <Card className="rounded-2xl text-center py-8">
        <p className="text-sm text-on-surface-variant">No habit rules yet.</p>
        <Button size="sm" className="mt-3 gap-1.5" onClick={onAdd}>
          <Plus className="w-3.5 h-3.5" /> Add your first rule
        </Button>
      </Card>
    );
  }

  return (
    <div className="space-y-2.5">
      {rules.map((rule) => (
        <Card key={rule.id} className="rounded-2xl space-y-3">
          <div className="flex items-center justify-between gap-3">
            <div className="flex items-center gap-3 min-w-0">
              <AppIcon name={ruleTargetLabel(rule).slice(0, 2).toUpperCase()} color="bg-primary/10 text-primary" />
              <div className="min-w-0">
                <div className="flex items-center gap-1.5 flex-wrap">
                  <p className="font-semibold leading-tight truncate">{ruleTargetLabel(rule)}</p>
                  {rule.targetApps.length > 0 && <Pill>{rule.targetApps.length} app{rule.targetApps.length === 1 ? "" : "s"}</Pill>}
                  {rule.targetWebsites.length > 0 && <Pill>{rule.targetWebsites.length} website{rule.targetWebsites.length === 1 ? "" : "s"}</Pill>}
                </div>
                <p className="text-xs text-on-surface-variant">{describeSchedule(rule.schedule)}</p>
                <p className="text-xs text-on-surface-variant flex items-center gap-1 mt-0.5">
                  <Laptop className="w-3 h-3 shrink-0" /> {ruleDeviceLabel(rule, devices)}
                </p>
              </div>
            </div>
            <div className="flex items-center gap-1.5 shrink-0">
              <Button variant="text" size="sm" className="h-7 px-2 text-xs" onClick={() => onEdit(rule)}>
                Edit
              </Button>
              <button
                onClick={() => removeRule(rule.id)}
                disabled={busyRuleId === rule.id}
                className="w-7 h-7 rounded-lg flex items-center justify-center text-on-surface-variant hover:bg-error-container hover:text-error transition-colors disabled:opacity-50"
              >
                <Trash2 className="w-3.5 h-3.5" />
              </button>
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
    </div>
  );
}

function HabitLibraryList({ habits, rules, onChanged }: { habits: Habit[]; rules: Rule[]; onChanged: () => void }) {
  const [draft, setDraft] = useState("");
  const [draftRequiresProof, setDraftRequiresProof] = useState(false);
  const [busy, setBusy] = useState(false);
  const [viewingProofId, setViewingProofId] = useState<string | null>(null);
  const [importing, setImporting] = useState(false);
  const [importMessage, setImportMessage] = useState<string | null>(null);

  const submit = async () => {
    const name = draft.trim();
    if (!name) return;
    setBusy(true);
    try {
      await api.addHabit(name, draftRequiresProof);
      setDraft("");
      setDraftRequiresProof(false);
      onChanged();
    } finally {
      setBusy(false);
    }
  };

  // Pulls habit names from the connected HabitShare account (see HabitShareAccountCard above)
  // and creates a matching library entry for any not already here by name -- see
  // HabitCompletionReporter.kt's doc comment for why a name match is what actually makes
  // completion reporting work; this just saves retyping each one.
  const importFromHabitShare = async () => {
    setImporting(true);
    setImportMessage(null);
    try {
      const result = await api.importHabitsFromHabitShare();
      setImportMessage(
        result.imported > 0
          ? `Imported ${result.imported} new habit${result.imported === 1 ? "" : "s"}.`
          : "No new habits found -- everything in HabitShare is already in this list.",
      );
      onChanged();
    } catch (error) {
      setImportMessage(error instanceof ApiError ? error.message : "Import failed -- check the connected HabitShare account.");
    } finally {
      setImporting(false);
    }
  };

  return (
    <div className="space-y-2">
      <div className="flex items-center gap-2">
        <Button variant="outlined" size="sm" className="h-8 px-3 text-xs" disabled={importing} onClick={importFromHabitShare}>
          {importing ? "Importing…" : "Import from HabitShare"}
        </Button>
        {importMessage && <span className="text-xs text-on-surface-variant">{importMessage}</span>}
      </div>
      {habits.length > 0 && (
        <div className="space-y-1.5">
          {habits.map((h) => {
            const usedInRules = rules.filter((r) => r.requiredHabitIds.includes(h.id));
            return (
              <div
                key={h.id}
                className="flex flex-col gap-0.5 pl-3 pr-1.5 py-1.5 rounded-xl bg-surface-variant text-sm"
              >
                <div className="flex items-center gap-2">
                  <span className="flex-1 min-w-0 truncate">
                    {h.name}
                    {h.doneToday && <span className="text-secondary ml-1">✓</span>}
                  </span>
                  {h.doneToday && h.hasProof && (
                    <button
                      onClick={() => setViewingProofId(h.id)}
                      className="text-[11px] font-medium text-primary hover:underline shrink-0"
                    >
                      View proof
                    </button>
                  )}
                  {h.doneToday && (
                    <button
                      onClick={() => api.revokeHabitCompletion(h.id).then(onChanged)}
                      title="Revoke today's completion"
                      className="text-[11px] font-medium text-error hover:underline shrink-0"
                    >
                      Revoke
                    </button>
                  )}
                  <label className="flex items-center gap-1 text-[11px] text-on-surface-variant shrink-0">
                    <input
                      type="checkbox"
                      checked={h.requiresProof}
                      onChange={(e) => api.setHabitRequiresProof(h.id, e.target.checked).then(onChanged)}
                    />
                    Requires proof
                  </label>
                  <button
                    onClick={() => api.removeHabit(h.id).then(onChanged)}
                    className="w-5 h-5 rounded-full flex items-center justify-center hover:bg-error-container hover:text-error transition-colors shrink-0"
                  >
                    <X className="w-3 h-3" />
                  </button>
                </div>
                <p className="text-[11px] text-on-surface-variant truncate">
                  {usedInRules.length > 0
                    ? `Used in: ${usedInRules.map((r) => ruleTargetLabel(r)).join(", ")}`
                    : "Not used in any rule"}
                </p>
              </div>
            );
          })}
        </div>
      )}
      <div className="flex items-center gap-2">
        <input
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={(e) => { if (e.key === "Enter") submit(); }}
          placeholder="New habit…"
          className="flex-1 h-9 px-3 rounded-xl border border-outline bg-surface text-sm focus:outline-none focus:ring-2 focus:ring-primary"
        />
        <label className="flex items-center gap-1 text-[11px] text-on-surface-variant whitespace-nowrap">
          <input
            type="checkbox"
            checked={draftRequiresProof}
            onChange={(e) => setDraftRequiresProof(e.target.checked)}
          />
          Requires proof
        </label>
        <Button variant="tonal" size="sm" className="h-9 px-3" disabled={busy || !draft.trim()} onClick={submit}>
          <Plus className="w-3.5 h-3.5" />
        </Button>
      </div>
      {viewingProofId && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-8"
          onClick={() => setViewingProofId(null)}
        >
          <img
            src={api.habitProofUrl(viewingProofId)}
            alt="Habit completion proof"
            className="max-w-full max-h-full rounded-2xl shadow-xl"
          />
        </div>
      )}
    </div>
  );
}
