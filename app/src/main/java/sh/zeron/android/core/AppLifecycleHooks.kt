package sh.zeron.android.core

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
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
 * The four OS lifecycle calls the core needs (android-ui-parity #35;
 * `mob:client_ffi/mod.rs:537-560`), behind a seam so tests can
 * drive them with a fake. The real sink holds the live [CoreClient].
 *
 * - [onForeground] — app returned to the foreground: resume, redial/probe;
 * - [onBackground] — app backgrounding: persist now (flushes pending cache
 *   writes synchronously, bounded at 2 s — **never on the main thread**);
 * - [setNetworkOnline] — the OS network path; `true` resets backoff and
 *   redials (android-app#06), and re-sends are deduplicated by
 *   [NetworkOnlineTracker];
 * - [networkPathChanged] — another interface or route: direct
 *   connections re-pair (`AppModel.swift:104-122`, only on a real change).
 */
interface LifecycleSink {
    fun onForeground()

    fun onBackground()

    fun setNetworkOnline(online: Boolean)

    fun networkPathChanged()
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
 * Pure dedupe logic for the default-network callback
 * (android-ui-parity #35, `NWPathMonitor`'s Kotlin stand-in,
 * `AppModel.swift:104-122`).
 *
 * `setNetworkOnline` is deduplicated on the pair
 * `(defaultNetworkHandle, online)`: a capability callback that changes
 * only bandwidth or signal strength produces no call, and an interface
 * change (Wi-Fi → cellular) sends `true` again, as on iOS — it resets the
 * redial backoff.
 *
 * `networkPathChanged` follows iOS's path *shape*: the default network
 * when online, empty when not. It fires when the shape changes to a
 * non-empty one after the first reading — a handover to another network,
 * or coming back online — and never for the first reading (the install
 * seed) or for going offline.
 *
 * Touched from the `ConnectivityManager` callback thread (after install)
 * and read by [AppLifecycleHooks.clientReady] on another thread.
 */
class NetworkOnlineTracker(initialNetwork: Long?, initialOnline: Boolean) {

    /** The last online value; volatile for the cross-thread `clientReady()` read. */
    @Volatile
    var online: Boolean = initialOnline
        private set

    private var current: Long? = initialNetwork

    /** The path shape last seen: the seed is the first reading. */
    private var shape: String = shapeOf(initialNetwork, initialOnline)

    /** `onAvailable`: a new (or handover) default network. */
    fun available(net: Long): NetworkCalls {
        val changed = net != current || !online
        current = net
        online = true
        return calls(if (changed) true else null)
    }

    /** `onCapabilitiesChanged`: only an INTERNET capability flip matters. */
    fun capabilities(net: Long, hasInternet: Boolean): NetworkCalls {
        if (net != current || hasInternet == online) return NetworkCalls.None
        online = hasInternet
        return calls(hasInternet)
    }

    /** `onLost`: only the current default going away with no replacement. */
    fun lost(net: Long): NetworkCalls {
        if (net != current) return NetworkCalls.None
        current = null
        if (!online) return NetworkCalls.None
        online = false
        return calls(false)
    }

    private fun calls(send: Boolean?): NetworkCalls {
        val next = shapeOf(current, online)
        val pathChanged = next != shape && next.isNotEmpty()
        shape = next
        return NetworkCalls(send, pathChanged)
    }

    private companion object {
        fun shapeOf(network: Long?, online: Boolean): String =
            if (online && network != null) network.toString() else ""
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
 *   so a rotation never triggers it);
 * - one `registerDefaultNetworkCallback`, never unregistered (iOS's
 *   `NWPathMonitor` also lives as long as the app model), feeding
 *   [NetworkOnlineTracker].
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
) : DefaultLifecycleObserver {

    /**
     * The network state; seeded by [install] **before** the callback is
     * registered, then touched only from the `ConnectivityManager`
     * callback thread. `@Volatile` for the cross-thread [clientReady].
     */
    @Volatile
    private var tracker: NetworkOnlineTracker? = null

    override fun onStart(owner: LifecycleOwner) {
        executor.execute {
            sink()?.onForeground()
            afterForeground()
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        executor.execute { sink()?.onBackground() }
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

        /** Process-wide: exactly one install per process (the #609 leak guard). */
        private val installed = AtomicBoolean(false)

        @Volatile
        private var instance: AppLifecycleHooks? = null

        /**
         * Call exactly once, from `ZeronApplication.onCreate` (main
         * thread). A second call logs a warning and returns the first
         * instance, adding no second observer.
         */
        @Synchronized
        fun install(
            app: Application,
            lifecycle: Lifecycle,
            sink: () -> LifecycleSink?,
            afterForeground: () -> Unit = {},
        ): AppLifecycleHooks {
            if (!installed.compareAndSet(false, true)) {
                Log.w(TAG, "AppLifecycleHooks already installed")
                return checkNotNull(instance) { "AppLifecycleHooks install raced" }
            }
            val executor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, THREAD_NAME).apply { isDaemon = true }
            }
            val hooks = AppLifecycleHooks(sink, executor, afterForeground)
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
         * The initial reading (the iOS monitor's first callback) plus the
         * one callback for the life of the process. Registration is
         * wrapped in `runCatching` (`pr621:NetworkWatcher.kt:49`) — a
         * device without a `ConnectivityManager` service still gets the
         * lifecycle hooks.
         */
        private fun seedNetwork(app: Application, hooks: AppLifecycleHooks) {
            val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
            val active = cm.activeNetwork
            val online = active?.let { network ->
                cm.getNetworkCapabilities(network)
                    ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            } == true
            val tracker = NetworkOnlineTracker(active?.networkHandle, online)
            hooks.tracker = tracker
            // Dropped when there is no client yet; clientReady() re-sends.
            hooks.network(NetworkCalls(online, pathChanged = false))
            runCatching {
                cm.registerDefaultNetworkCallback(
                    object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            hooks.network(tracker.available(network.networkHandle))
                        }

                        override fun onCapabilitiesChanged(
                            network: Network,
                            networkCapabilities: NetworkCapabilities,
                        ) {
                            hooks.network(
                                tracker.capabilities(
                                    network.networkHandle,
                                    networkCapabilities.hasCapability(
                                        NetworkCapabilities.NET_CAPABILITY_INTERNET,
                                    ),
                                ),
                            )
                        }

                        override fun onLost(network: Network) {
                            hooks.network(tracker.lost(network.networkHandle))
                        }
                    },
                )
            }
        }
    }
}
