package app.otterling.knox

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class KnoxLicenseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_KNOX_LICENSE_STATUS) return

        val errorCode = intent.getIntExtra(EXTRA_LICENSE_ERROR_CODE, UNKNOWN_ERROR)
        val resultType = intent.getIntExtra(EXTRA_LICENSE_RESULT_TYPE, UNKNOWN_RESULT_TYPE)

        if (errorCode == ERROR_NONE) {
            Log.i(TAG, "Knox license operation succeeded; resultType=$resultType")
        } else {
            Log.e(TAG, "Knox license operation failed; errorCode=$errorCode resultType=$resultType")
        }
    }

    private companion object {
        const val TAG = "KnoxLicenseReceiver"
        const val ACTION_KNOX_LICENSE_STATUS =
            "com.samsung.android.knox.intent.action.KNOX_LICENSE_STATUS"
        const val EXTRA_LICENSE_ERROR_CODE =
            "com.samsung.android.knox.intent.extra.KNOX_LICENSE_ERROR_CODE"
        const val EXTRA_LICENSE_RESULT_TYPE =
            "com.samsung.android.knox.intent.extra.KNOX_LICENSE_RESULT_TYPE"
        const val ERROR_NONE = 0
        const val UNKNOWN_ERROR = Int.MIN_VALUE
        const val UNKNOWN_RESULT_TYPE = -1
    }
}
