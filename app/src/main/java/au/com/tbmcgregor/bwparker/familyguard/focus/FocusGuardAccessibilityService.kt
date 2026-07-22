package au.com.tbmcgregor.bwparker.familyguard.focus

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
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

    private lateinit var mindfulAppManager: MindfulAppManager
    private lateinit var budgetManager: AppTimeBudgetManager
    private lateinit var habitRuleManager: HabitRuleManager
    private lateinit var detectedHabitManager: DetectedHabitManager

    override fun onServiceConnected() {
        super.onServiceConnected()
        mindfulAppManager = MindfulAppManager(applicationContext)
        budgetManager = AppTimeBudgetManager(applicationContext)
        habitRuleManager = HabitRuleManager(applicationContext)
        detectedHabitManager = DetectedHabitManager(applicationContext)
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
    }

    private fun onForegroundChanged(packageName: String) {
        tickJob?.cancel()
        scope.launch {
            // HabitShare is always scanned for habit rows (so detection/tuning works even before
            // any rule references it as a trigger), in addition to whatever other apps rules use.
            triggerPackages = habitRuleManager.rules().map { it.triggerPackageName }.toSet() + HABITSHARE_PACKAGE_NAME

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
        val root = rootInActiveWindow ?: return
        val texts = mutableListOf<String>()
        collectNodeInfo(root, texts = texts, resourceIds = mutableListOf(), maxNodes = 400)

        val habitEntries = mutableListOf<HabitTrackerScanner.Entry>()
        collectHabitEntries(root, habitEntries, maxNodes = 400)
        val detectedRows = HabitTrackerScanner.extractRows(habitEntries)
        detectedHabitManager.recordScan(detectedRows)

        val grantedCount = habitRuleManager.evaluateTrigger(packageName, texts, detectedRows)
        if (grantedCount > 0) {
            withContext(Dispatchers.Main) {
                val label = if (grantedCount == 1) "app" else "apps"
                Toast.makeText(applicationContext, "Unlocked $grantedCount $label for your habit!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Flattens the tree in traversal order, recording every node's text + checkable/checked state
     * -- input for [HabitTrackerScanner], which pairs checkboxes with their nearby label. */
    @Suppress("DEPRECATION")
    private fun collectHabitEntries(
        node: AccessibilityNodeInfo,
        out: MutableList<HabitTrackerScanner.Entry>,
        maxNodes: Int,
    ) {
        if (out.size >= maxNodes) return
        val text = node.text?.toString()?.takeIf { it.isNotBlank() }
        out.add(HabitTrackerScanner.Entry(text = text, checkable = node.isCheckable, checked = node.isChecked))
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectHabitEntries(child, out, maxNodes)
        }
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
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TICK_MILLIS = 5_000L
        const val TICK_SECONDS = 5
        const val HABITSHARE_PACKAGE_NAME = "com.habitshareapp"
        val SUB_FEATURE_HINTS = listOf("shorts", "reel")
    }
}
