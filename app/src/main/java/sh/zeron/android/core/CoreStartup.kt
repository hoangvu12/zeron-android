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
import uniffi.zeron_core.Credentials
import uniffi.zeron_core.authProductionEdgeUrl

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
 * build the single [CoreClient] from the stored [credentials], then flip
 * [state] to `Ready`. With no stored credentials it flips to `Ready`
 * without a client (the signed-out Welcome root).
 *
 * TODO(#13): replaced by the session holder (`StateFlow<CoreClient?>`,
 * `start(credentials)`, `signOutLocally`, per-account core dir); the
 * credentials come from #14's `CredentialStore`.
 *
 * Also owns the app's single [ClientListener]: every event fans
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
    /** TODO(#14): the `CredentialStore` read; nothing is stored before sign-in (#15). */
    private val credentials: () -> Credentials? = { null },
    private val newClient: (CoreConfig, Credentials, ClientListener) -> CoreClient = { config, credentials, listener ->
        CoreClient(config, credentials, listener)
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

    private val clientRef = AtomicReference<CoreClient?>(null)
    private val started = AtomicBoolean(false)
    private val sinks = CopyOnWriteArrayList<ClientEventSink>()

    /** The listener handed to `CoreClient`; tests deliver events through it. */
    @VisibleForTesting
    internal val listener: ClientListener = object : ClientListener {
        override fun onEvent(event: ClientEvent) = routeClientEvent(event)
    }

    /** Idempotent: the second call does nothing. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch(io) {
            try {
                // 1. Signed out: the Welcome root, no client.
                val stored = credentials()
                if (stored == null) {
                    _state.value = CoreStartState.Ready
                    return@launch
                }
                // 2. The device label and id are read once per process.
                val dataDir = File(context.noBackupFilesDir, "core").apply { mkdirs() }
                val config = CoreConfig(
                    edgeUrl = authProductionEdgeUrl(),
                    dataDir = dataDir.path,
                    deviceId = DeviceId.read(context),
                    deviceName = DeviceLabel.read(context),
                    platform = "android",
                    appVersion = BuildConfig.VERSION_NAME,
                )
                // 3. The constructor never dials.
                val client = try {
                    newClient(config, stored, listener)
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    Log.e(TAG, "core start failed", error)
                    _state.value = CoreStartState.Failed(error.message ?: error.javaClass.simpleName)
                    return@launch
                }
                // 4. Order matters: the client is set before Ready, so
                //    anyone who sees Ready sees it.
                clientRef.set(client)
                _state.value = CoreStartState.Ready
                // 5.
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
