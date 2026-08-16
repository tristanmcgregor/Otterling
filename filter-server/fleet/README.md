# Fleet-based Mac tamper detection → existing SMS pipeline

Detection-and-alert layer for the macOS filter. It does **not** prevent an admin from removing
Otterling (nothing consumer-installable can on this T1 Intel Mac — see the repo discussion); it
makes a removal **loud to the accountability partner**, over the SMS channel the Android app
already runs, fast enough that killing the Mac afterward can't unsend it.

No Cloudflare, no Telegram: everything runs on `vpn.bartholomew.help` and feeds
`lockprofile_service.py`'s existing `/alerts/tamper` → JSONL + ntfy + the phone's `/alerts/poll` →
`GuardianSmsSender` relay.

## Two signals (each covers the other's blind spot)

| Signal | Fires when | Path |
|---|---|---|
| **Failing-policy webhook** | app deleted OR daemon stopped, *while the Mac is online + fleetd alive* | Fleet → `POST /alerts/fleet-webhook?token=…` (new route in `lockprofile_service.py`) |
| **Dead-man's switch** | Mac stops checking in for N hours (network cut / fleetd killed / powered off) | `deadman.py` container → `POST /alerts/tamper` |

Both emit event types the SMS/ntfy pipeline already understands: `mac_tamper_policy`, `mac_silent`,
`mac_back` (see `NTFY_EVENT_STYLE` in `lockprofile_service.py`).

```
 ┌───────────┐  policy (installed+running)  ┌─────────┐  failing-policy webhook
 │ The Mac   │ ───────────────────────────► │  Fleet  │ ───────────────────────────┐
 │ fleetd    │  seen_time                   │ (:8412) │                             ▼
 └───────────┘ ◄─────────────┐              └─────────┘        lockprofile_service.py :8091
                             │  poll (http://fleet:8080)      /alerts/fleet-webhook?token=… ─┐
                    ┌────────────────┐                        /alerts/tamper  ◄──────────────┘
                    │  deadman.py    │ ─── mac_silent ───────►     │
                    │ (container)    │                             ▼  events.jsonl + ntfy
                    └────────────────┘              phone /alerts/poll ──► SMS to partner
```

## Components

| File | Role |
|---|---|
| `docker-compose.yml` | Fleet + MySQL + Redis + the `deadman` poller. Loopback-only Fleet on `127.0.0.1:8412`. |
| `deadman.py` | Dead-man's switch (stdlib only). |
| `otterling-tamper-policy.yml` | The osquery policy (installed **and** `FocusLockHelperd` running). Already applied. |
| `caddy-fleet.snippet` | The `fleet.bartholomew.help` vhost appended to the main Caddyfile. |
| `../lockprofile_service.py` | Gains `POST /alerts/fleet-webhook` (query-secret auth, since Fleet sends no Bearer header). |

## Deployed state (on `vpn.bartholomew.help`, `/opt/otterling-fleet/`)

- Fleet at `https://fleet.bartholomew.help` (Caddy TLS), admin + enroll secret configured.
- Tamper policy applied; the Mac (`Tristans-MacBook-Pro.local`) enrolled.
- `.env` (0600) holds: `MYSQL_ROOT_PASSWORD`, `FLEET_MYSQL_PASSWORD`, `FLEET_SERVER_PRIVATE_KEY`,
  and — for the deadman — `FLEET_API_EMAIL`, `FLEET_API_PASSWORD`, `FLEET_HOST_IDENTIFIER`,
  `LOCKPROFILE_TOKEN`, `DEADMAN_THRESHOLD_MINUTES`.

## Wiring the webhook

Set the failing-policies webhook (Fleet UI → Settings → Integrations → Automations, or the API) to:

```
https://vpn.bartholomew.help/alerts/fleet-webhook?token=<FLEET_WEBHOOK_SECRET>
```

scoped to the tamper policy's id. `FLEET_WEBHOOK_SECRET` is set on the `otterling-lockprofile`
container's environment (it gates that one route; empty ⇒ the route returns 403).

## Test end to end

- **Policy path:** on the Mac, `sudo launchctl bootout system/app.otterling.helperd` (watchdog
  restores it in ~20s; lower the policy interval or force a run in between). Partner gets an SMS.
- **Dead-man path:** in `.env` set `DEADMAN_THRESHOLD_MINUTES=1`, `docker compose up -d deadman`,
  take the Mac offline, wait a cycle → `mac_silent` SMS; bring it back → `mac_back`. Restore the
  real threshold.

## Honest limits

- **Alerts, doesn't prevent.** A determined admin removes Otterling regardless; they just can't do
  it quietly (webhook) or by killing the Mac (dead-man's switch). Same ceiling as Canopy on macOS.
- **The partner is load-bearing.** SMS must reach someone who will actually ask about it.
- **fleetd is removable** — but that's *silence*, which the dead-man's switch catches. Layer, not lock.
- **Threshold is a real trade** — too short pages the partner on ordinary offline nights, too long
  widens the quiet-bypass gap. Tune `DEADMAN_THRESHOLD_MINUTES` to how the Mac is actually used.
