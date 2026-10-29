package sh.zeron.android.terminal

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeGestures
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.zeron.android.ZeronApplication
import sh.zeron.android.core.Face
import sh.zeron.android.design.CapsuleShape
import sh.zeron.android.design.Haptic
import sh.zeron.android.design.LocalFeedback
import sh.zeron.android.design.LocalReduceMotion
import sh.zeron.android.design.LocalToaster
import sh.zeron.android.design.LocalTypeScale
import sh.zeron.android.design.StatusGlyph
import sh.zeron.android.design.StatusGlyphKind
import sh.zeron.android.design.ZeronIcons
import sh.zeron.android.design.ZeronTheme
import sh.zeron.android.design.glass.GlassCircleButton
import sh.zeron.android.design.glass.GlassSurface
import sh.zeron.android.design.glass.Menu
import sh.zeron.android.design.glass.MenuIcon
import sh.zeron.android.design.glass.MenuItem
import sh.zeron.android.design.glass.MenuSection
import sh.zeron.android.design.glass.hasContent
import sh.zeron.android.design.glass.rememberGlassMenuState
import sh.zeron.android.design.uiStyle
import sh.zeron.android.session.SessionHeader
import sh.zeron.android.session.SessionHeaderDefaults
import sh.zeron.android.shell.LocalNavEntry
import sh.zeron.android.shell.LocalNavStack
import sh.zeron.android.shell.SplitBreakpoint
import uniffi.zeron_core.KeyModifiers
import uniffi.zeron_core.TerminalKey
import uniffi.zeron_core.TerminalPhase

/** The page tone (iOS `TerminalViewController.background`, `#FAFAFB` / `#090909`). */
@Composable
fun terminalBackground(): Color = if (ZeronTheme.isDark) Color(0xFF090909) else Color(0xFFFAFAFB)

/**
 * The screen's state, retained on the page's nav entry so a host
 * recomposition (a fold, the split's column) keeps the tab on show, the
 * latches and whether the demo script already ran. Main thread.
 */
@Stable
internal class TerminalScreenState(initial: TerminalTab?) {
    var current by mutableStateOf(initial)
    var opening by mutableStateOf(false)
    var failure by mutableStateOf<String?>(null)
    var hasContent by mutableStateOf(false)
    var focused by mutableStateOf(false)
    val modifiers = TerminalModifiers()
    var scriptRan = false
    var raisedOnce = false
}

