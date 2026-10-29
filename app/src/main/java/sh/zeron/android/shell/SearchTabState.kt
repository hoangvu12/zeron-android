package sh.zeron.android.shell

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import sh.zeron.android.ZeronApplication

/**
 * The bottom bar's search: the port of the `UISearchController` that
 * `SessionsViewController.enableToolbarSearch()` puts in the iPhone's
 * bottom bar (`ios:Threads/SessionListController.swift:487-507`). The query
 * is the raw field text (spaces are trimmed only by the list's search,
 * never here) and filters the Sessions list in place. The holder lives
 * outside composition, in [ZeronApplication], so the query survives
 * rotation and the Phone ↔ Split switch; it is gone after process death.
 */
@Stable
class SearchTabState {

    /**
     * The field's text. `androidx.compose.foundation.text.input`'s
     * state-based field owns the value, selection and undo stack.
     */
    val text: TextFieldState = TextFieldState()

    /** Raw field text; snapshot-observable (`TextFieldState.text` is snapshot state). */
    val query: String get() = text.text.toString()

    /** Every text change, raw and untrimmed, distinct until changed (`updateSearchResults`). */
    fun queryFlow(): Flow<String> = snapshotFlow { query }.distinctUntilChanged()

    /**
     * iOS `searchController.isActive`: the field has expanded over the bar
     * (Settings and New session stepped aside, the close button showed).
     * Set when the field takes focus or [activate] runs; it outlives the
     * focus (putting the keyboard away keeps the search and its results),
     * and only [close] ends it.
     */
    var active: Boolean by mutableStateOf(false)
        private set

    /**
     * Incremented by [activate]; the field focuses once per new value —
     * `activateSearch()`'s `becomeFirstResponder` on the next run-loop
     * turn. Every route into search (⇧⌘F, a deep link, a launch extra)
     * goes through it.
     */
    var activationToken: Int by mutableIntStateOf(0)
        private set

    /**
     * Last token the field acted on, so a recreated composition (rotation)
     * does not refocus: the field's `LaunchedEffect` consumes it here.
     * `0` means "never activated".
     */
    internal var consumedToken: Int = 0

    /** The router's `showSearch()`: `isActive = true`, then focus the field. */
    fun activate() {
        active = true
        activationToken++
    }

    /** The field took the focus (a tap): the search becomes active. */
    fun onFieldFocused() {
        active = true
    }

    /**
     * The close button (and system Back while active): UIKit's cancel —
     * the text is cleared, so the list unfilters, and the bar returns to
     * its rest layout. The caller drops the focus and the IME.
     */
    fun close() {
        text.clearText()
        active = false
    }
}

/**
 * [SearchTabState] in composition: the app-scoped instance
 * ([ZeronApplication]'s holder survives rotation, not process death — the
 * query is deliberately not saveable). Non-app contexts (unit tests,
 * previews) get a throwaway instance.
 */
@Composable
fun rememberSearchTabState(): SearchTabState {
    val app = LocalContext.current.applicationContext
    return remember(app) {
        (app as? ZeronApplication)?.searchTabState ?: SearchTabState()
    }
}
