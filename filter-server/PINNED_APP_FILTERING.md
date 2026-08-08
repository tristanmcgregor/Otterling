# Filtering certificate-pinned apps (banking, YouTube) without breaking them

**Status: implemented** (Variant A below was the researched-but-rejected alternative; what shipped
is Variant B -- UID-based in-tunnel MITM exemption, see `MitmExemptManager`, `MitmExemptionPolicy`,
`AppUidResolver`). This note is kept as the design record for why, since the reasoning (especially
why defeating pinning itself was ruled out) isn't obvious from the code alone. Historically, apps in
this list were excluded from the tunnel entirely via `VpnService.Builder.addDisallowedApplication()`
-- their traffic never entered the tun device at all, so they got **zero filtering of any kind**: not
proxy-level, not even the DNS-level blocklist/AdGuard layer everything else gets. What shipped instead
keeps them inside the tunnel (DNS-level filtering applies) while only their proxy hop is skipped --
see `TcpRelayManager.establish` and the "App MITM exemptions" section of `README.md`.

## Why these apps break under the full HTTPS proxy in the first place

`TcpRelayManager` sends every captured TCP 80/443 flow through an HTTP `CONNECT` to mitmproxy, which
terminates the client's TLS and re-encrypts with its own on-the-fly-generated certificate signed by
its own CA (see `ca/README.md`). Certificate pinning -- which banking apps and the YouTube app both
use -- validates the exact leaf certificate or public key the app expects, not just "is this signed by
a CA my OS trusts." mitmproxy's substitute certificate can never match that pin, so the TLS handshake
fails from the app's point of view: it looks like the app itself is broken/offline, not like "blocked
by a filter" (no block page, just a connection error). `MitmExemptManager` (formerly
`VpnBypassManager`) exists specifically to avoid this without keeping these apps off the tunnel
entirely.

## The actual question: can we do better than "on the tunnel + broken" vs. "off the tunnel + unfiltered"?

**Short answer: not for content-level filtering, but yes for DNS-level filtering and no-QUIC-dodge,
via a third option between full MITM and full VPN bypass.**

### Ruled out: defeating certificate pinning itself

The only way to get the *same* content-level filtering these apps' ordinary web traffic gets (title/
keyword/domain matching on decrypted bytes) would be to make the pinned app accept mitmproxy's
substitute certificate anyway -- i.e. instrumentation-based pinning bypass (Frida/Xposed-style hooking
of the app's TLS stack, or a repackaged/patched APK with pinning code stripped). **Not recommended,
and not pursued further here:**

- **Needs root.** Knox Device Owner (what this app already has) is a management API, not root access
  -- it cannot hook another app's process or patch its code. Actually getting there would mean rooting
  the device, which is a fundamentally different and far riskier project than anything this app does
  today, and works against Knox's whole security model rather than with it.
- **Wrong app to take this risk with.** Even if it were technically achievable, doing this to a
  *banking* app specifically means routing real banking sessions/credentials through a MITM whose
  private CA key lives on a home server (see `filter-server/README.md`'s Firewall section, which
  already flags that key as sensitive). If that key or the proxy auth were ever compromised, the
  blast radius would include live banking sessions, not just browsing history. That's a strictly worse
  failure mode than "banking app doesn't get keyword-filtered."
- **Would likely just break differently.** Banking apps generally ship their own root/tamper/Frida
  detection (Play Integrity, SafetyNet-successor checks, jailbreak/root detectors) specifically to
  refuse to run under exactly this kind of instrumentation -- so this path plausibly doesn't even
  restore functionality, just trades one failure mode for another, worse one.

### Recommended direction: keep these apps *inside* the tunnel, but exempt only their TCP 80/443 flows from the proxy hop

Right now `TcpRelayManager.establish()` already has exactly the branch needed for this, just gated on
the wrong condition. Look at the existing code:

```kotlin
val useProxy = proxyConfig.enabled &&
    (connection.key.dstPort == HTTP_PORT || connection.key.dstPort == HTTPS_PORT) &&
    !isFilterHostDestination(connection.key.dstIp)
...
if (useProxy) {
    // CONNECT through mitmproxy
} else {
    // direct connect to the real destination -- already used today for non-80/443 ports
    // and for the filter/update host itself (isFilterHostDestination)
}
```

The `else` branch -- direct-connect, no MITM, byte-blind relay straight to the real destination -- is
already exactly "don't break certificate pinning for this flow." It's just currently reserved for
non-web ports and our own filter host. Extending the `useProxy` condition to also exclude a small set
of known pinned-app domains (or, more precisely, known pinned-app *flows* -- see the two variants
below) gets these apps a third option that doesn't exist today: **inside the tunnel (so DNS still goes
through the filtered resolver, and QUIC/443-UDP is still dropped, forcing them onto plain TCP like
everything else), but with their TCP 80/443 relayed directly instead of proxied, so their TLS reaches
the real origin untouched and pinning never fails.**

