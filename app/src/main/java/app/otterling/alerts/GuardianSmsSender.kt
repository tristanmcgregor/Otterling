package app.otterling.alerts

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Sends an alert SMS via the device SIM to whatever number it's given. Retries are handled by
 * [AlertReporter.flushOutbox].
 *
 * Waits for the carrier's own sent-status broadcast before reporting success -- `SmsManager`'s
 * send calls only mean "handed to the radio," not "the carrier actually accepted it" (no signal,
 * a SIM issue, or a carrier block can all still fail silently afterward). Without this, a lost
 * message was previously indistinguishable from a delivered one and never got retried by
 * [AlertReporter.flushOutbox].
 */
class GuardianSmsSender(private val context: Context) {
    suspend fun send(body: String, toNumber: String): Boolean {
        if (toNumber.isBlank()) {
            Log.w(TAG, "No guardian number configured")
            return false
        }
        if (!SmsPermissionGranter.hasSendSms(context)) {
            SmsPermissionGranter.grantSendSms(context)
            if (!SmsPermissionGranter.hasSendSms(context)) {
                Log.e(TAG, "SEND_SMS not granted")
                return false
            }
        }
        return try {
            @Suppress("DEPRECATION")
            val sms = SmsManager.getDefault()
            val parts = sms.divideMessage(body)
            val confirmed = withTimeoutOrNull(SEND_CONFIRM_TIMEOUT_MS) {
                awaitSendResult(sms, parts, toNumber)
            }
            if (confirmed == null) {
                // No confirmation broadcast within the timeout -- treat as failed so the caller
                // retries, rather than assuming success the way an unconditional `true` used to.
                Log.w(TAG, "No send confirmation within ${SEND_CONFIRM_TIMEOUT_MS}ms, treating as failed")
                false
            } else {
                if (confirmed) {
                    Log.i(TAG, "SMS confirmed sent (${body.length} chars, ${parts.size} parts)")
                } else {
                    Log.w(TAG, "Carrier reported SMS send failure")
                }
                confirmed
            }
        } catch (error: SecurityException) {
            Log.e(TAG, "SMS send refused", error)
            false
        } catch (error: IllegalArgumentException) {
            Log.e(TAG, "SMS send failed", error)
            false
        } catch (error: Exception) {
            Log.e(TAG, "SMS send failed", error)
            false
        }
    }

    /**
     * Registers a one-time receiver for every part's sent-status broadcast and resolves once
     * every part has reported in -- true only if every part's result code was RESULT_OK. Each call
     * uses its own action string (suffixed with a nanoTime) so concurrent sends never share a
     * receiver/PendingIntent and cross-resolve each other's results.
     */
    private suspend fun awaitSendResult(
        sms: SmsManager,
        parts: ArrayList<String>,
        toNumber: String,
    ): Boolean = suspendCancellableCoroutine { continuation ->
        val action = "$ACTION_SMS_SENT_PREFIX.${System.nanoTime()}"
        val remaining = AtomicInteger(parts.size)
        val allOk = AtomicBoolean(true)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                if (resultCode != Activity.RESULT_OK) {
                    allOk.set(false)
                }
                if (remaining.decrementAndGet() == 0) {
                    unregisterQuietly(this)
                    if (continuation.isActive) continuation.resume(allOk.get())
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, IntentFilter(action))
        }
        continuation.invokeOnCancellation { unregisterQuietly(receiver) }

        val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0)
        val sentIntents = ArrayList<PendingIntent>(parts.size)
        for (index in parts.indices) {
            sentIntents.add(
                PendingIntent.getBroadcast(
                    context,
                    index,
                    Intent(action).setPackage(context.packageName),
                    pendingIntentFlags,
                ),
            )
        }

        if (parts.size == 1) {
            sms.sendTextMessage(toNumber, null, parts[0], sentIntents[0], null)
        } else {
            sms.sendMultipartTextMessage(toNumber, null, parts, sentIntents, null)
        }
    }

    private fun unregisterQuietly(receiver: BroadcastReceiver) {
        try {
            context.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
            // Already unregistered (can happen if cancellation and the final part's callback race).
        }
    }

    private companion object {
        const val TAG = "GuardianSmsSender"
        const val ACTION_SMS_SENT_PREFIX = "app.otterling.alerts.SMS_SENT"
        const val SEND_CONFIRM_TIMEOUT_MS = 30_000L
    }
}
