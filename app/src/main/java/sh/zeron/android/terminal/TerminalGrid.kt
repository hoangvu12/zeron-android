package sh.zeron.android.terminal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import sh.zeron.android.ZeronApplication
import sh.zeron.android.design.LocalFeedback
import sh.zeron.android.design.ZeronTheme

/**
 * One terminal's grid in Compose (`ios:Companions/TerminalView.swift`):
 * the lifted [TerminalView] — a `Canvas`-painted `View`, because the grid,
 * its scroll physics, the floating selection toolbar and the raw input
 * connection are all platform-view work — hosted through [AndroidView].
 * Only the chrome is ours: the default foreground is `palette.text`, the
 * cursor and selection tint `palette.accent`, and the store follows the
 * app's light/dark (iOS `TerminalStore.setDark` on a trait change), so the
 * core resolves the terminal palette against the right background.
 *
 * The screen around it (title, key bar, status pill, the keyboard button)
 * is #44's; [configure] lets it wire the view's hooks (modifiers, Add to
 * Message, focus, first paint) once the view exists.
 */
@Composable
fun TerminalGrid(
    tab: TerminalTab,
    modifier: Modifier = Modifier,
    configure: (TerminalView) -> Unit = {},
) {
    val palette = ZeronTheme.palette
    val app = LocalContext.current.applicationContext as? ZeronApplication
    val dark = ZeronTheme.isDark
    LaunchedEffect(dark, app) { app?.terminals?.dark = dark }
    val foreground = palette.text.toArgb()
    val accent = palette.accent.toArgb()
    val feedback = LocalFeedback.current
    AndroidView(
        // The grid's own semantics (a direct-interaction region, E32); the
        // platform view reports the visible text to TalkBack while a screen
        // reader runs (#63, `TerminalView.onInitializeAccessibilityNodeInfo`).
        modifier = modifier.semantics { contentDescription = "Terminal" },
        factory = { context -> TerminalView(context, tab).also(configure) },
        update = { view ->
            view.foreground = foreground
            view.accent = accent
            view.feedback = feedback
        },
    )
    SideEffect { tab.session.setDark(dark) }
}
