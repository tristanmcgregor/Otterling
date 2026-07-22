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
 * time budgets, and (c) scans a configured habit-tracker app's screen text for a completion
 * pattern to grant [HabitGateManager] rewards and evaluate [HabitRuleManager] commands. Must be
 * enabled manually by the user in Settings > Accessibility -- there's no way to grant this
 * programmatically.
 */
class FocusGuardAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickJob: Job? = null
    private var currentPackage: String? = null
    private var triggerPackages: Set<String> = emptySet()

    private lateinit var mindfulAppManager: MindfulAppManager
    private lateinit var budgetManager: AppTimeBudgetManager
    private lateinit var habitGateManager: HabitGateManager
    private lateinit var habitRuleManager: HabitRuleManager

    override fun onServiceConnected() {
        super.onServiceConnected()
        mindfulAppManager = MindfulAppManager(applicationContext)
        budgetManager = AppTimeBudgetManager(applicationContext)
        habitGateManager = HabitGateManager(applicationContext)
        habitRuleManager = HabitRuleManager(applicationContext)
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

        if (packageName == habitGateManager.trackerPackageName || packageName in triggerPackages) {
            scope.launch { scanHabitTracker(packageName) }
        }
    }

    private fun onForegroundChanged(packageName: String) {
        tickJob?.cancel()
        scope.launch {
            triggerPackages = habitRuleManager.rules().map { it.triggerPackageName }.toSet()

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

        if (packageName == habitGateManager.trackerPackageName) {
            habitGateManager.lastCapturedText = texts.joinToString("\n").take(4000)
            if (!habitGateManager.isGrantedToday() && looksLikeAllHabitsComplete(texts)) {
                if (habitGateManager.grantIfNotAlready()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(applicationContext, "Habit reward earned!", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        val grantedCount = habitRuleManager.evaluateTrigger(packageName, texts)
        if (grantedCount > 0) {
            withContext(Dispatchers.Main) {
                val label = if (grantedCount == 1) "app" else "apps"
                Toast.makeText(applicationContext, "Unlocked $grantedCount $label for your habit!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Matches common "3/3" or "3 of 3" completion-counter phrasing where done == total > 0. */
    private fun looksLikeAllHabitsComplete(texts: List<String>): Boolean {
        val pattern = Regex("""(\d+)\s*(?:/|of)\s*(\d+)""", RegexOption.IGNORE_CASE)
        return texts.any { text ->
            val match = pattern.find(text) ?: return@any false
            val (done, total) = match.destructured
            val totalValue = total.toIntOrNull() ?: return@any false
            totalValue > 0 && done.toIntOrNull() == totalValue
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
        val SUB_FEATURE_HINTS = listOf("shorts", "reel")
    }
}
