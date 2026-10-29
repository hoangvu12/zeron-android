package sh.zeron.android.notify

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import sh.zeron.android.background.RunWatchController

/**
 * The Kotlin delivery half of the notifications (android-app#28), plus
 * the glue the app feeds it with.
 *
 * TODO(#56): Zeron's core has no notification sink (no `SessionEvent`,
 * `RunActivity` or `setNotificationSink`). D6's Kotlin `SessionWatcher`
 * derives done / needs-input / failed from workspace snapshots and feeds
 * [notifier] and the [RunWatchController] (#57); until then nothing is
 * posted.
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
    private val engineCount: () -> Int = { 0 },
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

    /**
     * The engine count source (the app's startup state); set after
     * construction by [setEngineCountSource] because the application's
     * startup pipeline outlives this constructor call site.
     */
    @Volatile
    private var engineCountSource: () -> Int = engineCount

    /** Point the subText engine-count gate at the live source. */
    fun setEngineCountSource(source: () -> Int) {
        engineCountSource = source
    }

    // ── the app-side seams ───────────────────────────────────────────────

    /**
     * Called with every client the session holder publishes (each
     * sign-in). TODO(#56): start the `SessionWatcher` here, replacing
     * the previous account's.
     */
    @Suppress("UNUSED_PARAMETER")
    fun attachTo(client: uniffi.zeron_core.CoreClient) = Unit

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
