import { useEffect, useState } from "react";
import { Lock, X } from "lucide-react";
import { Card, Button, Pill } from "../components/ui";
import { api, ApiError } from "../../lib/api";
import type { DefaultSettings, Protections } from "../../lib/api";
import { SectionLabel, SettingsRow } from "../components/shared";

// ─── Global Settings ───────────────────────────────────────────────────────────
// Fleet-wide config, not scoped to whichever device happens to be selected in the sidebar's
// device switcher -- Guardian PIN was already global data (one PIN shared across every device)
// but used to live inside per-device SettingsScreen, which read as "a setting of this device"
// when it wasn't. HabitShare account is new here. The habit library and habit rules live in their
// own top-level Habits screen (see HabitsScreen) rather than here.
export function GlobalSettingsScreen() {
  const [pinModalOpen, setPinModalOpen] = useState(false);
  const [pinStatus, setPinStatus] = useState<{ configured: boolean; updatedAt: number | null } | null>(null);
  const reloadPinStatus = () => api.getPin().then(setPinStatus).catch(() => setPinStatus(null));
  useEffect(() => { reloadPinStatus(); }, []);

  return (
    <div className="p-7 max-w-[900px] space-y-6">
      <div>
        <h1 className="text-xl font-bold">Global Settings</h1>
        <p className="text-sm text-on-surface-variant mt-0.5">
          Shared across every device on this account — not specific to whichever device is
          selected in the sidebar.
        </p>
      </div>

      <div className="space-y-3">
        <SectionLabel>Guardian PIN</SectionLabel>
        <Card className="rounded-2xl">
          <div className="flex items-center justify-between">
            <div>
              <div className="flex items-center gap-2">
                <p className="text-sm font-medium">Guardian PIN</p>
                <Pill variant={pinStatus?.configured ? "success" : "default"}>
                  {pinStatus?.configured ? "Set" : "Not set"}
                </Pill>
              </div>
              <p className="text-xs text-on-surface-variant">
                Shared across every Otterling device on this account — gates Settings on the phone,
                and also signs into this website and into /review (AI review history, device
                diagnostic logs). Changing it signs out every other /review session.
              </p>
            </div>
            <Button variant="outlined" size="sm" onClick={() => setPinModalOpen(true)}>Change PIN</Button>
          </div>
        </Card>
      </div>

      <div className="space-y-3">
        <SectionLabel>HabitShare Account</SectionLabel>
        <Card className="rounded-2xl">
          <p className="text-xs text-on-surface-variant mb-2">
            The HabitShare login every phone on this account uses to poll HabitShare's own
            servers directly for done/not-done status. Unlike the Guardian PIN, this doesn't gate
            anything Otterling enforces — it's a separate third-party account.
          </p>
          <HabitShareAccountCard />
        </Card>
      </div>

      <div className="space-y-3">
        <SectionLabel>Account Handoff</SectionLabel>
        <Card className="rounded-2xl">
          <HandoffLinkCard />
        </Card>
      </div>

      <div className="space-y-3">
        <SectionLabel>Default Protections for New Devices</SectionLabel>
        <p className="text-xs text-on-surface-variant -mt-1">
          What a brand-new device gets on its very first check-in, before you've touched its own
          Settings screen. Changing this only affects devices that haven't checked in yet — an
          already-configured device is untouched, same as changing this project's own hardcoded
          defaults used to require editing code for.
        </p>
        <DefaultSettingsPanel />
      </div>

      {pinModalOpen && (
        <div className="fixed inset-0 z-50 bg-black/70 backdrop-blur-md flex items-center justify-center">
          <SetPinModal
            onClose={() => setPinModalOpen(false)}
            onSave={async (pin) => {
              await api.setPin(pin);
              setPinModalOpen(false);
              reloadPinStatus();
            }}
          />
        </div>
      )}
    </div>
  );
}

