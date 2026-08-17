Redesign a modern Android app called "Family Device Guard" (by T.B McGregor & B.W Parker). 
It's a parental-control / self-discipline app that a "guardian" configures and locks with a PIN, 
so the device's owner can add restrictions but never remove them. The redesign should feel 
trustworthy, calm, and premium — reassuring rather than punitive — while still reading as a 
serious protection tool.
PLATFORM & SYSTEM
- Android app, Material 3 (Material You) / Jetpack Compose components.
- Full light and dark themes; design dark mode as a first-class variant (many users run dark).
- Mobile portrait first (360–412dp widths). Support dynamic type and large-tap targets (min 48dp).
- Use Material 3 cards, filled/tonal/outlined buttons, top app bars, list rows, switches, 
  progress indicators, dialogs, and snackbars. Keep 4/8/16/20dp spacing rhythm.
BRAND & VISUAL DIRECTION
- Mood: dependable, focused, quietly confident. Think "financial-grade trust meets wellbeing app."
- Suggested palette (adjust for M3 tonal harmony): deep indigo/blue primary, a fresh teal or 
  green secondary for "protected / done" states, warm amber for warnings, restrained red for 
  tamper/critical. Generous neutral surfaces, soft elevation, rounded corners (16–24dp on cards).
- Typography: a clean geometric/humanist sans (e.g. Inter / Roboto Flex). Clear hierarchy: 
  large headline on the dashboard, medium section titles, small captions for statuses/timestamps.
- Iconography: simple line/duotone icons (shield, lock-clock, history, globe, check-circle, bug).
- Subtle, purposeful motion: status changes animate, countdowns tick, cards expand smoothly.
SCREENS TO DESIGN (produce all, with realistic sample content and empty/loading/error states):
1. DASHBOARD (public home — shown WITHOUT the PIN; add-only, never remove)
   - Header: app name + one-line subtitle ("Your protection and progress today").
   - "Protection status" card: shield icon, "Protected" vs "Setup required", e.g. 
     "5 of 6 tamper protections active".
   - Two primary actions side by side: "Add rule" (filled) and "Block website" (outlined).
   - "Rules overview" card with a refresh icon (top-right) and, per rule: the target app name, 
     its required habits each with a status pill ("Not done", "Done, proof pending", 
     "Done and verified"), a "Verify" button next to any habit that's done-but-pending, a 
     time-window/day-of-week line (e.g. "Mon–Fri, 00:00–21:00"), and an unlock countdown 
     ("Unlocked for 12m 30s more").
   - "Remaining app time" card: per-app daily budgets as labeled linear progress bars 
     ("42m remaining of 60 min").
   - "Debug logs" card: monospace, newest-first log lines, collapsed to a few rows with 
     "Show more" and a refresh icon.
   - Bottom: full-width "Open Settings with PIN" button.
2. PIN LOCK SCREEN
   - Clean numeric keypad, masked entry dots, brand lockup, and a lockout state 
     ("Too many wrong PINs — try again in 30s").
3. SETTINGS (PIN-gated) — a sectioned list of Material 3 cards:
   - "Protection": toggles for Block Safe Mode, Block factory reset, Block USB debugging, 
     Block guest mode, Block app uninstall — each with a short explainer line and on/off state.
   - "Blocked websites": add-only list (add domain dialog; existing entries shown; guardian-only remove).
   - "Content filter VPN": on/off, status, and a "Bypass apps" sub-list (apps allowed to skip the VPN).
   - "HabitShare": connected account row, and a list of habits each with a checkbox 
     "Require photo verification", plus a "Match strictness" selector (Lenient / Normal / Strict).
   - "Habit rules": list of rules with edit/enable, and an "Add rule" entry.
   - "App time budgets": per-app daily limits with add/edit.
   - "Proof log": recent verified habit photos as thumbnails with habit name + time, and a remove action.
4. HABIT RULE WIZARD (multi-step bottom-sheet/dialog flow)
   - Step 1: pick the app to block (searchable installed-app list with icons).
   - Step 2: condition — "If [habit] is NOT done, block this app", with the ability to require 
     MULTIPLE habits (multi-select chips).
   - Step 3: when it applies — a "blocked before X" option, a custom time window, AND day-of-week 
     selectors (not just "every day").
   - Step 4: unlock duration (minutes) for reward-style rules.
   - Clear progress indicator across steps; a clean summary/confirm at the end.
5. PHOTO PROOF CAPTURE ("Prove it") — full-screen
   - Prompt "Prove it: [habit name]", explainer text, big "Take photo" button, a photo preview, 
     and clear states: Checking (spinner + "Checking against your reference photo…"), 
     Matched (success), No match ("That doesn't look close enough — retake"). A "Not now" secondary action.
6. FRICTION / DELAY SCREEN — shown before opening a "mindful" app: a calming full-screen with a 
   countdown timer, a reflective prompt, and "Continue" (enabled after the delay) / "Close app".
7. ACCESSIBILITY-GUARD LOCK SCREEN — a full-screen, un-dismissable nag telling the user the 
   required accessibility service was turned off, with a single "Re-enable in Settings" action.
CROSS-CUTTING
- Design consistent status pills/badges (done / pending / not done / blocked / active / disabled).
- Show empty states ("No habit rules yet", "No custom websites blocked", "No logs captured yet") 
  and loading skeletons/spinners.
- Microcopy tone: plain, supportive, non-judgmental; short section descriptions under titles.
- Provide a small component/style sheet: colors (light+dark), type scale, buttons, cards, 
  pills, list rows, dialogs, and icons.
Deliver a cohesive, polished design system plus all screens above in both light and dark themes.