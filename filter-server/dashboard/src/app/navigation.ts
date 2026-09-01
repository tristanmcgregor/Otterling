import React from "react";
import {
  Lock, Globe, Home, Settings as SettingsIcon, ShieldCheck, Wifi, CheckCircle, Users,
} from "lucide-react";
import type { DevicePlatform } from "../lib/api";

export type Screen =
  | "Dashboard"
  | "BlockedApps"
  | "BlockedSites"
  | "ProtectedApps"
  | "ContentFilter"
  | "Settings"
  | "Habits"
  | "Accountability"
  | "GlobalSettings"
  | "Wizard";

export interface NavDef {
  id: Screen;
  label: string;
  icon: React.FC<{ className?: string }>;
}

// Mirrors the macOS app's own sidebar taxonomy (ContentView.swift's "Protect" group: Overview,
// Blocked Apps, Blocked Sites, Protected Apps, Content Filter) instead of cramming
// everything into one long Settings page -- each of these is now its own focused nav item/screen.
// Blocked Sites (Android-only, DNS/VPN-filter blocklist) and Protected Apps (macOS-only,
// filesystem-locked apps) are hidden for a platform that has no such concept, same as those
// sections used to be conditionally hidden inside the old single Settings page. `platform`
// undefined (still loading, or no device selected yet) shows the fuller Android-shaped set so the
// sidebar doesn't flicker empty before the first settings fetch resolves.
export function deviceNav(platform: DevicePlatform | undefined): NavDef[] {
  const items: NavDef[] = [
    { id: "Dashboard", label: "Overview", icon: Home },
    { id: "BlockedApps", label: "Blocked Apps", icon: Lock },
  ];
  if (platform !== "macos") items.push({ id: "BlockedSites", label: "Blocked Sites", icon: Globe });
  if (platform === "macos") items.push({ id: "ProtectedApps", label: "Protected Apps", icon: ShieldCheck });
  items.push({ id: "ContentFilter", label: "Content Filter", icon: Wifi });
  items.push({ id: "Settings", label: "Settings", icon: SettingsIcon });
  return items;
}

// Fleet-wide, not scoped to whichever device is selected in the switcher above -- Guardian PIN,
// habit rules, HabitShare account, and the dashboard/review login passwords all live here instead
// of inside per-device Settings, where they used to be mixed in despite already being global data
// (see GlobalSettingsScreen's doc comment). The habit library itself has its own top-level "Habits"
// nav entry (see HabitsScreen) since it's a thing guardians check on its own, not just a setting.
// Its own nav section so it doesn't read as "a setting of whichever device happens to be selected
// right now".
export const FLEET_NAV: NavDef[] = [
  { id: "Habits", label: "Habits", icon: CheckCircle },
  { id: "Accountability", label: "Accountability", icon: Users },
  { id: "GlobalSettings", label: "Global Settings", icon: Globe },
];
