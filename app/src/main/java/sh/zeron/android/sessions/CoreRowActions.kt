package sh.zeron.android.sessions

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.zeron.android.design.Toaster
import uniffi.zeron_core.CoreClient
import uniffi.zeron_core.CoreException
import uniffi.zeron_core.SectionView

/**
 * The narrow FFI surface [CoreRowActions] needs (`mob:client_ffi/
 * workspace.rs:116-184`, android-app#07) — fakeable, because `CoreClient`
 * is a JNA object. Every method is the one-to-one Kotlin name UniFFI
 * generates from the Rust `snake_case` name; each throws the generated
 * [CoreException] variants (unknown id, invalid argument, …) and never
 * blocks on the network.
 */
interface SessionWrites {
    fun pinSession(chatId: String)
    fun unpinSession(chatId: String)
    fun archiveSession(chatId: String)
    fun unarchiveSession(chatId: String)
    fun deleteSession(chatId: String)
    fun assignSection(chatId: String, sectionId: String?)
    fun movePin(chatId: String, after: String?, before: String?)
    fun pinSessionAt(chatId: String, after: String?, before: String?)
    fun placeSession(chatId: String, sectionId: String?, after: String?, before: String?)
    fun renameSession(chatId: String, title: String)
    fun createSection(name: String): String
    fun renameSection(sectionId: String, name: String)
    fun deleteSection(sectionId: String)
}

/**
 * The [SessionWrites] view of the live [CoreClient] — one-line
 * delegations to the generated methods (`CoreResult<T>` lifts to `T`
 * throwing `CoreException`). Cheap: allocates the delegating object only.
 */
fun CoreClient.asSessionWrites(): SessionWrites = object : SessionWrites {
    override fun pinSession(chatId: String) = this@asSessionWrites.pinSession(chatId)
    override fun unpinSession(chatId: String) = this@asSessionWrites.unpinSession(chatId)
    override fun archiveSession(chatId: String) = this@asSessionWrites.archiveSession(chatId)
    override fun unarchiveSession(chatId: String) = this@asSessionWrites.unarchiveSession(chatId)
    override fun deleteSession(chatId: String) = this@asSessionWrites.deleteSession(chatId)
    override fun assignSection(chatId: String, sectionId: String?) =
        this@asSessionWrites.assignSection(chatId, sectionId)
    override fun movePin(chatId: String, after: String?, before: String?) =
        this@asSessionWrites.movePin(chatId, after, before)
    override fun pinSessionAt(chatId: String, after: String?, before: String?) =
        this@asSessionWrites.pinSessionAt(chatId, after, before)
    override fun placeSession(chatId: String, sectionId: String?, after: String?, before: String?) =
        this@asSessionWrites.placeSession(chatId, sectionId, after, before)
    override fun renameSession(chatId: String, title: String) =
        this@asSessionWrites.renameSession(chatId, title)
    override fun createSection(name: String): String =
        this@asSessionWrites.createSection(name)
    override fun renameSection(sectionId: String, name: String) =
        this@asSessionWrites.renameSection(sectionId, name)
    override fun deleteSection(sectionId: String) = this@asSessionWrites.deleteSection(sectionId)
}

/** The archive toast pair (`ios:App/AppModel.swift:535-539`). */
internal const val ARCHIVED_TOAST = "Archived"
internal const val ARCHIVED_TOAST_ACTION = "Undo"

/**
 * Pure: maps a thrown error to its outcome. Nothing toasts — iOS's
 * `attempt` only logs (`AppModel.swift:527-529`), and Zeron's core emits
 * no notices; the row simply stays until the snapshot moves it.
 * Rethrows `CancellationException` (a cancelled call never reports).
 */
internal fun classify(e: Throwable): ActionOutcome = when (e) {
    is CancellationException -> throw e
    is CoreException.NotFound -> ActionOutcome.Gone
    is CoreException.Closed -> ActionOutcome.NoClient
    else -> ActionOutcome.Failed
}

/**
 * The [RowActions] over the real FFI (android-ui-parity #42). One
 * instance for the whole app, app-lifetime scope — a toast's Undo must
 * outlive the screen that raised it (iOS holds `[weak self]` on the
 * app-lifetime `AppModel`).
 *
 * Threading (the ticket's table):
 *  - public methods are called on main;
 *  - the FFI call runs on [io] — `Dispatchers.IO.limitedParallelism(1)`,
 *    **one queue for every write**, so the user's order survives (an
 *    archive followed by an instant Undo, or a run of `movePin` calls);
 *  - the archive toast, `onResult` and `onCreated` run on [main].
 *
 * A failed write is logged and reported as an [ActionOutcome], never
 * toasted (iOS `attempt`).
 *
 * Never call the FFI on main: `CoreClient` goes through JNA and
 * android-app#06 warns about re-entrant listener callbacks. `writes` is
 * read on main at call time, so a re-created client is
 * picked up the same way iOS re-reads `client?`.
 *
 * [log] is iOS's `NSLog("<what> failed: <error>")` with the Android tag;
 * tests pass `{}` to keep `android.util.Log` off the plain JVM.
 */
