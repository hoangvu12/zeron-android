package sh.zeron.android.core

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import uniffi.zeron_core.CoreClient

/**
 * The OS lifecycle calls the core needs (android-ui-parity #35;
 * `mob:client_ffi/mod.rs`), behind a seam so tests can
 * drive them with a fake. The real sink holds the live [CoreClient].
 *
 * - [onForeground] — app returned to the foreground: resume, redial/probe;
 * - [onBackground] — app backgrounding: persist now (flushes pending cache
 *   writes synchronously, bounded at 2 s — **never on the main thread**);
 * - [onBackgroundIdle] — backgrounded for a while and Android hasn't
 *   suspended the process: let go of every socket until [onForeground]
 *   (Android only; upstream `AppModel.kt`'s `rest`);
 * - [setNetworkOnline] — the OS network path; `true` resets backoff and
 *   redials (android-app#06), and re-sends are deduplicated by
 *   [NetworkPathTracker];
 * - [networkPathChanged] — another network, transport or metered state:
 *   direct connections re-pair (only on a real change).
 */
interface LifecycleSink {
    fun onForeground()

    fun onBackground()

    fun onBackgroundIdle()

    fun setNetworkOnline(online: Boolean)

    fun networkPathChanged()
}

/** Delayed main-thread work (the background rest), behind a seam so tests can run it by hand. */
interface RestScheduler {
    fun schedule(delayMs: Long, task: Runnable)

    fun cancel(task: Runnable)
}

/** The production [RestScheduler]: the main looper. */
class MainRestScheduler : RestScheduler {
    private val handler = Handler(Looper.getMainLooper())

    override fun schedule(delayMs: Long, task: Runnable) {
        handler.postDelayed(task, delayMs)
    }

    override fun cancel(task: Runnable) {
        handler.removeCallbacks(task)
    }
}

/** The production sink: the live [CoreClient]. Logs each call (`adb logcat -s Zeron`). */
class CoreLifecycleSink(private val client: CoreClient) : LifecycleSink {
    override fun onForeground() {
        Log.d(TAG, "onForeground")
        client.onForeground()
    }

    override fun onBackground() {
        Log.d(TAG, "onBackground")
        client.onBackground()
    }

    override fun onBackgroundIdle() {
        Log.d(TAG, "onBackgroundIdle")
        client.onBackgroundIdle()
    }

    override fun setNetworkOnline(online: Boolean) {
        Log.d(TAG, "setNetworkOnline($online)")
        client.setNetworkOnline(online)
    }

    override fun networkPathChanged() {
        Log.d(TAG, "networkPathChanged")
        client.networkPathChanged()
    }

    private companion object {
        const val TAG = "Zeron"
    }
}

/**
 * What one network callback asks of the client: [online] to send through
 * `setNetworkOnline` (null = unchanged), then `networkPathChanged()` when
 * [pathChanged].
 */
data class NetworkCalls(val online: Boolean?, val pathChanged: Boolean) {
    companion object {
        val None = NetworkCalls(online = null, pathChanged = false)
    }
}

/**
 * The process lifecycle hooks (android-ui-parity #35), registered **once
 * in `ZeronApplication.onCreate`** — never per `onCreate` of an activity
 * (#609's observer leak) and never from an activity's `onStart`/`onStop`
 * (#621's redial per rotation):
 *
 * - `ProcessLifecycleOwner` `ON_START` → [onStart]: `onForeground()` then
 *   [afterForeground][] (iOS `willEnterForeground` → `refreshWorkspace()`;
 *   the app passes the workspace feed's re-read);
 * - `ProcessLifecycleOwner` `ON_STOP` → [onStop]: `onBackground()` (the
 *   androidx owner dispatches it ~700 ms after the last activity stops,
 *   so a rotation never triggers it), then the **rest**: [REST_AFTER_MS]
 *   later, still in the background, `onBackgroundIdle()`. iOS suspends a
 *   backgrounded app, which is what stops its sockets; Android may not for
 *   hours, so once delivery has had its time the core lets go of them
 *   (upstream `AppModel.kt` `rest`). While [busy] (a live voice call, which
 *   runs in the background on purpose, or the delivery hold) it looks
 *   again every [REST_RECHECK_MS]; returning to the foreground cancels it;
 * - one `registerDefaultNetworkCallback`, never unregistered (iOS's
 *   `NWPathMonitor` also lives as long as the app model), feeding
 *   [NetworkPathTracker].
 *
 * Every core call goes through the **single-thread** executor, so the
 * order is preserved (background then foreground can never be reordered)
 * and the up-to-2 s `onBackground` flush never runs on the main thread.
 * With no client (loading, signed out, core failed) calls are dropped,
 * like iOS's `client?`; the network state is still tracked, and
 * [clientReady] re-sends it once whenever the session holder publishes a
 * client.
 */