This is a strict improvement over today's `addDisallowedApplication` bypass: DNS-level protection
(the local blocklist, AdGuard's Parental control/Safe Search once configured -- see `README.md`'s
Deploy section) starts applying to these apps for the first time, where today they get literally
nothing. It does **not** close the content-inspection gap -- these flows still never reach
`mitm_nsfw_addon.py`, so no domain-list-on-path/title-keyword matching happens on their actual page
content, same blind spot as today. That's an inherent consequence of not decrypting their TLS, not a
shortcoming of the approach; see the "ruled out" section above for why that's the deliberate boundary.

#### Variant A -- static domain allowlist (simpler; not what shipped)

Maintain a short list of pinned-app domains (`*.googlevideo.com`, `*.youtube.com`, `commbank.com.au`,
`netbank.commbank.com.au`, `westpac.com.au`, `up.com.au`, `suncorp.com.au`, etc. -- would need the
actual set of hostnames each app's traffic touches, likely more than the app's own primary domain
once CDN/API subdomains are accounted for) and check the destination hostname (resolved via the same
`dnsAnswerHostnameCache`/reverse-DNS approach `TcpRelayManager` already uses for the CONNECT line's
target host, or the SNI from the TLS ClientHello itself, which is sent in cleartext even inside a
pinned handshake) against it in `TcpRelayManager.establish()`, alongside the existing
`isFilterHostDestination` check.

- **Pros:** Small, surgical change to an existing decision point; no new Android permissions; matches
  the pattern already used for the filter-host exemption.
