package au.com.tbmcgregor.bwparker.familyguard.focus

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.time.LocalDate
import java.util.Collections

/**
 * Bridges the HabitShare sync ([HabitShareSyncManager]) to the photo-proof prompt: when a habit
 * that requires proof is newly seen as done-but-not-yet-verified, this brings up
 * [HabitProofActivity]. Because sync usually runs while *another* app (HabitShare) is foreground or
 * while the app is fully backgrounded, a plain [Context.startActivity] can be silently dropped by
 * Android's background-activity-start rules, so a high-priority full-screen-intent notification is
 * also posted as a reliable fallback -- it launches the prompt directly when the system allows it,
 * and otherwise degrades to a heads-up the user can tap. [HabitProofActivity] cancels the
 * notification on open, so at most one of the two paths ends up visible.
 *
 * [promptFor] debounces per habit+day so the once-a-second HabitShare poll can't relaunch the
 * camera in a tight loop: each habit is prompted only once per day until it's actually verified
 * (which removes it from [HabitProofManager.namesNeedingProof]) or the day rolls over.
 */
object HabitProofPrompter {
    private val promptedKeys = Collections.synchronizedSet(mutableSetOf<String>())

    /** Returns true if a prompt was shown (i.e. this habit hadn't already been prompted today). */
    fun promptFor(context: Context, habitName: String): Boolean {
        if (!promptedKeys.add(keyFor(habitName))) return false
        val appContext = context.applicationContext
        val activityIntent = Intent(appContext, HabitProofActivity::class.java)
            .putExtra(HabitProofActivity.EXTRA_HABIT_NAME, habitName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        runCatching { appContext.startActivity(activityIntent) }
        postFullScreenNotification(appContext, habitName, activityIntent)
        return true
    }

    fun cancelNotification(context: Context) {
        context.applicationContext.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    private fun postFullScreenNotification(context: Context, habitName: String, activityIntent: Intent) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Habit photo proof", NotificationManager.IMPORTANCE_HIGH),
        )
        val pending = PendingIntent.getActivity(
            context,
            habitName.hashCode(),
            activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle("Prove it: $habitName")
            .setContentText("Take a photo to verify this habit and unlock your apps.")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setFullScreenIntent(pending, true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun keyFor(habitName: String): String =
        "${LocalDate.now().toEpochDay()}:${habitName.trim().lowercase()}"

    private const val CHANNEL_ID = "habit_proof_prompt"
    private const val NOTIFICATION_ID = 4310
}