function DefaultSettingsPanel() {
  const [data, setData] = useState<DefaultSettings | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [secondsDraft, setSecondsDraft] = useState("");

  useEffect(() => {
    api.getDefaultSettings()
      .then((d) => { setData(d); setSecondsDraft(String(d.frictionDelay.seconds)); })
      .catch(() => setError("Failed to load default settings"));
  }, []);

  const patchProtection = (key: keyof Protections, value: boolean) =>
    api.setDefaultSettings({ protections: { ...data!.protections, [key]: value } })
      .then(setData)
      .catch(() => setError(`Failed to update "${key}"`));

  if (error) return <p className="text-xs text-error">{error}</p>;
  if (!data) return <p className="text-xs text-on-surface-variant">Loading…</p>;

  return (
    <div className="space-y-3">
      <Card className="rounded-2xl space-y-0 divide-y divide-outline-variant/30">
        <SettingsRow
          title="Block Safe Mode bypass"
          sub="Prevent circumventing rules via reboot"
          checked={data.protections.safeMode}
          onChange={(v) => patchProtection("safeMode", v)}
        />
        <SettingsRow
          title="Block factory reset"
          sub="Require PIN to wipe device"
          checked={data.protections.factoryReset}
          onChange={(v) => patchProtection("factoryReset", v)}
        />
        <SettingsRow
          title="Block app uninstall"
          sub="Require PIN to remove Otterling"
          checked={data.protections.uninstallBlock}
          onChange={(v) => patchProtection("uninstallBlock", v)}
          danger
        />
        <SettingsRow
          title="Block guest mode"
          sub="Prevent switching to an unmanaged profile"
          checked={data.protections.guestMode}
          onChange={(v) => patchProtection("guestMode", v)}
        />
        <SettingsRow
          title="Block USB debugging"
          sub="Prevent ADB-based tampering"
          checked={data.protections.usbDebugging}
          onChange={(v) => patchProtection("usbDebugging", v)}
        />
        <SettingsRow
          title="Content filter (VPN)"
          sub="DNS/proxy filtering active from first check-in"
          checked={data.vpnFilter.enabled}
          onChange={(v) => api.setDefaultSettings({ vpnFilter: { enabled: v } }).then(setData).catch(() => setError("Failed to update content filter"))}
        />
        <SettingsRow
          title="Friction delay"
          sub="Short delay before an unapproved app opens"
          checked={data.frictionDelay.enabled}
          onChange={(v) =>
            api.setDefaultSettings({ frictionDelay: { ...data.frictionDelay, enabled: v } })
              .then(setData)
              .catch(() => setError("Failed to update friction delay"))
          }
        />
      </Card>
      {data.frictionDelay.enabled && (
        <Card className="rounded-2xl">
          <div className="flex items-center justify-between gap-3">
            <p className="text-sm font-medium">Friction delay length</p>
            <div className="flex items-center gap-2">
              <input
                type="number"
                min={1}
                value={secondsDraft}
                onChange={(e) => setSecondsDraft(e.target.value)}
                onBlur={() =>
                  api.setDefaultSettings({ frictionDelay: { ...data.frictionDelay, seconds: Number(secondsDraft) || 30 } })
                    .then(setData)
                    .catch(() => setError("Failed to update friction delay"))
                }
                className="w-20 h-9 px-3 rounded-xl border border-outline bg-surface text-sm focus:outline-none focus:ring-2 focus:ring-primary"
              />
              <span className="text-xs text-on-surface-variant">seconds</span>
            </div>
          </div>
        </Card>
      )}
    </div>
  );
}