/**
 * A chat's terminal (iOS `TerminalViewController`): a shell on the chat's
 * host, in its folder. The title menu switches between the chat's
 * terminals (✓ the one on show, "· ended" for the finished) and opens
 * another; + opens another; ⋯ has Text Size, Add Output to Message, Copy
 * All Output, Clear Screen and Close Terminal. The key bar rides the
 * keyboard; connection, exit and failure states show on a glass pill at
 * the bottom with the one way forward; a 48 dp glass keyboard button
 * brings the keyboard back once it was put away.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TerminalScreen(route: TerminalRoute) {
    val context = LocalContext.current
    val app = context.applicationContext as? ZeronApplication ?: return
    val store = app.terminals
    val chatId = route.chatId
    val view = LocalView.current
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val feedback = LocalFeedback.current
    val stack = LocalNavStack.current
    val entry = LocalNavEntry.current
    val menus = rememberGlassMenuState()
    val tabs = rememberChatTerminals(chatId)
    val state = remember(entry) {
        entry.retain("terminal-screen") {
            TerminalScreenState(route.terminalId?.let { id -> store.tabs(chatId).firstOrNull { it.ref.id == id } })
        }
    }
    var terminalView by remember { mutableStateOf<TerminalView?>(null) }
    // The keyboard comes up with the terminal on a phone; beside other
    // columns it waits for a tap.
    val raiseKeyboard = LocalConfiguration.current.screenWidthDp < SplitBreakpoint.value
    val imeUp = WindowInsets.isImeVisible

    // Pair with the host while the screen slides in.
    LaunchedEffect(chatId) {
        withContext(Dispatchers.IO) { runCatching { app.clientOrNull()?.warmDirect(chatId) } }
    }

    fun show(tab: TerminalTab) {
        val wasFocused = terminalView?.hasFocus() == true
        state.current = tab
        state.hasContent = false
        state.failure = null
        if (wasFocused) view.post { terminalView?.showKeyboard() }
    }

    fun runScript(tab: TerminalTab) {
        if (state.scriptRan || route.script.isEmpty()) return
        state.scriptRan = true
        scope.launch {
            for (line in route.script) {
                delay(900)
                for (ch in line) {
                    tab.session.typeText(ch.toString(), TerminalView.NO_MODS)
                    delay(45)
                }
                tab.session.press(TerminalKey.Enter, TerminalView.NO_MODS)
                delay(2400)
            }
        }
    }

    fun openAnother() {
        if (state.opening) return
        state.opening = true
        state.failure = null
        val (cols, rows) = terminalView?.grid ?: TerminalView.guessGrid(context, view.width, view.height, density)
        scope.launch {
            try {
                val tab = store.open(chatId, cols, rows)
                feedback.haptic(Haptic.Light)
                show(tab)
                if (raiseKeyboard) view.post { terminalView?.showKeyboard() }
                runScript(tab)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.failure = TerminalCopy.openFailed(e)
            } finally {
                state.opening = false
            }
        }
    }

    // iOS `viewDidAppear`: nothing on show opens a shell.
    LaunchedEffect(Unit) { if (state.current == null && !state.opening) openAnother() }

    fun addToMessage(text: String) {
        val block = terminalOutputBlock(text) ?: return
        val host = state.current?.ref?.deviceId ?: return
        app.appendToMessage(chatId, host, block)
        feedback.haptic(Haptic.Success)
        toaster.show(TerminalCopy.AddedToMessage)
    }

    fun closeCurrent() {
        val tab = state.current ?: return
        store.close(tab)
        val next = store.tabs(chatId).firstOrNull()
        if (next != null) show(next) else stack.pop()
    }

    fun clear() {
        state.current?.session?.typeText("l", KeyModifiers(ctrl = true, alt = false, shift = false))
    }

    fun resize(delta: Float) {
        terminalView?.let { v -> v.setFontSize((v.fontSize + delta).coerceIn(TerminalView.MIN_SIZE, TerminalView.MAX_SIZE)) }
    }

    val tab = state.current
    val phase = tab?.phase
    // Exit puts the keyboard away (iOS `resignFirstResponder` on `.exited`).
    LaunchedEffect(phase is TerminalPhase.Exited) {
        if (phase is TerminalPhase.Exited) terminalView?.hideKeyboard()
    }

    fun tabsMenu(): Menu = Menu(
        children = listOf(
            MenuSection(
                inline = true,
                children = tabs.map { t ->
                    MenuItem(
                        title = t.name,
                        subtitle = t.folder + if (t.isLive) "" else TerminalCopy.Ended,
                        icon = MenuIcon.Symbol(ZeronIcons.Terminal),
                        checked = t === state.current,
                        testTag = "terminal-tab",
                    ) { show(t) }
                },
            ),
            MenuItem(TerminalCopy.NewTerminalMenu, icon = MenuIcon.Symbol(ZeronIcons.Plus)) { openAnother() },
        ),
    )

    fun moreMenu(): Menu {
        val t = state.current ?: return Menu(children = emptyList())
        return Menu(
            children = listOf(
                MenuSection(
                    title = TerminalCopy.TextSize,
                    inline = true,
                    children = listOf(
                        MenuItem(TerminalCopy.Smaller) { resize(-1f) },
                        MenuItem(TerminalCopy.Reset) { terminalView?.setFontSize(TerminalView.DEFAULT_SIZE) },
                        MenuItem(TerminalCopy.Bigger) { resize(1f) },
                    ),
                ),
                MenuItem(TerminalCopy.AddOutput, icon = MenuIcon.Symbol(ZeronIcons.BubbleLeft)) {
                    addToMessage(t.session.text(false))
                },
                MenuItem(TerminalCopy.CopyAll, icon = MenuIcon.Symbol(ZeronIcons.DocOnDoc)) {
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText(TerminalCopy.Terminal, t.session.text(true)))
                    toaster.show(TerminalCopy.Copied)
                },
                MenuItem(TerminalCopy.ClearScreen) { clear() },
                MenuSection(
                    inline = true,
                    children = listOf(
                        MenuItem(TerminalCopy.CloseTerminal, icon = MenuIcon.Symbol(ZeronIcons.XmarkCircleFill), destructive = true) {
                            closeCurrent()
                        },
                    ),
                ),
            ),
        )
    }

    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val status: TerminalStatus? = terminalStatus(state.failure, phase)

    Column(
        Modifier
            .fillMaxSize()
            .background(terminalBackground())
            .testTag("terminal-screen")
            // iOS ⌘T / ⌘K / ⌘+ / ⌘−: Meta, because Ctrl belongs to the shell.
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || !e.isMetaPressed) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.T -> openAnother()
                    Key.K -> clear()
                    Key.Equals, Key.Plus, Key.NumPadAdd -> resize(1f)
                    Key.Minus, Key.NumPadSubtract -> resize(-1f)
                    else -> return@onPreviewKeyEvent false
                }
                true
            },
    ) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // The grid, below the bar row; its side gutters keep selection
            // drags clear of the system back gesture.
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(top = SessionHeaderDefaults.contentTopInset(statusBarTop))
                    .windowInsetsPadding(WindowInsets.safeGestures.only(WindowInsetsSides.Horizontal)),
            ) {
                if (tab != null) {
                    key(tab) {
                        TerminalGrid(tab, Modifier.fillMaxSize()) { v ->
                            terminalView = v
                            v.modifiers = { state.modifiers.modifiers }
                            v.consumeModifiers = { state.modifiers.consume() }
                            v.onContent = { state.hasContent = true }
                            v.onFocusChanged = { state.focused = it }
                            v.onAddToMessage = { addToMessage(it) }
                            if (v.hasContent) state.hasContent = true
                            if (raiseKeyboard && !state.raisedOnce) {
                                state.raisedOnce = true
                                v.post { v.showKeyboard() }
                            }
                        }
                    }
                }
            }
            // The loader until the shell paints (1.75× the cell spinner, 40
            // above the centre).
            val loading = state.failure == null &&
                (state.opening || (phase is TerminalPhase.Connecting && !state.hasContent))
            if (loading) {
                StatusGlyph(
                    StatusGlyphKind.Spinner,
                    modifier = Modifier.align(Alignment.Center).offset(y = (-40).dp).testTag("terminal-loading"),
                    scale = 1.75f,
                )
            }
            RiseIn(
                visible = status != null,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 12.dp),
            ) {
                status?.let { s ->
                    TerminalStatusPill(s) {
                        when (s.kind) {
                            TerminalStatus.Kind.Failed -> openAnother()
                            TerminalStatus.Kind.Exited -> {
                                state.current?.let(store::forget)
                                openAnother()
                            }
                            TerminalStatus.Kind.Reconnecting -> Unit
                        }
                    }
                }
            }
            // Brings the keyboard back once it was put away (spring 0.6 ↔ 1).
            val ended = tab?.isLive == false
            val showButton = tab != null && !ended && !(state.focused && imeUp)
            val reduce = LocalReduceMotion.current
            val shown by animateFloatAsState(
                if (showButton) 1f else 0f,
                if (reduce) spring(stiffness = 10_000f) else spring(dampingRatio = 0.8f),
                label = "terminalKeyboard",
            )
            if (shown > 0.01f) {
                GlassCircleButton(
                    icon = ZeronIcons.Keyboard,
                    contentDescription = TerminalCopy.ShowKeyboard,
                    onClick = { terminalView?.showKeyboard() },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(end = 16.dp, bottom = 12.dp)
                        .graphicsLayer {
                            alpha = shown
                            val scale = 0.6f + 0.4f * shown
                            scaleX = scale
                            scaleY = scale
                        }
                        .testTag("terminal-keyboard"),
                    size = 48.dp,
                    iconPt = 18f,
                    enabled = showButton,
                )
            }
            SessionHeader(
                title = tab?.name ?: TerminalCopy.Terminal,
                subtitle = tab?.let { "${it.folder} · ${app.deviceName(it.ref.deviceId)}" }.orEmpty(),
                edgeEngaged = false,
                onMore = { anchor ->
                    val menu = moreMenu()
                    if (menu.hasContent()) menus.open(anchor, menu)
                },
                accessory = {
                    GlassCircleButton(
                        icon = ZeronIcons.Plus,
                        contentDescription = TerminalCopy.NewTerminal,
                        onClick = { openAnother() },
                        modifier = Modifier.testTag("terminal-new"),
                        size = SessionHeaderDefaults.ButtonSize,
                        iconPt = SessionHeaderDefaults.IconPt,
                    )
                },
                onTitle = { anchor -> menus.open(anchor, tabsMenu()) },
                titleActionLabel = TerminalCopy.SwitchTerminal,
            )
        }
        // The keys a phone keyboard lacks, riding on top of it.
        AnimatedVisibility(
            visible = state.focused && imeUp,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
        ) {
            TerminalKeyBar(state.modifiers, onAction = { action ->
                val v = terminalView ?: return@TerminalKeyBar
                when (action) {
                    is KeyBarAction.Key -> v.press(action.key)
                    is KeyBarAction.Text -> v.insertText(action.text)
                    KeyBarAction.Paste -> v.pasteFromClipboard()
                    KeyBarAction.Dismiss -> v.hideKeyboard()
                }
            })
        }
        Spacer(Modifier.imePadding().navigationBarsPadding())
    }
}

/**
 * The pill's fade-and-rise, outside any layout scope so it never picks up
 * the page column's `ColumnScope.AnimatedVisibility`.
 */
