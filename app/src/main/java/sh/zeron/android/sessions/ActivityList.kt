package sh.zeron.android.sessions

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

/**
 * Activity: the Sessions page as one list of the chats that need you, the
 * one that came back last at the top — the port of iOS
 * `Threads/ActivityList.swift` (the desktop sidebar's Activity view,
 * `apps/zgx/ui/screens/shell/activity.luau`). A chat under way folds into
 * Working (running, or waiting on background work that will wake it), one
 * put to sleep into Asleep until its time or until it asks for you, and
 * archived ones sit in Archived. The three shelves stand at the list's
 * foot, folded until opened.
 *
 * What a chat is doing and its sleep are the engine's (synced, the same on
 * every device: a row's status, `waiting`, `sleepUntilMs`, `wokeAtMs`). The
 * view and its settings are this phone's ([sh.zeron.android.core.Prefs]'s
 * `activity`). Pure: the Sessions page, Settings and the sweep read it.
 */
object ActivityList {

    /** The phone-local keys (the iOS `UserDefaults` names). */
    object Keys {
        const val ON = "activity.on"
        const val WORKING_SHELF = "activity.workingShelf"
        const val ARCHIVE_AFTER = "activity.archiveAfter"

        /** Chats brought back from Archived, by id: when (Unix seconds). */
        const val UNARCHIVED = "activity.unarchived"

        /** The shelves opened (they start folded). */
        const val OPEN_SHELVES = "activity.openShelves"
    }

    /** The Sessions page's sections while Activity is on. */
    object Section {
        const val LIST = "activity"
        const val WORKING = "activity.working"
        const val ASLEEP = "activity.asleep"
        const val ARCHIVED = "activity.archived"

        /** The shelves: folded until opened. */
        val shelves: Set<String> = setOf(WORKING, ASLEEP, ARCHIVED)
    }

    const val LIST_TITLE = "Activity"
    const val WORKING_TITLE = "Working"
    const val ASLEEP_TITLE = "Asleep"
    const val ARCHIVED_TITLE = "Archived"

    enum class Shelf { Working, Asleep }

    /** Settings → Activity's choices for Archive Idle Chats, in days (0: never). */
    val archiveAfterChoices: List<Int> = listOf(0, 1, 3, 7, 14, 30)

    fun archiveAfterLabel(days: Int): String = when (days) {
        0 -> "Never"
        1 -> "After 1 Day"
        else -> "After $days Days"
    }

    /**
     * The shelf a chat sits on, or null for the list itself: asleep while
     * it sleeps, Working while under way (Waiting reads Working) when that
     * shelf is on.
     */
    fun shelf(vm: SessionRowVM, workingShelf: Boolean): Shelf? {
        if (vm.sleepUntilMs != null) return Shelf.Asleep
        if (workingShelf && vm.status == SessionRowVM.Status.Working) return Shelf.Working
        return null
    }

    /**
     * A row as Activity draws it: woken from sleep and not looked at since,
     * the alarm, unless it's asking for you or under way; asleep, its corner
     * says when it wakes.
     */
    fun decorate(vm: SessionRowVM, nowMs: Long): SessionRowVM {
        var out = vm
        if (vm.wokeAtMs != null &&
            (vm.status == SessionRowVM.Status.Idle || vm.status == SessionRowVM.Status.Completed)
        ) {
            out = out.copy(status = SessionRowVM.Status.Woke)
        }
        val until = vm.sleepUntilMs
        if (until != null) out = out.copy(timeLabel = wakesIn(until, nowMs))
        return out
    }

    /**
     * Higher stands nearer the top: the list by when it last came back (its
     * own last move, or waking); Asleep by how soon it wakes.
     */
    fun rank(vm: SessionRowVM, shelf: Shelf?): Long {
        val until = vm.sleepUntilMs
        if (shelf == Shelf.Asleep && until != null) return -until
        return max(vm.lastActivityMs, vm.wokeAtMs ?: Long.MIN_VALUE)
    }

    data class Page(
        val list: List<SessionRowVM> = emptyList(),
        val working: List<SessionRowVM> = emptyList(),
        val asleep: List<SessionRowVM> = emptyList(),
    )

