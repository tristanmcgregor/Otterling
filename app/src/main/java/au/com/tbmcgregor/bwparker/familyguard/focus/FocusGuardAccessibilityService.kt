package au.com.tbmcgregor.bwparker.familyguard.focus

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import au.com.tbmcgregor.bwparker.familyguard.monitoring.ProtectionController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    private lateinit var mindfulAppManager: MindfulAppManager
    private lateinit var budgetManager: AppTimeBudgetManager
    private lateinit var habitShareSyncManager: HabitShareSyncManager

    override fun onServiceConnected() {
        super.onServiceConnected()
        mindfulAppManager = MindfulAppManager(applicationContext)
        budgetManager = AppTimeBudgetManager(applicationContext)
        habitShareSyncManager = HabitShareSyncManager(applicationContext)
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
        if (!hasReelsTitleAtTop(root, screenHeight)) return
        lastReelsBounceMillis = now
        Log.d(TAG, "Facebook Reels title detected at top -- bouncing")
        performGlobalAction(GLOBAL_ACTION_BACK)
        Toast.makeText(applicationContext, "Reels is blocked.", Toast.LENGTH_SHORT).show()
    }

    /**
     * True if a "Reels" title sits near the very top of the screen -- the header the Reels player
     * shows. Deliberately position-gated so it ignores the feed's inline reel previews (mid/lower
     * screen) and the bottom-nav "Reels" tab, which would otherwise make this fire on the normal
     * feed too. Matches text or content-description equal to "Reels" (allowing a small amount of
     * surrounding text, e.g. "Reels").
     */
    private fun hasReelsTitleAtTop(node: AccessibilityNodeInfo, screenHeight: Int): Boolean {
        val label = node.text?.toString()?.trim() ?: node.contentDescription?.toString()?.trim()
        if (label != null && REELS_TITLE_PATTERN.matches(label)) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (!bounds.isEmpty && bounds.top < screenHeight * REELS_TITLE_TOP_FRACTION) return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (hasReelsTitleAtTop(child, screenHeight)) return true
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

    private fun collectNodeInfo(
        node: AccessibilityNodeInfo,
        texts: MutableList<String>,
        resourceIds: MutableList<String>,
        maxNodes: Int,
    ) {
        if (texts.size + resourceIds.size >= maxNodes) return
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { texts.add(it) }
        node.viewIdResourceName?.let { resourceIds.add(it) }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectNodeInfo(child, texts, resourceIds, maxNodes)
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
        val SUB_FEATURE_HINTS = listOf("shorts", "reel")

        // Facebook (main app + Lite) -- Reels blocking is scoped to these so the rest of the app
        // keeps working normally.
        val FACEBOOK_PACKAGES = setOf("com.facebook.katana", "com.facebook.lite")
        const val REELS_BOUNCE_DEBOUNCE_MS = 1_200L
        // The Reels player shows a "Reels" title in the top bar; only a title within this fraction
        // of the screen height from the top counts, so the feed's inline previews / bottom tab
        // don't trigger it.
        const val REELS_TITLE_TOP_FRACTION = 0.15
        val REELS_TITLE_PATTERN = Regex("""^reels$""", RegexOption.IGNORE_CASE)
    }
}
