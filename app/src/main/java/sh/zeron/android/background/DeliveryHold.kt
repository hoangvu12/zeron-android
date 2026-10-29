package sh.zeron.android.background

import android.os.SystemClock
import uniffi.zeron_core.SendState
import uniffi.zeron_core.WorkspaceSnapshot

/**
 * The delivery hold's rule (#58, audit 05 M1 / 01 A11; iOS
 * `AppModel.swift:278-311`, za: `core/AppModel.kt:462-482`): on
 * backgrounding, if the user sent something in the last [RECENT_MS] or
 * any front-page row is still `sending` / `queued`, hold the process for
 * [HOLD_MS] so the already-durable outbox reaches the edge. Ended on
 * foreground and on sign-out.
 *
 * Process-wide: every send site calls [noteActivity] once the core
 * accepted the message (iOS `noteDeliveryActivity()`), and
 * `ZeronApplication`'s process-lifecycle observer drives the rest.
 */
object DeliveryHold {
    /** How long a send counts as recent (iOS `< 120`). */
    const val RECENT_MS = 120_000L

    /** The hold itself (iOS's 25 s deadline on the background task). */
    const val HOLD_MS = 25_000L

    /** When the user last sent something (elapsed realtime); null = never. */
    @Volatile
    private var lastActivity: Long? = null

    /** A send the core accepted: called from the send paths (any thread). */
    fun noteActivity(at: Long = SystemClock.elapsedRealtime()) {
        lastActivity = at
    }

    /** The background decision at [now], given [signedIn] and [pending]. */
    fun shouldHold(now: Long, signedIn: Boolean, pending: Boolean): Boolean =
        shouldHold(lastActivity, now, signedIn, pending)

    /** Pure: [last] is the last send (null = none). */
    internal fun shouldHold(last: Long?, now: Long, signedIn: Boolean, pending: Boolean): Boolean {
        if (!signedIn) return false
        val recent = last != null && now - last < RECENT_MS
        return recent || pending
    }

    /** Any of [states] still on its way (za: pinned + sections + recent). */
    internal fun anyPending(states: Sequence<SendState?>): Boolean =
        states.any { it == SendState.SENDING || it == SendState.QUEUED }

    /** [anyPending] over the front page of [ws] (false while there is none). */
    fun pendingIn(ws: WorkspaceSnapshot?): Boolean {
        if (ws == null) return false
        val rows = ws.front.pinned.asSequence() +
            ws.front.sections.asSequence().flatMap { it.sessions.asSequence() } +
            ws.front.recent.asSequence()
        return anyPending(rows.map { it.sendState })
    }

    internal fun resetForTest() {
        lastActivity = null
    }
}
