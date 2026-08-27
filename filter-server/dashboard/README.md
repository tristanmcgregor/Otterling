# Device-settings dashboard

A guardian-facing web console for per-device Otterling settings (tamper protections, VPN
bypass apps, blocked websites, habit rules, app time budgets). Served by the `updates` Caddy
container at `https://<UPDATE_HOST>/dashboard` (HTTP Basic Auth — see `.env.example`'s
`DASHBOARD_USER`/`DASHBOARD_PASSWORD_HASH`, separate credentials from `/review`'s).

Originally generated from a Figma export
(<https://www.figma.com/design/yODRSUjtA32xeTJ8gfDQux/Complete-User-Prompt>), then rewired to real
data — every screen except the three phone-preview screens below reads/writes through
`/dashboard-api/*`, backed by `lockprofile_service.py`'s "Dashboard device settings" section.

**Not yet enforced on the phone.** Settings saved here persist server-side
(`device_settings.json` on the update host) but the Android app does not currently poll or act
on them — this dashboard is the guardian-facing config surface only. Wiring the app to actually
pull and enforce these settings is a separate, not-yet-started piece of work.

**Three screens are previews, not live controls**: Photo Capture, Friction Delay, and
Accessibility Nag are inherently on-device, kid-facing experiences (camera capture, a live
countdown before an app opens, a nag tied to the phone's own accessibility-service state) that a
browser can't perform. They stay in the sidebar under "Phone previews" with a banner making that
explicit — no fake mutations, no simulated AI photo match.

## `/dashboard-api/*` contract

Same-origin only — the browser's Basic Auth session is what gates access; Caddy injects
`Authorization: Bearer $LOCKPROFILE_TOKEN` itself on the way to `lockprofile_service.py`, so this
app's JS (see `src/lib/api.ts`) never handles a token in production.

| Route | Methods | Notes |
|---|---|---|
| `/dashboard-api/devices` | GET | List known devices (union of configured + alert-seen device ids) |
| `/dashboard-api/devices/{id}/settings` | GET, PATCH | Full settings object; PATCH merges one level deep |
| `/dashboard-api/devices/{id}/activity` | GET | `?since_id=` — this device's alerts, same shape as `/alerts/poll` |
| `/dashboard-api/devices/{id}/websites` | GET, POST | `{domain}` |
| `/dashboard-api/devices/{id}/websites/{domain}` | DELETE | |
| `/dashboard-api/devices/{id}/bypass-apps` | GET, POST | `{name}` |
| `/dashboard-api/devices/{id}/bypass-apps/{id}` | DELETE | |
| `/dashboard-api/devices/{id}/habits` | GET, POST | `{name}` |
| `/dashboard-api/devices/{id}/habits/{id}` | DELETE | |
| `/dashboard-api/devices/{id}/rules` | GET, POST | `{appName, requiredHabitIds, schedule, dailyBudgetMinutes}` |
| `/dashboard-api/devices/{id}/rules/{id}` | PATCH, DELETE | |
| `/dashboard-api/devices/{id}/app-budgets` | GET, POST | `{appName, dailyLimitMinutes}` |
| `/dashboard-api/devices/{id}/app-budgets/{id}` | PATCH, DELETE | |
| `/dashboard-api/devices/{id}/pin` | POST | `{pin}` — stored as a salted hash, never read back |

## Local dev

```bash
npm install
cp .env.local.example .env.local   # set VITE_DEV_TOKEN to a local lockprofile_service.py's LOCKPROFILE_TOKEN
# in another terminal: LOCKPROFILE_DATA_DIR=/tmp/otterling-dev LOCKPROFILE_TOKEN=devtoken python3 ../lockprofile_service.py
npm run dev
```

`vite.config.ts` proxies `/dashboard-api/*` to `http://127.0.0.1:8091` (or `VITE_DEV_API_TARGET`)
and injects `VITE_DEV_TOKEN` as the bearer header — dev-only; the production build never contains
a token, since Caddy injects the real one.

## Building & deploying

**`dist/` is committed to git** — the server needs no Node toolchain, same as every other static
asset in `filter-server/` (`review-dashboard/`, etc.). After any change under `src/`:

```bash
npm install
npm run build
git add dist
```

Then, on the update host:

```bash
cd filter-server
# DASHBOARD_PASSWORD_HASH already set in .env (see .env.example — generate with
# `docker run --rm caddy:alpine caddy hash-password --plaintext 'your-password-here'`)
docker compose up -d updates
```

The Compose mount `./dashboard/dist:/srv/dashboard:ro` means the rebuilt `dist/` is live as soon
as the container can read it — no image rebuild needed.
