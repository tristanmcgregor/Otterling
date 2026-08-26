package app.otterling.content

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.util.Log
import app.otterling.alerts.AlertReporter
import app.otterling.alerts.AlertSeverity
import app.otterling.alerts.GuardianAlertSettings
import app.otterling.focus.HabitRuleManager
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Local, always-on VpnService that filters DNS lookups (plus a short list of known public
 * DNS-over-HTTPS/DoT resolver IPs, refused outright) and, when the filter proxy is enabled,
 * routes every captured TCP 80/443 flow through a real HTTPS MITM proxy on the family's own
 * server ([CloudFilterSettings]) instead of relaying it directly -- that server decides whether
 * to block a whole request/page, giving page-content-aware filtering, not just DNS-level category
 * blocking. This proxy decision is the ONLY *content-category* blocking that applies during
 * normal operation -- every device on the account (this phone, a Mac running FocusLock) shares
 * the exact same verdict, since they're all reviewed by the same server-side page-content check.
 * The downloaded public domain blocklist and the AI-classified-domains cache
 * ([DomainBlocklistManager.isPublicListBlocked], [ServerClassifiedDomainsManager]) are NOT
 * consulted on top of that -- they're a fallback for when the proxy verdict genuinely isn't
 * available for a flow (a MITM-exempt app, or a real proxy outage), not an always-on second layer
 * that could disagree with it. The guardian's own per-device `blockedWebsites`
 * ([DomainBlocklistManager.isCustomBlocked]) is separate from all of that: an explicit rule they
 * set for this specific device, always enforced regardless of proxy state. See
 * [handleDnsPacket]'s `blocked` computation for exactly when each applies.
 *
 * Captures a full default route (every IPv4 destination) because a [VpnService] that captures all
 * app traffic but only has routes for a handful of specific IPs makes every *other* destination
 * unreachable for captured apps -- not "falls back to the real network" like an earlier version of
 * this class assumed, just broken (this is what caused otherwise-unrelated apps, e.g. Spotify, to
 * report "no internet" the moment this VPN turned on). Since DNS filtering needs *every* app's
 * traffic funneled through here anyway (so nothing can bypass it), the only way to do that without
 * breaking everything else is to also relay everything else back out ourselves --
 * [TcpRelayManager]/[UdpRelayManager] do that: real destinations get a real (protected) socket
 * opened on this device and bytes are bridged transparently in both directions; only DNS (port 53),
 * a hardcoded list of known public DoH resolver IPs, DNS-over-TLS (port 853, blocked outright
 * regardless of destination -- see [DOT_PORT]), TCP 80/443 (proxied, not relayed directly, when
 * the filter proxy is on), and UDP 443/QUIC (dropped outright when the filter proxy is on, forcing
 * HTTPS onto TCP so it can't bypass the proxy over HTTP/3) get special treatment.
 *
 * Registered as the device's mandatory VPN via [VpnFilterManager], which uses Device Owner's
 * `DevicePolicyManager.setAlwaysOnVpnPackage(..., lockdownEnabled = true)` -- once set, Android
 * blocks all network access (including a second VPN app's own tunnel) unless this service is
 * running, and the always-on VPN setting itself is locked out of the user-facing Settings UI.
 *
 * IPv6 isn't captured (no IPv6 address/route is ever added to the [Builder]), so it's blocked
 * outright for captured apps rather than relayed -- acceptable for now since this only matters on
 * networks that actually offer global IPv6 routing to begin with.
 *
 * Some browsers' built-in "Secure DNS"/DNS-over-HTTPS features may fail to load pages while this
 * is active, since their hardcoded resolver IP gets refused rather than falling through to the
 * (filtered) system resolver -- same trade-off as disabling Secure DNS in Chrome.
 */
class VpnFilterService : VpnService() {
    // Without this, an uncaught exception in any single relayed connection's coroutine (there are
    // many, one+ per TCP/UDP flow) crashes this whole process by default -- taking down every
    // other in-flight connection and the VPN itself, not just the one that hit a bug.
    private val exceptionHandler = CoroutineExceptionHandler { _, error -> Log.e(TAG, "Unhandled relay error", error) }
    // Dispatchers.IO is capped at ~64 concurrent threads (tuned for short-lived I/O bursts), but
    // this relay needs one thread *per open connection* for as long as a blocking socket.connect()/
    // read()/write() call is in flight -- with anything beyond ~64 simultaneous flows (trivially
    // reached by e.g. a speed-test site opening dozens of parallel probe connections), the excess
    // ones queue behind whichever 64 happen to be running, adding multi-second delays that read to
    // the client as a stalled/failed TLS handshake or an outright connect timeout, even though nothing
    // was actually wrong with the connection itself. An unbounded cached pool removes that ceiling.
    private val relayExecutor = Executors.newCachedThreadPool()
    private val scope = CoroutineScope(SupervisorJob() + relayExecutor.asCoroutineDispatcher() + exceptionHandler)
    private var tunInterface: ParcelFileDescriptor? = null
    private var workerJob: Job? = null
    // One "generation" per (re)establish of the tunnel: every DNS/TCP/UDP coroutine spawned while
    // handling packets off a given tun instance is a child of this scope, not the service-lifetime
    // [scope] above. Without this, cancelling workerJob on reestablish() only stopped the tun-read
    // loop itself -- every connection it had already spawned (each with its own coroutines, e.g.
    // TcpRelayManager's window-wait poll loop) kept running forever against a torn-down tunnel,
    // leaking coroutines/threads and burning CPU indefinitely every time the VPN got toggled or
    // its bypass list changed.
    private var connectionScope: CoroutineScope? = null
    private val running = AtomicBoolean(false)
    // Prevents multiple overlapping self-heal restarts from stacking up (e.g. a dead-tunnel exit
    // firing at the same time as an establish() retry): set when a restart is scheduled, cleared
    // right before it actually rebuilds, so at most one rebuild is ever pending at a time.
    private val restartScheduled = AtomicBoolean(false)
    // Best-effort IP->hostname cache populated from real (non-blocked) DNS answers, so
    // TcpRelayManager can put a real hostname on the CONNECT line to the filter proxy instead of a
    // bare IP for a flow whose app already resolved the name through us. Not required for
    // filtering to work (the proxy reads the true destination from the TLS ClientHello/Host header
    // regardless of what the CONNECT line said), just a nicety -- so this deliberately survives
    // reestablish() (a fresh generation shouldn't have to relearn every hostname it already knew)
    // but is capped and access-ordered (LRU) rather than kept forever, since plenty of real-world
    // IPs (CDN edges, shared hosting) serve many different hostnames over time and an unbounded
    // or stale mapping would misattribute a later, unrelated connection to an old hostname.
    private val dnsAnswerHostnameCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) =
                size > MAX_DNS_HOSTNAME_CACHE_ENTRIES
        },
    )

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra(EXTRA_REESTABLISH, false) == true) {
            reestablish()
        } else if (running.compareAndSet(false, true)) {
            startVpn()
        }
        return START_STICKY
    }

    /** Tears down the current tunnel and builds a fresh one so a changed bypass list (or blocklist)
     * takes effect without fully stopping the service / dropping the always-on registration. */
    private fun reestablish() {
        workerJob?.cancel()
        connectionScope?.cancel() // stops every relay coroutine from the old tunnel generation
        connectionScope = null
        tunInterface?.let { runCatching { it.close() } }
        tunInterface = null
        running.set(true)
        startVpn()
    }

    private fun startVpn() {
        val blocklist = DomainBlocklistManager(applicationContext)
        val classifiedDomains = ServerClassifiedDomainsManager(applicationContext)
        val habitRuleManager = HabitRuleManager(applicationContext)
        val cloudFilterSettings = CloudFilterSettings(applicationContext)
        val alertSettings = GuardianAlertSettings(applicationContext)
        val builder = Builder()
            .setSession("Otterling Filter")
            .setMtu(MTU)
            // A /32 tun address with the DNS server pointed at that *same* address was the bug:
            // the kernel treats traffic to an interface's own local address as local delivery, so
            // it never actually traverses the tun device -- our packet-read loop never sees it.
            // Queries to any *other* address (even one nothing else owns, like .2 here) genuinely
            // get routed out over tun0 by the 0.0.0.0/0 route below and hit our relay correctly.
            // This is why apps with a hardcoded fallback resolver (e.g. Chrome/WhatsApp querying
            // 8.8.8.8/8.8.4.4 directly) worked while everything using the network's *configured*
            // DNS server (i.e. nearly everything else, including Spotify) silently got NODATA.
            .addAddress(VIRTUAL_IP, 24)
            .addDnsServer(DNS_SERVER_IP)
            .addRoute("0.0.0.0", 0)
            // VpnService treats the tunnel as metered by default. Left unset, apps that respect
            // Data Saver / "restrict background data" (e.g. Spotify) get silently network-blocked
            // by netd over this VPN even though the underlying Wi-Fi/cellular network is unmetered.
            .setMetered(false)
        applyBypassApps(builder)

        tunInterface = try {
            builder.establish()
        } catch (error: Exception) {
            Log.e(TAG, "Failed to establish VPN tunnel", error)
            null
        }

        val tun = tunInterface
        if (tun == null) {
            // The service is still meant to be up (e.g. establish() can fail transiently when
            // started before the network is ready at boot). Keep running=true and retry after a
            // backoff rather than giving up forever; the anti-stacking guard keeps repeated
            // failures from tight-looping.
            Log.w(TAG, "VPN tunnel establish returned null -- scheduling retry")
            scheduleRestart()
            return
        }
        val generationScope = CoroutineScope(
            SupervisorJob(scope.coroutineContext[Job]) + relayExecutor.asCoroutineDispatcher() + exceptionHandler,
        )
        connectionScope = generationScope
        workerJob = scope.launch {
            runCatching { runPacketLoop(tun, blocklist, classifiedDomains, habitRuleManager, cloudFilterSettings, alertSettings, generationScope) }
                .onFailure { Log.e(TAG, "Packet loop crashed", it) }
            // The packet loop returned. If this coroutine is still active and the service is still
            // meant to be running, the loop exited unexpectedly (transient tun read IOException/EOF
            // during a network handover or brief teardown) rather than via an intentional
            // reestablish()/onDestroy() -- both of those cancel workerJob (isActive=false) and/or
            // clear running. In that case the tun is dead and filtering has silently stopped, so
            // self-heal by rebuilding the tunnel after a short backoff.
            if (isActive && running.get()) {
                Log.w(TAG, "Packet loop exited unexpectedly with tunnel still active -- self-healing")
                scheduleRestart()
            }
        }
    }

    /** Rebuilds the tunnel after [RESTART_BACKOFF_MS], on the service-lifetime [scope] (survives the
     * old workerJob completing). Anti-stacking: at most one restart is pending at a time. */
    private fun scheduleRestart() {
        if (!restartScheduled.compareAndSet(false, true)) return
        scope.launch {
            delay(RESTART_BACKOFF_MS)
            restartScheduled.set(false)
            // The service may have been torn down (VPN toggled off) during the backoff.
            if (!running.get()) return@launch
            reestablish()
        }
    }

    /**
     * Otterling's own package, plus [FULLY_VPN_EXEMPT_PACKAGES], are excluded from the tunnel
     * entirely. Own package: so its own update checks/settings probes don't hairpin through the
     * filter proxy (and so `protect()` isn't required for UI HTTPS). Certificate-pinned apps
     * ([MitmExemptManager]) are handled differently from both of these: they stay *inside* the
     * tunnel (see [runPacketLoop]/[TcpRelayManager]) so their DNS is still filtered, just not
     * MITM-proxied -- that's sufficient for apps that only make normal internet HTTPS requests.
     * [FULLY_VPN_EXEMPT_PACKAGES] is for apps that also need traffic types a MITM exemption can't
     * fix: Android Auto's wireless projection depends on local-network discovery (mDNS/multicast)
     * and a raw peer-to-peer socket to the head unit, neither of which is a filtered internet
     * flow -- capturing it into the tun device via the blanket 0.0.0.0/0 route breaks the
     * connection outright, regardless of any per-flow MITM exemption. There's no content-filtering
     * loss worth preserving here (a car head unit isn't a place a kid browses to porn), so a full
     * bypass is the right tradeoff, unlike Chrome (see [MitmExemptManager]).
     */
    private fun applyBypassApps(builder: Builder) {
        try {
            builder.addDisallowedApplication(packageName)
        } catch (error: PackageManager.NameNotFoundException) {
            Log.w(TAG, "Failed to exclude own package from VPN", error)
        }
        FULLY_VPN_EXEMPT_PACKAGES.forEach { pkg ->
            try {
                builder.addDisallowedApplication(pkg)
            } catch (error: PackageManager.NameNotFoundException) {
                // Not installed on this device -- fine, nothing to exempt.
            }
        }
    }

    private fun runPacketLoop(
        tun: ParcelFileDescriptor,
        blocklist: DomainBlocklistManager,
        classifiedDomains: ServerClassifiedDomainsManager,
        habitRuleManager: HabitRuleManager,
        cloudFilterSettings: CloudFilterSettings,
        alertSettings: GuardianAlertSettings,
        relayScope: CoroutineScope,
    ) {
        val input = FileInputStream(tun.fileDescriptor)
        val output = FileOutputStream(tun.fileDescriptor)
        val writeLock = Mutex()
        // Centralized so every caller (DNS/TCP/UDP relay) is protected: a write can fail with EIO
        // if the tun gets torn down (e.g. the VPN toggled off) while a background relay coroutine
        // is mid-write. Uncaught, that IOException propagates to the coroutine dispatcher's thread
        // and crashes the whole process -- taking down every other in-flight connection with it,
        // not just the one that failed.
        val writeToTun: suspend (ByteArray) -> Unit = { bytes ->
            try {
                writeLock.withLock { output.write(bytes) }
            } catch (error: IOException) {
                Log.w(TAG, "Failed writing to tun", error)
            }
        }
        val isBlockedDestination: (String, Int) -> Boolean = { ip, port -> ip in KNOWN_DOH_IPS || port == DOT_PORT }
        val proxyEnabled = cloudFilterSettings.isProxyEnabled()
        val proxyConfig = ProxyConfig(
            enabled = proxyEnabled,
            host = cloudFilterSettings.host(),
            port = cloudFilterSettings.proxyPort(),
            user = cloudFilterSettings.proxyUser(),
            password = cloudFilterSettings.proxyPassword(),
        )

        // Resolved once per tunnel generation (same lifecycle as proxyConfig) -- a Guardian
        // adding/removing an exempt app takes effect on the next reestablish(), same pattern as
        // every other setting this loop reads at startup.
        //
        // "MITM everything except this curated list" (opt-out), not "MITM only a curated list"
        // (opt-in) -- an opt-in model was tried (2026-08-18, reverted) to fix Netflix/Samsung
        // Wearable breaking under the proxy hop, restricting MITM to Chrome only. That fixed
        // those two apps but silently exempted every OTHER app and browser too: any non-Chrome
        // browser (or any app at all) got zero page-content review from mitm_nsfw_addon.py,
        // falling back to DNS-level-only filtering that's deliberately permissive about
        // ambiguous-but-not-known-bad domains (see useStrictDns below) since it normally trusts
        // this MITM hop to catch a bad page on an otherwise-fine domain -- installing any other
        // browser was a full content-filtering bypass. Netflix and Wearable are now seeded
        // exemptions instead (MitmExemptManager.DEFAULT_EXEMPT_PACKAGES_V5), and
        // PinningFailureTracker below still catches anything else that breaks, the same as it
        // always did for every other pinning-incompatible app.
        val exemptManager = MitmExemptManager(applicationContext)
        val mitmExemptUids = exemptManager.exemptPackages().mapNotNullTo(mutableSetOf()) { pkg ->
            runCatching { packageManager.getPackageUid(pkg, 0) }.getOrNull()
        }
        val ownerUidResolver = AppUidResolver(applicationContext)
        // Auto-exempts an app after a few suspected pinning rejections within a short window,
        // closing the gap a static seeded list can't: an app nobody thought to add in advance (see
        // the Morphe YouTube/HotDoc gaps) still ends up working without a Guardian having to notice
        // and add it manually. See PinningFailureTracker/PinningFailureHeuristic for the actual
        // signal, and PinningFailureTracker's own doc for the persistence bug that used to stop
        // this from ever firing in practice.
        val pinningFailureTracker = PinningFailureTracker(applicationContext)
        // Fresh per tunnel generation, same as pinningFailureTracker above, but unlike it this is
        // never persisted -- a proxy outage is a live signal about *this* generation's proxy
        // health, not a slow-accumulating per-app one. See ProxyOutageTracker's own doc for why it
        // needs a materially shorter window/different shape than the pinning heuristic.
        val proxyOutageTracker = ProxyOutageTracker()

        val tcpRelay = TcpRelayManager(
            scope = relayScope,
            protect = { socket -> protect(socket) },
            writeToTun = writeToTun,
            isBlockedDestination = isBlockedDestination,
            proxyConfig = proxyConfig,
            resolveHostname = { ip -> dnsAnswerHostnameCache[ip] },
            resolveOwnerUid = { localIp, localPort, remoteIp, remotePort ->
                ownerUidResolver.ownerUid(localIp, localPort, remoteIp, remotePort)
            },
            mitmExemptUids = mitmExemptUids,
            mitmExemptHostSuffixes = MitmExemptionPolicy.DEFAULT_HOST_SUFFIXES,
            onSuspectedPinningFailure = { uid ->
                if (pinningFailureTracker.recordSuspectedFailure(uid)) {
                    // Newly exempted -- rebuild the tunnel so the change applies to this app's
                    // next connection attempt instead of waiting for some unrelated settings change.
                    VpnFilterService.reestablish(applicationContext)
                }
            },
            onProxyConnectFailure = { dstIp ->
                if (proxyOutageTracker.recordFailure(dstIp)) {
                    // Visibility only -- never auto-remediates, never touches MitmExemptManager.
                    // Same launch-and-log-on-failure idiom as the VPN_BLOCK alert in
                    // handleDnsPacket below.
                    scope.launch {
                        runCatching {
                            AlertReporter(applicationContext).report(
                                type = "CONTENT_FILTER_PROXY_OUTAGE",
                                details = "Filter proxy CONNECT failing across multiple destinations -- filter server may be unreachable",
                                severity = AlertSeverity.WARNING,
                                debounceKey = "CONTENT_FILTER_PROXY_OUTAGE",
                            )
                        }.onFailure { Log.w(TAG, "Proxy outage alert failed", it) }
                    }
                }
            },
        )
        val udpRelay = UdpRelayManager(
            scope = relayScope,
            protect = { socket -> protect(socket) },
            writeToTun = writeToTun,
            isBlockedDestination = isBlockedDestination,
        )

        var consecutiveEmptyReads = 0
        while (running.get()) {
            // A fresh buffer per read -- the previous one may still be in flight on a background
            // coroutine, since DNS/TCP/UDP handling all happen off this loop.
            val buffer = ByteArray(MTU + 100) // headroom over MTU for IP/TCP headers on inbound client segments
            val length = try {
                input.read(buffer)
            } catch (error: IOException) {
                if (running.get()) Log.w(TAG, "tun read failed", error)
                break
            }
            if (length < 0) {
                // EOF: the tun fd is done (torn down elsewhere) -- exit rather than spin on it.
                if (running.get()) Log.w(TAG, "tun read hit EOF")
                break
            }
            if (length == 0) {
                // A blocking read on the tun fd should never return 0 without any data -- but on
                // some devices/transient network states (observed: right after the VPN starts, or
                // during a network handover) it does exactly that instead of actually blocking.
                // Measured on-device: ~55,000 of these per second with no backoff here, each one
                // still allocating the buffer above -- a busy-spin that pegged a CPU core
                // continuously and was the actual cause of severe battery drain (not merely a
                // once-the-VPN-has-run-a-while problem: it started from the moment the tunnel came
                // up). Back off briefly so this state is nearly free instead of CPU-melting, and
                // recovers immediately once real packets start arriving again.
                consecutiveEmptyReads++
                if (consecutiveEmptyReads == 1 || consecutiveEmptyReads % 1000 == 0) {
                    Log.w(TAG, "tun read returned 0 with no data ($consecutiveEmptyReads consecutive) -- backing off")
                }
                Thread.sleep(EMPTY_READ_BACKOFF_MS)
                continue
            }
            consecutiveEmptyReads = 0

            val packet = IpPacket.parse(buffer, length)
            if (packet == null) {
                Log.d(TAG, "tun: unparseable packet, $length bytes, first byte 0x${"%02x".format(buffer[0])}")
                continue
            }
            when (packet.protocol) {
                IpPacket.PROTOCOL_UDP -> {
                    if (packet.destinationPort == DNS_PORT) {
                        // Handled on its own coroutine so one slow/stalled upstream lookup can't
                        // stall this read loop and pile up every other in-flight query behind it
                        // (this alone used to be enough to make apps that fire off many DNS
                        // lookups in quick succession, e.g. Spotify resolving several
                        // edge/access-point hostnames at once, see lookups time out).
                        relayScope.launch {
                            handleDnsPacket(
                                packet,
                                writeToTun,
                                blocklist,
                                classifiedDomains,
                                habitRuleManager,
                                cloudFilterSettings,
                                alertSettings,
                                ownerUidResolver,
                                mitmExemptUids,
                                isProxyUnavailable = { !proxyEnabled || proxyOutageTracker.isLikelyDown() },
                            )
                        }
                    } else if (proxyEnabled && packet.destinationPort == QUIC_PORT) {
                        // Silent drop: forces browsers/apps to fall back to TCP HTTPS instead of
                        // HTTP/3-over-QUIC, which would otherwise sail straight past the proxy
                        // (QUIC carries its own encrypted transport, not just TLS-over-TCP, so
                        // there's no equivalent "CONNECT and bridge" option for it here). Only
                        // dropped while the proxy is actually in use -- plain DNS-only filtering
                        // has no reason to break QUIC.
                    } else {
                        udpRelay.handle(packet)
                    }
                }
                IpPacket.PROTOCOL_TCP -> tcpRelay.handle(packet)
                else -> {} // e.g. ICMP -- not relayed, no reply sent
            }
        }
    }

    private suspend fun handleDnsPacket(
        packet: IpPacket,
        writeToTun: suspend (ByteArray) -> Unit,
        blocklist: DomainBlocklistManager,
        classifiedDomains: ServerClassifiedDomainsManager,
        habitRuleManager: HabitRuleManager,
        cloudFilterSettings: CloudFilterSettings,
        alertSettings: GuardianAlertSettings,
        ownerUidResolver: AppUidResolver,
        mitmExemptUids: Set<Int>,
        isProxyUnavailable: () -> Boolean,
    ) {
        val query = DnsMessage.parseQuery(packet.payload)
        if (query == null) {
            Log.d(TAG, "DNS: unparseable query (${packet.payload.size} bytes) from ${packet.sourceAddress}:${packet.sourcePort}")
            return
        }
        // A MITM-exempt app (see mitmExemptUids above -- now just the curated
        // MitmExemptManager list, not "everything except Chrome") gets NONE of
        // mitm_nsfw_addon.py's page-content-level review, only whatever the DNS-level cloud
        // filter decides from the domain name alone -- which is deliberately permissive about
        // ambiguous-but-not-known-bad domains, because it normally trusts the MITM hop to catch a
        // genuinely bad page on an otherwise-fine domain. An unresolvable owner UID (older
        // Android, or the lookup itself failing) fails toward the STRICT path too, not the lenient
        // one, matching "assume unknown = discourage" rather than "assume unknown = safe".
        val ownerUid = ownerUidResolver.ownerUid(
            packet.sourceAddress, packet.sourcePort, packet.destinationAddress, packet.destinationPort,
            protocol = OsConstants.IPPROTO_UDP,
        )
        val isMitmExempt = ownerUid == null || ownerUid in mitmExemptUids
        // The guardian's own dashboard-configured blockedWebsites for THIS device is always
        // enforced -- an intentional, per-device rule they set directly (DNS is the only
        // enforcement path for a domain-only entry, see CustomBlocklistManager's doc), not an
        // incidental extra filtering layer. See blocklist.isCustomBlocked's own doc. Same stance
        // for a dashboard habit rule that targets a website instead of an app (targetType
        // "website") -- an explicit guardian-authored condition, not a coarse fallback list, so
        // it's checked unconditionally here too, same as the app-targeted rules' package
        // suspension isn't gated on proxy availability either.
        val customBlocked = blocklist.isCustomBlocked(query.questionName) ||
            habitRuleManager.isWebsiteCurrentlyBlocked(query.questionName)
        // Every device on the account gets the SAME blocking decision for everything else: the
        // MITM proxy's page-content-aware review (mitm_nsfw_addon.py), same as macOS. Neither
        // local list here (blocklist's curated public hosts files, classifiedDomains' coarser AI
        // guess) runs unconditionally anymore -- a domain that only tripped one of these
        // client-side lists but that the proxy itself wouldn't block used to get blocked on
        // Android and nowhere else, which is exactly the inconsistency this condition exists to
        // prevent. They're consulted ONLY when the proxy isn't going to make (or can't make) that
        // decision for this flow at all: a MITM-exempt app (no page-content review ever happens
        // for it, on any device) or a real proxy outage (isProxyUnavailable) -- see FAMILY_DNS's
        // useStrictDns fallback below for the same "no proxy nuance available, fail toward more
        // restrictive" reasoning applied to the DNS resolver choice.
        val fallbackBlocked = (isMitmExempt || isProxyUnavailable()) &&
            (blocklist.isPublicListBlocked(query.questionName) || classifiedDomains.isBlocked(query.questionName))
        val blocked = customBlocked || fallbackBlocked
        // Merely visiting a blocked site (a rule-gated domain like a habit-locked website, or a
        // domain-list/AI-classified filtering hit) is never reported on its own -- only when the
        // domain itself contains an actual trigger word, mirroring mitm_nsfw_addon.py's
        // "don't report a bare block" rule on the proxy side. DNS only ever gives us the hostname,
        // never a full URL/page, so that's the only text there is to check here.
        if (blocked) {
            val questionName = query.questionName.lowercase(Locale.US)
            val words = alertSettings.triggerWords()
            val hit = words.firstOrNull { word ->
                Regex("\\b${Regex.escape(word.lowercase(Locale.US))}\\b").containsMatchIn(questionName)
            }
            if (hit != null) {
                scope.launch {
                    runCatching {
                        AlertReporter(applicationContext).report(
                            type = "VPN_BLOCK",
                            details = "\"$hit\" seen in blocked ${query.questionName}",
                            severity = AlertSeverity.WARNING,
                            debounceKey = "VPN_BLOCK|${query.questionName}",
                        )
                    }.onFailure { Log.w(TAG, "VPN block alert failed", it) }
                }
            }
        }
        // Without the MITM safety net, "less restrictive at the domain level" would just mean
        // unfiltered for anything not already on a static blocklist -- exactly the loophole a
        // non-MITM'd app becomes. So a MITM-exempt app's queries skip the smart cloud filter
        // entirely and go straight to Cloudflare Family (blunter, but blocks known-bad categories
        // with no page-content nuance needed) -- a normal (non-exempt) app's DNS query is unaffected.
        val useStrictDns = !blocked && isMitmExempt
        val response = if (blocked) {
            DnsMessage.buildBlockedResponse(packet.payload)
        } else if (useStrictDns) {
            forwardToHost(packet.payload, FAMILY_DNS, DNS_PORT)
        } else {
            forwardQuery(packet.payload, cloudFilterSettings)
        }
        if (response == null) {
            Log.d(TAG, "DNS: '${query.questionName}' upstream lookup failed/timed out -- no reply sent")
            return
        }
        if (!blocked) {
            // Best-effort: lets TcpRelayManager's proxy CONNECT use a real hostname for a flow to
            // one of these IPs instead of a bare IP. See dnsAnswerHostnameCache's own comment for
            // why this is a nicety, not a correctness requirement.
            DnsMessage.parseAnswerIPv4s(response).forEach { ip -> dnsAnswerHostnameCache[ip] = query.questionName }
        }
        Log.d(TAG, "DNS: '${query.questionName}' blocked=$blocked -> replying with ${response.size} bytes")

        try {
            writeToTun(packet.buildUdpReply(response))
        } catch (error: IOException) {
            Log.w(TAG, "Failed writing DNS reply to tun", error)
        }
    }

    /**
     * Forwards a non-locally-blocked query to the cloud filter first (the Canopy-style category
     * filter is the primary decision-maker for everything the local list doesn't already know
     * about). Two different fallbacks depending on *why* the cloud filter isn't answering: if it's
     * unconfigured/deliberately disabled by the Guardian, [UPSTREAM_DNS] (permissive) is the right
     * choice -- that's an intentional policy decision, not a fault. If it's enabled but
     * unreachable (a real outage), fall back to [FAMILY_DNS] (stricter) instead -- the device is
     * about to lose the AI/page-content-aware cloud filter's nuance entirely, so failing toward
     * MORE restrictive during that window is the safer default, same "assume unknown = discourage"
     * philosophy as the `useStrictDns` branch in [handleDnsPacket].
     */
    private fun forwardQuery(queryBytes: ByteArray, cloudFilterSettings: CloudFilterSettings): ByteArray? {
        if (cloudFilterSettings.isEnabled()) {
            val cloudResponse = forwardToHost(queryBytes, cloudFilterSettings.host(), cloudFilterSettings.port())
            if (cloudResponse != null) return cloudResponse
            Log.w(TAG, "Cloud filter unreachable -- falling back to stricter Family DNS")
            return forwardToHost(queryBytes, FAMILY_DNS, DNS_PORT)
        }
        return forwardToHost(queryBytes, UPSTREAM_DNS, DNS_PORT)
    }

    /** Uses a protect()-ed socket so this outbound query doesn't loop back into the VPN itself. */
    private fun forwardToHost(queryBytes: ByteArray, host: String, port: Int): ByteArray? {
        return try {
            DatagramSocket().use { socket ->
                protect(socket)
                socket.soTimeout = UPSTREAM_TIMEOUT_MS
                val upstream = InetSocketAddress(InetAddress.getByName(host), port)
                socket.send(DatagramPacket(queryBytes, queryBytes.size, upstream))
                val responseBuffer = ByteArray(2048)
                val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
                socket.receive(responsePacket)
                responseBuffer.copyOf(responsePacket.length)
            }
        } catch (error: IOException) {
            Log.w(TAG, "DNS query to $host:$port failed", error)
            null
        }
    }

    override fun onDestroy() {
        running.set(false)
        connectionScope?.cancel()
        scope.cancel() // tears down every in-flight TCP/UDP relay connection too, not just the read loop
        relayExecutor.shutdownNow()
        tunInterface?.let { runCatching { it.close() } }
        tunInterface = null
        super.onDestroy()
    }

    override fun onRevoke() {
        stopSelf()
        super.onRevoke()
    }

    /**
     * As quiet as Android's foreground-service requirements allow -- see the identical reasoning
     * on `ProtectionEnforcementService.buildNotification`. This notification can't be dropped
     * (the VPN would be killed / flagged non-compliant without an active FGS notification), but
     * nothing requires it to make noise: `IMPORTANCE_MIN` plus the channel's own
     * `setSound(null, null)`/`enableVibration(false)`/`setShowBadge(false)` gets no sound/
     * vibration/heads-up/badge -- the authoritative source of truth for silence on API 28+
     * (channels are mandatory there); the plain framework `Notification.Builder` used here has no
     * separate per-notification "silent" flag (that's AndroidX `NotificationCompat.Builder`-only).
     * `_v2` channel suffix for the same reason -- channel
     * settings are locked in after first creation, so an existing install's already-created
     * louder channel needs a new ID to pick up these quieter defaults.
     */
    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Content filter VPN active", NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            },
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Otterling")
            .setContentText("")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    companion object {
        private const val TAG = "VpnFilterService"
        private const val CHANNEL_ID = "vpn_content_filter_v2"
        private const val NOTIFICATION_ID = 1002
        // See applyBypassApps: apps whose traffic can't be fixed by a per-flow MITM exemption
        // (local-network discovery, peer-to-peer sockets) get excluded from the tunnel entirely.
        // Not private: [VpnFilterManager] also needs this set, as the DPM lockdown allowlist --
        // see that class for why Builder.addDisallowedApplication alone isn't sufficient here.
        val FULLY_VPN_EXEMPT_PACKAGES = setOf(
            "com.google.android.projection.gearhead", // Android Auto
        )
        private const val VIRTUAL_IP = "10.111.222.1"
        private const val DNS_SERVER_IP = "10.111.222.2"
        private const val DNS_PORT = 53
        // HTTPS's UDP port when carried over QUIC/HTTP3 -- dropped while the filter proxy is on;
        // see the drop site in runPacketLoop for why.
        private const val QUIC_PORT = 443
        private const val UPSTREAM_DNS = "1.1.1.1"
        // Cloudflare's "1.1.1.1 for Families" -- blocks malware + adult content at the DNS level
        // with no page-content nuance needed, unlike the smart cloud filter. Used for MITM-exempt
        // apps' DNS queries (see handleDnsPacket's useStrictDns) -- same resolver macOS's
        // DNSEnforcer already uses as ITS stricter/no-cloud-filter-available fallback, so this is
        // consistent with an already-established pattern in this project, not a new one.
        private const val FAMILY_DNS = "1.1.1.3"
        private const val UPSTREAM_TIMEOUT_MS = 5_000
        // Real Ethernet/Wi-Fi framing never sees this value: these "packets" only ever travel
        // between our relay code and the local kernel over the virtual tun device, since real
        // segmentation onto the actual network happens transparently inside the OS's own TCP/IP
        // stack when we call socket.write() on a real Socket/DatagramSocket. A too-small MTU here
        // (this used to be the standard Ethernet 1500) forces every relayed byte through many more
        // 1400-ish-byte packets than necessary, and each one pays a fixed per-packet cost (mutex
        // acquisition, a tun write() syscall, a coroutine dispatch) -- that fixed cost, multiplied
        // by many more packets, was capping real download throughput to a small fraction of the
        // underlying link's actual speed even once flow control/window scaling were fixed.
        private const val MTU = 16384
        // Cap on dnsAnswerHostnameCache -- generous for a single phone's worth of concurrently
        // "recently resolved" hostnames while still bounding memory.
        private const val MAX_DNS_HOSTNAME_CACHE_ENTRIES = 2_000
        // See runPacketLoop's zero-length-read handling: how long to back off when the tun fd
        // returns 0 bytes without blocking, instead of busy-spinning on it.
        private const val EMPTY_READ_BACKOFF_MS = 20L
        // How long to wait before rebuilding the tunnel after an unexpected packet-loop exit or a
        // failed establish(). 3s balances fast recovery against not hammering establish() in a
        // tight loop when the network genuinely isn't ready yet (e.g. right after boot).
        private const val RESTART_BACKOFF_MS = 3_000L

        /** Public DoH resolver IPs -- refused (RST/dropped) so apps can't dodge filtering by
         *  hardcoding their own DNS instead of using the (filtered) system resolver set above.
         *  Necessarily incomplete (hundreds of DoH endpoints exist beyond these well-known ones) --
         *  when the filter proxy is on, DoH over 443 to *any* IP is still caught by the existing
         *  "proxy every TCP 80/443 flow" behavior regardless of this list; this only matters when
         *  the proxy is off. [DOT_PORT] below is the complete fix for DNS-over-TLS specifically,
         *  since (unlike DoH sharing port 443 with ordinary HTTPS) blocking that port outright
         *  carries no such caveat -- nothing else legitimately uses it. */
        private val KNOWN_DOH_IPS = setOf(
            "1.1.1.1", "1.0.0.1", // Cloudflare
            "8.8.8.8", "8.8.4.4", // Google
            "9.9.9.9", "149.112.112.112", // Quad9
            "208.67.222.222", "208.67.220.220", // OpenDNS
        )

        /** Standard DNS-over-TLS port -- blocked outright regardless of destination IP, the same
         *  way UDP 443/QUIC is blocked outright when the filter proxy is on (see class doc
         *  comment). Unlike the DoH IP list above, this closes the DoT gap completely: no static
         *  IP list can keep up with every DoT resolver that exists, but no legitimate non-DoT
         *  traffic uses this port, so blocking it by port number has no such gap and no
         *  false-positive risk. */
        private const val DOT_PORT = 853

        private const val EXTRA_REESTABLISH = "reestablish"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, VpnFilterService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VpnFilterService::class.java))
        }

        /** Rebuilds the tunnel in place so a changed bypass/blocklist takes effect immediately,
         * without dropping the always-on registration. No-op if the service isn't running. */
        fun reestablish(context: Context) {
            context.startForegroundService(
                Intent(context, VpnFilterService::class.java).putExtra(EXTRA_REESTABLISH, true),
            )
        }
    }
}
