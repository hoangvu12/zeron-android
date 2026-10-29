package sh.zeron.android.core

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import sh.zeron.android.BuildConfig
import sh.zeron.android.session.SessionEventHub
import uniffi.zeron_core.ClientEvent
import uniffi.zeron_core.ClientListener
import uniffi.zeron_core.CoreClient
import uniffi.zeron_core.CoreConfig
import uniffi.zeron_core.EngineView

/** The startup pipeline's state; the splash (ticket 35) holds on `Loading`. */
sealed interface CoreStartState {
    data object Loading : CoreStartState
    data object Ready : CoreStartState
    data class Failed(val reason: String) : CoreStartState
}

/**
 * One consumer of the core's event stream. Called on a Rust runtime
 * thread — hop threads yourself and return fast (the #621 self-repost
 * trap is why the method is *not* named `onEvent`).
 */
fun interface ClientEventSink {
    fun onClientEvent(event: ClientEvent)
}

/**
 * The app's startup (ticket 71, the port of `AppModel.init`/`start`):
 * build the single [CoreClient], publish its engine views, then flip
 * [state] to `Ready`. The credentials it starts from arrive with sign-in
 * (#10 swaps this onto the Zeron core's `CoreClient(config, credentials,
 * listener)`).
 *
 * Also owns the app's single [ClientListener]: `EnginesChanged`
 * refreshes [engines] through a coalescing flow, every event fans
 * out to the [ClientEventSink]s other tickets register (40/42) —
 * register them before [start] to see every event — and every event
 * hops to the [sessionEvents] hub (51) for the session controllers.
 * `onEvent` itself only does non-blocking `trySend`/`tryEmit` plus the
 * fan-out, so it is safe to call from the Rust callback thread while
 * the client is still constructing.
 */
class CoreStartup(
    private val context: Context,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
    private val newClient: (CoreConfig, ClientListener) -> CoreClient = { config, listener ->
        CoreClient(config, emptyList(), emptyList(), listener)
    },
    private val onClientReady: (CoreClient) -> Unit = {},
    /**
     * The app-scoped session event hub (ticket 51): one line of wiring
     * per event, fan-out on main inside the hub. Ticket 76's
     * `SessionRouteFactory` passes this to `SessionController.create`.
     * Tests inject a hub with their own poster to observe the one hop.
     */
    val sessionEvents: SessionEventHub = SessionEventHub(),
) {

    private val _state = MutableStateFlow<CoreStartState>(CoreStartState.Loading)
    val state: StateFlow<CoreStartState> = _state.asStateFlow()

    /** Null until `Ready`; then `engines()`, re-read on every `EnginesChanged`. */
    private val _engines = MutableStateFlow<List<EngineView>?>(null)
    val engines: StateFlow<List<EngineView>?> = _engines.asStateFlow()

    private val clientRef = AtomicReference<CoreClient?>(null)
    private val started = AtomicBoolean(false)
    private val sinks = CopyOnWriteArrayList<ClientEventSink>()

    private val enginesDirty = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** The listener handed to `CoreClient`; tests deliver events through it. */
    @VisibleForTesting
    internal val listener: ClientListener = object : ClientListener {
        override fun onEvent(event: ClientEvent) = routeClientEvent(event)
    }

    init {
        scope.launch(compute) {
            enginesDirty.collect { clientRef.get()?.let { _engines.value = it.engines() } }
        }
    }

    /** Idempotent: the second call does nothing. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch(io) {
            try {
                // 1. The device label is read once per process.
                val dataDir = File(context.noBackupFilesDir, "core").apply { mkdirs() }
                val config = CoreConfig(
                    dataDir = dataDir.path,
                    deviceName = DeviceLabel.read(context),
                    platform = "android",
                    appVersion = BuildConfig.VERSION_NAME,
                    showMockHarness = BuildConfig.DEBUG,
                )
                // 2. The constructor never dials.
                val client = try {
                    newClient(config, listener)
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    Log.e(TAG, "core start failed", error)
                    _state.value = CoreStartState.Failed(error.message ?: error.javaClass.simpleName)
                    return@launch
                }
                // 3. Order matters: engines published before Ready, so
                //    anyone who sees Ready sees a non-null list.
                clientRef.set(client)
                _engines.value = client.engines()
                _state.value = CoreStartState.Ready
                // 4.
                onClientReady(client)
            } catch (cancelled: CancellationException) {
                throw cancelled
            }
        }
    }

    /** The live client, or null before `Ready`/after a failed start. */
    fun clientOrNull(): CoreClient? = clientRef.get()

    /** Registers an event sink; do this before [start] to see every event. */
    fun addEventSink(sink: ClientEventSink) {
        sinks.add(sink)
    }

    private fun routeClientEvent(event: ClientEvent) {
        // Ticket 51: one hop to main per event for the session
        // controllers. Qualified on purpose — a bare `onEvent(event)`
        // here would re-post every event forever (#621).
        sessionEvents.onClientEvent(event)
        when (event) {
            ClientEvent.EnginesChanged -> enginesDirty.tryEmit(Unit)
            else -> {}
        }
        for (sink in sinks) {
            try {
                sink.onClientEvent(event)
            } catch (error: Throwable) {
                Log.w(TAG, "client event sink failed", error)
            }
        }
    }

    private companion object {
        const val TAG = "Zeron"
    }
}
