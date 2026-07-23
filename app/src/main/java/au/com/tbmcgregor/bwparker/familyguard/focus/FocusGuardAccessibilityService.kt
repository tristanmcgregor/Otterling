package au.com.tbmcgregor.bwparker.familyguard.focus

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * One shared accessibility service backing all the self-improvement features: it watches which
 * app is in the foreground and (a) shows [FrictionActivity] before "mindful" apps, (b) ticks
 * [AppTimeBudgetManager] counters and heuristically detects YouTube-Shorts-style sub-features for
 * time budgets, and (c) scans a configured habit-tracker app's screen for individual habit rows
 * to evaluate [HabitRuleManager] commands. Must be enabled manually by the user in
 * Settings > Accessibility -- there's no way to grant this programmatically.
 */
class FocusGuardAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickJob: Job? = null
    private var currentPackage: String? = null
    private var triggerPackages: Set<String> = emptySet()
    private var lastReelsBounceMillis = 0L

    private lateinit var mindfulAppManager: MindfulAppManager
    private lateinit var budgetManager: AppTimeBudgetManager
    private lateinit var habitRuleManager: HabitRuleManager
    private lateinit var detectedHabitManager: DetectedHabitManager
    private lateinit var habitProofManager: HabitProofManager

    override fun onServiceConnected() {
        super.onServiceConnected()
        mindfulAppManager = MindfulAppManager(applicationContext)
        budgetManager = AppTimeBudgetManager(applicationContext)
        habitRuleManager = HabitRuleManager(applicationContext)
        detectedHabitManager = DetectedHabitManager(applicationContext)
        habitProofManager = HabitProofManager(applicationContext)
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

        if (packageName in triggerPackages) {
            scope.launch { scanHabitTracker(packageName) }
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
        scope.launch {
            // HabitShare is always scanned for habit rows (so detection/tuning works even before
            // any rule references it as a trigger), in addition to whatever other apps rules use
            // (rules created before the trigger was hardcoded to HabitShare, if any survive).
            triggerPackages = habitRuleManager.rules().map { it.triggerPackageName }.toSet() +
                HabitTrackerScanner.HABITSHARE_PACKAGE_NAME

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

    private suspend fun scanHabitTracker(packageName: String) {
        val root = rootInActiveWindow ?: run {
            Log.d(TAG, "scanHabitTracker: no rootInActiveWindow")
            return
        }
        val texts = mutableListOf<String>()
        collectNodeInfo(root, texts = texts, resourceIds = mutableListOf(), maxNodes = 400)

        val habitEntries = mutableListOf<HabitTrackerScanner.Entry>()
        collectHabitEntries(root, habitEntries, maxNodes = 400)
        var detectedRows = HabitTrackerScanner.extractRows(habitEntries)
        Log.d(TAG, "scanHabitTracker: rows=${detectedRows.map { it.first }}")

        val todayCells = mutableMapOf<String, Rect>()
        collectHabitTodayCells(root, currentHabitName = null, out = todayCells)
        Log.d(TAG, "scanHabitTracker: todayCells=${todayCells.keys}")
        val doneByColor = detectDoneViaScreenshot(todayCells)
        Log.d(TAG, "scanHabitTracker: doneByColor=$doneByColor")
        if (doneByColor.isNotEmpty()) {
            detectedRows = detectedRows.map { (name, fallback) -> name to (doneByColor[name] ?: fallback) }
        }

        detectedHabitManager.recordScan(detectedRows)

        val doneNamesRaw = detectedRows.filter { it.second }.map { it.first }
        val needsProof = habitProofManager.namesNeedingProof(doneNamesRaw)
        if (needsProof.isNotEmpty()) {
            withContext(Dispatchers.Main) { HabitProofActivity.launch(applicationContext, needsProof.first()) }
        }

        val grantedCount = habitRuleManager.evaluateTrigger(packageName, texts, detectedRows)
        if (grantedCount > 0) {
            withContext(Dispatchers.Main) {
                val label = if (grantedCount == 1) "app" else "apps"
                Toast.makeText(applicationContext, "Unlocked $grantedCount $label for your habit!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Flattens the tree in traversal order, recording every node's text, content description, and
     * checkable/checked state -- input for [HabitTrackerScanner]. */
    @Suppress("DEPRECATION")
    private fun collectHabitEntries(
        node: AccessibilityNodeInfo,
        out: MutableList<HabitTrackerScanner.Entry>,
        maxNodes: Int,
    ) {
        if (out.size >= maxNodes) return
        val text = node.text?.toString()?.takeIf { it.isNotBlank() }
        val contentDescription = node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        out.add(
            HabitTrackerScanner.Entry(
                text = text,
                contentDescription = contentDescription,
                checkable = node.isCheckable,
                checked = node.isChecked,
            ),
        )
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectHabitEntries(child, out, maxNodes)
        }
    }

    /**
     * Walks the tree tracking the nearest ancestor "streak summary" row (see
     * [HabitTrackerScanner]) as the current habit name, and records the screen bounds of that
     * row's "today" day cell (content description containing "TODAY") the first time it's seen.
     * This is how completion is actually read for trackers like HabitShare that only convey
     * done/not-done via cell colour, never via accessibility-visible checked state.
     */
    private fun collectHabitTodayCells(
        node: AccessibilityNodeInfo,
        currentHabitName: String?,
        out: MutableMap<String, Rect>,
    ) {
        if (out.size >= MAX_HABIT_ROWS) return
        var habitName = currentHabitName
        val desc = node.contentDescription?.toString()
        if (desc != null) {
            val rowMatch = HABIT_ROW_NAME_PATTERN.find(desc)
            if (rowMatch != null) {
                habitName = rowMatch.groupValues[1].trim()
            } else if (habitName != null && !out.containsKey(habitName) && desc.contains("TODAY", ignoreCase = true)) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (!bounds.isEmpty) out[habitName] = bounds
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectHabitTodayCells(child, habitName, out)
        }
    }

    /** Takes a screenshot and classifies each cell's centre-ish region as "done" if it's green,
     * which is how completed habits are shown in trackers like HabitShare. Requires API 30+ and
     * the canTakeScreenshot capability; returns an empty map (leaving the caller's fallback in
     * place) if either is unavailable or the screenshot fails. */
    private suspend fun detectDoneViaScreenshot(cells: Map<String, Rect>): Map<String, Boolean> {
        if (cells.isEmpty()) {
            Log.d(TAG, "detectDoneViaScreenshot: no today-cells found")
            return emptyMap()
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.d(TAG, "detectDoneViaScreenshot: SDK ${Build.VERSION.SDK_INT} < R, screenshot unsupported")
            return emptyMap()
        }
        val bitmap = captureScreenshot() ?: run {
            Log.d(TAG, "detectDoneViaScreenshot: captureScreenshot() returned null")
            return emptyMap()
        }
        return try {
            cells.mapValues { (name, bounds) ->
                val result = isCellGreenDebug(bitmap, bounds)
                Log.d(TAG, "detectDoneViaScreenshot: $name bounds=$bounds rgb=(${result.r},${result.g},${result.b}) green=${result.isGreen}")
                result.isGreen
            }
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun captureScreenshot(): Bitmap? = suspendCancellableCoroutine { cont ->
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val hardwareBuffer = result.hardwareBuffer
                        val hardwareBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, result.colorSpace)
                        hardwareBuffer.close()
                        val softwareBitmap = hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                        hardwareBitmap?.recycle()
                        if (cont.isActive) cont.resume(softwareBitmap)
                    }

                    override fun onFailure(errorCode: Int) {
                        if (cont.isActive) cont.resume(null)
                    }
                },
            )
        } catch (error: SecurityException) {
            if (cont.isActive) cont.resume(null)
        } catch (error: IllegalStateException) {
            if (cont.isActive) cont.resume(null)
        }
    }

    /** Samples a grid of points across [bounds] and averages them, since a single pixel could land
     * on the day-letter glyph rather than the cell's fill colour. Returns the averaged RGB
     * alongside the green/not-green verdict purely for debug logging. */
    private fun isCellGreenDebug(bitmap: Bitmap, bounds: Rect): CellColorResult {
        var rSum = 0L
        var gSum = 0L
        var bSum = 0L
        var count = 0
        for (ix in 0 until COLOR_SAMPLE_GRID) {
            for (iy in 0 until COLOR_SAMPLE_GRID) {
                val x = (bounds.left + bounds.width() * (ix + 0.5) / COLOR_SAMPLE_GRID)
                    .toInt().coerceIn(0, bitmap.width - 1)
                val y = (bounds.top + bounds.height() * (iy + 0.5) / COLOR_SAMPLE_GRID)
                    .toInt().coerceIn(0, bitmap.height - 1)
                val pixel = bitmap.getPixel(x, y)
                rSum += Color.red(pixel)
                gSum += Color.green(pixel)
                bSum += Color.blue(pixel)
                count++
            }
        }
        if (count == 0) return CellColorResult(false, 0, 0, 0)
        val r = (rSum / count).toInt()
        val g = (gSum / count).toInt()
        val b = (bSum / count).toInt()
        val isGreen = g > r + GREEN_DOMINANCE_THRESHOLD && g > b + GREEN_DOMINANCE_THRESHOLD
        return CellColorResult(isGreen, r, g, b)
    }

    private data class CellColorResult(val isGreen: Boolean, val r: Int, val g: Int, val b: Int)

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
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "FocusGuardAccessibility"
        const val TICK_MILLIS = 5_000L
        const val TICK_SECONDS = 5
        const val MAX_HABIT_ROWS = 60
        const val COLOR_SAMPLE_GRID = 5
        const val GREEN_DOMINANCE_THRESHOLD = 15
        val SUB_FEATURE_HINTS = listOf("shorts", "reel")
        val HABIT_ROW_NAME_PATTERN = Regex("""^(.+?),\s*Streak:""")

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
