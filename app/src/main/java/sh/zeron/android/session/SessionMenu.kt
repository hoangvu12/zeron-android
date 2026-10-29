package sh.zeron.android.session

import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.Configuration
import android.os.Build
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.zeron.android.design.Appearance
import sh.zeron.android.design.LocalToaster
import sh.zeron.android.design.ZeronIcons
import sh.zeron.android.design.ZeronTheme
import sh.zeron.android.design.Toaster
import sh.zeron.android.design.glass.GlassCircleButton
import sh.zeron.android.design.glass.GlassMenuPanel
import sh.zeron.android.design.glass.Menu
import sh.zeron.android.design.glass.MenuIcon
import sh.zeron.android.design.glass.MenuItem
import sh.zeron.android.design.glass.MenuSection
import sh.zeron.android.design.glass.flattenLevel
import sh.zeron.android.design.glass.rememberGlassMenuState
import sh.zeron.android.sessions.ActionOutcome
import sh.zeron.android.sessions.LocalRowActions
import sh.zeron.android.sessions.rememberSessionPrompts
import sh.zeron.android.shell.AppRouter
import sh.zeron.android.shell.LocalAppRouter
import sh.zeron.android.shell.LocalNavEntry
import sh.zeron.android.shell.LocalNavStack
import sh.zeron.android.transcript.TranscriptState
import uniffi.zeron_core.LayoutFrame

/**
 * The header's ⋯ pull-down (#33) — the port of `sessionMenu(title:subtitle:)`
 * (`ios:Session/SessionViewController.swift:800-833`). Everything visual
 * belongs to [Menu]/[MenuItem]/[GlassMenuPanel]; this file owns only the
 * content, the actions and the one router hook Archive and Delete need.
 *
 * The menu is **rebuilt on every open** (iOS
 * `UIDeferredMenuElement.uncached`): [rememberSessionMenu] builds a fresh
 * [Menu] inside the click lambda, so the pin title is always current,
 * never cached in composition.
 *
 * Zeron adaptations: the leave after Archive happens only on `Ok`/`Gone`
 * outcomes; Copy Transcript builds the string off main and handles
 * Binder's transaction limit with a failure toast.
 */

/**
 * Pure: the ⋯ menu for one open, headed by the session's title over
 * "project @ device" ([title]/[subtitle], one wrapped `Menu.title`; blank
 * = no header). Rows in iOS order:
 *
 * - an inline group: "New Side Chat" (SF `bubble.left.and.text.bubble.right`),
 *   "Fork Into Side Chat" (SF `arrow.triangle.branch`), "Terminal"
 *   (SF `apple.terminal`) — [companions];
 * - "Pin" (SF `pin`) / "Unpin" (SF `pin.slash`) by [pinned];
 * - "Copy Transcript" (SF `doc.on.doc`);
 * - "Archive" (SF `archivebox`);
 * - "Delete" (SF `trash`), destructive, after the "Delete session?" confirm.
 *
 * Keep it pure: no `@Composable`, no state reads. [rememberSessionMenu]
 * calls this once per open, from the click lambda.
 */
fun sessionMenu(
    pinned: Boolean,
    onSetPinned: (Boolean) -> Unit,
    onCopyTranscript: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    companions: CompanionMenuRows = CompanionMenuRows(),
    title: String = "",
    subtitle: String = "",
): Menu = Menu(
    title = sessionMenuHeader(title, subtitle),
    children = listOf(
        companionSection(companions),
        MenuItem(
            title = if (pinned) "Unpin" else "Pin",
            icon = MenuIcon.Symbol(if (pinned) ZeronIcons.PinSlash else ZeronIcons.Pin),
            testTag = "session-menu-pin",
            action = { onSetPinned(!pinned) },
        ),
        MenuItem(
            title = "Copy Transcript",
            icon = MenuIcon.Symbol(ZeronIcons.DocOnDoc),
            testTag = "session-menu-copy",
            action = onCopyTranscript,
        ),
        MenuItem(
            title = "Archive",
            icon = MenuIcon.Symbol(ZeronIcons.Archivebox),
            testTag = "session-menu-archive",
            action = onArchive,
        ),
        MenuItem(
            title = "Delete",
            icon = MenuIcon.Symbol(ZeronIcons.Trash),
            destructive = true,
            testTag = "session-menu-delete",
            action = onDelete,
        ),
    ),
)

/**
 * The ⋯ menu's header (upstream #967): the title, then where it runs on
 * its own line — a menu subtitle isn't drawn in the header, so the two
 * share the title, split by a line break (the menu draws each line once,
 * ellipsized). `null` when both are blank.
 */
