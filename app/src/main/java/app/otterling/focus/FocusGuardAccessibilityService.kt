package app.otterling.focus

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import app.otterling.alerts.AlertReporter
import app.otterling.alerts.AlertSeverity
import app.otterling.alerts.GuardianAlertSettings
import app.otterling.content.CustomBlocklistManager
import app.otterling.content.UrlPathBlockEnforcer
import app.otterling.monitoring.ProtectionController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * One shared accessibility service backing all the self-improvement features: it watches which
 * app is in the foreground and (a) shows [FrictionActivity] before "mindful" apps and (b) ticks
 * [AppTimeBudgetManager] counters and heuristically detects YouTube-Shorts-style sub-features for
 * time budgets, plus bounces the Facebook Reels player. Habit completion data comes entirely from
 * the HabitShare REST API (see [HabitShareSyncManager]); this service doesn't read the screen for
 * it, but it does use its foreground-app knowledge to poll that API once a second *while HabitShare
 * itself is open* (see [startHabitPolling]) so a habit ticked in HabitShare unblocks its gated app
 * almost immediately, instead of waiting up to the background sync interval. Must be enabled
 * manually by the user in Settings > Accessibility -- there's no way to grant this programmatically.
 */
class FocusGuardAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickJob: Job? = null
    private var habitPollJob: Job? = null
    private var currentPackage: String? = null
    private var lastReelsBounceMillis = 0L
    private var lastPathBlockMillis = 0L
    private var lastTriggerScanMillis = 0L

    private lateinit var mindfulAppManager: MindfulAppManager
    private lateinit var budgetManager: AppTimeBudgetManager
    private lateinit var habitShareSyncManager: HabitShareSyncManager
    private lateinit var customBlocklist: CustomBlocklistManager
    private lateinit var alertSettings: GuardianAlertSettings

    override fun onServiceConnected() {
        super.onServiceConnected()
        mindfulAppManager = MindfulAppManager(applicationContext)
        budgetManager = AppTimeBudgetManager(applicationContext)
        habitShareSyncManager = HabitShareSyncManager(applicationContext)
        customBlocklist = CustomBlocklistManager(applicationContext)
        alertSettings = GuardianAlertSettings(applicationContext)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return
        }

        if (packageName != currentPackage) {
            currentPackage = packageName
            onForegroundChanged(packageName)
        }

        // Facebook stays usable; only its full-screen Reels player gets bounced. Checked on every
        // content change (not just app switch) because you can swipe into Reels without leaving
        // Facebook, so there's no window-state-change event to key off.
        if (packageName in FACEBOOK_PACKAGES) {
            blockReelsIfPresent()
        }
        if (packageName in UrlPathBlockEnforcer.BROWSER_PACKAGES ||
            packageName in UrlPathBlockEnforcer.YOUTUBE_PACKAGES
        ) {
            blockPathRulesIfPresent(packageName)
            checkTriggerWords(packageName)
        }
    }

    /**
     * Enforces custom blocklist path rules (e.g. youtube.com/shorts). Domain-only rules are handled
     * by the VPN; path rules need the address bar / in-app Shorts UI because HTTPS hides paths.
     */
    private fun blockPathRulesIfPresent(packageName: String) {
        if (!ProtectionController(applicationContext).isEnabled()) return
        val now = System.currentTimeMillis()
        if (now - lastPathBlockMillis < PATH_BLOCK_DEBOUNCE_MS) return
        val pathEntries = customBlocklist.pathEntries()
        if (pathEntries.isEmpty()) return

        if (packageName in UrlPathBlockEnforcer.YOUTUBE_PACKAGES &&
            UrlPathBlockEnforcer.shouldBlockYoutubeShorts(pathEntries) &&
            isInSubFeature()
        ) {
            lastPathBlockMillis = now
            Log.d(TAG, "YouTube Shorts blocked by path rule -- bouncing")
            performGlobalAction(GLOBAL_ACTION_BACK)
            Toast.makeText(applicationContext, "This page is blocked.", Toast.LENGTH_SHORT).show()
            return
        }

        if (packageName !in UrlPathBlockEnforcer.BROWSER_PACKAGES) return
        val root = rootInActiveWindow ?: return
        val urlText = UrlPathBlockEnforcer.extractBrowserUrl(root) ?: return
        if (!UrlPathBlockEnforcer.shouldBlockBrowserUrl(pathEntries, urlText)) return
        lastPathBlockMillis = now
        Log.d(TAG, "Browser URL blocked by path rule: $urlText")
        performGlobalAction(GLOBAL_ACTION_BACK)
        Toast.makeText(applicationContext, "This page is blocked.", Toast.LENGTH_SHORT).show()
    }

    /** Presses Back to leave the Reels player the moment it's detected, keeping the rest of
     * Facebook available. Debounced so a single detection doesn't fire a burst of Back presses
     * before the UI has had a chance to transition away. */
    private fun blockReelsIfPresent() {
        if (!ProtectionController(applicationContext).isEnabled()) return
        val now = System.currentTimeMillis()
        if (now - lastReelsBounceMillis < REELS_BOUNCE_DEBOUNCE_MS) return
        val root = rootInActiveWindow ?: return
        val screenHeight = resources.displayMetrics.heightPixels
        val screenWidth = resources.displayMetrics.widthPixels
        if (!isInFacebookReelsPlayer(root, screenWidth, screenHeight)) return
        lastReelsBounceMillis = now
        Log.d(TAG, "Facebook Reels player detected -- bouncing")
        performGlobalAction(GLOBAL_ACTION_BACK)
        Toast.makeText(applicationContext, "Reels is blocked.", Toast.LENGTH_SHORT).show()
    }

    /**
     * True when Facebook is showing the fullscreen Reels player -- either via the Reels tab
     * (which puts a "Reels" title at the top) or by tapping a video/reel from the main feed
     * (which often has no "Reels" title, but uses a near-fullscreen viewer with reel-specific
     * view ids / content descriptions). Deliberately avoids the feed's inline reel cards and the
     * bottom-nav "Reels" tab label.
     */
    private fun isInFacebookReelsPlayer(
        root: AccessibilityNodeInfo,
        screenWidth: Int,
        screenHeight: Int,
    ): Boolean {
        if (hasReelsTitleAtTop(root, screenHeight)) return true
        return hasFullscreenReelsViewer(root, screenWidth, screenHeight)
    }

    /**
     * True if a "Reels" title sits near the very top of the screen -- the header the Reels *tab*
     * player shows. Position-gated so the bottom-nav "Reels" tab doesn't count.
     *
     * Bounded BFS (not unbounded recursion) for the same reason as [hasFullscreenReelsViewer]'s
     * own queue -- this runs on every Facebook content-change event (roughly every
     * [REELS_BOUNCE_DEBOUNCE_MS]), and Facebook's view hierarchy is routinely hundreds of nodes
     * deep. An unbounded walk here was allocating an AccessibilityNodeInfo per node with no cap at
     * all, which is exactly what showed up as recurring OutOfMemoryError crashes in
     * AccessibilityNodeInfo.getChild() after 1-4 hours of real usage.
     */
    private fun hasReelsTitleAtTop(node: AccessibilityNodeInfo, screenHeight: Int): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(node)
        var visited = 0
        while (queue.isNotEmpty() && visited < REELS_SCAN_MAX_NODES) {
            val current = queue.removeFirst()
            visited++
            val label = current.text?.toString()?.trim() ?: current.contentDescription?.toString()?.trim()
            if (label != null && REELS_TITLE_PATTERN.matches(label)) {
                val bounds = Rect()
                current.getBoundsInScreen(bounds)
                if (!bounds.isEmpty && bounds.top < screenHeight * REELS_TITLE_TOP_FRACTION) return true
            }
            for (i in 0 until current.childCount) {
                queue.add(current.getChild(i) ?: continue)
            }
        }
        return false
    }

    /**
     * Detects the fullscreen Reels viewer opened from the feed (or anywhere else that doesn't
     * show a "Reels" title). Requires BOTH a reel/viewer resource-id or content-desc signal AND a
     * near-fullscreen media surface, so inline feed cards (small mid-screen previews) don't trip it.
     */
    private fun hasFullscreenReelsViewer(
        root: AccessibilityNodeInfo,
        screenWidth: Int,
        screenHeight: Int,
    ): Boolean {
        var hasReelSignal = false
        var hasFullscreenSurface = false
        val minHeight = (screenHeight * REELS_FULLSCREEN_HEIGHT_FRACTION).toInt()
        val minWidth = (screenWidth * REELS_FULLSCREEN_WIDTH_FRACTION).toInt()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < REELS_SCAN_MAX_NODES) {
            val node = queue.removeFirst()
            visited++
            val id = node.viewIdResourceName?.lowercase().orEmpty()
            val desc = (node.contentDescription?.toString() ?: "").lowercase()
            val text = (node.text?.toString() ?: "").lowercase()
            if (REELS_VIEWER_ID_HINTS.any { id.contains(it) } ||
                REELS_VIEWER_DESC_HINTS.any { desc.contains(it) }
            ) {
                hasReelSignal = true
            }
            // "Reel by …" / "Video by …" author lines often appear in the feed-opened player.
            if (REELS_AUTHOR_LINE_PATTERN.containsMatchIn(desc) ||
                REELS_AUTHOR_LINE_PATTERN.containsMatchIn(text)
            ) {
                hasReelSignal = true
            }
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (!bounds.isEmpty &&
                bounds.height() >= minHeight &&
                bounds.width() >= minWidth &&
                bounds.top < screenHeight * 0.2f
            ) {
                // A near-fullscreen surface starting near the top -- typical of the Reels player,
                // not an inline feed card.
                val className = node.className?.toString().orEmpty()
                if (className.contains("View", ignoreCase = true) ||
                    className.contains("Video", ignoreCase = true) ||
                    className.contains("Image", ignoreCase = true) ||
                    id.contains("video") ||
                    id.contains("media") ||
                    id.contains("reel")
                ) {
                    hasFullscreenSurface = true
                }
            }
            if (hasReelSignal && hasFullscreenSurface) return true
            for (i in 0 until node.childCount) {
                queue.add(node.getChild(i) ?: continue)
            }
        }
        // Strong viewer ids alone are enough -- Facebook's reels_viewer_* hierarchy is specific
        // to the fullscreen player and isn't used for tiny inline feed previews.
        return hasStrongReelsViewerId(root)
    }

    /** Bounded BFS, same reasoning as [hasReelsTitleAtTop] -- this was the other unbounded
     *  traversal contributing to the OOM crashes. */
    private fun hasStrongReelsViewerId(node: AccessibilityNodeInfo): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(node)
        var visited = 0
        while (queue.isNotEmpty() && visited < REELS_SCAN_MAX_NODES) {
            val current = queue.removeFirst()
            visited++
            val id = current.viewIdResourceName?.lowercase().orEmpty()
            if (REELS_STRONG_VIEWER_ID_HINTS.any { id.contains(it) }) return true
            for (i in 0 until current.childCount) {
                queue.add(current.getChild(i) ?: continue)
            }
        }
        return false
    }

    private fun onForegroundChanged(packageName: String) {
        tickJob?.cancel()
        // Poll HabitShare's API every second only while HabitShare is the foreground app -- this is
        // when a habit is most likely being ticked, and it's the one moment fast feedback matters
        // (an app gated on that habit should unblock right away). Leaving HabitShare cancels it, so
        // the once-a-second network calls are bounded to actual in-app time; the background sync
        // still covers everything else.
        habitPollJob?.cancel()
        if (packageName == HabitTrackerScanner.HABITSHARE_PACKAGE_NAME) {
            startHabitPolling()
        }
        scope.launch {
            if (!ProtectionController(applicationContext).isEnabled()) return@launch
            maybeReportWatchedApp(packageName)
            val mindfulApp = mindfulAppManager.apps().find { it.packageName == packageName }
            if (mindfulApp != null && !mindfulAppManager.isWithinGracePeriod(packageName)) {
                withContext(Dispatchers.Main) { launchFriction(packageName, mindfulApp.delaySeconds) }
                return@launch
            }

            if (budgetManager.budget(packageName) != null) {
                if (budgetManager.isOverBudget(packageName)) {
                    withContext(Dispatchers.Main) { kickToHome("Daily limit reached for this app.") }
                    return@launch
                }
                startTicking(packageName)
            }
        }
    }

    private suspend fun maybeReportWatchedApp(packageName: String) {
        if (!::alertSettings.isInitialized) return
        if (packageName !in alertSettings.watchedPackages()) return
        runCatching {
            AlertReporter(applicationContext).report(
                type = "WATCHED_APP",
                details = "Opened $packageName",
                severity = AlertSeverity.WARNING,
                debounceKey = "WATCHED_APP|$packageName",
            )
        }.onFailure { Log.w(TAG, "Watched-app alert failed", it) }
    }

    /**
     * Scans browser omnibox / YouTube a11y text for configured trigger words and reports yellow hits.
     */
    private fun checkTriggerWords(packageName: String) {
        if (!ProtectionController(applicationContext).isEnabled()) return
        if (!::alertSettings.isInitialized) return
        val words = alertSettings.triggerWords()
        if (words.isEmpty()) return
        val now = System.currentTimeMillis()
        if (now - lastTriggerScanMillis < TRIGGER_SCAN_DEBOUNCE_MS) return
        lastTriggerScanMillis = now

        val root = rootInActiveWindow ?: return
        val texts = mutableListOf<String>()
        val ids = mutableListOf<String>()
        collectNodeInfo(root, texts = texts, resourceIds = ids, maxNodes = 180)
        val haystack = buildString {
            if (packageName in UrlPathBlockEnforcer.BROWSER_PACKAGES) {
                UrlPathBlockEnforcer.extractBrowserUrl(root)?.let { append(it).append(' ') }
            }
            texts.forEach { append(it).append(' ') }
        }.lowercase(Locale.US)
        if (haystack.isBlank()) return

        // Word-boundary, not plain substring -- a bare short word like "ass" or "sex" would
        // otherwise match inside "class"/"assignment"/"Sussex" and fire constantly on unrelated
        // text. \b works the same way for a multi-word phrase (e.g. "hardcore sex") since it only
        // asserts a boundary at the very start/end of the whole phrase, not at the internal space.
        val hit = words.firstOrNull { word ->
            Regex("\\b${Regex.escape(word.lowercase(Locale.US))}\\b").containsMatchIn(haystack)
        } ?: return

        scope.launch {
            runCatching {
                AlertReporter(applicationContext).report(
                    type = "TRIGGER_WORD",
                    details = "\"$hit\" seen in $packageName",
                    severity = AlertSeverity.WARNING,
                    debounceKey = "TRIGGER_WORD|$hit|$packageName",
                )
            }.onFailure { Log.w(TAG, "Trigger-word alert failed", it) }
        }
    }

    /**
     * Once-a-second HabitShare sync, running only while HabitShare is foreground. Each iteration
     * awaits the sync before delaying, so a slow (or timing-out) network call can never stack up
     * overlapping requests -- the effective rate is at most one in flight at a time. [syncIfConnected]
     * is a cheap no-op when no account is connected, and reuses the cached auth token, so a poll is
     * just a single GET. The loop exits as soon as the user leaves HabitShare.
     */
    private fun startHabitPolling() {
        habitPollJob = scope.launch {
            while (isActive && currentPackage == HabitTrackerScanner.HABITSHARE_PACKAGE_NAME) {
                runCatching { habitShareSyncManager.syncIfConnected() }
                    .onFailure { Log.w(TAG, "HabitShare fast-poll sync failed", it) }
                delay(HABIT_POLL_MILLIS)
            }
        }
    }

    private fun startTicking(packageName: String) {
        tickJob = scope.launch {
            while (isActive && currentPackage == packageName) {
                delay(TICK_MILLIS)
                if (currentPackage != packageName) break
                val inSub = isInSubFeature()
                budgetManager.addTick(packageName, TICK_SECONDS, inSub)
                if (budgetManager.isOverBudget(packageName)) {
                    withContext(Dispatchers.Main) { kickToHome("Daily limit reached for this app.") }
                    break
                }
            }
        }
    }

    /**
     * Heuristic only: looks for resource-ids containing "shorts"/"reel", which is how YouTube's
     * Shorts player has historically been named internally. If this over- or under-fires on your
     * installed YouTube version, use the debug capture in Settings to see actual ids and adjust
     * [SUB_FEATURE_HINTS].
     */
    private fun isInSubFeature(): Boolean {
        val root = rootInActiveWindow ?: return false
        val ids = mutableListOf<String>()
        collectNodeInfo(root, texts = mutableListOf(), resourceIds = ids, maxNodes = 200)
        return ids.any { id -> SUB_FEATURE_HINTS.any { hint -> id.contains(hint, ignoreCase = true) } }
    }

    /**
     * Bounded BFS (not unbounded recursion) -- [maxNodes] used to only cap how much text/id data
     * got collected, not how many nodes got visited, so a subtree full of empty-text/no-id
     * container views (extremely common) recursed through all of them anyway, each still
     * allocating a fresh AccessibilityNodeInfo via getChild(). That was one of the causes behind
     * this service's recurring OutOfMemoryError crashes; this now stops at [maxNodes] *visited*
     * nodes regardless of what they contributed.
     */
    private fun collectNodeInfo(
        node: AccessibilityNodeInfo,
        texts: MutableList<String>,
        resourceIds: MutableList<String>,
        maxNodes: Int,
    ) {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(node)
        var visited = 0
        while (queue.isNotEmpty() && visited < maxNodes) {
            val current = queue.removeFirst()
            visited++
            current.text?.toString()?.takeIf { it.isNotBlank() }?.let { texts.add(it) }
            current.viewIdResourceName?.let { resourceIds.add(it) }
            for (i in 0 until current.childCount) {
                queue.add(current.getChild(i) ?: continue)
            }
        }
    }

    private fun launchFriction(packageName: String, delaySeconds: Int) {
        val intent = Intent(this, FrictionActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(FrictionActivity.EXTRA_PACKAGE_NAME, packageName)
            putExtra(FrictionActivity.EXTRA_DELAY_SECONDS, delaySeconds)
        }
        startActivity(intent)
    }

    private fun kickToHome(message: String) {
        performGlobalAction(GLOBAL_ACTION_HOME)
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        tickJob?.cancel()
        habitPollJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "FocusGuardAccessibility"
        const val TICK_MILLIS = 5_000L
        const val TICK_SECONDS = 5
        const val HABIT_POLL_MILLIS = 1_000L
        const val PATH_BLOCK_DEBOUNCE_MS = 800L
        const val TRIGGER_SCAN_DEBOUNCE_MS = 2_000L
        val SUB_FEATURE_HINTS = listOf("shorts", "reel")

        // Facebook (main app + Lite) -- Reels blocking is scoped to these so the rest of the app
        // keeps working normally.
        val FACEBOOK_PACKAGES = setOf("com.facebook.katana", "com.facebook.lite")
        const val REELS_BOUNCE_DEBOUNCE_MS = 800L
        // The Reels tab shows a "Reels" title in the top bar; only a title within this fraction
        // of the screen height from the top counts, so the bottom-nav tab doesn't trigger it.
        const val REELS_TITLE_TOP_FRACTION = 0.18
        val REELS_TITLE_PATTERN = Regex("""^reels$""", RegexOption.IGNORE_CASE)
        // Feed-opened Reels player: near-fullscreen media surface + reel signal, or a strong
        // reels_viewer resource id on its own.
        const val REELS_FULLSCREEN_HEIGHT_FRACTION = 0.65f
        const val REELS_FULLSCREEN_WIDTH_FRACTION = 0.75f
        const val REELS_SCAN_MAX_NODES = 250
        val REELS_VIEWER_ID_HINTS = listOf(
            "reels_viewer",
            "reel_viewer",
            "reels_video",
            "reel_video",
            "reels_playback",
            "fb_reels",
            "reels_watch",
        )
        val REELS_STRONG_VIEWER_ID_HINTS = listOf(
            "reels_viewer",
            "reel_viewer",
            "reels_playback",
            "fb_reels_viewer",
        )
        val REELS_VIEWER_DESC_HINTS = listOf(
            "reel video",
            "reels video",
            "playing reel",
            "pause reel",
            "close reels",
            "exit reels",
        )
        val REELS_AUTHOR_LINE_PATTERN = Regex("""\b(reel|reels)\b.*\bby\b|\bby\b.*\b(reel|reels)\b""")
    }
}
