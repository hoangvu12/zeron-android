package sh.zeron.android.sessions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.zeron.android.design.PullToRefreshState
import sh.zeron.android.design.rememberPullToRefreshState
import uniffi.zeron_core.CoreClient

/**
 * What a pull-to-refresh does (android-ui-parity #48): the Android
 * reading of iOS's "redial the registry and rooms and re-probe health"
 * (`ios:App/AppModel.swift:292-318`). Zeron's core has no `refresh()`;
 * [coreRefresher] nudges it with `onForeground()`. The 0.6 s floor and the UI-side 3 s
 * backstop live in `design/PullToRefresh.kt`'s [holdRefresh][sh.zeron.android.design.holdRefresh],
 * which wraps this — never call it directly from a screen.
 */
fun interface Refresher {
    suspend fun refresh()
}

/**
 * The ambient [Refresher]. A no-op until the app root (ticket 35's owner
 * of `CoreClient`) provides [coreRefresher] — the same place that
 * provides [LocalRowActions] (ticket 42 step 7). With the default, a pull
 * still spins for the 0.6 s minimum, so the UI works unpaired.
 */
val LocalRefresher = staticCompositionLocalOf<Refresher> { Refresher { } }

/**
 * Production: `CoreClient.onForeground()`, which redials and re-pulls the
 * workspace. It returns at once, so the spinner holds only the 0.6 s
 * floor. TODO(#23): hold until the next `WorkspaceChanged` /
 * `ConnectivityChanged`, as iOS `AppModel.refresh` does. A null client
 * (signed out / torn down) returns at once too. The call runs off main on [Dispatchers.IO] (ticket
 * 42's FFI threading rule: no `CoreClient` call on the main thread — JNA
 * entry and android-app#06's re-entrant listener callbacks stay off it),
 * and `withContext` is cancellable, so `holdRefresh`'s 3 s backstop still
 * cuts it. Never wrap it in `runBlocking`.
 */
fun coreRefresher(client: () -> CoreClient?): Refresher =
    Refresher { withContext(Dispatchers.IO) { client()?.onForeground() } }

/**
 * One [PullToRefreshState] for a session-list screen (tickets 45, 46, 49;
 * 105 reuses 46), wired to the ambient [Refresher]. Pass it to
 * [LargeTitleScaffold][sh.zeron.android.shell.LargeTitleScaffold]'s
 * `pullToRefresh` slot.
 */
@Composable
fun rememberSessionsPullToRefresh(): PullToRefreshState {
    val refresher = LocalRefresher.current
    return rememberPullToRefreshState(onRefresh = { refresher.refresh() })
}
