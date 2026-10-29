package sh.zeron.android.core

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import sh.zeron.android.transcript.reconnects as risingEdges
import uniffi.zeron_core.ClientEvent
import uniffi.zeron_core.Connectivity
import uniffi.zeron_core.ConnectivityState
import uniffi.zeron_core.CoreClient
import uniffi.zeron_core.CoreException
import uniffi.zeron_core.DeviceView
import uniffi.zeron_core.WorkspaceSnapshot

/**
 * The workspace FFI seam — the calls the feed, the fold state and their
 * tests need (`zeron/crates/mobile/src/client_ffi/mod.rs`). Every method is
 * cheap, never blocks on the network and is safe from any thread;
 * [setSectionCollapsed] fails fast.
 */
interface WorkspaceCore {
    /** Current workspace revision (compare before pulling). */
    fun workspaceRevision(): ULong

    /** The whole derived workspace; its `devices` are Zeron's `devices()`. */
    fun workspace(): WorkspaceSnapshot

    /** The aggregate connectivity posture. */
    fun connectivity(): Connectivity

    /** Sets a user section's synced fold (optimistic overlay, background RPC). */
    @Throws(CoreException::class)
    fun setSectionCollapsed(sectionId: String, collapsed: Boolean)
}

/**
 * The [WorkspaceCore] over the live [CoreClient], resolved at call time:
 * `ZeronApplication` builds this over the session holder's
 * `clientOrNull()`, so the feed and the fold state always reach the
 * current account's client and never hold one that was signed out. While
 * signed out the calls refuse like an unreachable host — nothing in the
 * UI can reach them then (there is no snapshot to draw sections from).
 */
class CoreClientWorkspace(private val client: () -> CoreClient?) : WorkspaceCore {

    private fun live(): CoreClient =
        client() ?: throw CoreException.HostUnavailable("the Zeron core has not started yet")

    override fun workspaceRevision(): ULong = live().workspaceRevision()

    override fun workspace(): WorkspaceSnapshot = live().workspace()

    override fun connectivity(): Connectivity = live().connectivity()

    override fun setSectionCollapsed(sectionId: String, collapsed: Boolean) =
        live().setSectionCollapsed(sectionId, collapsed)
}

/**
 * The single Kotlin owner of the workspace snapshot — the port of iOS's
 * `AppModel.refreshWorkspace` and its triggers (`App/AppModel.swift`):
 *
 * - `WorkspaceChanged` → re-read (iOS `scheduleRefresh`). Bursts conflate
 *   to at most one pending read per in-flight one, and an event whose
 *   revision is already published is dropped before it costs a read.
 * - `ConnectivityChanged` → the event's own [Connectivity] is published
 *   (iOS stores `c` as is; no FFI call).
 * - [refresh] → an unconditional re-read: the foreground return (iOS
 *   `willEnterForeground`) and [runClock]'s 30 s tick (iOS's `clock`
 *   timer, so relative times like "4m" and the 45 s staleness gate age
 *   without events; docs/port/01 A13).
 *
 * A read publishes whenever the snapshot *differs* — not only when the
 * revision moved: the core derives `timeLabel` and the staleness-gated
 * `indicator` at read time, so a clock tick with the same revision can
 * still carry new labels. [devices] are the snapshot's own `devices`
 * (Zeron's `devices()` reads exactly that), so the two never disagree.
 *
 * The FFI never runs on the caller's thread: reads run on one serial lane
 * over [io], and `onEvent` (called from a Rust runtime thread) only does
 * non-blocking work.
 */
class WorkspaceFeed(
    private val core: () -> WorkspaceCore?,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    private val serial = io.limitedParallelism(1)
    private val dirty = Channel<Unit>(Channel.CONFLATED)

    private val _snapshot = MutableStateFlow<WorkspaceSnapshot?>(null)
    /** The merged workspace; null until the first read. */
    val snapshot: StateFlow<WorkspaceSnapshot?> = _snapshot.asStateFlow()

    private val _devices = MutableStateFlow<List<DeviceView>>(emptyList())
    /** Every device in the org (the snapshot's `devices`); empty until the first read. */
    val devices: StateFlow<List<DeviceView>> = _devices.asStateFlow()

    private val _connectivity = MutableStateFlow<Connectivity?>(null)
    /** The aggregate connectivity posture; null until the first read. */
    val connectivity: StateFlow<Connectivity?> = _connectivity.asStateFlow()

    /**
     * One emission each time the client becomes connected again after a
     * known non-connected state (an unknown `null` never counts) — failed
     * transcript image tiles retry at once (the follow-up of #8).
     */
    val reconnects: Flow<Unit> =
        risingEdges(connectivity.filterNotNull().map { it.state == ConnectivityState.CONNECTED })

    init {
        scope.launch(serial) {
            for (trigger in dirty) {
                val c = core() ?: continue // no client yet: reset() re-triggers
                // While signed out the live seam refuses with
                // HostUnavailable; the next trigger retries, so the lane
                // must outlive the refusal instead of failing the app scope.
                val ws = try { c.workspace() } catch (e: CoreException) { continue }
                if (ws != _snapshot.value) _snapshot.value = ws
                if (ws.devices != _devices.value) _devices.value = ws.devices
                if (_connectivity.value == null) {
                    // The first read: no ConnectivityChanged may come for a while.
                    val conn = try { c.connectivity() } catch (e: CoreException) { null }
                    if (conn != null && _connectivity.value == null) _connectivity.value = conn
                }
            }
        }
    }

    /** Re-reads the workspace (iOS `refreshWorkspace`); safe from any thread, before any client. */
    fun refresh() {
        dirty.trySend(Unit)
    }

    /**
     * The 30 s re-read clock (iOS `AppModel.clock`). Suspends until
     * cancelled; the app runs it only while it is in the foreground
     * (`repeatOnLifecycle(STARTED)`) — iOS timers don't fire while
     * suspended either, and the return itself re-reads at once.
     */
    suspend fun runClock(period: Duration = CLOCK_PERIOD) {
        while (true) {
            delay(period)
            refresh()
        }
    }

    /**
     * The client changed (sign-in, sign-out, `AuthExpired`): drop the old
     * account's lists, then read the new client's, if any. On the serial
     * lane, so a read of the old client already in flight lands before
     * the clear, never after it.
     */
    fun reset() {
        scope.launch(serial) {
            _snapshot.value = null
            _devices.value = emptyList()
            _connectivity.value = null
            refresh()
        }
    }

    /**
     * The app's [ClientEvent] sink — registered before `SessionHolder.boot()`
     * so it sees every event; called on a Rust runtime thread, so it only
     * conflates and returns.
     */
    fun onEvent(event: ClientEvent) {
        when (event) {
            is ClientEvent.WorkspaceChanged ->
                if (event.revision != _snapshot.value?.revision) refresh()
            is ClientEvent.ConnectivityChanged -> _connectivity.value = event.connectivity
            else -> {}
        }
    }

    companion object {
        /** iOS `Timer.scheduledTimer(withTimeInterval: 30, …)`. */
        val CLOCK_PERIOD: Duration = 30.seconds
    }
}
