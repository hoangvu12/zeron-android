package sh.zeron.android.newsession

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import uniffi.zeron_core.FolderListing

/**
 * The typed-path field and the locations rail's data (android-ui-parity
 * #112): the search/typed-path field's behaviour, as the web add-project
 * palette's flow (`state/add-space.ts`). The rail and the field
 * themselves are `FolderLocationsHeader.kt`; this file owns the behaviour
 * between them and 110/111's browser parts.
 *
 * TODO(#27): Zeron's core has no `listDrives` and no `prepareSpacePath`,
 * so the rail is Home only and a typed path is simply browsed (a missing
 * one shows the browser's read-failed status; there is no Create and
 * add). #27 ports the page to iOS, which has neither the rail nor the
 * field.
 */

// ---------------------------------------------------------------------------
// The host seam over 110/111
// ---------------------------------------------------------------------------

/**
 * What this ticket needs from 110/111's folder browser: the browsing state
 * and the two write hooks. Implemented over the pair by
 * [FolderBrowserHostAdapter]; tests implement it on a fake.
 */
interface FolderBrowserHost {
    /** The selected engine's scoped device id; null = no engines. */
    val deviceId: String?

    val deviceOnline: Boolean

    /** The last good listing (110 keeps it across failures). */
    val listing: FolderListing?

    /** The engine the [listing] came from (iOS's `listingDevice`). */
    val listingDeviceId: String?

    /**
     * The path of the last successful **pathless** load on this engine;
     * null until known. The Home chip's root. 110 resets it on engine
     * change.
     */
    val home: String?

    val loading: Boolean

    /** 111's shared write guard: ✕, rows, chips and the field all read it. */
    val submitting: Boolean

    /**
     * 110's generation-guarded load. `gitHint` is the git flag 111 uses
     * for the folder if it is created (the tapped row's `isRepo`, or the
     * probe's `gitDetected` for a typed path).
     */
    fun load(path: String?, showHidden: Boolean = false, gitHint: Boolean? = null)

    /** 111's shared submitting setter (no `creatingProject`: no Use spinner). */
    fun setSubmitting(busy: Boolean)

    /**
     * 111's create body without its entry guard: dedupe, `createSpace`,
     * `onCreated` + dismiss on success, status
     * `Couldn't create the project on <device>.` on failure. True on
     * success.
     */
    suspend fun createProjectAt(path: String, gitDetected: Boolean): Boolean
}

/**
 * [FolderBrowserHost] over 110's [FolderBrowserController] and 111's
 * [FolderBrowserActions] — the ticket's adapter table, one row per member.
 */
class FolderBrowserHostAdapter(
    private val controller: FolderBrowserController,
    private val actions: FolderBrowserActions,
) : FolderBrowserHost {
    override val deviceId: String? get() = controller.engine?.id
    override val deviceOnline: Boolean get() = controller.engine?.online == true
    override val listing: FolderListing? get() = controller.listing
    override val listingDeviceId: String? get() = controller.listingEngineId
    override val home: String? get() = controller.home
    override val loading: Boolean get() = controller.loading
    override val submitting: Boolean get() = actions.submitting
    override fun load(path: String?, showHidden: Boolean, gitHint: Boolean?) =
        controller.load(path, isRepoHint = gitHint, showHidden = showHidden)
    override fun setSubmitting(busy: Boolean) = actions.setSubmitting(busy)
    override suspend fun createProjectAt(path: String, gitDetected: Boolean): Boolean =
        actions.createProjectAt(path, gitDetected)
}

// ---------------------------------------------------------------------------
// The calls seam
// ---------------------------------------------------------------------------

/** Test seam: 110's host-path join (the first matching row's full path on plain-text Go). */
interface FolderBrowserCalls {
    fun join(base: String, name: String): String
}

/** The live [FolderBrowserCalls] over [HostPaths]. */
class CoreFolderCalls(private val paths: HostPathOps = HostPaths) : FolderBrowserCalls {
    override fun join(base: String, name: String): String = paths.join(base, name)
}

// ---------------------------------------------------------------------------
// The state holder
// ---------------------------------------------------------------------------

/**
 * The rail + field's brain. Plain text filters the folder rows
 * (`folderRows`); path-shaped text is browsed on IME **Go**. All state is
 * Compose snapshot state, mutated on the main thread.
 */
