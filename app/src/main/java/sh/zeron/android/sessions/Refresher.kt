package sh.zeron.android.sessions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import sh.zeron.android.ZeronApplication
import sh.zeron.android.core.WorkspaceFeed
import sh.zeron.android.design.PullToRefreshState
import sh.zeron.android.design.rememberPullToRefreshState
import uniffi.zeron_core.Connectivity
import uniffi.zeron_core.ConnectivityState
import uniffi.zeron_core.CoreClient

/**
 * What a pull-to-refresh does (#23): the port of iOS
 * `AppModel.refresh(minimum:maximum:completion:)`
 * (`ios:App/AppModel.swift:314-340`) — "redial the registry and rooms and
 * re-probe health (the same resync as returning to the foreground), then
 * report back once the workspace has updated". [refresh] returns when the
 * resync has landed; the 0.6 s floor and the 3 s backstop live in
 * `design/PullToRefresh.kt`'s [holdRefresh][sh.zeron.android.design.holdRefresh],
 * which wraps this — never call it directly from a screen.
 */
fun interface Refresher {
    suspend fun refresh()
}

/**
 * An override of the [Refresher] (galleries, tests); `null` (production)
 * uses the app's [feedRefresher] over the one workspace feed and the
 * current client.
 */
val LocalRefresher = staticCompositionLocalOf<Refresher?> { null }

/**
 * Production: `CoreClient.onForeground()` (off main — no `CoreClient`
 * call on the main thread) and a workspace re-read, then
 * [awaitResync] over the feed: the call returns on the next completed
 * read or connectivity change, except while connectivity says
 * reconnecting/offline (iOS: "a reconnecting/offline blip from the redial
 * isn't the resync landing"). A null client (signed out) still re-reads,
 * which lands at once.
 */
fun feedRefresher(
    feed: WorkspaceFeed,
    client: () -> CoreClient?,
    io: CoroutineDispatcher = Dispatchers.IO,
): Refresher = Refresher {
    awaitResync(feed.reads, feed.connectivity, ::connectivitySettled) {
        withContext(io) { client()?.onForeground() }
        feed.refresh()
    }
}

/** False while the core is redialling or offline: an update then is not the resync landing. */
internal fun connectivitySettled(c: Connectivity?): Boolean =
    c?.state != ConnectivityState.RECONNECTING && c?.state != ConnectivityState.OFFLINE

/**
 * Runs [kick], then suspends until [reads] has moved past its value at
 * the start, or [connectivity] has changed, with [settled] true for the
 * current connectivity — iOS's one-shot observer that ignores the
 * reconnecting/offline notifications. Both baselines are taken **before**
 * [kick], so an update that lands while it runs is never missed. Never
 * returns on its own otherwise: the caller's backstop ends the wait.
 */
suspend fun <C> awaitResync(
    reads: StateFlow<Long>,
    connectivity: StateFlow<C>,
    settled: (C) -> Boolean,
    kick: suspend () -> Unit,
) {
    val readsBefore = reads.value
    val connectivityBefore = connectivity.value
    kick()
    combine(reads, connectivity) { r, c -> (r != readsBefore || c != connectivityBefore) && settled(c) }
        .first { it }
}

/**
 * One [PullToRefreshState] for a session-list screen (the front page, the
 * folder pages, the sidebar), wired to [LocalRefresher] or, by default,
 * the app's [feedRefresher]. Pass it to
 * [LargeTitleScaffold][sh.zeron.android.shell.LargeTitleScaffold]'s
 * `pullToRefresh` slot.
 */
@Composable
fun rememberSessionsPullToRefresh(): PullToRefreshState {
    val override = LocalRefresher.current
    val app = LocalContext.current.applicationContext as? ZeronApplication
    val refresher = remember(override, app) {
        override ?: app?.let { feedRefresher(it.workspaceFeed, it::clientOrNull) } ?: Refresher { }
    }
    return rememberPullToRefreshState(onRefresh = { refresher.refresh() })
}
