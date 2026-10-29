package sh.zeron.android.sessions

import uniffi.zeron_core.FrontPage
import uniffi.zeron_core.ProjectView
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
 *     when rows 1–2 produced at least one section; or, with Group by
 *     Project on, one headed section per project group instead
 *     ([projectGroups], built client-side: Zeron's `FrontPage` has no
 *     `groups`).
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

    /** A project group: `project:<groupKey>` / `home:<deviceId>`, its name and rows in recency order. */
    data class ProjectGroup(val id: String, val name: String, val rows: List<SessionRow>)

    /** Builds the section list (`buildSections`, `SessionListController.swift:509-525`). */
    fun build(front: FrontPage, projects: List<ProjectView>, groupByProject: Boolean): Built {
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
        if (groupByProject) {
            for (group in projectGroups(front.recent, projects)) {
                raw += RawSection(
                    id = group.id, title = group.name, rows = group.rows,
                    kind = FrontSectionKind.ProjectGroup, rawName = null,
                )
            }
            return built(raw)
        }
        raw += RawSection(
            id = RECENT,
            // A header only when Pinned or at least one user section
            // came first — an empty user section counts.
            title = if (raw.isEmpty()) null else RECENT_TITLE,
            rows = front.recent,
            kind = FrontSectionKind.Recent, rawName = null,
        )
        return built(raw)
    }

    /**
     * iOS `AppModel.projectGroups` (`App/AppModel.swift:442-468`), like the
     * desktop's grouping: checkouts of one repository share a group
     * (`project:<groupKey>`, named `groupName`); a project the snapshot
     * doesn't list falls back to its own id and name; project-less
     * sessions group per device (`home:<deviceId>`, "Home · <device>" or
     * "Home"). Groups and their rows keep [recent]'s recency order. Pure.
     */
    fun projectGroups(recent: List<SessionRow>, projects: List<ProjectView>): List<ProjectGroup> {
        val byId = HashMap<String, ProjectView>(projects.size)
        for (p in projects) byId.putIfAbsent(p.id, p)
        val order = LinkedHashMap<String, Pair<String, MutableList<SessionRow>>>()
        for (row in recent) {
            val ref = row.project
            val id: String
            val name: String
            if (ref != null) {
                val project = byId[ref.id]
                id = "$PROJECT_PREFIX${project?.groupKey ?: ref.id}"
                name = project?.groupName ?: ref.name
            } else {
                id = "$HOME_PREFIX${row.deviceId}"
                name = row.deviceName?.let { "$HOME_TITLE · $it" } ?: HOME_TITLE
            }
            order.getOrPut(id) { name to ArrayList() }.second += row
        }
        return order.map { (id, entry) -> ProjectGroup(id, entry.first, entry.second) }
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
     * The ids Collapse All may fold, in order (`SessionListController.swift:581-586`):
     * `"pinned"` when pins exist, every user section, then — only while
     * grouping — every project group. **Recent is never in the list.**
     */
    fun collapsibleIds(front: FrontPage, projects: List<ProjectView>, groupByProject: Boolean): List<String> =
        buildList {
            if (front.pinned.isNotEmpty()) add(PINNED)
            front.sections.forEach { add(it.id) }
            if (groupByProject) projectGroups(front.recent, projects).forEach { add(it.id) }
        }

    // ------------------------------------------------------------------ internals

    private const val PROJECT_PREFIX = "project:"
    private const val HOME_PREFIX = "home:"
    private const val HOME_TITLE = "Home"

    private fun built(raw: List<RawSection>) = Built(
        sections = raw.map { r -> ListSection(id = r.id, header = r.title, rows = r.rows) },
        meta = raw.associate { r -> r.id to FrontSectionMeta(kind = r.kind, rawName = r.rawName) },
    )

    /** A section before it becomes a [ListSection]: the title plus its metadata. */
    private class RawSection(
        val id: String,
        val title: String?,
        val rows: List<SessionRow>,
        val kind: FrontSectionKind,
        val rawName: String?,
    )
}
