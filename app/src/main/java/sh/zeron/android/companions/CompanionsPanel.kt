package sh.zeron.android.companions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import sh.zeron.android.core.Face
import sh.zeron.android.design.FadingText
import sh.zeron.android.design.Haptic
import sh.zeron.android.design.Icon
import sh.zeron.android.design.LocalFeedback
import sh.zeron.android.design.LocalTypeScale
import sh.zeron.android.design.StatusGlyph
import sh.zeron.android.design.StatusGlyphKind
import sh.zeron.android.design.SymbolWeight
import sh.zeron.android.design.TranscriptPalette
import sh.zeron.android.design.ZeronIcon
import sh.zeron.android.design.ZeronIcons
import sh.zeron.android.design.ZeronTheme
import sh.zeron.android.design.color
import sh.zeron.android.design.glass.GlassCircleButton
import sh.zeron.android.design.glass.Menu
import sh.zeron.android.design.glass.MenuIcon
import sh.zeron.android.design.glass.MenuItem
import sh.zeron.android.design.glass.rememberGlassMenuState
import sh.zeron.android.design.rememberSmoothRoundedShape
import sh.zeron.android.design.sheet.GlassSheet
import sh.zeron.android.design.sheet.GlassSheetDefaults
import sh.zeron.android.design.sheet.SheetDetent
import sh.zeron.android.design.sheet.rememberGlassSheetState
import sh.zeron.android.design.uiStyle
import sh.zeron.android.session.SessionTitleView
import sh.zeron.android.sessions.SessionRow
import sh.zeron.android.sessions.SwipeAction
import sh.zeron.android.sessions.SwipeRow
import sh.zeron.android.sessions.rememberRowHighlight
import sh.zeron.android.sessions.rememberSwipeCoordinator
import sh.zeron.android.sidechats.AgentState
import sh.zeron.android.sidechats.SideChatsAgent
import sh.zeron.android.sidechats.SideChatsUi
import uniffi.zeron_core.ColorRole
import uniffi.zeron_core.modelLabel

/** The side chats header's + menu (iOS `headerButton(.sideChats)`). Pure. */
fun sideChatsAddMenu(onNew: () -> Unit, onFork: () -> Unit): Menu = Menu(
    title = null,
    children = listOf(
        MenuItem(
            title = CompanionsCopy.NewSideChatMenu,
            icon = MenuIcon.Symbol(ZeronIcons.BubbleLeftAndTextBubbleRight),
            testTag = "companions-new-side-chat",
            action = onNew,
        ),
        MenuItem(
            title = CompanionsCopy.ForkThisChatMenu,
            icon = MenuIcon.Symbol(ZeronIcons.ArrowTriangleBranch),
            testTag = "companions-fork",
            action = onFork,
        ),
    ),
)

/**
 * Everything working alongside one session (iOS `CompanionsViewController`,
 * phone sheet): the subagents its agent spawned (live first), its side
 * chats, and its terminals — each opens in place, and each section can
 * start another. A `GlassSheet` at `[Medium, Large]` with a grabber,
 * headed by the chat's title over "Agents, side chats & terminals" (or "N
 * agents working") with a Close circle. Host it as the **last child of
 * the session screen**.
 *
 * Picking a row dismisses the sheet, then opens it on the session's stack
 * (B17). New side chat does the same once made; a fork keeps the sheet up
 * with "Forking…" on its row until it lands ([forking]), then opens the
 * fork the same way ([CompanionsPanelController.closeThen]).
 *
 * @param onAppear iOS `viewDidAppear`: `warmDirect(chatId)` (a terminal
 *   may be a tap away)
 */