function HabitShareAccountCard() {
  const [account, setAccount] = useState<{ username: string | null; password: string | null } | null>(null);
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState("");

  const reload = () => api.getHabitShareAccount().then(setAccount).catch(() => setAccount(null));
  useEffect(() => { reload(); }, []);

  const connect = async () => {
    if (!username.trim() || !password) return;
    setBusy(true);
    setStatus("");
    try {
      await api.setHabitShareAccount(username.trim(), password);
      setPassword("");
      setStatus("Connected.");
      reload();
    } catch (err) {
      setStatus(err instanceof ApiError ? err.message : "Couldn't reach the server");
    } finally {
      setBusy(false);
    }
  };

  const disconnect = async () => {
    setBusy(true);
    try {
      await api.removeHabitShareAccount();
      setStatus("Disconnected.");
      reload();
    } finally {
      setBusy(false);
    }
  };

  if (account?.username) {
    return (
      <div className="flex items-center justify-between">
        <div>
          <div className="flex items-center gap-2">
            <p className="text-sm font-medium">{account.username}</p>
            <Pill variant="success">Connected</Pill>
          </div>
          {status && <p className="text-xs text-on-surface-variant mt-0.5">{status}</p>}
        </div>
        <Button variant="outlined" size="sm" disabled={busy} onClick={disconnect}>Disconnect</Button>
      </div>
    );
  }

  return (
    <div className="space-y-2">
      <div className="flex items-center gap-2">
        <input
          value={username}
          onChange={(e) => setUsername(e.target.value)}
          placeholder="HabitShare username or email"
          className="flex-1 h-9 px-3 rounded-xl border border-outline bg-surface text-sm focus:outline-none focus:ring-2 focus:ring-primary"
        />
        <input
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          type="password"
          placeholder="Password"
          autoComplete="new-password"
          className="flex-1 h-9 px-3 rounded-xl border border-outline bg-surface text-sm focus:outline-none focus:ring-2 focus:ring-primary"
        />
        <Button variant="tonal" size="sm" disabled={busy || !username.trim() || !password} onClick={connect}>
          Connect
        </Button>
      </div>
      {status && <p className="text-xs text-on-surface-variant">{status}</p>}
    </div>
  );
}

// One-time account-handoff link (see lockprofile_service.py's HANDOFF_TOKEN_PATH comment) --
// generates a single-use, expiring link that lets whoever holds it set a BRAND NEW Guardian PIN
// without needing to know the current one. Meant for the one-time moment you're done setting
// this up and ready to hand the account to your guardian -- not an ongoing reset mechanism.
// Generating a new link invalidates whatever was generated before.
function HandoffLinkCard() {
  const [pending, setPending] = useState<{ pending: boolean; expiresAt: number | null } | null>(null);
  const [freshLink, setFreshLink] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState("");
  const [copied, setCopied] = useState(false);

  const reload = () => api.getHandoffLinkStatus().then(setPending).catch(() => setPending(null));
  useEffect(() => { reload(); }, []);

  const generate = async () => {
    setBusy(true);
    setStatus("");
    setCopied(false);
    try {
      const result = await api.generateHandoffLink();
      setFreshLink(`${window.location.origin}/handoff/?token=${result.token}`);
      setPending({ pending: true, expiresAt: result.expiresAt });
    } catch (err) {
      setStatus(err instanceof ApiError ? err.message : "Couldn't reach the server");
    } finally {
      setBusy(false);
    }
  };

  const cancel = async () => {
    setBusy(true);
    try {
      await api.cancelHandoffLink();
      setFreshLink(null);
      setPending({ pending: false, expiresAt: null });
    } catch (err) {
      setStatus(err instanceof ApiError ? err.message : "Couldn't reach the server");
    } finally {
      setBusy(false);
    }
  };

  const copy = () => {
    if (!freshLink) return;
    navigator.clipboard?.writeText(freshLink).then(() => {
      setCopied(true);
      window.setTimeout(() => setCopied(false), 3000);
    });
  };

  return (
    <div className="space-y-2">
      <div>
        <p className="text-sm font-medium">Account handoff link</p>
        <p className="text-xs text-on-surface-variant">
          A one-time link for when you're done setting this up -- send it to your guardian so
          they can set their own Guardian PIN. Works once, expires in 48 hours, and doesn't
          require knowing the current PIN. Generating a new link cancels any unused one.
        </p>
      </div>
      {freshLink ? (
        <div className="space-y-1.5">
          <div className="flex items-center gap-2">
            <input
              readOnly
              value={freshLink}
              onFocus={(e) => e.target.select()}
              className="flex-1 h-9 px-3 rounded-xl border border-outline bg-surface text-xs font-mono focus:outline-none focus:ring-2 focus:ring-primary"
            />
            <Button variant="tonal" size="sm" onClick={copy}>{copied ? "Copied" : "Copy"}</Button>
          </div>
          <p className="text-[11px] text-on-surface-variant">
            This is shown only once -- copy it now before leaving this page.
          </p>
        </div>
      ) : (
        <div className="flex items-center gap-2">
          <Button variant="tonal" size="sm" disabled={busy} onClick={generate}>
            {pending?.pending ? "Generate new link" : "Generate handoff link"}
          </Button>
          {pending?.pending && (
            <Button variant="outlined" size="sm" disabled={busy} onClick={cancel}>
              Cancel pending link
            </Button>
          )}
        </div>
      )}
      {pending?.pending && pending.expiresAt && !freshLink && (
        <p className="text-xs text-on-surface-variant">
          A link is pending, expires {new Date(pending.expiresAt * 1000).toLocaleString()}. It
          was only shown once when generated -- this is just a status check.
        </p>
      )}
      {status && <p className="text-xs text-error">{status}</p>}
    </div>
  );
}

