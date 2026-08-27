package app.otterling.focus

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import app.otterling.alerts.AlertReporter
import app.otterling.alerts.AlertSeverity
import app.otterling.alerts.GuardianAlertSettings
import app.otterling.content.AppSuspensionManager
import app.otterling.content.CustomBlocklistManager
import app.otterling.content.DashboardConfigStore
import app.otterling.content.UrlPathBlockEnforcer
import app.otterling.monitoring.ScreenshotUploader
import app.otterling.tamper.TamperEventLogger
import app.otterling.monitoring.ProtectionController
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
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
    private var screenshotJob: Job? = null
    private var currentPackage: String? = null
    private var lastReelsBounceMillis = 0L
    private var lastPathBlockMillis = 0L
    private var lastTriggerScanMillis = 0L

    /** Own package + the current launcher -- resolved once, not per capture. Screenshotting our
     *  own Settings UI or the home screen/lock screen is wasted uploads and a mild privacy smell. */
    private val screenshotSkipPackages: Set<String> by lazy {
        val launcher = packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName
        setOfNotNull(packageName, launcher, "com.android.systemui", "com.android.keyguard")
    }

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
        screenshotJob?.cancel()
        startScreenshotLoop(packageName)
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

    /**
     * Visual filtering: periodically uploads a screenshot of the foreground app to the server for
     * NSFW classification (see `nsfw_image_classifier.py` / `lockprofile_service.py`) -- processed
     * server-side, not on-device, per the dashboard's `visualFilterEnabled` setting. Same
     * app-switch-triggered, once-per-interval-while-foregrounded loop shape as [startTicking]/
     * [startHabitPolling]: capturing immediately on entering the loop covers "on app switch", and
     * the delay at the end of each iteration bounds it to at most once per
     * [visualFilterIntervalMillis] for a single long session in one app. `takeScreenshot` is API
     * 30+ only; below that this feature is simply a no-op for the (shrinking) fraction of devices
     * on Android 10 or earlier.
     */
    private fun startScreenshotLoop(packageName: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        screenshotJob = scope.launch {
            while (isActive && currentPackage == packageName) {
                captureScreenshotIfAllowed(packageName)
                delay(visualFilterIntervalMillis())
            }
        }
    }

    private fun captureScreenshotIfAllowed(packageName: String) {
        if (!ProtectionController(applicationContext).isEnabled()) return
        if (packageName in screenshotSkipPackages) return
        val snapshot = DashboardConfigStore(applicationContext).snapshot()
        // Missing snapshot (not yet synced) defaults to enabled, same "fail toward more
        // restrictive" stance every other protection field in this file already takes -- only an
        // explicit guardian opt-out (false) skips capture.
        if (snapshot?.optBoolean("visualFilterEnabled", true) == false) return
        val powerManager = getSystemService(PowerManager::class.java)
        if (powerManager?.isInteractive != true) return
        takeScreenshot(
            android.view.Display.DEFAULT_DISPLAY,
            Dispatchers.Default.asExecutor(),
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val bitmap = try {
                        Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                    } finally {
                        result.hardwareBuffer.close()
                    }
                    if (bitmap == null) return
                    val scaled = downscale(bitmap)
                    val jpegBytes = ByteArrayOutputStream().use { stream ->
                        scaled.compress(Bitmap.CompressFormat.JPEG, SCREENSHOT_JPEG_QUALITY, stream)
                        stream.toByteArray()
                    }
                    if (scaled !== bitmap) bitmap.recycle()
                    scaled.recycle()
                    scope.launch { handleCapturedScreenshot(packageName, jpegBytes) }
                }

                override fun onFailure(errorCode: Int) {
                    Log.w(TAG, "Screenshot capture failed: errorCode=$errorCode")
                }
            },
        )
    }

    /** Plenty of resolution for a vision model to judge "is this NSFW"; drastically cuts upload
     *  size/battery vs. full device resolution. */
    private fun downscale(bitmap: Bitmap): Bitmap {
        val maxDimension = maxOf(bitmap.width, bitmap.height)
        if (maxDimension <= SCREENSHOT_MAX_DIMENSION) return bitmap
        val scale = SCREENSHOT_MAX_DIMENSION.toFloat() / maxDimension
        val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private suspend fun handleCapturedScreenshot(packageName: String, jpegBytes: ByteArray) {
        // Guards a fast app-switch racing the async capture callback -- don't act on a stale
        // classification for an app that's no longer foreground.
        if (currentPackage != packageName) return
        val result = ScreenshotUploader.upload(applicationContext, packageName, jpegBytes).getOrNull() ?: return
        if (result.classification != "nsfw") return

        // Prefer the server-computed deadline (keeps the duration dashboard-tunable later without
        // an app update) over a hardcoded client-side constant; clamp for clock skew between
        // phone and server.
        val durationMillis = result.blockUntilMillis
            ?.let { it - System.currentTimeMillis() }
            ?.coerceAtLeast(MIN_NSFW_BLOCK_MILLIS)
            ?: DEFAULT_NSFW_BLOCK_MILLIS
        runCatching { AppSuspensionManager(applicationContext).blockTemporarily(packageName, durationMillis) }
            .onFailure { Log.w(TAG, "Temporary NSFW block failed for $packageName", it) }
        reportNsfwDetection(packageName)
    }

    private suspend fun reportNsfwDetection(packageName: String) {
        runCatching {
            AlertReporter(applicationContext).report(
                type = "NSFW_SCREENSHOT_DETECTED",
                details = "NSFW content detected in $packageName; blocked for 15 minutes",
                severity = AlertSeverity.CRITICAL,
                debounceKey = "NSFW_SCREENSHOT_DETECTED|$packageName",
            )
        }.onFailure { Log.w(TAG, "NSFW screenshot alert failed", it) }
    }

    private fun visualFilterIntervalMillis(): Long {
        val seconds = DashboardConfigStore(applicationContext).snapshot()
            ?.optInt("visualFilterIntervalSeconds", DEFAULT_VISUAL_FILTER_INTERVAL_SECONDS)
            ?: DEFAULT_VISUAL_FILTER_INTERVAL_SECONDS
        return seconds.coerceAtLeast(MIN_VISUAL_FILTER_INTERVAL_SECONDS) * 1000L
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
     * Heuristic only: requires BOTH a near-fullscreen *portrait* video surface starting at the
     * very top of the screen ([hasFullscreenPortraitVideoSurface]) AND a "shorts"/"reel" id or
     * content-description signal somewhere in the same tree. Neither signal alone is safe: the
     * surface shape alone would also match a regular video expanded to fullscreen (mostly
     * landscape, but not always); an id/text signal alone would also match YouTube's persistent
     * "Shorts" bottom-nav tab label, which -- unlike Facebook's Reels tab title -- has no special
     * position to gate on since it sits in the same bottom bar on every screen, and would also
     * match an ordinary video whose title happens to contain the word "shorts". That's why this
     * checks content descriptions/ids, not arbitrary on-screen text: Shorts' action rail
     * (like/dislike/comment/share/remix) is icon-only, so those labels only ever exist as content
     * descriptions, never as visible text. A rebranded YouTube client (e.g. Morphe) commonly
     * ships its own resource-id namespace for the chrome around the player but still renders
     * YouTube's real strings/labels, so checking descriptions as well as ids -- the previous
     * behavior only checked ids -- catches forks whose ids don't say "shorts", which matches
     * Shorts continuing to play uninterrupted in Morphe despite the seeded youtube.com/shorts
     * block entry.
     */
    private fun isInSubFeature(): Boolean {
        val root = rootInActiveWindow ?: return false
        if (!hasFullscreenPortraitVideoSurface(root)) return false
        val ids = mutableListOf<String>()
        val descriptions = mutableListOf<String>()
        collectNodeInfo(root, texts = mutableListOf(), resourceIds = ids, descriptions = descriptions, maxNodes = 200)
        val haystack = ids.asSequence() + descriptions.asSequence()
        return haystack.any { value -> SUB_FEATURE_HINTS.any { hint -> value.contains(hint, ignoreCase = true) } }
    }

    /**
     * True if some node is a near-fullscreen, taller-than-wide video/texture surface anchored at
     * the very top of the screen -- the shape of a Shorts (or Reels) player, and NOT the shape of
     * YouTube's regular watch page, whose player is confined to roughly the top 40% of the screen
     * with a scrollable info/comments area below. Bounded BFS, same OOM reasoning as
     * [collectNodeInfo]/[hasFullscreenReelsViewer].
     */
    private fun hasFullscreenPortraitVideoSurface(root: AccessibilityNodeInfo): Boolean {
        val screenHeight = resources.displayMetrics.heightPixels
        val screenWidth = resources.displayMetrics.widthPixels
        val minHeight = (screenHeight * SHORTS_FULLSCREEN_HEIGHT_FRACTION).toInt()
        val minWidth = (screenWidth * SHORTS_FULLSCREEN_WIDTH_FRACTION).toInt()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < SHORTS_SCAN_MAX_NODES) {
            val node = queue.removeFirst()
            visited++
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (!bounds.isEmpty &&
                bounds.height() >= minHeight &&
                bounds.width() >= minWidth &&
                bounds.height() > bounds.width() &&
                bounds.top < screenHeight * 0.1f
            ) {
                val className = node.className?.toString().orEmpty()
                if (className.contains("View", ignoreCase = true) ||
                    className.contains("Video", ignoreCase = true) ||
                    className.contains("Texture", ignoreCase = true) ||
                    className.contains("Surface", ignoreCase = true)
                ) {
                    return true
                }
            }
            for (i in 0 until node.childCount) {
                queue.add(node.getChild(i) ?: continue)
            }
        }
        return false
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
        descriptions: MutableList<String> = mutableListOf(),
    ) {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(node)
        var visited = 0
        while (queue.isNotEmpty() && visited < maxNodes) {
            val current = queue.removeFirst()
            visited++
            current.text?.toString()?.takeIf { it.isNotBlank() }?.let { texts.add(it) }
            current.viewIdResourceName?.let { resourceIds.add(it) }
            current.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { descriptions.add(it) }
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

    /** Fires when the system interrupts this service (e.g. another accessibility service takes
     *  over, or the framework resets it) -- a faster signal than waiting for the next
     *  [app.otterling.restrictions.AccessibilityGuard.isEnabled] poll, which can lag up to 15
     *  minutes on the WorkManager-only path. */
    override fun onInterrupt() {
        scope.launch {
            runCatching {
                TamperEventLogger(applicationContext).log(
                    type = "ACCESSIBILITY_SERVICE_INTERRUPTED",
                    details = "Accessibility service was interrupted",
                )
            }
        }
    }

    override fun onDestroy() {
        // Best-effort, last-chance signal (same reasoning as
        // DeviceAdminReceiverImpl.onDisabled) -- fires right as the service is torn down, so this
        // uses its own short-lived scope rather than the service's own `scope` field, which gets
        // cancelled two lines down and would silently drop a launch on itself.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                TamperEventLogger(applicationContext).log(
                    type = "ACCESSIBILITY_SERVICE_DESTROYED",
                    details = "Accessibility service process was destroyed",
                )
            }
        }
        tickJob?.cancel()
        habitPollJob?.cancel()
        screenshotJob?.cancel()
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
        // 30s (TESTING -- was 60), matching lockprofile_service.py's own default; only used if
        // the dashboard snapshot is unavailable, since the server value normally wins.
        const val DEFAULT_VISUAL_FILTER_INTERVAL_SECONDS = 30
        const val MIN_VISUAL_FILTER_INTERVAL_SECONDS = 15
        const val SCREENSHOT_MAX_DIMENSION = 720
        const val SCREENSHOT_JPEG_QUALITY = 80
        const val DEFAULT_NSFW_BLOCK_MILLIS = 15 * 60 * 1000L
        const val MIN_NSFW_BLOCK_MILLIS = 60_000L
        val SUB_FEATURE_HINTS = listOf("shorts", "reel")
        // Shorts player: near-fullscreen *portrait* surface anchored at the very top of the
        // screen -- see hasFullscreenPortraitVideoSurface's doc comment for why that shape (not
        // just an id/description hint) is required.
        const val SHORTS_FULLSCREEN_HEIGHT_FRACTION = 0.7f
        const val SHORTS_FULLSCREEN_WIDTH_FRACTION = 0.85f
        const val SHORTS_SCAN_MAX_NODES = 250

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
