package sh.zeron.android.sessions

import androidx.compose.runtime.staticCompositionLocalOf
import uniffi.zeron_core.SectionView

/**
 * How one session/section write ended (android-ui-parity #42). The UI
 * changes nothing on failure — rows move only when the core's snapshot
 * changes — so this is *information*, not a command:
 *
 *  - [Ok] — the FFI call returned; android-app#07's overlay will move the row.
 *  - [Skipped] — a blank trimmed name/title; nothing was called.
 *  - [NoClient] — no live client (signed out / torn down / `Closed`).
 *  - [Gone] — the id is unknown; the row is already gone.
 *  - [Failed] — anything else (pins not ready, an invalid argument, a
 *    host error, …); logged like iOS's `attempt`, never toasted, and the
 *    row stays.
 */
enum class ActionOutcome { Ok, Skipped, NoClient, Gone, Failed }

/**
 * Every session/section write the UI makes (android-ui-parity #42) — the
 * port of `AppModel`'s `// MARK: Writes` surface
 * (`ios:App/AppModel.swift:495-519`), one method per iOS method, same FFI
 * calls, same log labels.
 *
 * Main-thread API; the FFI work runs off main on one serial IO lane
 * ([CoreRowActions]), so archive-then-instant-Undo and a run of `movePin`
 * calls reach the core in the user's order. [onResult] is called once, on
 * the main thread, after the call has returned — callers use it where iOS
 * acts after the write (ticket 45 updates the folder title after a rename
 * and pops after a delete; ticket 80 decides whether to pop after an
 * archive; tickets 44/47 revert the gesture on refusal).
 *
 * Implementations live for the app's lifetime (iOS's `AppModel`): an Undo
 * raised by the session ⋯ menu must still work after that screen popped.
 */
interface RowActions {

    /** Pin/unpin a chat (`AppModel.setPinned`). */
    fun setPinned(chatId: String, pinned: Boolean, onResult: (ActionOutcome) -> Unit = {})

    /**
     * Archive a chat. On [ActionOutcome.Ok] shows the "Archived · Undo"
     * toast; Undo calls [unarchive] through the same queue.
     */
    fun archive(chatId: String, onResult: (ActionOutcome) -> Unit = {})

    /** Unarchive a chat — no toast of its own (iOS has none). */
    fun unarchive(chatId: String, onResult: (ActionOutcome) -> Unit = {})

    /**
     * Delete a chat for good (`AppModel.delete`, upstream #967) — the
     * caller has already confirmed ("Delete session?"); no toast and no
     * Undo of its own.
     */
    fun delete(chatId: String, onResult: (ActionOutcome) -> Unit = {})

    /** Move a chat into a section; `null` = "No Section" (back to Recent). */
    fun move(chatId: String, toSection: String?, onResult: (ActionOutcome) -> Unit = {})

    /** Reorder a pin between neighbours (`null` at either end). */
    fun movePin(
        chatId: String,
        after: String?,
        before: String?,
        onResult: (ActionOutcome) -> Unit = {},
    )

    /**
     * Pin between neighbours (`null` at either end) — a drop into Pinned
     * (wave-10 41, the core's `pinSessionAt`). The default reports
     * [ActionOutcome.Skipped] (previews and fakes).
     */
    fun pinAt(chatId: String, after: String?, before: String?, onResult: (ActionOutcome) -> Unit = {}) {
        onResult(ActionOutcome.Skipped)
    }

    /**
     * Move a chat into a section between its members (`null` anchors at
     * either end; `sectionId == null` = back to Recent) — a drop there
     * (wave-10 41, the core's `placeSession`). Default: [ActionOutcome.Skipped].
     */
    fun place(
        chatId: String,
        sectionId: String?,
        after: String?,
        before: String?,
        onResult: (ActionOutcome) -> Unit = {},
    ) {
        onResult(ActionOutcome.Skipped)
    }

    /** Rename a chat; a blank trimmed title is skipped. */
    fun rename(chatId: String, title: String, onResult: (ActionOutcome) -> Unit = {})

    /**
     * Create a section (Zeron's sections are workspace-wide); a blank
     * trimmed name is skipped. [onCreated] receives the section id, on
     * the main thread, only on [ActionOutcome.Ok].
     */
    fun createSection(
        name: String,
        onResult: (ActionOutcome) -> Unit = {},
        onCreated: (sectionId: String) -> Unit = {},
    )

    /** Rename a section; a blank trimmed name is skipped. */
    fun renameSection(sectionId: String, name: String, onResult: (ActionOutcome) -> Unit = {})

    /** Delete a section (its sessions return to Recent). */
    fun deleteSection(sectionId: String, onResult: (ActionOutcome) -> Unit = {})

    /**
     * The user sections, in snapshot order, empty ones included (iOS
     * `folders`, `AppModel.swift:382-386`); Pinned never — it is not a
     * `SectionView`. Runs on the caller's thread and never touches the
     * FFI — the list comes from the snapshot the app already holds.
     */
    fun sections(): List<SectionView>

    companion object {
        /**
         * Previews and tests of neighbouring tickets: every write is a
         * no-op ([onResult] is never called), `sections` lists nothing.
         */
        val Noop: RowActions = object : RowActions {
            override fun setPinned(chatId: String, pinned: Boolean, onResult: (ActionOutcome) -> Unit) {}
            override fun archive(chatId: String, onResult: (ActionOutcome) -> Unit) {}
            override fun unarchive(chatId: String, onResult: (ActionOutcome) -> Unit) {}
            override fun delete(chatId: String, onResult: (ActionOutcome) -> Unit) {}
            override fun move(chatId: String, toSection: String?, onResult: (ActionOutcome) -> Unit) {}
            override fun movePin(chatId: String, after: String?, before: String?, onResult: (ActionOutcome) -> Unit) {}
            override fun rename(chatId: String, title: String, onResult: (ActionOutcome) -> Unit) {}
            override fun createSection(name: String, onResult: (ActionOutcome) -> Unit, onCreated: (sectionId: String) -> Unit) {}
            override fun renameSection(sectionId: String, name: String, onResult: (ActionOutcome) -> Unit) {}
            override fun deleteSection(sectionId: String, onResult: (ActionOutcome) -> Unit) {}
            override fun sections(): List<SectionView> = emptyList()
        }
    }
}

/**
 * The ambient [RowActions]. [RowActions.Noop] until the app root (ticket
 * 35) provides the app-scoped instance inside its `ToastHost`.
 */
val LocalRowActions = staticCompositionLocalOf<RowActions> { RowActions.Noop }
