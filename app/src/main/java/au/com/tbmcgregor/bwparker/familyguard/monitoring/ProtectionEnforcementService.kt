package au.com.tbmcgregor.bwparker.familyguard.monitoring

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import au.com.tbmcgregor.bwparker.familyguard.content.AppSuspensionManager
import au.com.tbmcgregor.bwparker.familyguard.restrictions.DeviceRestrictionsManager
import au.com.tbmcgregor.bwparker.familyguard.tamper.TamperEventLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Long-running foreground service that periodically re-asserts Phase 3 restrictions and the
 * Phase 4 blocked-app list in case anything cleared them (e.g. a factory OEM reset of user
 * restrictions). Declared as `foregroundServiceType="specialUse"` per Android 14+ requirements.
 */
class ProtectionEnforcementService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (loopJob == null) {
            val restrictionsManager = DeviceRestrictionsManager(applicationContext)
            val tamperLogger = TamperEventLogger(applicationContext)
            val suspensionManager = AppSuspensionManager(applicationContext)
            loopJob = scope.launch {
                runCatching { suspensionManager.reapplyAll() }
                    .onFailure { Log.w(TAG, "Blocked-app reapply failed", it) }
                while (isActive) {
                    runCatching { restrictionsManager.detectDriftAndReapply(tamperLogger) }
                        .onFailure { Log.w(TAG, "Restriction drift check failed", it) }
                    delay(POLL_INTERVAL_MS)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        loopJob?.cancel()
        loopJob = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Device protection active", NotificationManager.IMPORTANCE_MIN),
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Family Device Guard")
            .setContentText("Protections are active")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "ProtectionEnforcementService"
        private const val CHANNEL_ID = "protection_enforcement"
        private const val NOTIFICATION_ID = 1001
        private const val POLL_INTERVAL_MS = 5 * 60 * 1000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ProtectionEnforcementService::class.java))
        }
    }
}