    /**
     * The list and its shelves from the active (unpinned, unarchived)
     * chats, each ranked, drawn as Activity draws them. A chat listed twice
     * (Recent and a section) lists once.
     */
    fun page(rows: List<SessionRowVM>, workingShelf: Boolean, nowMs: Long): Page {
        class Keyed(val rank: Long, val vm: SessionRowVM, val shelf: Shelf?)
        val seen = HashSet<String>()
        val keyed = ArrayList<Keyed>(rows.size)
        for (vm in rows) {
            if (!seen.add(vm.id)) continue
            val shelf = shelf(vm, workingShelf)
            keyed += Keyed(rank(vm, shelf), decorate(vm, nowMs), shelf)
        }
        keyed.sortWith { a, b -> if (a.rank != b.rank) b.rank.compareTo(a.rank) else a.vm.id.compareTo(b.vm.id) }
        val list = ArrayList<SessionRowVM>()
        val working = ArrayList<SessionRowVM>()
        val asleep = ArrayList<SessionRowVM>()
        for (k in keyed) {
            when (k.shelf) {
                Shelf.Working -> working += k.vm
                Shelf.Asleep -> asleep += k.vm
                null -> list += k.vm
            }
        }
        return Page(list, working, asleep)
    }

    /** How soon [untilMs] is, as a row's corner says it: "45m", "3h", "2d". */
    fun wakesIn(untilMs: Long, nowMs: Long): String {
        val left = max(0L, untilMs - nowMs) / 1000.0
        if (left < 3600) return "${max(1, ceil(left / 60).toInt())}m"
        if (left < 86_400) return "${ceil(left / 3600).toInt()}h"
        return "${ceil(left / 86_400).toInt()}d"
    }

    /**
     * One of Sleep's choices: what it's called, when it wakes as its
     * subtitle says it, and that moment (Unix ms).
     */
    data class Preset(val id: String, val title: String, val detail: String, val untilMs: Long)

    /** The locale's short time ("9:00 AM", "09:00"): iOS `timeStyle = .short`. */
    fun shortTime(locale: Locale): (ZonedDateTime) -> String {
        val f = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
        return { f.format(it) }
    }

    /**
     * Sleep's choices, soonest first, one a minute: in an hour, in three,
     * this evening (6 PM, while it's still ahead), tomorrow at 9, next
     * Monday at 9. Each says when it wakes: its time, and its day where its
     * name doesn't.
     *
     * @param time the short-time format (iOS `DateFormatter.timeStyle =
     *   .short`); the app passes one that follows the 24-hour setting
     */
    fun presets(
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
        time: (ZonedDateTime) -> String = shortTime(locale),
    ): List<Preset> {
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        val today = now.toLocalDate()
        fun at(days: Long, hour: Int): ZonedDateTime = today.plusDays(days).atTime(hour, 0).atZone(zone)
        val weekday = DateTimeFormatter.ofPattern("EEE", locale)
        val out = ArrayList<Preset>()
        fun add(id: String, title: String, until: ZonedDateTime, namesDay: Boolean) {
            val ms = until.toInstant().toEpochMilli()
            if (ms <= nowMs) return
            val sameDay = until.toLocalDate() == today
            val detail = if (namesDay || sameDay) time(until) else "${weekday.format(until)} ${time(until)}"
            out += Preset(id, title, detail, ms)
        }
        add("hour", "In 1 Hour", now.plusSeconds(3600), namesDay = false)
        add("hours", "In 3 Hours", now.plusSeconds(3 * 3600), namesDay = false)
        add("evening", "This Evening", at(0, 18), namesDay = true)
        add("tomorrow", "Tomorrow", at(1, 9), namesDay = true)
        // Monday: a week on from a Monday; the next one from any other day.
        val toMonday = (8 - now.dayOfWeek.value) % 7
        add("week", "Next Week", at(if (toMonday == 0) 7L else toMonday.toLong(), 9), namesDay = false)
        out.sortBy { it.untilMs }
        val minutes = HashSet<Long>()
        return out.filter { minutes.add(Math.floorDiv(it.untilMs, 60_000L)) }
    }

    /** Where Custom… opens: tomorrow at 9. */
    fun customStart(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().plusDays(1)
            .atTime(9, 0).atZone(zone).toInstant().toEpochMilli()

    /** The earliest Custom… may pick: a minute from now. */
    fun customMinimum(nowMs: Long): Long = nowMs + 60_000L

    /**
     * Whether auto-archive may take a chat idle long enough: never one
     * that's pinned, open now, asleep, woken, under way or needing you.
     */
    fun archivesWhenIdle(vm: SessionRowVM, open: Boolean): Boolean {
        if (vm.pinned || open || vm.sleepUntilMs != null || vm.wokeAtMs != null || vm.sendFailed) return false
        return when (vm.status) {
            SessionRowVM.Status.Working,
            SessionRowVM.Status.Awaiting,
            SessionRowVM.Status.Errored,
            SessionRowVM.Status.Woke,
            -> false
            SessionRowVM.Status.Idle, SessionRowVM.Status.Completed -> true
        }
    }
}
