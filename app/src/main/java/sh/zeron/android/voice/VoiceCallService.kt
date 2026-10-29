package sh.zeron.android.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import sh.zeron.android.MainActivity
import sh.zeron.android.R
import sh.zeron.android.ZeronApplication

/**
 * The ongoing call (#60, D13: Android's stand-in for the iOS Live Activity,
 * `VoiceActivityController` + `VoiceLiveActivity`): a microphone foreground
 * service, so the call keeps its microphone with the screen locked or Zeron
 * behind, and its CallStyle notification — "Codex · host", the status word,
 * the call clock, Mute/Unmute and End. Tapping it returns to the stage.
 *
 * Started by the peer's Prepare step once the microphone is granted (a
 * microphone service must start while the app is visible and after the
 * grant) and stopped when the call ends. Updates at most once a second;
 * mute, end and the call going live answer at once (iOS
 * `VoiceActivityController.interval`). Main thread only.
 */
class VoiceCallService : Service() {

    companion object {
        private const val TAG = "ZeronVoice"
        private const val CHANNEL = "voice-call-v1"
        private const val NOTIFICATION = 0x766f
        private const val ACTION_MUTE = "sh.zeron.android.voice.MUTE"
        private const val ACTION_END = "sh.zeron.android.voice.END"

        /** The notification's tap: back to the stage (`MainActivity` → `VoiceController.requestStage`). */
        const val ACTION_OPEN_STAGE = "sh.zeron.android.voice.OPEN_STAGE"

        private var instance: VoiceCallService? = null
        private var starting = false
        private var stopRequested = false

        fun start(context: Context) {
            if (instance != null || starting) return
            starting = true
            stopRequested = false
            try {
                ContextCompat.startForegroundService(context, Intent(context, VoiceCallService::class.java))
            } catch (e: Exception) {
                // Backgrounded before the microphone opened: the call runs while Zeron is in front.
                Log.w(TAG, "call service not started", e)
                starting = false
            }
        }

        fun stop() {
            val service = instance
            if (service != null) service.finish() else if (starting) stopRequested = true
        }

        /** Mute and the call going live answer at once (the flow catches the rest). */
        fun refresh() {
            instance?.post()
        }
    }

    private val scope = MainScope()
    private var lastKey: Key? = null

    private val controller: VoiceController
        get() = (application as ZeronApplication).voice

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        starting = false
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The notification's Mute/End run in-process (iOS's App Intents).
        when (intent?.action) {
            ACTION_MUTE -> controller.toggleMute()
            ACTION_END -> controller.stop()
        }
        if (lastKey == null) {
            // Within five seconds of startForegroundService, always — even when stopping right away.
            try {
                val snapshot = controller.snapshot.value
                ServiceCompat.startForeground(this, NOTIFICATION, build(snapshot), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                lastKey = Key(snapshot)
            } catch (e: Exception) {
                Log.w(TAG, "startForeground failed", e)
                finish()
                return START_NOT_STICKY
            }
            observe()
        }
        if (stopRequested || !controller.live) finish()
        return START_NOT_STICKY
    }

    private fun observe() {
        scope.launch {
            // Orb states flip with every sentence; the shade needs at most one update a second.
            controller.snapshot
                .map(::Key)
                .distinctUntilChanged()
                .collect { key ->
                    if (key != lastKey) post()
                    delay(1000)
                }
        }
    }

    private fun post() {
        val snapshot = controller.snapshot.value
        if (!snapshot.live) return
        lastKey = Key(snapshot)
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION, build(snapshot))
        } catch (_: SecurityException) {
            // Notifications denied: the service's own notification still holds the microphone.
        }
    }

    /** What the notification shows: a change re-posts it. */
    private data class Key(val host: String, val status: String, val muted: Boolean, val since: Long?) {
        constructor(s: VoiceSnapshot) : this(s.hostName, s.activityStatus, s.muted, s.activeSinceWall)
    }

    private fun finish() {
        if (instance === this) instance = null
        starting = false
        stopRequested = false
        scope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        scope.cancel()
        super.onDestroy()
    }

    /** Quiet: the call has its own sounds; a new channel would default to the notification sound. */
    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Voice calls", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "The ongoing Codex voice call"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
    }

    private fun pending(action: String, code: Int): PendingIntent = PendingIntent.getService(
        this,
        code,
        Intent(this, VoiceCallService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun build(snapshot: VoiceSnapshot): Notification {
        val open = PendingIntent.getActivity(
            this,
            NOTIFICATION,
            Intent(this, MainActivity::class.java)
                .setAction(ACTION_OPEN_STAGE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = VoiceCopy.notificationTitle(snapshot.hostName)
        val caller = Person.Builder().setName(title).setImportant(true).build()
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.zi_microphone)
            .setColor(ContextCompat.getColor(this, R.color.brand_ribbon))
            .setContentTitle(title)
            .setContentText(snapshot.activityStatus)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(caller, pending(ACTION_END, 3)))
            .addAction(
                if (snapshot.muted) R.drawable.zi_microphone else R.drawable.zi_microphone_off,
                VoiceCopy.muteAction(snapshot.muted),
                pending(ACTION_MUTE, 2),
            )
        val since = snapshot.activeSinceWall
        if (since != null) {
            builder.setWhen(since).setShowWhen(true).setUsesChronometer(true)
        } else {
            builder.setShowWhen(false)
        }
        return builder.build()
    }
}
