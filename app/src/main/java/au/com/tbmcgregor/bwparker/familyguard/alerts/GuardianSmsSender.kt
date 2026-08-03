package au.com.tbmcgregor.bwparker.familyguard.alerts

import android.content.Context
import android.telephony.SmsManager
import android.util.Log

/**
 * Sends guardian SMS via the device SIM. Retries are handled by [AlertReporter.flushOutbox].
 */
class GuardianSmsSender(private val context: Context) {
    fun send(body: String, toNumber: String): Boolean {
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
            if (parts.size == 1) {
                sms.sendTextMessage(toNumber, null, body, null, null)
            } else {
                sms.sendMultipartTextMessage(toNumber, null, parts, null, null)
            }
            Log.i(TAG, "SMS queued to carrier (${body.length} chars, ${parts.size} parts)")
            true
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

    private companion object {
        const val TAG = "GuardianSmsSender"
    }
}
