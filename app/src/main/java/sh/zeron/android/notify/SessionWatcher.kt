package sh.zeron.android.notify

import uniffi.zeron_core.ChatIndicator
import uniffi.zeron_core.SessionRow
import uniffi.zeron_core.WorkspaceSnapshot

/** What the watcher needs of a session row. */
data class WatchedRow(
    val id: String,
    val title: String,
    /** `SessionRow.hostIndicator`: host-reported and staleness-gated, never a local send. */
    val indicator: ChatIndicator,
    val workingSinceMs: Long?,
    val lastActivityMs: Long,
    /** Archived, or a side chat: never notifies (`chatForNotification`). */
    val silent: Boolean,
)

/** One notification to post: [kind] for [chatId], titled [title], dated [whenMs]. */
data class SessionNotice(
    val chatId: String,
    val title: String,
    val kind: NotificationKind,
    val whenMs: Long,
)

/**
 * D6's SessionWatcher (#56): the edge's notification rule
 * (`edge/src/push-notify.ts` `notificationFor`) applied in Kotlin to
 * workspace snapshots, since the edge only pushes to APNs. A port of
 * docs/port/05 §2.4:
 *
 * - a chat seen for the first time (or again after it left the snapshot)
 *   only seeds its baseline, so a launch, a sign-in or a reconnect never
 *   replays old events;
 * - **failed**: the indicator becomes Errored;
 * - **input**: it becomes AwaitingInput (a question or permission prompt);
 * - **done**: Working/AwaitingInput → Completed, or Working → Working with
 *   a new `workingSinceMs` (a queued message started the next turn and
 *   the Completed frame was coalesced away);
 * - Working → Idle is silent (an interrupt, a stale row, or a turn already
 *   seen on another device — the last is a deliberate small divergence
 *   from the edge, which reads `lastCompletedTurn`);
 * - side chats and archived chats never notify.
 *
 * Not thread-safe: the notification centre drives it from one lane.
 */
class SessionWatcher {

    private data class Baseline(val indicator: ChatIndicator, val workingSinceMs: Long?)

    private var baseline: Map<String, Baseline> = emptyMap()

    /** Forget every chat (sign-in, sign-out): the next snapshot only seeds. */
    fun reset() {
        baseline = emptyMap()
    }

    /** The notices [rows] call for against the previous snapshot; the rows become the new baseline. */
    fun update(rows: List<WatchedRow>): List<SessionNotice> {
        val notices = ArrayList<SessionNotice>()
        val next = HashMap<String, Baseline>(rows.size)
        for (row in rows) {
            next[row.id] = Baseline(row.indicator, row.workingSinceMs)
            val before = baseline[row.id] ?: continue
            if (row.silent) continue
            val kind = transition(before, row) ?: continue
            notices += SessionNotice(row.id, row.title.ifBlank { "New session" }, kind, row.lastActivityMs)
        }
        baseline = next
        return notices
    }

    private fun transition(before: Baseline, row: WatchedRow): NotificationKind? {
        val prev = before.indicator
        val now = row.indicator
        return when {
            now == ChatIndicator.ERRORED && prev != ChatIndicator.ERRORED -> NotificationKind.Failed
            now == ChatIndicator.AWAITING_INPUT && prev != ChatIndicator.AWAITING_INPUT -> NotificationKind.Input
            now == ChatIndicator.COMPLETED &&
                (prev == ChatIndicator.WORKING || prev == ChatIndicator.AWAITING_INPUT) -> NotificationKind.Done
            now == ChatIndicator.WORKING && prev == ChatIndicator.WORKING &&
                before.workingSinceMs != null && row.workingSinceMs != null &&
                row.workingSinceMs != before.workingSinceMs -> NotificationKind.Done
            else -> null
        }
    }

    companion object {
        /**
         * Every active row of [snapshot], once: pinned, sections, recent,
         * projects and project-less (archived rows live elsewhere and are
         * skipped; a row also listed as archived stays silent).
         */
        fun rows(snapshot: WorkspaceSnapshot): List<WatchedRow> {
            val seen = LinkedHashMap<String, SessionRow>()
            fun add(row: SessionRow) {
                seen.putIfAbsent(row.id, row)
            }
            snapshot.front.pinned.forEach(::add)
            snapshot.front.sections.forEach { section -> section.sessions.forEach(::add) }
            snapshot.front.recent.forEach(::add)
            snapshot.projects.forEach { project -> project.sessions.forEach(::add) }
            snapshot.projectless.forEach(::add)
            return seen.values.map(::watched)
        }

        fun watched(row: SessionRow) = WatchedRow(
            id = row.id,
            title = row.title,
            indicator = row.hostIndicator,
            workingSinceMs = row.workingSinceMs,
            lastActivityMs = row.lastActivityMs,
            silent = row.archived || row.parentChatId != null,
        )
    }
}
