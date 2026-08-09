# Otterling filter server

Cloud-side half of Otterling's NSFW content filter, deployed to a home server/VPS you control.
Four services:

1. **[mitmproxy](https://mitmproxy.org/)** (`otterling-mitmproxy`, host port `8090`, listening on
   `8080` inside the container) -- a real HTTPS MITM proxy. `8090` (not `8080`) is what phones
   connect to; `8080` is reserved on this host for an unrelated process outside this project's
   control (see `port8080_mux.py`'s docstring for the multiplexer this used to route through, no
   longer needed for Otterling's own traffic). The phone's `VpnFilterService`/`TcpRelayManager`
   sends every captured TCP 80/443
   flow here via HTTP `CONNECT`, authenticated with a fixed username/password, **except apps that
   certificate-pin** (YouTube, banking apps -- see "App MITM exemptions" below), whose flows stay
   inside the tunnel but connect directly instead of through mitmproxy. `mitm_nsfw_addon.py`
   decides, server-side, whether to let a request through or return a block page -- **whole
   requests/pages are blocked, not scrubbed in-page** (no Canopy-style image blanking). Its domain
   blocklist and AI classifier live in `domain_blocklist.py`/`ai_classifier.py`, shared (via
   volume mount, not import -- separate containers) with `dns_classify_mux.py` below.
2. **`dns_classify_mux.py`** (`otterling-dns-classifier`, port `53`) -- a small stdlib-`asyncio`
   UDP relay that now holds the LAN-facing DNS port. It checks every query's domain against the
   same blocklist mitmproxy uses and, for domains that aren't already known-good/known-bad, fetches
   the domain's homepage and runs it through the same AI classifier `mitm_nsfw_addon.py` uses --
   all *before* the very first lookup of that domain completes (bounded by a timeout; fails open
   on timeout/error). This is what closes the DNS-only gap for MITM-exempt apps: they never reach
   `mitm_nsfw_addon.py`'s HTML-level checks, but every app's DNS query passes through here
   equally. It can only ever judge "is this domain's own content bad," never "is this specific
   page bad" -- a DNS query has no path, and one lookup gets reused for many page loads
   afterward -- so it doesn't replace mitmproxy's per-request checks for non-pinned apps, it
   narrows the blind spot for pinned ones. Allowed queries are relayed to AdGuardHome (below,
   internal `adguardhome:53`) and its raw answer relayed back verbatim; blocked ones get a
   synthetic NXDOMAIN, same wire format as `DnsMessage.kt`'s `buildBlockedResponse` on the Android
   side. UDP only for v1 -- a truncated response needing a TCP retry falls through this mux
   (named gap, not silently dropped; rare for ordinary A-record lookups).
3. **[AdGuard Home](https://github.com/AdguardTeam/AdGuardHome)** (`otterling-filter-server`,
   admin UI port `3000` only -- no longer LAN-facing on `53`, see above) -- plain DNS, reached
   internally by `dns_classify_mux.py` over Docker's own network. Its **Parental control** and
   **Safe Search** settings (see "Deploy" below) are a category/search-level backstop for
   everything relayed through it -- including MITM-exempt apps (see "App MITM exemptions" below),
   since exemption only skips the proxy hop, not the tunnel or its DNS.
4. **[Caddy](https://caddyserver.com/)** (`otterling-updates`, ports `80`/`443`) -- serves
   `updates/manifest.json` + signed release APKs over HTTPS with an auto-provisioned Let's Encrypt
   cert. This is the *only* place the Otterling app will install an update from -- see "Gated app
   updates" below.

The phone still applies its own local adult-domain list first, client-side, regardless of whether
any server is reachable (see the app's `DomainBlocklistManager`) -- a brief outage here doesn't
remove filtering entirely, it just loses the server-side category/domain-classification/
page-content layers. No Android app changes were needed for the DNS classifier -- the app already
forwards every non-locally-blocked DNS query to the same configured host:port; only what's
*listening* there on the server changed.

## App MITM exemptions

Certificate-pinned apps break under *any* MITM proxy, not just this one -- pinning validates the
exact leaf cert/public key, which mitmproxy's own on-the-fly-generated certificate can never
match. `MitmExemptManager.DEFAULT_EXEMPT_PACKAGES` seeds a starting set on first app run so this
works out of the box: YouTube (`com.google.android.youtube`) and common AU banking apps
(CommBank, Westpac, Up, Suncorp). These apps stay **inside** the VPN tunnel -- their DNS is still
checked against the local blocklist and this filter server's Parental control/Safe Search (see
"Deploy" below) -- but `TcpRelayManager` connects their TCP 80/443 flows directly instead of
CONNECT-proxying through mitmproxy, so pinning never breaks. Flow-to-app attribution uses
`ConnectivityManager.getConnectionOwnerUid` (`AppUidResolver`), with a narrow apex-domain-suffix
fallback (`MitmExemptionPolicy`) for when UID lookup can't resolve an owner. Content-level
filtering (domain-on-path/title-keyword matching in `mitm_nsfw_addon.py`) still never sees these
apps' HTTPS bytes -- that blind spot is inherent to not decrypting their TLS, unchanged from
before. Path-based rules (e.g. YouTube Shorts) still apply separately via on-device accessibility
(`UrlPathBlockEnforcer`), which doesn't need MITM at all. The Guardian can add or remove exempt
apps any time in Settings. See [`PINNED_APP_FILTERING.md`](PINNED_APP_FILTERING.md) for the design
research behind this (including why defeating pinning itself, for full content inspection, was
ruled out).

## How blocking decisions are made

- `mitm_nsfw_addon.py` blocks a request outright if its Host matches the downloaded adult-domain
  list (same two sources as the Android app), or if the URL path/query matches a short list of
  unambiguous adult-content path tokens/site names (e.g. `/r/nsfw`, `xxx`, `pornhub`).
- For anything that gets through, it also inspects HTML responses' `<title>`/`og:description` for
  a short list of high-confidence porn keywords/phrases and blocks the whole response if matched.
- Once blocked, a repeat hit fails closed for the rest of the day without re-fetching or
  re-classifying anything -- scoped to the **whole host** for a domain-list hit (the entire domain
  is known-bad), but only to the **exact host+path** for a path-pattern/title-keyword hit, so one
  flagged URL on an otherwise-fine site doesn't take the rest of that site down for the whole day.
- No custom ML, no image inspection -- keyword/domain rules only, deliberately narrow to keep
  false positives low. See [`VISUAL_FILTERING.md`](VISUAL_FILTERING.md) for research on lighter
  visual/image-level filtering options (not implemented yet).
- Separately, at DNS resolution time, `dns_classify_mux.py` checks the same domain blocklist and,
  for new domains, AI-classifies the domain's own homepage -- this is what applies to MITM-exempt
  apps too, since it runs before mitmproxy would ever see (or not see) their traffic. Its
  allow/deny cache is its own, per-domain, 24h -- separate process/container from
  `mitm_nsfw_addon.py`'s per-path caches, not shared.

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
8. Turn on AdGuard Home's own accuracy layer -- all under its web UI:
   - **Filters → DNS blocklists → Add blocklist**: enable an adult-content list (search "adult"/
     "porn" in the built-in list gallery, e.g. the Anti-Porn list) plus any other category lists
     you want as a DNS-level backstop.
   - **Settings → General settings → Parental control**: turn on. This is AdGuard's own
     category-classification service for adult content, independent of the blocklist above.
   - **Settings → General settings → Safe Search**: turn on ("Enforce" for Google/YouTube/Bing/
     DuckDuckGo/etc, or per-service if you want to leave some off). This forces safe-search mode
     at the DNS level for anything that respects it.
   - These apply to essentially all traffic, including MITM-exempt apps (see "App MITM
     exemptions" above) -- exemption only skips the mitmproxy hop, not the tunnel or its DNS.
9. In the Otterling app: Settings → Content Filter VPN → enter `vpn.bartholomew.help`, port `53`
   for DNS, port `8090` + your `PROXY_USER`/`PROXY_PASSWORD` for the proxy, tap **Save**, then
   **Test filter server** / **Test proxy** to confirm both are reachable, then toggle **Use cloud
   filter** (and, under it, **Use filter proxy**) on.

### Smoke-testing the proxy directly

Before wiring up the phone, confirm the proxy itself works from any machine:

```bash
curl -x http://PROXY_USER:PROXY_PASSWORD@vpn.bartholomew.help:8090 https://example.com
```

A successful fetch means CONNECT + auth + TLS interception are all working. If it hangs or
errors, check `docker compose logs mitmproxy` before troubleshooting the phone side.

## Gated app updates

`updates/` publish output is **not** the git `filter-server/updates/` folder. On this host it is
`/var/lib/otterling/updates/`, written only by root via the GitHub-webhook → `release.sh` path after
AI `VERDICT: PASS` (see [`SELF_LOCKOUT.md`](SELF_LOCKOUT.md)). Caddy serves updates at
`https://<UPDATE_HOST>/updates/` and proxies `https://<UPDATE_HOST>/hooks/github` to the host
webhook. Phones use `ApprovedUpdateManager` (Settings → App updates). Secrets and the live
checklist live under `/var/lib/otterling/ci/` (root-owned), not in GitHub Actions.

## Production host (vpn.bartholomew.help)

Intended public hostname: **`vpn.bartholomew.help`** (points at the home/server PC).

DNS (Cloudflare):
- Add an **A** (or AAAA) record for `vpn` (`vpn.bartholomew.help`) → your server's **public** IP.
- Set the record to **DNS only** (grey cloud), **not** proxied. Cloudflare's proxy doesn't forward
  plain TCP/UDP on ports 53/8090 to origin the way this needs, and Caddy needs to answer the ACME
  challenge on 80/443 directly too; orange-cloud will break the phone's DNS/proxy connections and
  Caddy's cert issuance alike.
- MX already exists; that's fine and unrelated.

On the server PC:
- Port-forward **TCP 8090** (proxy -- not 8080; that port is reserved on this host for an
  unrelated process, see the mitmproxy bullet at the top of this file), **UDP/TCP 53** (DNS), and
  **TCP 80/443** (update host), and optionally **TCP 3000** (AdGuard UI), from the router to the
  machine running Docker.
- Or run all of these only on LAN and use Tailscale/WireGuard mesh so the phone reaches them
  without opening any port to the world -- meaningfully safer, since 8090 is now a real proxy with
  your family's browsing passing through it, not just a DNS resolver (Caddy's Let's Encrypt cert
  issuance does need real internet-facing 80/443 for the ACME challenge, though, unless you use
  Caddy's DNS-01 challenge support instead -- not set up here).

## Firewall

Restrict ports 53/8090 (and ideally 3000) to only the phone's IP if it's static, or your ISP's IP
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
- No DNS-over-HTTPS/TLS for the DNS layer (`dns_classify_mux.py` in front of AdGuard Home) -- the
  app talks plain DNS to it. That's
  fine since the VPN tunnel itself protects the query from being read/tampered with anywhere else
  on the path, and the phone already refuses known public DoH/DoT resolver IPs so nothing
  on-device can dodge this by hardcoding its own encrypted resolver. QUIC (UDP/443) is dropped
  by the phone's VPN entirely while the proxy is enabled, forcing HTTPS traffic onto TCP so it
  actually goes through mitmproxy instead of bypassing it over HTTP/3.
- Out of scope for this pass: on-device NSFW ML/image blanking (researched, not built -- see
  [`VISUAL_FILTERING.md`](VISUAL_FILTERING.md)), a macOS Network Extension / system-wide proxy
  equivalent (macOS enforcement stays DNS + hosts + pf, see `macos/FocusLock/README.md`), and
  forcing non-web traffic (chat/game/VoIP on other ports) through the home server -- only TCP
  80/443 and QUIC are affected. Content-level MITM of the apps in
  `MitmExemptManager.DEFAULT_EXEMPT_PACKAGES` is explicitly out of scope (they still get DNS-level
  filtering, just not page/title inspection) -- see "App MITM exemptions" above for why.