class AppLifecycleHooks internal constructor(
    private val sink: () -> LifecycleSink?,
    private val executor: Executor,
    private val afterForeground: () -> Unit,
    private val scheduler: RestScheduler? = null,
    private val busy: () -> Boolean = { false },
) : DefaultLifecycleObserver {

    /**
     * The network state; seeded by [install] **before** the callback is
     * registered, then touched only from the `ConnectivityManager`
     * callback thread. `@Volatile` for the cross-thread [clientReady].
     */
    @Volatile
    private var tracker: NetworkPathTracker? = null

    /** The lifecycle callbacks and the rest all run on the main thread. */
    private var inForeground = true

    private val rest = object : Runnable {
        override fun run() {
            if (inForeground) return
            if (busy()) {
                scheduler?.schedule(REST_RECHECK_MS, this)
            } else {
                executor.execute { sink()?.onBackgroundIdle() }
            }
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        inForeground = true
        scheduler?.cancel(rest)
        executor.execute {
            sink()?.onForeground()
            afterForeground()
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        inForeground = false
        executor.execute { sink()?.onBackground() }
        scheduler?.cancel(rest)
        scheduler?.schedule(REST_AFTER_MS, rest)
    }

    /** Forwards a network change (already deduplicated): online first, then the path, as on iOS. */
    internal fun network(calls: NetworkCalls) {
        if (calls == NetworkCalls.None) return
        executor.execute {
            val sink = sink() ?: return@execute
            calls.online?.let(sink::setNetworkOnline)
            if (calls.pathChanged) sink.networkPathChanged()
        }
    }

    /**
     * Called by the session holder's wiring each time a client is
     * published: re-sends the current online value once, so the fresh
     * client starts with the real network state even if it was captured
     * before the client existed.
     */
    fun clientReady() {
        val online = tracker?.online ?: return
        executor.execute { sink()?.setNetworkOnline(online) }
    }

    companion object {
        private const val TAG = "Zeron"
        private const val THREAD_NAME = "zeron-lifecycle"

        /** How long after backgrounding the core lets go of its sockets (upstream `REST_AFTER_MS`). */
        const val REST_AFTER_MS = 30_000L

        /** How often a busy rest looks again (upstream `REST_RECHECK_MS`). */
        const val REST_RECHECK_MS = 60_000L

        /** Process-wide: exactly one install per process (the #609 leak guard). */
        private val installed = AtomicBoolean(false)

        @Volatile
        private var instance: AppLifecycleHooks? = null

        /**
         * Call exactly once, from `ZeronApplication.onCreate` (main
         * thread). A second call logs a warning and returns the first
         * instance, adding no second observer. [busy] holds the rest off
         * (a live voice call, the delivery hold); read on the main thread.
         */
        @Synchronized
        fun install(
            app: Application,
            lifecycle: Lifecycle,
            sink: () -> LifecycleSink?,
            afterForeground: () -> Unit = {},
            busy: () -> Boolean = { false },
        ): AppLifecycleHooks {
            if (!installed.compareAndSet(false, true)) {
                Log.w(TAG, "AppLifecycleHooks already installed")
                return checkNotNull(instance) { "AppLifecycleHooks install raced" }
            }
            val executor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, THREAD_NAME).apply { isDaemon = true }
            }
            val hooks = AppLifecycleHooks(sink, executor, afterForeground, MainRestScheduler(), busy)
            instance = hooks
            lifecycle.addObserver(hooks)
            seedNetwork(app, hooks)
            return hooks
        }

        /**
         * Robolectric reuses one JVM across test classes: forget the
         * installed instance so each test installs its own.
         */
        @VisibleForTesting
        @Synchronized
        internal fun resetForTest() {
            installed.set(false)
            instance = null
        }

        /**
         * The initial reading (with no network at launch Android reports
         * nothing at all) plus the one callback for the life of the process
         * (upstream `AppModel.watchNetwork`). Registration is wrapped in
         * `runCatching` (`pr621:NetworkWatcher.kt:49`) — a device without a
         * `ConnectivityManager` service still gets the lifecycle hooks.
         *
         * `onAvailable` says nothing `onCapabilitiesChanged` doesn't: it
         * always follows immediately. `onBlockedStatusChanged` (Doze,
         * restricted background data) counts as no path.
         */
        private fun seedNetwork(app: Application, hooks: AppLifecycleHooks) {
            val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
            val tracker = NetworkPathTracker()
            tracker.seed(
                cm.activeNetwork?.let { network ->
                    cm.getNetworkCapabilities(network)?.let { networkShape(network, it) }
                },
            )
            hooks.tracker = tracker
            // Dropped when there is no client yet; clientReady() re-sends.
            hooks.network(NetworkCalls(tracker.online, pathChanged = false))
            runCatching {
                cm.registerDefaultNetworkCallback(
                    object : ConnectivityManager.NetworkCallback() {
                        override fun onCapabilitiesChanged(
                            network: Network,
                            networkCapabilities: NetworkCapabilities,
                        ) {
                            hooks.network(tracker.update(networkShape(network, networkCapabilities)))
                        }

                        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                            hooks.network(tracker.setBlocked(blocked))
                        }

                        override fun onLost(network: Network) {
                            hooks.network(tracker.update(null))
                        }
                    },
                )
            }
        }
    }
}
