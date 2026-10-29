package sh.zeron.android.sessions

import uniffi.zeron_core.FrontPage
import uniffi.zeron_core.SessionRow

/**
 * The front page's section list (ticket 40) — the port of
 * `SessionListController.buildSections()` (`ios:Threads/SessionListController.swift:354-370`)
 * over the core's `FrontPage`.
 *
 * Pure: no Compose types, no state, no FFI — the model
 * (`FrontPageModel`) feeds it a snapshot and the grouping flag, and hands
 * the result plus `collapsedIds` to ticket 41's list builder.
 *
 * Section order (the Spec table):
 *
 *  1. `pinned` — only when pins exist;
 *  2. each user section, core order, empty sections kept;
 *  3. `recent` — **always** (even empty), with header `"Recent"` only
 *     when rows 1–2 produced at least one section.
 *
 * TODO(#21): with Group by Project on, iOS replaces Recent with one
 * section per project group, built client-side from `front.recent` and
 * `WorkspaceSnapshot.projects[].groupKey/groupName` (Zeron's `FrontPage`
 * has no `groups`). Until then Recent shows either way.
 *
 * Rows are every session of the section, FFI records in core order —
 * never folded, never de-duplicated, never counted here (41 does all
 * three, over exactly these rows).
 */
object FrontPageSections {

    const val PINNED = "pinned"
    const val RECENT = "recent"
    const val PINNED_TITLE = "Pinned"
    const val RECENT_TITLE = "Recent"

    /** The build result: the sections and the per-id metadata. */
    data class Built(val sections: List<ListSection>, val meta: Map<String, FrontSectionMeta>)

    /** Builds the section list; [groupByProject] has no effect yet (TODO(#21)). */
    @Suppress("UNUSED_PARAMETER")
    fun build(front: FrontPage, groupByProject: Boolean): Built {
        val raw = ArrayList<RawSection>(front.pinned.size + front.sections.size + 1)
        if (front.pinned.isNotEmpty()) {
            raw += RawSection(
                id = PINNED, title = PINNED_TITLE, rows = front.pinned,
                kind = FrontSectionKind.Pinned, rawName = null,
            )
        }
        for (section in front.sections) {
            raw += RawSection(
                id = section.id, title = section.name,
                rows = section.sessions, kind = FrontSectionKind.User, rawName = section.name,
            )
        }
        raw += RawSection(
            id = RECENT,
            // A header only when Pinned or at least one user section
            // came first — an empty user section counts.
            title = if (raw.isEmpty()) null else RECENT_TITLE,
            rows = front.recent,
            kind = FrontSectionKind.Recent, rawName = null,
        )
        return Built(
            sections = raw.map { r -> ListSection(id = r.id, header = r.title, rows = r.rows) },
            meta = raw.associate { r -> r.id to FrontSectionMeta(kind = r.kind, rawName = r.rawName) },
        )
    }

    /**
     * The ids of the **headed** sections in [sections] whose effective fold
     * is true. A header-less section is never a member: a user who folded
     * Recent and then deleted every section must not see an empty page,
     * and 41 ignores the fold without a header anyway — excluding it
     * keeps the hand-off honest.
     */
    fun collapsedIds(sections: List<ListSection>, isCollapsed: (id: String) -> Boolean): Set<String> =
        sections.filter { it.header != null && isCollapsed(it.id) }.mapTo(HashSet()) { it.id }

    /**
     * The ids Collapse All may fold, in order (ticket 46): `"pinned"` when
     * pins exist and every user section (the project groups join with
     * #21). **Recent is never in the list** (`SessionListController.swift:419-421`).
     */
    @Suppress("UNUSED_PARAMETER")
    fun collapsibleIds(front: FrontPage, groupByProject: Boolean): List<String> = buildList {
        if (front.pinned.isNotEmpty()) add(PINNED)
        front.sections.forEach { add(it.id) }
    }

    // ------------------------------------------------------------------ internals

    /** A section before it becomes a [ListSection]: the title plus its metadata. */
    private class RawSection(
        val id: String,
        val title: String?,
        val rows: List<SessionRow>,
        val kind: FrontSectionKind,
        val rawName: String?,
    )
}