- **Cons:** Domain lists for CDN-backed services drift (YouTube especially -- Google's edge/API
  hostnames are numerous and can change), so this needs periodic maintenance, similar in kind to the
  existing `DOMAIN_LIST_URLS` refresh but for an *allowlist* instead of a blocklist. A missed domain
  means that specific flow still gets proxied and still breaks pinning -- a maintenance burden, not a
  silent security gap (worst case is "app breaks again until the list is updated," not "app gets
  MITM'd unexpectedly").

#### Variant B -- per-app UID attribution (what shipped)

Android's `ConnectivityManager.getConnectionOwnerUid(protocol, localSocketAddress,
remoteSocketAddress)` (API 29+) answers "which app UID owns this TCP tuple?" -- `AppUidResolver`
wraps this call. Because a `VpnService` tun is point-to-point, the originating app's own real
socket's local address *is* the tun's assigned address once the default route points at it (no
separate NAT rewrite happens anywhere in this codebase), so the 4-tuple `TcpRelayManager` already
has for each flow is exactly the tuple the kernel's own connection tracking associates with the
real originating app -- the same technique local-VPN per-app firewalls (NetGuard, RethinkDNS/Intra)
already use from inside their own `VpnService`. `MitmExemptionPolicy.isExempt` makes the actual
decision: a resolved UID is authoritative (never falls through to a hostname check either way,
so an unrelated app is never accidentally exempted just because it happens to hit a curated
domain); a narrow apex-domain-suffix fallback (`MitmExemptionPolicy.DEFAULT_HOST_SUFFIXES`) only
applies when UID lookup returns nothing (e.g. pre-API-29 devices), reusing the DNS-answer hostname
cache already threaded into `TcpRelayManager` -- no new SNI/TLS parsing needed. Fail-safe: if
attribution can't confirm exemption, the flow stays MITM'd (a false negative just reproduces
today's tolerated pinning failure for that one flow; a false positive would be an actual filtering
bypass, which this design never allows).

Exact `getConnectionOwnerUid` behavior across the full OEM/Android-version range this app targets
(minSdk 28, so pre-Q devices always take the hostname-suffix fallback path) hasn't been fully
verified on real hardware -- first live-device test (Galaxy S22, Android 16) surfaced a real gap:
the family's actual YouTube client is `app.morphe.android.youtube` (a fork), not
`com.google.android.youtube` (unused for 569 days per `dumpsys usagestats`) -- the fork wasn't in
`DEFAULT_EXEMPT_PACKAGES`, so it correctly went through MITM and broke, same as any unexempted
pinned app. Fixed by adding it to the default list. Whether `getConnectionOwnerUid` itself resolves
correctly hasn't been independently confirmed yet, since the same test session also hit an
unrelated mitmproxy server outage (every proxied CONNECT failing with "no response", including to
apps never meant to be exempt) that masked cleaner signal -- worth re-testing once the server side
is healthy.

## If banking/YouTube look "blocked" right now, check this first

`MitmExemptManager.DEFAULT_EXEMPT_PACKAGES` seeds YouTube + AU banking packages by default, but
that list (and any Guardian-added entries) is only re-read when the tunnel is (re)established
(`VpnFilterService.runPacketLoop`, called from `startVpn()`/`reestablish()`). A device whose VPN
tunnel was already running *before* an app update that changed this list won't pick up the change
until the tunnel actually rebuilds -- toggling the content filter VPN off/on in Settings (or a
reboot) forces that. Also check whether the actual app in use matches a package name on the list at
all -- YouTube/banking forks and rebrands (ReVanced, RVX, Morphe, etc.) are common and won't be
covered by a name seeded for the official app. Worth ruling out both before assuming something is
broken.

## Automatic pinning detection -- researched, not built

The recurring gap above (a static list missing an app nobody thought to add) raises the obvious
question: can the app detect pinning failures itself instead of relying on a human noticing and
adding an exemption? Researched what real products in this exact space (enterprise SSL-inspection
appliances, which have solved "some apps break under our MITM" at far larger scale for years) 
actually do.

**Finding: nobody does fully-automatic, silent, runtime detection-and-self-exemption -- including
sophisticated commercial products.** Palo Alto's own decryption troubleshooting docs describe a
*manual* admin workflow: query logs for `UnknownCA`/`BadCertificate` error codes, confirm by
"looking for the client breaking the connection immediately after the TLS handshake," then manually
add the site to an exclusion list ([Palo Alto Networks: Troubleshoot Pinned
Certificates](https://docs.paloaltonetworks.com/pan-os/10-1/pan-os-admin/decryption/troubleshoot-and-monitor-decryption/decryption-troubleshooting-workflow-examples/troubleshoot-pinned-certificates)).
Netskope and Symantec/Broadcom both ship **vendor-curated static bypass lists** (Crowdstrike,
Dropbox, iCloud, etc.) that customers supplement manually when something not on the list breaks
([Netskope: Add Bypasses](https://docs.netskope.com/en/add-bypasses-in-netskope),
[Broadcom: SSL-pinned application
list](https://knowledge.broadcom.com/external/article/173380/list-of-applications-and-mobile-applicat.html))
-- structurally the same pattern as `DEFAULT_EXEMPT_PACKAGES` + the Settings "Add exempt app"
button this project already has, just with a bigger vendor-maintained seed list. OWASP's own MASTG
test notes why nobody closes this loop automatically: **"A passive observer cannot reliably
differentiate pinning rejection from other certificate validation failures without additional
context, as both produce similar TLS alert sequences"** ([OWASP MASTG-TEST-0244](https://mas.owasp.org/MASTG/tests/android/MASVS-NETWORK/MASTG-TEST-0244/)).
An app failing right after our proxy's certificate could just as easily be a network blip, a
mitmproxy hiccup, or an unrelated server error -- not pinning at all. Auto-exempting on that signal
alone risks silently reducing filtering coverage for an app that never actually needed it.

**The detection signal itself is real and well-documented, though** -- both Palo Alto's and OWASP's
docs describe the same observable pattern: the client receives our substitute certificate, then
aborts (an Alert record, or just an abrupt close) within moments, having exchanged little to no
further data, well short of a real request/response cycle. This is visible from *connection
metadata alone* -- bytes exchanged, timing, which side closed first -- without decrypting anything.
`TcpRelayManager.Connection` already tracks exactly this (`bytesFromSocket`, `startedAtMillis`,
etc.) for its existing debug throughput logging in `closeConnection` -- the raw signal needed is
already flowing through code that exists today.

**Proposed design, if built: detect-and-suggest, not detect-and-auto-exempt.** Given the industry
consensus above, closing the loop *silently* isn't the right target. A sounder version: extend
`closeConnection` to flag a proxied connection as a suspected-pinning-failure when it closes
abnormally fast with minimal data exchanged; require several such failures for the *same app* (via
the UID this design already resolves) before acting, to filter out one-off blips or a transient
proxy outage (like the one that muddied the live test above); then surface a one-tap suggestion to
the Guardian (a notification, reusing the existing `AlertReporter` pattern) -- "Otterling noticed
[App] repeatedly fails to load with HTTPS filtering on. Add it to the exempt list?" -- rather than
silently mutating `MitmExemptManager`'s list. This keeps a human decision in the loop for the actual
filtering-coverage trade-off (matching how every reference product above still requires a human to
add the final exception) while automating the part that's actually toil: noticing the pattern and
diagnosing it, which is exactly the gap that caused the Morphe YouTube issue above -- the signal was
there in logcat the whole time, nobody was watching it.

Not built -- this is a real feature (parsing thresholds, notification UX, false-positive tuning) 
that deserves its own pass, not a drive-by addition to the exemption fix above.
