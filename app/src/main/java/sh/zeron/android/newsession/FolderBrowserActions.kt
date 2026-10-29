package sh.zeron.android.newsession

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import uniffi.zeron_core.FolderListing

/** The create's copy, verbatim from `NewProjectViewController.swift` (ASCII apostrophe). */
internal object FolderWriteCopy {

    /** `Couldn't create the project on \(device.name).` — the single status line for every create failure. */
    fun createFailed(deviceName: String) = "Couldn't create the project on $deviceName."
}

/**
 * The New Project's one write (android-ui-parity #111, #27): the Use tap —
 * `create()` in `ios:Threads/NewProjectViewController.swift:153-166` —
 * over [FolderBrowserController], plus the `submitting` guard every
 * control on the sheet reads. iOS has no folder create, so neither does
 * this page.
 *
 * All mutators run on the main thread; the write runs as a coroutine in
 * [scope] (the screen's `rememberCoroutineScope`), so a reply that lands
 * after the screen is disposed dies with it. The core is reached only
 * through [ProjectWriteApi], host-path arithmetic only through
 * [HostPathOps].
 */
@Stable
class FolderBrowserActions(
    private val c: FolderBrowserController,
    private val paths: HostPathOps,
    private val api: ProjectWriteApi,
    private val scope: CoroutineScope,
    private val onCreated: (scopedSpaceId: String) -> Unit,
    private val dismissSheet: () -> Unit,
) {

    /**
     * True while the create is in flight: ticket 08's busy look (spinner,
     * clicks ignored) — iOS's `showsActivityIndicator`.
     */
    var creatingProject by mutableStateOf(false)
        private set

    /** The controller's flag, mirrored for callers that hold only the actions. */
    val submitting: Boolean get() = c.submitting

    /**
     * `Use “<name>”` — the listing's last path component (or the whole path
     * for a root) between U+201C and U+201D, or `""` before the first
     * listing. The controller replaces `listing` only when a load is
     * applied, so a failed or running load keeps the last title, as iOS
     * does.
     */
    val useTitle: String by derivedStateOf {
        val listing = c.listing ?: return@derivedStateOf ""
        val name = paths.name(listing.path).ifEmpty { listing.path }
        "Use “$name”"
    }

    /** iOS's `useButton.isEnabled`: a usable listing from the selected device and nothing in flight. */
    val canAct: Boolean get() = c.listingUsable && !c.submitting

    /**
     * The Use tap: the path must be one this device listed (`guard let
     * device, let path, listingDevice == device.id`); the git hint, then
     * `createProject` inside the busy look. On success the draft gets the
     * new id first (`onCreated`, still under this sheet), then the sheet
     * dismisses; on failure the status line says so and the sheet stays.
     */
    fun createProject() {
        if (!canAct) return
        val device = c.device ?: return
        val listing = c.listing ?: return
        val path = listing.path
        val git = gitDetected(listing)
        creatingProject = true
        c.submitting = true
        scope.launch {
            try {
                val id = try {
                    api.createProject(device.id, path, git)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "create project failed", e)
                    null
                }
                if (id == null) {
                    c.status = FolderWriteCopy.createFailed(device.name)
                } else {
                    onCreated(id)
                    dismissSheet()
                }
            } finally {
                creatingProject = false
                c.submitting = false
            }
        }
    }

    /**
     * `gitDetected`: the listing's own `.git` entry (iOS's only check)
     * OR-ed with [FolderBrowserController.listingIsRepo] — the `isRepo` of
     * the row that reached this folder, because Zeron listings leave
     * dot-entries out. The host re-verifies either way.
     */
    private fun gitDetected(listing: FolderListing): Boolean =
        listing.entries.any { it.name == ".git" } || c.listingIsRepo == true
}

private const val LOG_TAG = "NewProject"

/** The screen's [FolderBrowserActions], over the screen's coroutine scope. */
@Composable
fun rememberFolderBrowserActions(
    controller: FolderBrowserController,
    paths: HostPathOps,
    api: ProjectWriteApi,
    onCreated: (String) -> Unit,
    dismissSheet: () -> Unit,
): FolderBrowserActions {
    val scope = rememberCoroutineScope()
    return remember(controller) {
        FolderBrowserActions(controller, paths, api, scope, onCreated, dismissSheet)
    }
}
