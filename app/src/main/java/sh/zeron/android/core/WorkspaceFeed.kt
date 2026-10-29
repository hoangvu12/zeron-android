package sh.zeron.android.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uniffi.zeron_core.ClientEvent
import uniffi.zeron_core.Connectivity
import uniffi.zeron_core.CoreClient
import uniffi.zeron_core.CoreException
import uniffi.zeron_core.DeviceView
import uniffi.zeron_core.WorkspaceSnapshot

/**
 * The workspace FFI seam (ticket 40) — the four calls the feed, the fold
 * state and their tests need (`mob:client_ffi/workspace.rs`, android-app#07).
 * Every method is cheap, never blocks on the network and is safe from any
 * thread; [setSectionCollapsed] fails fast and never throws for a reason
 * that a fold retry could not survive.
 */
interface WorkspaceCore {
    /** Current workspace revision (compare before pulling). */
    fun workspaceRevision(): ULong

    /** The whole derived workspace. */
    fun workspace(): WorkspaceSnapshot

    /** Every device in the org (Zeron's `devices()`; Roboco's `engines()`). */
    fun devices(): List<DeviceView>

    /** The aggregate connectivity posture (ticket 50 reads it). */
    fun connectivity(): Connectivity

    /** Sets a user section's engine-synced fold (optimistic overlay, background RPC). */
    @Throws(CoreException::class)
    fun setSectionCollapsed(sectionId: String, collapsed: Boolean)
}

/**
 * The [WorkspaceCore] over the live [CoreClient], resolved at call time:
 * `ZeronApplication` builds this over the session holder's
 * `clientOrNull()`, so the feed and the fold state always reach the
 * current account's client and never hold one that was signed out. While
 * signed out the calls refuse like an unreachable engine — nothing in the
 * UI can reach them then (there is no snapshot to draw sections from).
 */
class CoreClientWorkspace(private val client: () -> CoreClient?) : WorkspaceCore {

    private fun live(): CoreClient =
        client() ?: throw CoreException.HostUnavailable("the Zeron core has not started yet")

    override fun workspaceRevision(): ULong = live().workspaceRevision()

    override fun workspace(): WorkspaceSnapshot = live().workspace()

    override fun devices(): List<DeviceView> = live().devices()

    override fun connectivity(): Connectivity = live().connectivity()

    override fun setSectionCollapsed(sectionId: String, collapsed: Boolean) =
        live().setSectionCollapsed(sectionId, collapsed)
}

/**
 * The single Kotlin owner of the workspace snapshot (ticket 40 — the
 * settled feed-owner of this set): the `StateFlow<WorkspaceSnapshot?>` that
 * ticket 27 counts from, tickets 42/49/92/110 read, and ticket 40's model
 * builds the Sessions tab on.
 *
 * Re-reads are **revision-gated** (the core coalesces events into bursts
 * that carry only revisions): a `WorkspaceChanged{revision}` event triggers
 * a read only when the revision moved, the read itself publishes only when
 * the fetched revision differs, and bursts conflate to at most one pending
 * read per in-flight one — a burst of N events costs at most two
 * `workspace()` calls. Device views and connectivity refresh the same way
 * on `WorkspaceChanged`/`ConnectivityChanged`.
 *
 * The FFI never runs on the caller's thread: both drains run on one serial
 * lane over [io], and `onEvent` (called from a Rust runtime thread) only
 * does non-blocking `trySend`s.
 */
class WorkspaceFeed(
    private val core: () -> WorkspaceCore?,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    private val serial = io.limitedParallelism(1)
    private val workspaceDirty = Channel<Unit>(Channel.CONFLATED)
    private val devicesDirty = Channel<Unit>(Channel.CONFLATED)

    private val _snapshot = MutableStateFlow<WorkspaceSnapshot?>(null)
    /** The merged workspace; null until the first read. */
    val snapshot: StateFlow<WorkspaceSnapshot?> = _snapshot.asStateFlow()

    private val _devices = MutableStateFlow<List<DeviceView>>(emptyList())
    /** Every device in the org; empty until the first read. */
    val devices: StateFlow<List<DeviceView>> = _devices.asStateFlow()

    private val _connectivity = MutableStateFlow<Connectivity?>(null)
    /** The aggregate connectivity posture; null until the first read (ticket 50). */
    val connectivity: StateFlow<Connectivity?> = _connectivity.asStateFlow()

    init {
        scope.launch(serial) {
            for (trigger in workspaceDirty) {
                val c = core() ?: continue // no client yet: start() re-triggers when Ready
                // Before Ready (or after a failed start) the live seam refuses
                // with HostUnavailable; the next trigger retries, so the lane
                // must outlive the refusal instead of failing the app scope.
                val ws = try { c.workspace() } catch (e: CoreException) { continue }
                if (ws.revision != _snapshot.value?.revision) _snapshot.value = ws
            }
        }
        scope.launch(serial) {
            for (trigger in devicesDirty) {
                val c = core() ?: continue
                val deviceViews = try { c.devices() } catch (e: CoreException) { continue }
                if (deviceViews != _devices.value) _devices.value = deviceViews
                // Ticket 50: the aggregate connectivity posture, read on
                // the same drain, so the devices and the posture never
                // disagree. Off the main thread, like the devices read
                // above it.
                val conn = try { c.connectivity() } catch (e: CoreException) { continue }
                if (conn != _connectivity.value) _connectivity.value = conn
            }
        }
    }

    /** Triggers a first read of both lists; safe to call before the client exists. */
    fun start() {
        workspaceDirty.trySend(Unit)
        devicesDirty.trySend(Unit)
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
            start()
        }
    }

    /**
     * The app's [ClientEvent] sink — registered before `SessionHolder.boot()`
     * so it sees every event; called on a Rust runtime thread, so it only
     * conflates and returns.
     */
    fun onEvent(event: ClientEvent) {
        when (event) {
            // Zeron has no `EnginesChanged`: device presence rides on the
            // workspace (`WorkspaceSnapshot.devices`), so a workspace change
            // re-reads the devices too.
            is ClientEvent.WorkspaceChanged -> {
                if (event.revision != _snapshot.value?.revision) workspaceDirty.trySend(Unit)
                devicesDirty.trySend(Unit)
            }
            is ClientEvent.ConnectivityChanged -> devicesDirty.trySend(Unit)
            else -> {}
        }
    }
}
