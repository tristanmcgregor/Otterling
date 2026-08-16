package app.otterling.alerts

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * FCM receiver that turns a filter-server push into an *instant* run of the existing tamper poll,
 * cutting alert latency from [MacTamperPollWorker]'s 15-minute WorkManager floor down to seconds.
 *
 * By design the push carries **no trusted payload** -- it's only a wake signal. [onMessageReceived]
 * just kicks [MacTamperPollWorker.enqueueOneShot], which fetches events from `/alerts/poll` and
 * feeds them through the same durable SMS pipeline ([AlertReporter] -> Room outbox ->
 * [GuardianSmsSender]) the periodic poll uses. So a dropped or delayed push loses nothing: the
 * periodic poll still delivers every event, and the server never drops an unacked one
 * (`lockprofile_service.py`). Push is pure speedup layered on the reliable path.
 *
 * See `filter-server/lockprofile_service.py` (`/alerts/register-token`, and the FCM send on tamper
 * ingest) for the server half.
 */
class MacTamperMessagingService : FirebaseMessagingService() {

    /** Fires when FCM issues or rotates this device's token. Only fires on *change*, so the token
     *  is also registered on app launch (see [FcmTokenRegistrar.registerCurrentToken]) to cover the
     *  very first install where onNewToken may already have fired before a token was configured. */
    override fun onNewToken(token: String) {
        Log.i(TAG, "FCM token refreshed; registering with filter-server")
        FcmTokenRegistrar.register(applicationContext, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // Any message at all means "there may be new tamper events" -- go poll now. We deliberately
        // don't read message.data as the event source; the poll is the source of truth.
        Log.i(TAG, "FCM wake received; running tamper poll now")
        MacTamperPollWorker.enqueueOneShot(applicationContext)
    }

    private companion object {
        const val TAG = "MacTamperMessaging"
    }
}