class CoreRowActions(
    private val writes: () -> SessionWrites?,
    sections: () -> List<SectionView>,
    private val toaster: Toaster,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
    private val main: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val log: (String) -> Unit = { Log.w("Zeron", it) },
) : RowActions {

    private val readSections = sections

    // ------------------------------------------------------------------ writes

    override fun setPinned(chatId: String, pinned: Boolean, onResult: (ActionOutcome) -> Unit) {
        if (pinned) dispatch("pin", onResult) { it.pinSession(chatId) }
        else dispatch("pin", onResult) { it.unpinSession(chatId) }
    }

    override fun archive(chatId: String, onResult: (ActionOutcome) -> Unit) {
        dispatch("archive", onResult, onOk = {
            // Gated on Ok: iOS shows this even when the call threw
            // (AppModel.swift:535-539); the row then stays, and a stale
            // Undo would promise a restore that never happened.
            toaster.show(ARCHIVED_TOAST, ARCHIVED_TOAST_ACTION) { unarchive(chatId) }
        }) { it.archiveSession(chatId) }
    }

    override fun unarchive(chatId: String, onResult: (ActionOutcome) -> Unit) {
        dispatch("unarchive", onResult) { it.unarchiveSession(chatId) }
    }

    override fun delete(chatId: String, onResult: (ActionOutcome) -> Unit) {
        dispatch("delete", onResult) { it.deleteSession(chatId) }
    }

    override fun move(chatId: String, toSection: String?, onResult: (ActionOutcome) -> Unit) {
        dispatch("assign", onResult) { it.assignSection(chatId, toSection) }
    }

    override fun movePin(chatId: String, after: String?, before: String?, onResult: (ActionOutcome) -> Unit) {
        dispatch("move pin", onResult) { it.movePin(chatId, after, before) }
    }

    override fun pinAt(chatId: String, after: String?, before: String?, onResult: (ActionOutcome) -> Unit) {
        dispatch("pin", onResult) { it.pinSessionAt(chatId, after, before) }
    }

    override fun place(
        chatId: String,
        sectionId: String?,
        after: String?,
        before: String?,
        onResult: (ActionOutcome) -> Unit,
    ) {
        dispatch("assign", onResult) { it.placeSession(chatId, sectionId, after, before) }
    }

    override fun rename(chatId: String, title: String, onResult: (ActionOutcome) -> Unit) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) {
            onResult(ActionOutcome.Skipped)
            return
        }
        dispatch("rename", onResult) { it.renameSession(chatId, trimmed) }
    }

    override fun createSection(
        name: String,
        onResult: (ActionOutcome) -> Unit,
        onCreated: (sectionId: String) -> Unit,
    ) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            onResult(ActionOutcome.Skipped)
            return
        }
        dispatch("section", onResult, onOk = onCreated) {
            it.createSection(trimmed)
        }
    }

    override fun renameSection(sectionId: String, name: String, onResult: (ActionOutcome) -> Unit) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            onResult(ActionOutcome.Skipped)
            return
        }
        dispatch("rename section", onResult) { it.renameSection(sectionId, trimmed) }
    }

    override fun deleteSection(sectionId: String, onResult: (ActionOutcome) -> Unit) {
        dispatch("delete section", onResult) { it.deleteSection(sectionId) }
    }

    // ------------------------------------------------------------------ reads

    override fun sections(): List<SectionView> = readSections()

    // ------------------------------------------------------------------ the runner

    /**
     * The one runner every write goes through (named `dispatch`, not
     * `run`, so it never shadows the stdlib `run { }` used in its body):
     * read the client on main, make the call on the serial IO lane, then
     * log and report on main.
     */
    private fun <T> dispatch(
        label: String,
        onResult: (ActionOutcome) -> Unit,
        onOk: (T) -> Unit = {},
        call: (SessionWrites) -> T,
    ) {
        scope.launch(main) {
            val w = writes() ?: run {
                log("$label skipped: no client")
                onResult(ActionOutcome.NoClient)
                return@launch
            }
            val result = withContext(io) { runCatching { call(w) } }
            result.fold(
                onSuccess = {
                    onOk(it)
                    onResult(ActionOutcome.Ok)
                },
                onFailure = { e ->
                    val outcome = classify(e) // rethrows CancellationException
                    log("$label failed: $e")
                    onResult(outcome)
                },
            )
        }
    }
}
