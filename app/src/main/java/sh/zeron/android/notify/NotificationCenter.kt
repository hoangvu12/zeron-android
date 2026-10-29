package sh.zeron.android.notify

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import sh.zeron.android.background.RunWatchController
import uniffi.zeron_core.WorkspaceSnapshot

/**
 * The Kotlin delivery half of the notifications (android-app#28), plus
 * the glue the app feeds it with.
 *
 * Zeron's core has no notification sink (no `SessionEvent`, `RunActivity`
 * or `setNotificationSink`), and the edge pushes only to APNs (D6): the
 * app feeds every workspace snapshot to [onSnapshot], where the
 * [SessionWatcher] applies the edge's done / needs-input / failed rule and
 * the gates decide what posts, and the [RunActivityTracker] feeds the
 * [RunWatchController] (#57) from the same snapshots.
 *
 * Threading: every callback hops to [scope] (Dispatchers.Default —
 * `NotificationManagerCompat.notify` is thread-safe) and is fully
 * wrapped: a coroutine in an app scope must never throw. The gates run at
 * delivery time (the permission may have been revoked between checks):
 * the foreground gate first (iOS shows nothing in front — the banner AND
 * the chime), then the master (pref + permission), then the kind.
 *
 * The run activity feeds the [RunWatchController] (the foreground
 * service); `resolved` and `linkRestored` cancel what they resolve.
 */
class NotificationCenter(
    private val context: Context,
    private val scope: CoroutineScope,
    private val controller: RunWatchController,
    private val prefs: NotificationPrefs = NotificationPrefs.get(context),
    private val notifier: Notifier = Notifier(context),
    private val status: () -> NotificationStatus = { NotificationPermission.status(context) },
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String, Throwable?) -> Unit = { message, error ->
        if (error == null) Log.w(TAG, message) else Log.w(TAG, message, error)
    },
) {

    /**
     * The process foreground state (ProcessLifecycleOwner ≥ STARTED), set
     * by the app's lifecycle observer. Volatile: the callbacks arrive on
     * a core thread.
     */
    @Volatile
    var foreground: Boolean = true

    // ── the app-side seams ───────────────────────────────────────────────

    /** Driven only from [scope]'s single lane, so they need no lock. */
    private val watcher = SessionWatcher()
    private val runs = RunActivityTracker()

    /**
     * Called with every client the session holder publishes (each
     * sign-in, and a launch with a stored account): the new account's
     * first snapshot only seeds, so nothing from before replays.
     */
    @Suppress("UNUSED_PARAMETER")
    fun attachTo(client: uniffi.zeron_core.CoreClient) {
        scope.launch {
            watcher.reset()
            runs.reset()
        }
    }

    /**
     * Every workspace snapshot the feed publishes (null while signed out):
     * the transitions it holds against the last one post, unless a gate
     * says no — the app is in front (iOS shows nothing there), the master
     * switch or the permission is off, or the kind is off. Baselines move
     * on regardless, so coming back from the front never replays.
     */
    fun onSnapshot(snapshot: WorkspaceSnapshot?) {
        scope.launch {
            try {
                if (snapshot == null) {
                    watcher.reset()
                    runs.reset()
                    controller.onRunActivity(emptyList(), 0)
                    return@launch
                }
                val rows = SessionWatcher.rows(snapshot)
                val activity = runs.update(rows)
                controller.onRunActivity(activity.working, activity.awaiting)
                val notices = watcher.update(rows)
                if (notices.isEmpty()) return@launch
                val current = prefs.state.value
                val permission = status()
                for (notice in notices) {
                    if (!NotificationPolicy.sessionEventAllowed(notice.kind, current, permission, foreground)) continue
                    notifier.postSessionEvent(notice.chatId, notice.title, notice.kind, notice.whenMs)
                }
            } catch (error: Throwable) {
                log("session notifications failed", error)
            }
        }
    }

    /**
     * Signed out (#18's listener; docs/port/05 K6): nothing of the account
     * is remembered, and every notification it raised goes.
     */
    fun signedOut() {
        scope.launch {
            try {
                watcher.reset()
                runs.reset()
                notifier.cancelAll()
            } catch (error: Throwable) {
                log("sign-out cancel failed", error)
            }
        }
    }

    /** A chat was opened in the app: cancel its notification. */
    fun cancelChatFor(chatId: String) {
        scope.launch {
            try {
                notifier.cancelChatFor(chatId)
            } catch (error: Throwable) {
                log("open-cancel for $chatId failed", error)
            }
        }
    }

    private companion object {
        const val TAG = "ZeronNotify"
    }
}