function SetPinModal({ onClose, onSave }: { onClose: () => void; onSave: (pin: string) => Promise<void> }) {
  const [pin, setPin] = useState("");
  const [confirm, setConfirm] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const submit = async () => {
    if (pin.length !== 4) { setError("PIN must be 4 digits"); return; }
    if (pin !== confirm) { setError("PINs don't match"); return; }
    setSaving(true);
    setError(null);
    try {
      await onSave(pin);
    } catch {
      setError("Couldn't save — try again");
    } finally {
      setSaving(false);
    }
  };

  return (
    <Card className="w-[380px] rounded-3xl shadow-2xl p-8 border border-outline-variant/40">
      <div className="flex items-center justify-between mb-6">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 bg-primary-container rounded-full flex items-center justify-center">
            <Lock className="w-5 h-5 text-primary" />
          </div>
          <div>
            <h2 className="font-bold text-base leading-tight">Change Guardian PIN</h2>
            <p className="text-xs text-on-surface-variant">Applies to every device on this account</p>
          </div>
        </div>
        <button
          onClick={onClose}
          className="w-8 h-8 rounded-lg hover:bg-surface-variant flex items-center justify-center text-on-surface-variant transition-colors"
        >
          <X className="w-4 h-4" />
        </button>
      </div>
      <div className="space-y-3">
        <input
          type="password"
          inputMode="numeric"
          maxLength={4}
          value={pin}
          onChange={(e) => setPin(e.target.value.replace(/\D/g, ""))}
          placeholder="New 4-digit PIN"
          className="w-full h-11 px-4 rounded-xl border border-outline bg-surface text-sm tracking-[0.5em] focus:outline-none focus:ring-2 focus:ring-primary"
        />
        <input
          type="password"
          inputMode="numeric"
          maxLength={4}
          value={confirm}
          onChange={(e) => setConfirm(e.target.value.replace(/\D/g, ""))}
          placeholder="Confirm PIN"
          className="w-full h-11 px-4 rounded-xl border border-outline bg-surface text-sm tracking-[0.5em] focus:outline-none focus:ring-2 focus:ring-primary"
        />
        {error && <p className="text-error text-sm font-medium">{error}</p>}
        <Button className="w-full" disabled={saving} onClick={submit}>
          {saving ? "Saving…" : "Save PIN"}
        </Button>
      </div>
    </Card>
  );
}