@Composable
private fun RiseIn(visible: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
    ) { content() }
}

/** What the status pill shows (iOS `TerminalStatusPill.State`) and its action. */
data class TerminalStatus(val kind: Kind, val text: String) {
    enum class Kind { Reconnecting, Exited, Failed }

    /** "Try Again" / "New Terminal" / none. */
    val action: String?
        get() = when (kind) {
            Kind.Failed -> TerminalCopy.TryAgain
            Kind.Exited -> TerminalCopy.NewTerminalAction
            Kind.Reconnecting -> null
        }

    val a11y: String get() = listOfNotNull(text, action).joinToString(", ")
}

/** iOS `syncState` + the open failure, as one value. Pure. */
fun terminalStatus(failure: String?, phase: TerminalPhase?): TerminalStatus? = when {
    failure != null -> TerminalStatus(TerminalStatus.Kind.Failed, failure)
    phase is TerminalPhase.Reconnecting -> TerminalStatus(TerminalStatus.Kind.Reconnecting, TerminalCopy.Reconnecting)
    phase is TerminalPhase.Exited -> TerminalStatus(TerminalStatus.Kind.Exited, TerminalCopy.exited(phase))
    phase is TerminalPhase.Gone -> TerminalStatus(TerminalStatus.Kind.Exited, TerminalCopy.EndedOnHost)
    else -> null
}

