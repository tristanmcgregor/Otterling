# Otterling filter server

Cloud-side half of Otterling's Canopy-style NSFW content filter: a small [AdGuard Home](https://github.com/AdguardTeam/AdGuardHome) DNS server you deploy to a VPS you control. The phone's `VpnFilterService` points its DNS queries here (see the "Cloud filter server" section in the app's Content Filter VPN settings) so adult/category filtering is enforced by a server outside the phone itself, not just by an on-device list.

This is deliberately just AdGuard Home's own category blocklists doing the filtering -- no custom ML, no image inspection. The phone still applies its own local adult-domain list first regardless of whether this server is reachable (see the app's `DomainBlocklistManager`), so a brief outage here doesn't remove filtering entirely.

## Deploy

1. Get a small VPS (1 vCPU / 512MB is plenty -- this only serves DNS).
2. Install Docker + the Docker Compose plugin on it.
3. Copy this `filter-server/` directory to the VPS.
4. `cp .env.example .env` and adjust ports only if 53/3000 conflict with something already running (e.g. `systemd-resolved` -- see below).
5. `docker compose up -d`.
6. Open `http://<vps-ip>:3000` and complete the AdGuard Home setup wizard (pick an admin username/password -- this UI controls DNS for every device pointed at it, so don't leave it on defaults).
7. In AdGuard Home's web UI, under **Filters → DNS blocklists**, enable an adult-content list (e.g. search "adult" in the list of preset blocklists, or add a custom one) and any other category lists you want enforced (gambling, etc.).
8. In the Otterling app: Settings → Content Filter VPN → **Cloud filter server** → enter the VPS's IP/hostname and port `53`, tap **Save filter server**, then **Test filter server** to confirm it's reachable, then toggle **Use cloud filter** on.

## Firewall

Restrict port 53 (and ideally 3000) to only the phone's IP if it's static, or your ISP's IP range, rather than leaving DNS open to the whole internet -- an open recursive resolver is a standing abuse target. At minimum, put the admin UI (3000) behind an IP allowlist or a reverse proxy with auth; don't expose it to the open internet.

## Notes

- If port 53 is already bound (common on Ubuntu, where `systemd-resolved` listens on it), either stop/reconfigure `systemd-resolved` or change `DNS_PORT` in `.env` and use that port when configuring the app.
- No DNS-over-HTTPS/TLS in this MVP -- the app talks plain DNS to this server. That's fine since the VPN tunnel itself is what protects the query from being read/tampered with anywhere else on the path, and the phone already refuses known public DoH/DoT resolver IPs so nothing on-device can dodge this by hardcoding its own encrypted resolver.
- Out of scope for this pass (see the top-level plan): SNI/IP-based egress filtering beyond DNS, partner remote policy, and a Knox DA license path. This server only ever makes DNS-level decisions.
