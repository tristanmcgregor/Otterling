import type { DeviceSummary, Rule, RuleSchedule } from "../../lib/api";

const DAY_LABELS = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];

export function describeSchedule(schedule: RuleSchedule): string {
  if (!schedule || (!schedule.startTime && !schedule.daysOfWeek)) return "Always";
  const days = schedule.daysOfWeek && schedule.daysOfWeek.length > 0 && schedule.daysOfWeek.length < 7
    ? schedule.daysOfWeek.slice().sort().map((d) => DAY_LABELS[d]).join(", ")
    : "Every day";
  const window = schedule.startTime && schedule.endTime ? `${schedule.startTime}–${schedule.endTime}` : "";
  return [days, window].filter(Boolean).join(", ");
}

// A rule can gate an app AND a website at once now (see api.ts's Rule doc comment) -- this is the
// one place that turns targetApps/targetWebsites into a single display label, reused everywhere a
// rule is rendered (DashboardScreen's read-only summary, GlobalSettingsScreen's editable list).
export function ruleTargetLabel(rule: Rule): string {
  const names = [...rule.targetApps.map((a) => a.appName), ...rule.targetWebsites.map((w) => w.domain)];
  return names.length > 0 ? names.join(", ") : "(no target)";
}

export function ruleDeviceLabel(rule: Rule, devices: DeviceSummary[]): string {
  if (rule.deviceIds.includes("all")) return "All devices";
  const names = rule.deviceIds.map((id) => devices.find((d) => d.device_id === id)?.device_name || id);
  return names.length > 0 ? names.join(", ") : "No devices";
}
