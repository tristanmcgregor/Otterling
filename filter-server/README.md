# Otterling filter server

Cloud-side half of Otterling's NSFW content filter, deployed to a home server/VPS you control. Two
services:

1. **[mitmproxy](https://mitmproxy.org/)** (`otterling-mitmproxy`, TCP `8080`) -- a real HTTPS
   MITM proxy. The phone's `VpnFilterService`/`TcpRelayManager` sends every captured TCP 80/443
   flow here via HTTP `CONNECT`, authenticated with a fixed username/password. `mitm_nsfw_addon.py`
   decides, server-side, whether to let a request through or return a block page -- **whole
   requests/pages are blocked, not scrubbed in-page** (no Canopy-style image blanking).
2. **[AdGuard Home](https://github.com/AdguardTeam/AdGuardHome)** (`otterling-filter-server`, port
   `53`) -- plain DNS, kept as a fallback/failsafe layer and for anything that isn't proxied
   traffic.
3. **[Caddy](https://caddyserver.com/)** (`otterling-updates`, ports `80`/`443`) -- serves
   `updates/manifest.json` + signed release APKs over HTTPS with an auto-provisioned Let's Encrypt
   cert. This is the *only* place the Otterling app will install an update from -- see "Gated app
   updates" below.

The phone still applies its own local adult-domain list first, client-side, regardless of whether
either server is reachable (see the app's `DomainBlocklistManager`) -- a brief outage here doesn't
remove filtering entirely, it just loses the server-side category/page-content layer.

## How blocking decisions are made

- `mitm_nsfw_addon.py` blocks a request outright if its Host matches the downloaded adult-domain
  list (same two sources as the Android app), or if the URL path/query matches a short list of
  unambiguous adult-content path tokens (e.g. `/r/nsfw`, `xxx`).
- For anything that gets through, it also inspects HTML responses' `<title>`/`og:description` for
  a short list of high-confidence porn keywords and blocks the whole response if matched.
- Once a host is blocked for any reason, it goes on an in-memory 24h deny list so repeat requests
  to that host fail closed for the rest of the day without re-fetching or re-classifying it.
- No custom ML, no image inspection -- keyword/domain rules only, deliberately narrow to keep
  false positives low.

## Deploy

1. Get a small home server or VPS (mitmproxy is more CPU-hungry than plain DNS since it's doing
   real TLS termination on every proxied connection, but a single low-power box is still plenty
   for a household's worth of traffic).
2. Install Docker + the Docker Compose plugin on it.
3. Copy this `filter-server/` directory to the server.
4. `cp .env.example .env`, set `PROXY_PASSWORD` to something long and random (required -- mitmproxy
   won't start without it), and adjust ports only if they conflict with something already running
   (e.g. `systemd-resolved` on 53 -- see below).
5. `docker compose up -d`.
6. Extract the freshly-generated proxy CA certificate and copy it into the Android app -- see
   [`ca/README.md`](ca/README.md) for the exact steps. **The app will not work as a proxy filter
   until you do this**; it ships with a throwaway placeholder CA that doesn't match any real
   mitmproxy instance.
7. Open `http://<server-ip>:3000` and complete the AdGuard Home setup wizard (pick an admin
   username/password -- this UI controls DNS for every device pointed at it, so don't leave it on
   defaults).
8. In AdGuard Home's web UI, under **Filters → DNS blocklists**, enable an adult-content list and
   any other category lists you want as a DNS-level backstop.
9. In the Otterling app: Settings → Content Filter VPN → enter `vpn.bartholomew.help`, port `53`
   for DNS, port `8080` + your `PROXY_USER`/`PROXY_PASSWORD` for the proxy, tap **Save**, then
   **Test filter server** / **Test proxy** to confirm both are reachable, then toggle **Use cloud
   filter** (and, under it, **Use filter proxy**) on.

### Smoke-testing the proxy directly

Before wiring up the phone, confirm the proxy itself works from any machine:

```bash
curl -x http://PROXY_USER:PROXY_PASSWORD@vpn.bartholomew.help:8080 https://example.com
```

A successful fetch means CONNECT + auth + TLS interception are all working. If it hangs or
errors, check `docker compose logs mitmproxy` before troubleshooting the phone side.

## Gated app updates

`updates/` publish output is **not** the git `filter-server/updates/` folder. On this host it is
`/var/lib/otterling/updates/`, writable only by the `otterling-deploy` SFTP user from CI after AI
`VERDICT: PASS` (see [`SELF_LOCKOUT.md`](SELF_LOCKOUT.md)). Caddy serves that path at
`https://<UPDATE_HOST>/updates/`. The Otterling app's
`ApprovedUpdateManager` (Settings → App updates → "Check for update") is the only thing on the
phone that ever installs anything, and it verifies the downloaded APK's SHA-256 *and* its signing
certificate against a fingerprint baked into the app at build time before installing -- see
`scripts/update_review_checklist.md`'s section 1 and the top-level README's "Gated updates"
section for the full trust chain. Nothing about this repo's own CI setup is committed here; see
that workflow file for the GitHub secrets it expects (`UPDATE_HOST`,
`UPDATE_HOST_SSH_USER=otterling-deploy`, `UPDATE_HOST_SSH_KEY`, plus the release signing secrets).

On the server, this just needs an SSH user CI can `scp` to that has write access to
`filter-server/updates/` -- nothing else on this stack needs to trust that account with anything
more than that.

## Production host (vpn.bartholomew.help)

Intended public hostname: **`vpn.bartholomew.help`** (points at the home/server PC).

DNS (Cloudflare):
- Add an **A** (or AAAA) record for `vpn` (`vpn.bartholomew.help`) → your server's **public** IP.
- Set the record to **DNS only** (grey cloud), **not** proxied. Cloudflare's proxy doesn't forward
  plain TCP/UDP on ports 53/8080 to origin the way this needs, and Caddy needs to answer the ACME
  challenge on 80/443 directly too; orange-cloud will break the phone's DNS/proxy connections and
  Caddy's cert issuance alike.
- MX already exists; that's fine and unrelated.

On the server PC:
- Port-forward **TCP 8080** (proxy), **UDP/TCP 53** (DNS), and **TCP 80/443** (update host), and
  optionally **TCP 3000** (AdGuard UI), from the router to the machine running Docker.
- Or run all of these only on LAN and use Tailscale/WireGuard mesh so the phone reaches them
  without opening any port to the world -- meaningfully safer, since 8080 is now a real proxy with
  your family's browsing passing through it, not just a DNS resolver (Caddy's Let's Encrypt cert
  issuance does need real internet-facing 80/443 for the ACME challenge, though, unless you use
  Caddy's DNS-01 challenge support instead -- not set up here).

## Firewall

Restrict ports 53/8080 (and ideally 3000) to only the phone's IP if it's static, or your ISP's IP
range, rather than leaving them open to the whole internet -- an open recursive resolver is a
standing abuse target, and an open MITM proxy is worse: proxy auth is the only thing stopping
anyone who finds it from routing their own traffic through your server (and, if they also somehow
obtained the CA's private key, decrypting it). At minimum, put the admin UI (3000) behind an IP
allowlist or a reverse proxy with auth; don't expose it to the open internet. Ports 80/443 (the
update host) are lower-risk to leave open -- it's read-only static file serving, and the actual
install-time trust check happens on-device (signature pinning), not by trusting the transport --
but still restrict them if you'd rather not have this hostname discoverable at all.

Note that `PROXY_PASSWORD` is passed to the mitmproxy container as a plain environment
variable/command-line argument (see `docker-compose.yml`), which means it's visible to anyone who
can run `docker inspect`/`docker exec`/`ps` on this host -- acceptable for a single-user home
server you administer yourself, but don't treat it as a secret from anyone who already has shell
access to the machine.

## Notes

- If port 53 is already bound (common on Ubuntu, where `systemd-resolved` listens on it), either
  stop/reconfigure `systemd-resolved` or change `DNS_PORT` in `.env` and use that port when
  configuring the app.
- The proxy CA (`mitm-ca/mitmproxy-ca-cert.pem` + its private key) is generated fresh, unique per
  deployment, the first time the container starts with an empty `mitm-ca/` volume. It is never
  committed to this repo (see `.gitignore`) -- treat the private key half as sensitive as any other
  root CA key, since anyone who has it can transparently MITM TLS for any host, not just the ones
  this addon blocks.
- No DNS-over-HTTPS/TLS for the AdGuard fallback layer -- the app talks plain DNS to it. That's
  fine since the VPN tunnel itself protects the query from being read/tampered with anywhere else
  on the path, and the phone already refuses known public DoH/DoT resolver IPs so nothing
  on-device can dodge this by hardcoding its own encrypted resolver. QUIC (UDP/443) is dropped
  by the phone's VPN entirely while the proxy is enabled, forcing HTTPS traffic onto TCP so it
  actually goes through mitmproxy instead of bypassing it over HTTP/3.
- Out of scope for this pass: on-device NSFW ML/image blanking, a macOS Network Extension /
  system-wide proxy equivalent (macOS enforcement stays DNS + hosts + pf, see
  `macos/FocusLock/README.md`), and forcing non-web traffic (chat/game/VoIP on other ports) through
  the home server -- only TCP 80/443 and QUIC are affected.
