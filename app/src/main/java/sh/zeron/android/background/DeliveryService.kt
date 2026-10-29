package sh.zeron.android.background

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ServiceCompat
import sh.zeron.android.ZeronApplication

/**
 * Keeps the process running for a few seconds after the user leaves, so a
 * message sent just before can reach the edge (#58; iOS
 * `beginBackgroundTask`; ported from za: `core/DeliveryService.kt`). The
 * core does the work on its own threads; this only holds the process out
 * of the cached (frozen) state. Started from the process lifecycle's
 * `ON_STOP`, inside the platform's foreground-to-background grace window;
 * [DeliveryHold] decides whether to.
 *
 * Type `shortService` on API 34+ (no budget, separate from RunWatch's
 * `dataSync`), `dataSync` below. The notification rides the quiet
 * `runs-v1` channel ([sh.zeron.android.notify.Notifier.buildDeliveryHold]).
 */
class DeliveryService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val stop = Runnable { stopSelf() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        try {
            val notification = (application as ZeronApplication).notifier.buildDeliveryHold()
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        } catch (e: Exception) {
            // Too late to start from the background: delivery resumes next launch.
            Log.w(TAG, "delivery hold refused", e)
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        main.removeCallbacks(stop)
        main.postDelayed(stop, intent?.getLongExtra(EXTRA_MS, DeliveryHold.HOLD_MS) ?: DeliveryHold.HOLD_MS)
        return START_NOT_STICKY
    }

    /** Android 14+ short services must stop when their time is up. */
    override fun onTimeout(startId: Int) = stopSelf()

    override fun onDestroy() {
        running = false
        main.removeCallbacks(stop)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Zeron"

        /** Distinct from RunWatchService's id 1 (both can be up at once). */
        private const val NOTIFICATION_ID = 7101
        private const val EXTRA_MS = "ms"

        /** In the foreground state (stopping one that hasn't got there yet is an error). */
        @Volatile private var running = false

        fun hold(context: Context, ms: Long = DeliveryHold.HOLD_MS) {
            val intent = Intent(context, DeliveryService::class.java).putExtra(EXTRA_MS, ms)
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "delivery hold refused", e)
            }
        }

        fun release(context: Context) {
            if (running) context.stopService(Intent(context, DeliveryService::class.java))
        }
    }
}
