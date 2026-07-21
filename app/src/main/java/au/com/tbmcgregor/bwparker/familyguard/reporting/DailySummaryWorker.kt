package au.com.tbmcgregor.bwparker.familyguard.reporting

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import au.com.tbmcgregor.bwparker.familyguard.monitoring.AppUsageStat
import au.com.tbmcgregor.bwparker.familyguard.monitoring.UsageStatsCollector
import au.com.tbmcgregor.bwparker.familyguard.tamper.TamperEventLogger
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Once-daily local notification digest of today's app usage. Notification-only by design -- no
 * email/SMTP backend, so nothing about a child's activity leaves the device.
 */
class DailySummaryWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        val collector = UsageStatsCollector(applicationContext)
        collector.collectToday()
        val topApps = collector.today().sortedByDescending { it.totalForegroundMillis }.take(5)
        val zone = ZoneId.systemDefault()
        val startOfDay = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        val tamperEvents = TamperEventLogger(applicationContext).since(startOfDay)
        postNotification(topApps, tamperEvents.size)
        Result.success()
    } catch (error: Exception) {
        Result.retry()
    }

    private fun postNotification(topApps: List<AppUsageStat>, tamperEventCount: Int) {
        val manager = applicationContext.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Daily usage summary", NotificationManager.IMPORTANCE_DEFAULT),
        )

        val usageBody = if (topApps.isEmpty()) {
            "No app usage recorded today."
        } else {
            topApps.joinToString("\n") { "${it.packageName}: ${formatDuration(it.totalForegroundMillis)}" }
        }
        val body = "$usageBody\nTamper events: $tamperEventCount"

        val notification = Notification.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Today's screen time summary")
            .setContentText(body.lineSequence().first())
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .build()

        // POST_NOTIFICATIONS can be revoked by the user on API 33+; degrade silently rather than crash.
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun formatDuration(millis: Long): String {
        val totalMinutes = millis / 60_000
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }

    companion object {
        private const val CHANNEL_ID = "daily_summary"
        private const val NOTIFICATION_ID = 2001
        private const val UNIQUE_WORK_NAME = "daily_summary"

        fun enqueuePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<DailySummaryWorker>(1, TimeUnit.DAYS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun runOnce(context: Context) {
            WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<DailySummaryWorker>().build())
        }
    }
}