@Stable
class FolderNavState(
    private val host: FolderBrowserHost,
    private val calls: FolderBrowserCalls,
    @Suppress("unused") private val scope: CoroutineScope,
) {
    /** The field's text: the filter, or the typed path. */
    var query by mutableStateOf("")
        private set

    /** Home only: Zeron has no drives listing (TODO(#27)). */
    val chips: List<LocationChipModel> = locationChips(emptyList())

    /**
     * The selected chip: the one whose root holds the listing's path most
     * specifically. `null` with no listing, a listing from another engine,
     * or an unknown home.
     */
    val selectedChip: Int? by derivedStateOf { selectedLocation(listingPath, host.home, chips) }

    /** The listing's path, only when it belongs to the selected engine (else no chip is selected). */
    private val listingPath: String?
        get() {
            if (host.listingDeviceId != host.deviceId) return null
            return host.listing?.path
        }

    private var lastDeviceId: String? = null

    /** True while a `.`-prefixed query has the hidden names shown (the reload toggle). */
    private var showingHidden = false

    // ------------------------------------------------------------- the field

    /**
     * Any edit: `query = text`. A **non-path** edit that
     * flips the `.`-prefix flag reloads the current listing with hidden
     * names on/off (the engine only returns `.`-names when asked); the
     * query itself is never cleared. A path-shaped edit never toggles the
     * flag (web `:291-301`).
     */
    fun onQueryChange(text: String) {
        if (text == query) return
        query = text
        if (manualPathQuery(text)) return
        val hidden = text.startsWith(".")
        if (hidden == showingHidden) return
        val listing = host.listing ?: return
        if (host.listingDeviceId != host.deviceId) return
        showingHidden = hidden
        host.load(listing.path, showHidden = hidden)
    }

    /**
     * IME **Go** (focus clearing is the field's job, and it always runs):
     * a path-shaped query is browsed; non-blank plain text browses the first
     * matching row (web `openActive`); a blank query does nothing. Never
     * while 111 is submitting.
     */
    fun onGo() {
        if (host.submitting) return
        val text = query
        if (manualPathQuery(text)) {
            navigate(text.trim(), gitHint = null)
            return
        }
        if (text.isBlank()) return
        val listing = host.listing ?: return
        if (host.listingDeviceId != host.deviceId) return
        val first = filteredFolders(listing.entries, text).firstOrNull() ?: return
        navigate(calls.join(listing.path, first.name), first.isRepo)
    }

    // -------------------------------------------------------------- the rail

    /**
     * Tapping a chip browses its root — Home means the engine user's home
     * (a pathless load). Allowed while a load is in flight (110's
     * generation guard supersedes the older load), ignored while
     * submitting.
     */
    fun onPickLocation(chip: LocationChipModel) {
        if (host.submitting) return
        navigate(chip.path, gitHint = null)
    }

    /**
     * The engine picker's feed (110's screen runs this from a
     * `LaunchedEffect(engine?.id, engine?.online)`): an engine change
     * resets the field (the listing reload stays 110's business — the
     * pr621 trap).
     */
    fun onDevice(id: String?, @Suppress("UNUSED_PARAMETER") online: Boolean) {
        if (id == lastDeviceId) return
        lastDeviceId = id
        query = ""
        showingHidden = false
    }

    // --------------------------------------------------------- navigation

    /**
     * Browse [path] (null = home), clearing the query and the hidden flag
     * — 110's row taps and this ticket's chip taps and typed paths all go
     * through here.
     */
    fun navigate(path: String?, gitHint: Boolean?) {
        query = ""
        showingHidden = false
        host.load(path, showHidden = false, gitHint = gitHint)
    }

    /** 110's list rows for the current query. */
    fun <T> rows(entries: List<T>, name: (T) -> String, isDir: (T) -> Boolean): FolderRows<T> =
        folderRows(entries, query, name, isDir)
}

/**
 * The sheet body's [FolderNavState]: 110's controller + 111's actions
 * behind the [FolderBrowserHostAdapter], the calls seam, and the screen's
 * `rememberCoroutineScope` — a reply that lands after the screen goes away
 * dies with it, like 110's loads and 111's writes.
 */
@Composable
internal fun rememberFolderNavState(
    controller: FolderBrowserController,
    actions: FolderBrowserActions,
    calls: FolderBrowserCalls,
): FolderNavState {
    val scope = rememberCoroutineScope()
    return remember(controller, actions, calls) {
        FolderNavState(FolderBrowserHostAdapter(controller, actions), calls, scope)
    }
}
