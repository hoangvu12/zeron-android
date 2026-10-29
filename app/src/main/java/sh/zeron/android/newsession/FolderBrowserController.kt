package sh.zeron.android.newsession

import android.os.Looper
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.zeron_core.CoreClient
import uniffi.zeron_core.FolderListing

/**
 * The folder browser's browsing brain (android-ui-parity #110, #27): the
 * device pick, the load guard, the status strings and the "listing usable"
 * rule — the port of `ios:Threads/NewProjectViewController.swift:85-146`.
 *
 * The screen (`NewProjectScreen.kt`) draws the nav bar, the device menu
 * and the folder rows; this file never draws. The core is reached only
 * through [FolderBrowserSource], host-path arithmetic only through
 * [HostPathOps], so the tests need no native library. Everything mutates
 * on the main thread; the FFI call runs inside [CoreFolderBrowserSource]
 * on `Dispatchers.IO`, never on main.
 */

/** `listFolders(deviceId, path?)` (suspend): null on any failure (the iOS `try?`), never throws except cancellation. */
fun interface FolderBrowserSource {
    suspend fun listFolders(deviceId: String, path: String?): FolderListing?
}

/**
 * The source over the live core: resolves [client] at call time (null →
 * `null` — iOS's `try?` on a missing client), calls `listFolders` on [io]
 * and swallows every non-cancellation failure with a log.
 */