internal fun sessionMenuHeader(title: String, subtitle: String): String? = when {
    title.isBlank() && subtitle.isBlank() -> null
    subtitle.isBlank() -> title
    title.isBlank() -> subtitle
    else -> "$title\n$subtitle"
}

/** The actions of the ⋯ menu's first, inline group (the companions). */
class CompanionMenuRows(
    val onNewSideChat: () -> Unit = {},
    val onForkSideChat: () -> Unit = {},
    val onTerminal: () -> Unit = {},
)

/** The inline group (hairlines above and below), always shown as on iOS. */
private fun companionSection(rows: CompanionMenuRows): MenuSection = MenuSection(
    inline = true,
    children = listOf(
        MenuItem(
            title = "New Side Chat",
            icon = MenuIcon.Symbol(ZeronIcons.BubbleLeftAndTextBubbleRight),
            testTag = "session-menu-new-side-chat",
            action = rows.onNewSideChat,
        ),
        MenuItem(
            title = "Fork Into Side Chat",
            icon = MenuIcon.Symbol(ZeronIcons.ArrowTriangleBranch),
            testTag = "session-menu-fork-side-chat",
            action = rows.onForkSideChat,
        ),
        MenuItem(
            title = "Terminal",
            icon = MenuIcon.Symbol(ZeronIcons.Terminal),
            testTag = "session-menu-terminal",
            action = rows.onTerminal,
        ),
    ),
)

/**
 * The [SessionHeader] `onMore` handler (80's step 7): returns a lambda
 * that builds a fresh [sessionMenu] from the **current** pin state, and
 * opens it on ticket 15's host at the ⋯
 * circle's window rect. The values are read inside the click lambda —
 * never during composition — so each open is current (the uncached
 * deferred element) and a pin toggle between opens needs no
 * recomposition.
 *
 * The writes go through [LocalRowActions] (42), the Undo through the
 * app-scoped [LocalToaster] (14) — both outlive this screen, so the
 * "Archived · Undo" toast still works after the pop. Copy runs in the
 * composition scope: if the screen leaves mid-copy the copy is cancelled
 * (acceptable — the menu is closed and the user is gone).
 *
 * Requires [sh.zeron.android.shell.LocalNavStack] /
 * [sh.zeron.android.shell.LocalNavEntry] (any screen inside a
 * `NavStackHost`) and a mounted `GlassMenuHost` (the app root, 35).
 */
@Composable
fun rememberSessionMenu(
    controller: SessionController,
    transcript: TranscriptState,
    /** The companion group's actions, read at open time. */
    companions: () -> CompanionMenuRows = { CompanionMenuRows() },
): (anchorInWindow: Rect) -> Unit {
    val currentCompanions by rememberUpdatedState(companions)
    val menuState = rememberGlassMenuState()
    val actions = LocalRowActions.current
    val router = LocalAppRouter.current
    val nav = LocalNavStack.current
    val entry = LocalNavEntry.current
    val toaster = LocalToaster.current
    val prompts = rememberSessionPrompts(rowActions = actions)
    // The application context: the clipboard service outlives any
    // activity-level context flip while this remembered lambda is held.
    val appContext = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    return remember(controller, transcript, menuState, actions, router, nav, entry, toaster, prompts) {
        val open: (Rect) -> Unit = { anchor: Rect ->
            val chatId = controller.chatId
            val chrome = controller.chrome.value
            // Off this session once it's gone (iOS `leave()`): the split's
            // draft, else the guarded pop. Shared by Archive and Delete.
            val leave = {
                leaveAfterArchive(
                    chatId,
                    router,
                    isTopAndIdle = { nav.top == entry && nav.transition == null },
                    pop = { nav.pop(animated = true) },
                )
            }
            val menu = sessionMenu(
                pinned = controller.pinned,
                onSetPinned = { pinned -> actions.setPinned(chatId, pinned) },
                onCopyTranscript = {
                    scope.launch {
                        val clipboard = appContext.getSystemService(ClipboardManager::class.java)
                            ?: return@launch
                        copyTranscript(transcript, clipboard, toaster)
                    }
                },
                onArchive = {
                    actions.archive(chatId) { outcome ->
                        if (outcome == ActionOutcome.Ok || outcome == ActionOutcome.Gone) leave()
                    }
                },
                // TODO(#53): a Cloud chat's confirm adds "and its Cloud machine…".
                onDelete = { prompts.confirmDeleteSession(chatId, chrome.title, onDeleted = leave) },
                companions = currentCompanions(),
                title = chrome.title,
                subtitle = chrome.subtitle,
            )
            menuState.open(anchor, menu)
        }
        open
    }
}