/**
 * The status pill (iOS `TerminalStatusPill`): a 40 dp glass capsule, a
 * 12 dp glyph (the spinner while reconnecting, a dot otherwise — idle, or
 * danger for a failed open), the text (Medium 14, `text`) and the action
 * (Semibold 14, `accent`); the whole pill is the button.
 */
@Composable
private fun TerminalStatusPill(status: TerminalStatus, onAction: () -> Unit) {
    val palette = ZeronTheme.palette
    val ts = LocalTypeScale.current
    val source = remember { MutableInteractionSource() }
    GlassSurface(
        modifier = Modifier
            .height(40.dp)
            .clickable(source, indication = null, role = Role.Button, onClick = onAction)
            .clearAndSetSemantics {
                contentDescription = status.a11y
                role = Role.Button
                onClick { onAction(); true }
            }
            .testTag("terminal-status"),
        shape = CapsuleShape,
        interactive = true,
        interactionSource = source,
        contentAlignment = Alignment.Center,
    ) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusGlyph(
                when (status.kind) {
                    TerminalStatus.Kind.Reconnecting -> StatusGlyphKind.Spinner
                    TerminalStatus.Kind.Exited -> StatusGlyphKind.Dot(palette.status.idle)
                    TerminalStatus.Kind.Failed -> StatusGlyphKind.Dot(palette.danger)
                },
            )
            Spacer(Modifier.width(9.dp))
            BasicText(
                status.text,
                style = uiStyle(Face.SansMedium, ts.fixed(14f), palette.text),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 240.dp),
            )
            status.action?.let { action ->
                Spacer(Modifier.width(9.dp))
                BasicText(action, style = uiStyle(Face.SansSemibold, ts.fixed(14f), palette.accent), maxLines = 1)
            }
        }
    }
}