@Composable
fun CompanionsOverlay(
    controller: CompanionsPanelController,
    chatTitle: String,
    ui: SideChatsUi,
    forking: Boolean,
    onOpenChat: (chatId: String) -> Unit,
    onOpenAgent: (SideChatsAgent) -> Unit,
    onNewSideChat: () -> Unit,
    onFork: () -> Unit,
    onNewTerminal: () -> Unit,
    onArchive: (chatId: String, onAccepted: (Boolean) -> Unit) -> Unit,
    onAppear: () -> Unit = {},
) {
    if (!controller.showing) return
    val detents = remember { listOf(SheetDetent.Medium, SheetDetent.Large) }
    val appear by rememberUpdatedState(onAppear)
    LaunchedEffect(Unit) { appear() }
    GlassSheet(
        onDismiss = { controller.onDismissed() },
        modifier = Modifier.testTag("companions"),
        state = rememberGlassSheetState(detents),
        grabber = true,
        title = chatTitle.ifEmpty { "Session" },
        header = {
            CompanionsHeader(
                title = chatTitle.ifEmpty { "Session" },
                subtitle = CompanionsCopy.subtitle(ui.running.size),
                onClose = { dismiss() },
            )
        },
    ) { bottomInset ->
        LaunchedEffect(controller.closeRequested) { if (controller.closeRequested) dismiss() }
        val pick: (() -> Unit) -> Unit = { action -> controller.closeThen(action) }
        CompanionsList(
            ui = ui,
            forking = forking,
            initialSection = controller.initialSection,
            onScrolledToInitial = { controller.initialSection = null },
            bottomInset = bottomInset.value,
            onOpenChat = { id -> pick { onOpenChat(id) } },
            onOpenAgent = { agent -> pick { onOpenAgent(agent) } },
            onAction = { action ->
                when (action) {
                    CompanionAction.NewSideChat -> pick(onNewSideChat)
                    CompanionAction.Fork -> onFork()
                    CompanionAction.NewTerminal -> pick(onNewTerminal)
                }
            },
            onArchive = onArchive,
        )
    }
}

/** The sheet's bar: the chat's title over the subtitle, centred; Close trailing (iOS `.close`). */
@Composable
private fun CompanionsHeader(title: String, subtitle: String, onClose: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(GlassSheetDefaults.HeaderHeight)) {
        SessionTitleView(
            title = title,
            subtitle = subtitle,
            maxWidth = 240.dp,
            modifier = Modifier.align(Alignment.Center).semantics { heading() },
        )
        GlassCircleButton(
            icon = ZeronIcons.Xmark,
            contentDescription = CompanionsCopy.Close,
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp)
                .testTag("companions-close"),
        )
    }
}

/**
 * The rows ([panelItems]): section headers with their + buttons, agent
 * rows (the running ones tick their elapsed time each second while the
 * panel is up), side chat rows (the Sessions list's row, archived with a
 * trailing swipe), empty lines and action rows. Scrolls to
 * [initialSection]'s header once it is laid out (iOS `initialSection`).
 */
@Composable
internal fun CompanionsList(
    ui: SideChatsUi,
    forking: Boolean,
    initialSection: CompanionSection?,
    onScrolledToInitial: () -> Unit,
    bottomInset: Float,
    onOpenChat: (String) -> Unit,
    onOpenAgent: (SideChatsAgent) -> Unit,
    onAction: (CompanionAction) -> Unit,
    onArchive: (chatId: String, onAccepted: (Boolean) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = remember(ui) { panelItems(ui) }
    val listState = rememberLazyListState()
    val feedback = LocalFeedback.current
    val openChat by rememberUpdatedState(onOpenChat)
    val openAgent by rememberUpdatedState(onOpenAgent)
    val act by rememberUpdatedState(onAction)
    val archive by rememberUpdatedState(onArchive)
    // Running agents' elapsed time ticks on screen only.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    if (initialSection != null) {
        LaunchedEffect(initialSection, rows) {
            val index = rows.indexOfFirst { it is PanelItem.Header && it.section == initialSection }
            if (index >= 0) {
                listState.animateScrollToItem(index)
                onScrolledToInitial()
            }
        }
    }
    val coordinator = rememberSwipeCoordinator()
    val palette = ZeronTheme.palette
    val select: (() -> Unit) -> Unit = { action ->
        feedback.haptic(Haptic.Select)
        action()
    }
    LazyColumn(
        modifier.fillMaxSize().testTag("companions-list"),
        state = listState,
        contentPadding = PaddingValues(bottom = (bottomInset + 16f).dp),
    ) {
        items(rows, key = { it.key }) { item ->
            when (item) {
                is PanelItem.Header -> CompanionHeaderRow(item.section, onAction = { act(it) })
                is PanelItem.Agent -> {
                    val agent = item.agent
                    CompanionRow(
                        art = agentArt(agent),
                        title = agent.title,
                        text = agentRowText(agent, now, ::modelLabel),
                        trailingColor = palette.status.working,
                        chevron = agent.docId != null,
                        testTag = "companion-agent",
                        onClick = if (agent.docId != null) ({ select { openAgent(agent) } }) else null,
                    )
                }
                is PanelItem.SideChat -> {
                    val vm = item.vm
                    val src = remember { MutableInteractionSource() }
                    val highlight = rememberRowHighlight(src)
                    SwipeRow(
                        id = vm.id,
                        // SwipeRow fills its parent's height: give it the
                        // session row's (62 pt × TypeScale), as the list does.
                        modifier = Modifier.height(LocalTypeScale.current.scaledDp(62f)),
                        leading = null,
                        trailing = listOf(
                            SwipeAction(
                                title = CompanionsCopy.Archive,
                                icon = ZeronIcons.Archivebox,
                                background = palette.secondary,
                                testTag = "companion-archive",
                                perform = { onAccepted -> archive(vm.id, onAccepted) },
                            ),
                        ),
                        coordinator = coordinator,
                    ) {
                        SessionRow(
                            vm,
                            modifier = Modifier
                                .testTag("companion-side-chat")
                                .clickable(src, indication = null, role = Role.Button) { select { openChat(vm.id) } },
                            highlight = { highlight.value },
                        )
                    }
                }
                is PanelItem.Action -> ActionRow(item.action, busy = item.action == CompanionAction.Fork && forking) {
                    select { act(item.action) }
                }
                is PanelItem.Empty -> EmptyRow(item.section)
            }
        }
    }
}