/**
 * Copies `plainText()` of the **latest** frame (iOS
 * `UIPasteboard.general.string = engine.frame().plainText()`), off main:
 *
 * 1. on main: `transcript.engine.frame()` — a fresh Kotlin wrapper this
 *    code owns — unless the transcript is closed, in which case the last
 *    applied [TranscriptState.frame] is used (owned by the state,
 *    **never closed**);
 * 2. on [work]: `plainText()` and the fresh wrapper's `close()` (the
 *    pr621 closing rule);
 * 3. back on main: the platform `ClipboardManager` write.
 *
 * Feedback (android-app#22): API 33+ shows the system's own copy overlay
 * — nothing here; API 31–32 shows Zeron's "Copied" toast. A
 * `TransactionTooLargeException`-style `RuntimeException` from Binder
 * logs and toasts "Could not copy the transcript." (iOS's pasteboard has
 * no such limit; Android's must not crash the app). An empty transcript
 * copies `""`, as iOS does.
 */
internal suspend fun copyTranscript(
    transcript: TranscriptState,
    clipboard: ClipboardManager,
    toaster: Toaster,
    sdkInt: Int = Build.VERSION.SDK_INT,
    work: CoroutineDispatcher = Dispatchers.Default,
) {
    // Both reads happen on main; never read the snapshot-state
    // `transcript.frame` off main.
    val fresh: LayoutFrame? = if (transcript.isClosed) null else transcript.engine.frame()
    val held: LayoutFrame? = if (fresh == null) transcript.frame else null
    val text = withContext(work) {
        when {
            // pr621's closing rule: the fresh wrapper is closed in a
            // finally, so a throwing plainText() never leaks it. (A bare
            // `use` does not resolve here: kotlin.io's applies to
            // java.io.Closeable, the bindings' own to `Disposable` in
            // uniffi.zeron_core.)
            fresh != null -> try {
                fresh.plainText()
            } finally {
                fresh.close()
            }
            held != null -> held.plainText()
            else -> ""
        }
    }
    try {
        clipboard.setPrimaryClip(ClipData.newPlainText("Transcript", text))
        // Android 12/12L has no copy confirmation of its own; Android 13+
        // shows its overlay and a second toast would duplicate it.
        if (sdkInt <= Build.VERSION_CODES.S_V2) toaster.show("Copied")
    } catch (e: RuntimeException) {
        Log.w("Zeron", "copy transcript failed: $e")
        toaster.show("Could not copy the transcript.")
    }
}

/**
 * Router first (the tablet split's embedded draft, iOS
 * `split.showDraft(prompt: nil, focus: false)`), else pop this screen if
 * it is still the stack's top and no transition is running. The guard
 * stops a late `onResult` from popping a *different* screen — the user
 * may have swiped back while the write was in flight.
 *
 * Lambdas instead of `NavStack`/`NavEntry` because `NavStack` is a final
 * class (ticket 24) and cannot be faked; the production call site is
 * `{ nav.top == entry && nav.transition == null }` / `{ nav.pop(animated = true) }`.
 */
internal fun leaveAfterArchive(
    chatId: String,
    router: AppRouter,
    isTopAndIdle: () -> Boolean,
    pop: () -> Unit,
) {
    if (router.leaveArchivedSession(chatId)) return
    if (isTopAndIdle()) pop()
}

// ---------------------------------------------------------------------------
// Previews (step 9): the open state, statically — a @Preview cannot drive
// the host's open animation, so the panel is rendered directly through
// [GlassMenuPanel] next to a 44 dp ⋯ circle, at the placement the host
// would use (panel trailing edge = circle trailing edge, panel top =
// circle top: `cover = true`).
// ---------------------------------------------------------------------------

/** The previews' shared fixture: the ⋯ circle with the menu open below it. */
@Composable
private fun SessionMenuOpenPreview(pinned: Boolean) {
    Box(Modifier.fillMaxWidth().height(220.dp).background(ZeronTheme.palette.background)) {
        GlassCircleButton(
            icon = ZeronIcons.Ellipsis,
            contentDescription = "More",
            onClick = {},
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 24.dp, end = 16.dp),
        )
        GlassMenuPanel(
            lines = flattenLevel(
                null,
                sessionMenu(
                    pinned = pinned,
                    onSetPinned = {},
                    onCopyTranscript = {},
                    onArchive = {},
                    onDelete = {},
                ).children,
                { null },
                isRoot = true,
                backTitle = null,
            ),
            onRow = {},
            onSubmenu = {},
            onBack = {},
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 24.dp, end = 16.dp),
        )
    }
}

@Preview(name = "light")
@Composable
private fun SessionMenuUnpinnedLightPreview() {
    ZeronTheme(Appearance.Light) { SessionMenuOpenPreview(pinned = false) }
}

@Preview(name = "dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SessionMenuPinnedDarkPreview() {
    ZeronTheme(Appearance.Dark) { SessionMenuOpenPreview(pinned = true) }
}