class CoreFolderBrowserSource(
    private val client: () -> CoreClient?,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : FolderBrowserSource {
    override suspend fun listFolders(deviceId: String, path: String?): FolderListing? {
        val c = client() ?: return null
        return try {
            withContext(io) { c.listFolders(deviceId = deviceId, path = path) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("FolderBrowser", "listFolders failed", e)
            null
        }
    }
}

/** One row of the folder list: `".."` (the parent) or a directory from the listing. */
sealed interface FolderRow {
    /** The list's key: unique within one listing. */
    val key: String

    /** `items.append("..")` — `Parent Folder`, always first when present. */
    data object Parent : FolderRow {
        override val key: String = ".."
    }

    /** `listing.entries.filter(\.isDir)` — files are never listed. */
    data class Folder(val name: String, val isRepo: Boolean) : FolderRow {
        override val key: String get() = "dir:$name"
    }
}

/** The screen's copy, verbatim from the Swift source. */
object FolderBrowserCopy {
    const val DefaultTitle = "New Project"
    const val ParentFolder = "Parent Folder"

    /** `Loading…` — U+2026, as in the Swift source. */
    const val Loading = "Loading…"

    /** Straight apostrophe U+0027, as in the Swift source. */
    const val ReadFailed = "Couldn't read that folder."

    /** The status with no host at all (U+2014). */
    const val NoDevices = "No desktop devices yet — open Zeron on a computer to add one."

    /** The device button's title with no host (`device?.name ?? "No hosts"`). */
    const val NoHosts = "No hosts"

    /** The device menu's title. */
    const val Device = "Device"
    const val Online = "Online"
    const val Offline = "Offline"

    /** `"\(device.name) is offline."` */
    fun offline(name: String) = "$name is offline."
}

/**
 * The load-guard state holder (the `load(_:)` / `didSelectItemAt` /
 * host-picker behaviour of `NewProjectViewController.swift`):
 *
 * - **Device pick** — the first online host, else the first
 *   (`hosts.first(where: \.online) ?? hosts.first`); the menu re-picks and
 *   reloads at the host's home.
 * - **Generation guard** — every load bumps a private generation counter;
 *   a reply whose generation is stale is dropped without any state
 *   change, exactly like iOS's `guard generation == loadGeneration`.
 *   Cancelling the previous job is never a substitute — UniFFI futures may
 *   complete after cancellation — only the compare guarantees a late reply
 *   is never applied.
 * - **No clearing on failure** — a failed or in-flight load keeps the last
 *   good `listing` (iOS keeps the last rows); the tap guard, not an empty
 *   list, protects the user.
 * - **`listingUsable`** — iOS's `useButton.isEnabled` made declarative:
 *   false while loading, true after a success, and after a failure true
 *   only when the last good listing came from the currently selected
 *   device.
 *
 * All mutators are main-thread only; the reply is applied on [scope]
 * (main). Screen disposed → the scope is cancelled; in-flight replies are
 * discarded.
 */
@Stable
class FolderBrowserController(
    private val source: FolderBrowserSource,
    private val paths: HostPathOps,
    private val scope: CoroutineScope,
    /** Non-null → devices/device are set synchronously in init (no load — no coroutine may launch during composition). */
    initialHosts: List<HostOption>? = null,
) {
    /** The live host list (iOS reads `hostOptions` once; Zeron's list is live). */
    var devices: List<HostOption> by mutableStateOf(emptyList())
        private set

    /** The selected host (iOS `device`); the first online one by default, else the first. */
    var device: HostOption? by mutableStateOf(null)
        private set

    /** The last good listing; never cleared by a failure or a load. */
    var listing: FolderListing? by mutableStateOf(null)
        private set

    /** The device the [listing] came from (iOS's `listingDevice`). */
    var listingDeviceId: String? by mutableStateOf(null)
        private set

    /**
     * `isRepo` of the row tapped to reach [listing]; null = unknown (home,
     * parent, device switch). Zeron listings leave dot-entries out, so
     * iOS's `.git` check alone would never see a repo — the Use action ORs
     * this in.
     */
    var listingIsRepo: Boolean? by mutableStateOf(null)
        private set

    /** The listing's path (iOS's `path`). */
    var path: String? by mutableStateOf(null)
        private set

    var loading: Boolean by mutableStateOf(false)
        private set

    /** Owned by [FolderBrowserActions] (the create in flight); read by the tap guard. */
    var submitting: Boolean by mutableStateOf(false)

    /** The status line; [FolderBrowserBottomBar] draws it. */
    var status: String by mutableStateOf("")

    /** Bumps on every applied listing; the screen uses it in row keys (scroll retention). */
    var listingSerial: Int by mutableIntStateOf(0)
        private set

    init {
        initialHosts?.let { hosts ->
            devices = hosts
            device = defaultPick(hosts)
        }
    }

    /** The parent row (when the path has one) plus the directories, in host order. */
    val rows: List<FolderRow> by derivedStateOf {
        val l = listing ?: return@derivedStateOf emptyList()
        buildList {
            if (paths.parent(l.path) != null) add(FolderRow.Parent)
            for (entry in l.entries) if (entry.isDir) add(FolderRow.Folder(entry.name, entry.isRepo))
        }
    }

    /** The nav-bar title: the folder name, or `New Project` for roots and before the first listing. */
    val title: String by derivedStateOf {
        listing?.let { l -> paths.name(l.path) }?.takeIf { it.isNotEmpty() } ?: FolderBrowserCopy.DefaultTitle
    }

    /** The device button's label: the selected host's name, or `No hosts`. */
    val deviceTitle: String get() = device?.name ?: FolderBrowserCopy.NoHosts

    /** iOS's `useButton.isEnabled`, made declarative (see the class doc). */
    val listingUsable: Boolean
        get() {
            val d = device ?: return false
            return !loading && listing != null && listingDeviceId == d.id
        }

    private var generation = 0
    private var started = false

    /**
     * First call: devices = hosts, default pick, `load(null)` — also when
     * init already set them from `initialHosts` (the replayed StateFlow
     * value). Later calls refresh [devices]; a null [device] plus a
     * non-empty list (or a selected id that vanished) re-runs the default
     * pick and loads; otherwise the same-id entry replaces [device] (fresh
     * name/online) **without** reloading.
     */
    fun updateDevices(hosts: List<HostOption>) {
        checkMainThread()
        if (!started) {
            started = true
            devices = hosts
            device = defaultPick(hosts)
            load(null)
            return
        }
        devices = hosts
        val current = device
        when {
            current == null && hosts.isNotEmpty() -> {
                device = defaultPick(hosts)
                load(null)
            }
            current != null && hosts.none { it.id == current.id } -> {
                // The selected host left the workspace while the sheet was
                // open: the default pick runs again (may be null → the
                // no-devices status).
                device = defaultPick(hosts)
                load(null)
            }
            current != null -> device = hosts.first { it.id == current.id }
            else -> Unit // still no hosts: nothing to pick, nothing to reload
        }
    }

    /** The menu's pick (also the current one — iOS reloads at home either way). */
    fun selectDevice(id: String) {
        checkMainThread()
        device = devices.firstOrNull { it.id == id } ?: return
        load(null)
    }

    /**
     * `load(_:)`: `Loading…` + a generation bump, then the FFI call on the
     * scope; a stale reply changes nothing at all. With no device: the
     * no-devices status and a generation bump (so a vanished host's
     * in-flight reply can never apply), nothing else.
     */
    fun load(path: String?, isRepoHint: Boolean? = null) {
        checkMainThread()
        val d = device ?: run {
            generation++
            loading = false
            status = FolderBrowserCopy.NoDevices
            return
        }
        loading = true
        status = FolderBrowserCopy.Loading
        val gen = ++generation
        scope.launch {
            val result = source.listFolders(d.id, path)
            // A newer load (another device or folder) superseded this one.
            if (gen != generation) return@launch
            loading = false
            if (result == null) {
                val live = devices.firstOrNull { it.id == d.id } ?: d
                status = if (live.online) FolderBrowserCopy.ReadFailed else FolderBrowserCopy.offline(live.name)
                // Keep browsing the last good folder — but only on the same device.
                return@launch
            }
            Snapshot.withMutableSnapshot {
                listingDeviceId = d.id
                listing = result
                this@FolderBrowserController.path = result.path
                listingIsRepo = isRepoHint
                status = result.path
                listingSerial++
            }
        }
    }

    /**
     * `didSelectItemAt`: taps are ignored while loading (`guard !loading`),
     * while a create runs, and when the listing came from another device.
     * Parent → up one level; folder → down (the row's `isRepo` becomes the
     * next listing's hint).
     */
    fun tap(row: FolderRow) {
        checkMainThread()
        if (loading || submitting) return
        val d = device ?: return
        val l = listing ?: return
        if (listingDeviceId != d.id) return
        when (row) {
            FolderRow.Parent -> paths.parent(l.path)?.let { load(it) }
            is FolderRow.Folder -> load(paths.join(l.path, row.name), isRepoHint = row.isRepo)
        }
    }

    private fun defaultPick(hosts: List<HostOption>): HostOption? = hosts.firstOrNull { it.online } ?: hosts.firstOrNull()

    /**
     * Main-thread assertion, skipped when there is no looper at all (plain
     * JVM tests); Robolectric runs the test on the main looper, so it holds
     * there too.
     */
    private fun checkMainThread() {
        val my = Looper.myLooper() ?: return
        check(my == Looper.getMainLooper()) { "FolderBrowserController mutations must run on the main thread" }
    }
}

/**
 * The sheet-body wiring: the app's single workspace feed snapshot mapped
 * through `WorkspaceSnapshot.toNewSessionOptions()` into the hosts flow,
 * the [CoreFolderBrowserSource] over `ZeronApplication.clientOrNull()`,
 * and the controller over both. The StateFlow replays its current value,
 * so the first `collect` performs the first-call branch (default pick +
 * `load(null)`) right after the first frame; the initial value is the
 * current snapshot, not `null`, so the first frame already has the default
 * device.
 */
@Composable
fun rememberFolderBrowserController(
    hosts: StateFlow<List<HostOption>?>,
    source: FolderBrowserSource,
    paths: HostPathOps = HostPaths,
): FolderBrowserController {
    val scope = rememberCoroutineScope()
    val controller = remember { FolderBrowserController(source, paths, scope, initialHosts = hosts.value) }
    LaunchedEffect(hosts) {
        hosts.filterNotNull().collect { controller.updateDevices(it) }
    }
    return controller
}