/** The agent row's tile: working (spinner on accentSoft), done (✓ on chip), failed (✗ on dangerWash). */
private sealed interface Art {
    data object Spinner : Art
    data class Glyph(val icon: ZeronIcon, val ink: Color, val tile: Color) : Art
}

@Composable
private fun agentArt(agent: SideChatsAgent): Art {
    val palette = ZeronTheme.palette
    return when (agent.state) {
        AgentState.Running -> Art.Spinner
        AgentState.Done -> Art.Glyph(ZeronIcons.Checkmark, palette.status.done, palette.chip)
        AgentState.Failed -> Art.Glyph(
            ZeronIcons.Xmark,
            palette.danger,
            Color(TranscriptPalette(ZeronTheme.isDark).color(ColorRole.DANGER_WASH)),
        )
    }
}

/**
 * A companion row (iOS `CompanionCell`): 62 pt × TypeScale, a 30 dp r8
 * status tile 20 dp in, the title (Medium 16) over one line of detail
 * (Sans 13.5 secondary), what it's doing on the right (Medium 13), and a
 * 12 dp tertiary chevron when it opens. Pressed: controlFill, 16 dp
 * corners, inset 8 × 1. [onClick] `null`: not selectable.
 */
@Composable
private fun CompanionRow(
    art: Art,
    title: String,
    text: AgentRowText,
    trailingColor: Color,
    chevron: Boolean,
    testTag: String,
    onClick: (() -> Unit)?,
) {
    val palette = ZeronTheme.palette
    val ts = LocalTypeScale.current
    val src = remember { MutableInteractionSource() }
    val highlight = rememberRowHighlight(src)
    val shape = rememberSmoothRoundedShape(16.dp)
    val tileShape = rememberSmoothRoundedShape(8.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .height(ts.scaledDp(62f))
            .pressedFill(shape, palette.controlFill) { highlight.value }
            .then(
                if (onClick != null) Modifier.clickable(src, indication = null, role = Role.Button, onClick = onClick)
                else Modifier,
            )
            .clearAndSetSemantics {
                this.testTag = testTag
                contentDescription = text.a11y
                val click = onClick
                if (click != null) {
                    role = Role.Button
                    this.onClick { click(); true }
                }
            }
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(30.dp)
                .background(
                    when (art) {
                        Art.Spinner -> palette.accentSoft
                        is Art.Glyph -> art.tile
                    },
                    tileShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            when (art) {
                Art.Spinner -> StatusGlyph(StatusGlyphKind.Spinner)
                is Art.Glyph -> Icon(art.icon, art.ink, sizePt = 12f, weight = SymbolWeight.Bold)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            FadingText(title, uiStyle(Face.SansMedium, ts.scaled(16f), palette.text))
            Spacer(Modifier.height(3.dp))
            FadingText(text.detail, uiStyle(Face.Sans, ts.scaled(13.5f), palette.secondary))
        }
        val trailing = text.trailing
        if (trailing != null) {
            Spacer(Modifier.width(10.dp))
            BasicText(
                trailing,
                style = uiStyle(Face.SansMedium, ts.scaled(13f), trailingColor).copy(textAlign = TextAlign.End),
                maxLines = 1,
                softWrap = false,
            )
        }
        if (chevron) {
            Spacer(Modifier.width(6.dp))
            Box(Modifier.width(12.dp), contentAlignment = Alignment.Center) {
                Icon(ZeronIcons.ChevronRight, palette.tertiary, sizePt = 12f, weight = SymbolWeight.Semibold)
            }
        }
    }
}

/** An action row (iOS `configure(action:busy:)`): 48 pt × TypeScale, a 26 dp accentSoft tile, the accent title. */
@Composable
private fun ActionRow(action: CompanionAction, busy: Boolean, onClick: () -> Unit) {
    val palette = ZeronTheme.palette
    val ts = LocalTypeScale.current
    val src = remember { MutableInteractionSource() }
    val highlight = rememberRowHighlight(src)
    val shape = rememberSmoothRoundedShape(16.dp)
    val tileShape = rememberSmoothRoundedShape(8.dp)
    val title = actionTitle(action, busy)
    val icon = if (action == CompanionAction.Fork) ZeronIcons.ArrowTriangleBranch else ZeronIcons.Plus
    Row(
        Modifier
            .fillMaxWidth()
            .height(ts.scaledDp(48f))
            .pressedFill(shape, palette.controlFill) { highlight.value }
            .clickable(src, indication = null, role = Role.Button, enabled = !busy, onClick = onClick)
            .clearAndSetSemantics {
                testTag = "companion-action-$action"
                contentDescription = title
                role = Role.Button
                if (!busy) this.onClick { onClick(); true }
            }
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(26.dp).background(palette.accentSoft, tileShape), contentAlignment = Alignment.Center) {
            if (busy) StatusGlyph(StatusGlyphKind.Spinner) else Icon(icon, palette.accent, sizePt = 13f, weight = SymbolWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        FadingText(title, uiStyle(Face.SansMedium, ts.scaled(16f), palette.accent), Modifier.weight(1f))
    }
}

/** A section's empty line (Sans 14 tertiary, 20 dp in). */
@Composable
private fun EmptyRow(section: CompanionSection) {
    val palette = ZeronTheme.palette
    val text = when (section) {
        CompanionSection.Agents -> CompanionsCopy.NoAgents
        CompanionSection.SideChats -> CompanionsCopy.SideChatsEmpty
        CompanionSection.Terminals -> CompanionsCopy.TerminalsEmpty
    }
    BasicText(
        text,
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 8.dp)
            .testTag("companion-empty-$section"),
        style = uiStyle(Face.Sans, LocalTypeScale.current.fixed(14f), palette.tertiary),
    )
}

/**
 * A section title with its add button (iOS `CompanionHeaderCell`): 46 dp,
 * Semibold 13 secondary 20 dp in, a 28 dp glass + 18 dp from the end —
 * Side chats: a menu (New Side Chat / Fork This Chat); Terminals: New
 * terminal; Agents: none.
 */
@Composable
private fun CompanionHeaderRow(section: CompanionSection, onAction: (CompanionAction) -> Unit) {
    val palette = ZeronTheme.palette
    val menuState = rememberGlassMenuState()
    var plusBounds by remember { mutableStateOf(Rect.Zero) }
    Box(Modifier.fillMaxWidth().height(46.dp)) {
        BasicText(
            CompanionsCopy.title(section),
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 20.dp, bottom = 6.dp)
                .semantics { heading() },
            style = uiStyle(Face.SansSemibold, LocalTypeScale.current.fixed(13f), palette.secondary),
            maxLines = 1,
        )
        if (section != CompanionSection.Agents) {
            GlassCircleButton(
                icon = ZeronIcons.Plus,
                contentDescription = if (section == CompanionSection.SideChats) CompanionsCopy.AddSideChat else CompanionsCopy.NewTerminal,
                onClick = {
                    if (section == CompanionSection.SideChats) {
                        menuState.open(
                            plusBounds,
                            sideChatsAddMenu(
                                onNew = { onAction(CompanionAction.NewSideChat) },
                                onFork = { onAction(CompanionAction.Fork) },
                            ),
                        )
                    } else {
                        onAction(CompanionAction.NewTerminal)
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 18.dp, bottom = 4.dp)
                    .onGloballyPositioned { plusBounds = it.boundsInWindow() }
                    .testTag("companions-add-$section"),
                size = 28.dp,
                iconPt = 12f,
                weight = SymbolWeight.Bold,
            )
        }
    }
}

/** The session row's pressed fill: controlFill, 16 dp continuous corners, inset 8 × 1; [level] read in draw only. */
private fun Modifier.pressedFill(
    shape: androidx.compose.ui.graphics.Shape,
    fill: Color,
    level: () -> Float,
): Modifier = drawWithCache {
    val inset = 8.dp.toPx()
    val vInset = 1.dp.toPx()
    val outline = shape.createOutline(Size(size.width - 2f * inset, size.height - 2f * vInset), layoutDirection, this)
    onDrawBehind {
        val h = level().coerceIn(0f, 1f)
        if (h > 0f) translate(inset, vInset) { drawOutline(outline, fill, alpha = h) }
    }
}
