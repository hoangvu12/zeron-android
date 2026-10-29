package sh.zeron.android.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import sh.zeron.android.MainActivity
import sh.zeron.android.R
import sh.zeron.android.shell.DeepLinks

/**
 * The notification copy that has no per-kind owner (android-app#28,
 * verbatim from web `notifications.ts:215-220`; the "Background updates
 * paused" pair is new copy, flagged in the ticket's Comments). The
 * session texts live on [NotificationKind.title] — iOS's `Kind.title`
 * (`PushNotifications.swift:18-20`) is both the row title and the banner
 * body.
 */
object NotifyCopy {
    const val BudgetTitle = "Background updates paused"
    const val BudgetText = "Open Zeron to resume."

    /** za: `DeliveryService.kt` (the delivery hold, #58). */
    const val DeliveryHoldTitle = "Sending your message…"
}

/**
 * Local notifications: versioned channels carrying the desktop chimes,
 * posting, replacing and cancelling (android-app#28).
 *
 * Channel ids carry a version (sounds are immutable once a channel exists,
 * so `migrateChannels` deletes every `session-*` / `runs-*` channel of
 * another version at start). One notification per chat: tag
 * `chat:<chatId>`, id 1 — a newer event replaces the older one, including
 * a change of kind/channel (the edge's `apns-collapse-id = chatId`,
 * docs/port/05 K11). `link:<engineId>` is
 * the engine outage's notification, cancelled by LinkRestored. The
 * foreground service's ongoing notification posts untagged with id 1.
 */
class Notifier(private val context: Context) {

    private val manager: NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    // ── channels ─────────────────────────────────────────────────────────

    /** The session channel of a kind (`session-done-v1`). */
    fun channelId(kind: NotificationKind): String =
        "session-${kind.raw}-v$CHANNEL_VERSION"

    /** The runs channel (the foreground service's ongoing notification). */
    fun runsChannelId(): String = "runs-v$CHANNEL_VERSION"

    /**
     * Delete session/runs channels of other versions (their sounds cannot
     * be edited in place). Call before [ensureChannels] at app start.
     */
    fun migrateChannels() {
        val suffix = "-v$CHANNEL_VERSION"
        manager.notificationChannels
            .filter {
                (it.id.startsWith("session-") || it.id.startsWith("runs-")) &&
                    !it.id.endsWith(suffix)
            }
            .forEach { manager.deleteNotificationChannel(it.id) }
    }

