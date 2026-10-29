package sh.zeron.android.sessions

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import sh.zeron.android.core.Prefs
import sh.zeron.android.core.WorkspaceFeed
import sh.zeron.android.design.Feedback

/**
 * What the Sessions tab draws (ticket 40) — the `AppModel.frontPage`
 * half of `FrontPage` (`ios:App/AppModel.swift:9-15`): the section list
 * plus the fold resolution. [sections] carries **every** row of every
 * section (not folded, not de-duplicated); ticket 41's
 * `buildSessionListItems` folds via [collapsedIds] and de-duplicates.
 *
 * The rebuild runs on every published snapshot (the feed publishes only
 * when it differs — a 30 s clock read with the same revision still
 * carries new time labels), the grouping flag and the fold state.
 */
data class FrontPageUi(
    val revision: ULong,
    val synced: Boolean,
    val sections: List<ListSection>,
    /** Headed ids whose effective fold is true — never a header-less section. */
    val collapsedIds: Set<String>,
    /** Per-id section metadata (kind, raw name). */
    val meta: Map<String, FrontSectionMeta>,
)

/**
 * The front page's model (ticket 40) — the `AppModel` front-page half:
 * it combines the workspace feed, the `groupByProject` preference and
 * the fold state into [ui], and routes the two front-page actions
 * (`toggleSection`, `collapseAll`) into [FoldState].
 *
 * `null` until [Prefs] and [FoldState] have loaded from disk **and** a
 * first snapshot exists — no flash of expanded sections, none of Recent
 * before the groups. The section list itself is computed on [compute],
 * never the caller's thread, and nothing here calls the FFI: the feed,
 * and the fold state own every call.
 */
class FrontPageModel(
    private val feed: WorkspaceFeed,
    private val prefs: Prefs,
    private val fold: FoldState,
    scope: CoroutineScope,
    compute: CoroutineDispatcher = Dispatchers.Default,
) {

    val ui: StateFlow<FrontPageUi?> = flow {
        prefs.awaitLoaded()
        fold.awaitLoaded()
        emitAll(
            combine(
                feed.snapshot.filterNotNull(),
                prefs.groupByProject,
                fold.state,
            ) { ws, groupByProject, f ->
                val built = FrontPageSections.build(ws.front, ws.projects, groupByProject)
                FrontPageUi(
                    revision = ws.revision,
                    synced = ws.synced,
                    sections = built.sections,
                    // Pure over the combined Fold — never `state.value` — so
                    // the rebuild is race-free.
                    collapsedIds = FrontPageSections.collapsedIds(built.sections) { id ->
                        fold.isCollapsedIn(f, id)
                    },
                    meta = built.meta,
                )
            },
        )
    }
        .flowOn(compute)
        // Not a no-op (round 53): `stateIn` fuses with a directly preceding
        // `flowOn` and runs its sharing coroutine in the flowOn context, so
        // the StateFlow was written on a Dispatchers.Default worker. A
        // composition collecting it could then be resumed on that worker
        // (compose-ui-test resumes effect coroutines inline), recompose and
        // lay out there while main was mid-measure: "performMeasureAndLayout
        // called during measure layout" (CI 9307a945, NotificationFlowTest).
        // This non-channel operator breaks the fusion: the build stays on
        // [compute], the StateFlow is written on [scope]'s dispatcher (main),
        // and equal values were already conflated by the StateFlow.
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** A header tap (`SessionListController.toggleSection`): every fold is phone-local. */
    fun toggleSection(id: String, feedback: Feedback) {
        fold.toggle(id, feedback)
    }

    /** A drop into a folded section opens it (#24; no haptic of its own). */
    fun expandSection(id: String) {
        fold.expand(id)
    }

    /**
     * The ⋯ menu's Collapse All (`SessionListController.swift:581-586`):
     * fold every still-open id among Pinned (if pins exist), the user
     * sections and — only while grouping is on — the groups. Recent is
     * never touched, and each id goes through the same `toggleSection`
     * path, so persistence and haptics behave identically
     * (one `Haptic.Select` per toggled id; ticket 05's gate makes the
     * burst feel like one tick on device).
     */
    fun collapseAll(feedback: Feedback) {
        val ws = feed.snapshot.value ?: return
        for (id in FrontPageSections.collapsibleIds(ws.front, ws.projects, prefs.groupByProject.value)) {
            if (!fold.isCollapsed(id)) fold.toggle(id, feedback)
        }
    }
}
