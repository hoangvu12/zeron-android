package sh.zeron.android.sessions

import androidx.annotation.MainThread
import sh.zeron.android.core.ActivitySettings
import sh.zeron.android.core.Prefs
import uniffi.zeron_core.FrontPage
import uniffi.zeron_core.SessionRow
import uniffi.zeron_core.WorkspaceSnapshot
import kotlin.math.max

/**
 * The chats on screen now (iOS 1.0.2 `AppModel.openChatIds`): each Session
 * screen reports itself while it shows, under its own token, so a chat
 * open twice (the phone stack and a tablet column) stays open until both
 * leave. Never archived for being idle. Main thread.
 */
class OpenChats {
    private val byToken = HashMap<Any, String>()

    @MainThread
    fun open(token: Any, chatId: String) {
        byToken[token] = chatId
    }

    @MainThread
    fun close(token: Any) {
        byToken.remove(token)
    }

    @MainThread
    operator fun contains(chatId: String): Boolean = byToken.containsValue(chatId)
}

/**
 * Activity's auto-archive (iOS 1.0.2 `AppModel.sweepIdle`, as the desktop's
 * sweep): at most once a minute, each chat idle longer than the days set
 * goes to Archived. Never one that's pinned, open now, asleep, woken, under
 * way or needing you ([ActivityList.archivesWhenIdle]); nothing until the
 * pins are known (or a pinned chat could look unpinned). A chat brought
 * back from Archived counts as just moved, so being old doesn't send it
 * straight back. The first sweep only looks.
 *
 * Main thread: the app calls [sweep] on every workspace read and
 * [settingsChanged] when Activity's settings change.
 */
class ActivitySweep(
    private val settings: () -> ActivitySettings,
    private val unarchived: () -> Map<String, Long>,
    private val setUnarchived: (Map<String, Long>) -> Unit,
    private val open: OpenChats,
    private val archive: (chatId: String) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val toVm: (SessionRow) -> SessionRowVM = { SessionRowVM.from(it) },
) {
    /** Over the app's [Prefs] (`activity`, `activity.unarchived`). */
    constructor(prefs: Prefs, open: OpenChats, archive: (chatId: String) -> Unit) : this(
        settings = { prefs.activity.value },
        unarchived = { prefs.activityUnarchived.value },
        setUnarchived = prefs::setActivityUnarchived,
        open = open,
        archive = archive,
    )

    private var lastSweepMs: Long? = null

    /** The archived chats the last sweep saw (null until the first: it only looks). */
    private var sweptArchived: Set<String>? = null

    /** A view setting changed: sweep at once rather than a minute on. */
    @MainThread
    fun settingsChanged() {
        lastSweepMs = null
    }

    @MainThread
    fun sweep(ws: WorkspaceSnapshot) = sweep(ws.front, ws.archived, ws.pinsReady)

    @MainThread
    fun sweep(front: FrontPage, archived: List<SessionRow>, pinsReady: Boolean, nowMs: Long = now()) {
        lastSweepMs?.let { if (nowMs - it < SWEEP_INTERVAL_MS) return }
        if (!pinsReady) return
        lastSweepMs = nowMs
        val active = front.pinned + front.recent + front.sections.flatMap { it.sessions }
        val archivedNow = archived.mapTo(HashSet()) { it.id }
        val back = unarchived().toMutableMap()
        val was = sweptArchived
        sweptArchived = archivedNow
        val here = active.mapTo(HashSet()) { it.id }
        if (was != null) {
            for (id in here) if (id in was) back[id] = nowMs / 1000
        }
        back.keys.retainAll(here)
        if (back != unarchived()) setUnarchived(back)
        val s = settings()
        if (was == null || !s.on || s.archiveAfterDays <= 0) return
        val cutoff = nowMs - s.archiveAfterDays * DAY_MS
        val seen = HashSet<String>()
        for (row in active) {
            if (!seen.add(row.id)) continue
            val vm = toVm(row)
            val idleSince = max(vm.lastActivityMs, back[vm.id]?.let { it * 1000 } ?: Long.MIN_VALUE)
            if (ActivityList.archivesWhenIdle(vm, open = vm.id in open) && idleSince < cutoff) {
                archive(vm.id)
            }
        }
    }

    private companion object {
        const val SWEEP_INTERVAL_MS = 60_000L
        const val DAY_MS = 86_400_000L
    }
}