    /** Create the four channels (idempotent, cheap). */
    fun ensureChannels() {
        for (kind in NotificationKind.entries) {
            val id = channelId(kind)
            if (manager.getNotificationChannel(id) != null) continue
            val channel = NotificationChannel(
                id,
                kind.channelName(),
                when (kind) {
                    NotificationKind.Done -> NotificationManager.IMPORTANCE_DEFAULT
                    NotificationKind.Input -> NotificationManager.IMPORTANCE_HIGH
                    NotificationKind.Failed -> NotificationManager.IMPORTANCE_HIGH
                },
            )
            channel.description = kind.channelDescription()
            channel.setSound(
                Uri.parse("android.resource://${context.packageName}/raw/${kind.chimeResource()}"),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            channel.enableVibration(true)
            channel.vibrationPattern = kind.vibrationPattern()
            manager.createNotificationChannel(channel)
        }
        val runs = runsChannelId()
        if (manager.getNotificationChannel(runs) == null) {
            // Not IMPORTANCE_MIN: that would block Live Update promotion.
            val channel = NotificationChannel(
                runs,
                "Runs in progress",
                NotificationManager.IMPORTANCE_LOW,
            )
            channel.description = "Shown while an agent run is active"
            // Quiet: a new channel defaults to the system notification
            // sound, which a user raising the importance would then hear.
            channel.setSound(null, null)
            manager.createNotificationChannel(channel)
        }
    }

    // ── session events ───────────────────────────────────────────────────

    /**
     * A session event for one chat (#56's SessionWatcher): replaces any
     * earlier notification for the same chat (the tag); tapping opens the
     * chat. [whenMs] is the row's last activity.
     */
    fun postSessionEvent(
        chatId: String,
        chatTitle: String,
        kind: NotificationKind,
        whenMs: Long,
    ) {
        val text = kind.title
        val tag = chatTag(chatId)
        val notification = NotificationCompat.Builder(context, channelId(kind))
            .brand()
            // The core applies the fallback already; a blank title from any
            // other caller still reads "New session" (NotifierTest).
            .setContentTitle(chatTitle.ifBlank { "New session" })
            .setContentText(text)
            .setCategory(
                if (kind == NotificationKind.Failed) {
                    NotificationCompat.CATEGORY_ERROR
                } else {
                    NotificationCompat.CATEGORY_STATUS
                }
            )
            .setShowWhen(true)
            .setWhen(whenMs)
            .setAutoCancel(true)
            .setContentIntent(chatTapPendingIntent(tag, chatId))
            .build()
        notify(tag, notification)
    }

    // ── cancels ──────────────────────────────────────────────────────────

    /** The chat was opened in the app (the router or the session screen): its notification goes. */
    fun cancelChatFor(chatId: String) {
        manager.cancel(chatTag(chatId), NOTIFICATION_ID)
    }

    /**
     * Signed out (#18, the stand-in for iOS's `push.signingOut`): every
     * notification the account raised goes. A foreground service's own
     * ongoing notification stays until that service stops.
     */
    fun cancelAll() {
        manager.cancelAll()
    }

    // ── the dataSync budget ──────────────────────────────────────────────

    /** `onTimeout`: background updates are paused until the app opens. */
    fun postBudgetExhausted() {
        val notification = NotificationCompat.Builder(context, runsChannelId())
            .brand()
            .setContentTitle(NotifyCopy.BudgetTitle)
            .setContentText(NotifyCopy.BudgetText)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .build()
        notify(BUDGET_TAG, notification)
    }

    /** The app returned to the foreground: the budget reset. */
    fun cancelBudgetExhausted() {
        manager.cancel(BUDGET_TAG, NOTIFICATION_ID)
    }

    // ── the foreground service's ongoing notification ────────────────────

    /**
     * The RunWatchService's ongoing notification: channel `runs-v1`,
     * silent, ongoing, a chronometer, and — on API 36 when the platform
     * allows it and a run started on this phone — a promoted Live Update
     * with the status chip. `onDeleteDismissed` receives the deleteIntent
     * taps (a dismissed Live Update is never reposted until the working
     * set changes).
     */
    fun buildOngoing(content: OngoingContent, onDeleteDismissed: PendingIntent?) =
        NotificationCompat.Builder(context, runsChannelId())
            .brand()
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setCategory(
                if (content.working) {
                    NotificationCompat.CATEGORY_PROGRESS
                } else {
                    NotificationCompat.CATEGORY_SERVICE
                },
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(content.whenMs != null)
            .setWhen(content.whenMs ?: System.currentTimeMillis())
            .setUsesChronometer(content.whenMs != null)
            .setRequestPromotedOngoing(content.promoteLiveUpdate)
            .apply {
                // The idle "Staying connected" content (ADR 0010) has no
                // progress bar and no status chip.
                if (content.working) {
                    setProgress(0, 0, true)
                    setShortCriticalText(content.chip)
                }
                if (content.subText != null) setSubText(content.subText)
                setContentIntent(
                    if (content.tapChatId != null) {
                        chatTapPendingIntent("ongoing", content.tapChatId)
                    } else {
                        rootTapPendingIntent()
                    },
                )
                if (onDeleteDismissed != null) setDeleteIntent(onDeleteDismissed)
                if (content.working && android.os.Build.VERSION.SDK_INT >= 36) {
                    setStyle(
                        NotificationCompat.ProgressStyle()
                            .setProgressIndeterminate(true),
                    )
                }
            }
            .build()

    /**
     * The delivery hold's notification (#58, za: `DeliveryService`'s copy):
     * a foreground service needs one. It rides the quiet `runs-v1` channel
     * rather than za:'s own `delivery` channel; the hold lasts 25 s, so
     * the platform's ~10 s FGS notification deferral usually hides it.
     */
    fun buildDeliveryHold(): Notification =
        NotificationCompat.Builder(context, runsChannelId())
            .brand(smallIcon = R.drawable.zi_arrow_up)
            .setContentTitle(NotifyCopy.DeliveryHoldTitle)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(rootTapPendingIntent())
            .build()

    // ── internals ────────────────────────────────────────────────────────

    /**
     * The branding on every builder (#140): Zeron's logo mark as the small
     * icon (status bar and shade; the delivery hold keeps za:
     * `DeliveryService`'s arrow-up), the Zeron icon's purple accent and
     * the launcher artwork as the large icon (expanded notification).
     */
    private fun NotificationCompat.Builder.brand(smallIcon: Int = SMALL_ICON): NotificationCompat.Builder =
        setSmallIcon(smallIcon)
            .setColor(ContextCompat.getColor(context, R.color.brand_accent))
            .apply { largeIcon?.let { setLargeIcon(it) } }

    /**
     * The launcher artwork, decoded once per [Notifier] (Zeron's
     * `ic_launcher_foreground`); `null` if it cannot be decoded, and the
     * notification simply goes without a large icon.
     */
    private val largeIcon: Bitmap? by lazy {
        try {
            BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher_foreground)
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun notify(tag: String, notification: Notification) {
        try {
            NotificationManagerCompat.from(context).notify(tag, NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // The permission was revoked between the policy check and the
            // post (pr699:core/Notifier.kt:139-142): log, never crash.
            Log.w(TAG, "notification $tag skipped: notifications not permitted")
        }
    }

    private fun chatTag(chatId: String) = "$CHAT_TAG_PREFIX$chatId"


    /**
     * The tap (#19): an explicit `MainActivity` intent with the chat id in
     * the `chatId` extra — iOS's `userInfo["chatId"]`, not a URL.
     * `singleTask`, so a warm app gets it in `onNewIntent` and a cold
     * start holds it in the hub's pending slot. No chat (the outage
     * notification) just opens the app.
     */
    private fun chatTapPendingIntent(tag: String, chatId: String?): PendingIntent {
        if (chatId == null) return rootTapPendingIntent()
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_CHAT
            putExtra(DeepLinks.EXTRA_CHAT_ID, chatId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return PendingIntent.getActivity(
            context,
            tag.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun rootTapPendingIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return PendingIntent.getActivity(
            context,
            "root".hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private companion object {
        const val TAG = "ZeronNotify"
        const val CHANNEL_VERSION = 1
        const val NOTIFICATION_ID = 1
        const val BUDGET_TAG = "budget"
        const val CHAT_TAG_PREFIX = "chat:"

        /** The tap's action: only labels the intent (it is explicit, no filter matches it). */
        const val ACTION_OPEN_CHAT = "sh.zeron.android.action.OPEN_CHAT"
        val SMALL_ICON = R.drawable.zi_zeron
    }
}

/**
 * The raw chime a kind's channel plays: ChimesSync names them after the
 * desktop files (`done` / `request` / `attention`), not after [NotificationKind.raw]
 * (`input` / `failed`). Kept from resource shrinking by `res/raw/keep_chimes.xml`
 * (referenced by URI string only).
 */
private fun NotificationKind.chimeResource(): String = when (this) {
    NotificationKind.Done -> "chime_done"
    NotificationKind.Input -> "chime_request"
    NotificationKind.Failed -> "chime_attention"
}

/** The system-settings channel name of a kind (Zeron web's labels). */
private fun NotificationKind.channelName(): String = when (this) {
    NotificationKind.Done -> "Task completed"
    NotificationKind.Input -> "Input required"
    NotificationKind.Failed -> "Errors"
}

/** The system-settings channel description of a kind. */
private fun NotificationKind.channelDescription(): String = when (this) {
    NotificationKind.Done -> "When an agent finishes a run"
    NotificationKind.Input -> "When an agent needs your response"
    NotificationKind.Failed -> "When a run fails"
}

/** #699's vibration patterns (pr699:core/Notifier.kt:61-63), in ms. */
private fun NotificationKind.vibrationPattern(): LongArray = when (this) {
    NotificationKind.Done -> longArrayOf(0, 24, 40, 30)
    NotificationKind.Input -> longArrayOf(0, 18, 90, 18)
    NotificationKind.Failed -> longArrayOf(0, 35, 55, 45)
}
