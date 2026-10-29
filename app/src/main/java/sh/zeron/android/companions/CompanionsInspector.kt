package sh.zeron.android.companions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.zeron.android.ZeronApplication
import sh.zeron.android.design.BottomChromeInsets
import sh.zeron.android.design.scrollEndPadding
import sh.zeron.android.design.Haptic
import sh.zeron.android.design.LocalFeedback
import sh.zeron.android.design.LocalToaster
import sh.zeron.android.design.ZeronTheme

import sh.zeron.android.sessions.ActionOutcome
import sh.zeron.android.sessions.SessionRowVM
import sh.zeron.android.shell.CompanionsRootRoute
import sh.zeron.android.shell.LocalInspectorClose
import sh.zeron.android.shell.LocalNavEntry
import sh.zeron.android.shell.LocalNavStack
import sh.zeron.android.shell.LocalRouterHooks
import sh.zeron.android.shell.NavRoute
import sh.zeron.android.shell.OPEN_ERROR_TOAST
import sh.zeron.android.shell.openSessionRoute
import sh.zeron.android.sidechats.SideChatCopy
import sh.zeron.android.sidechats.SideChatFork
import sh.zeron.android.sidechats.SideChatsAgent
import sh.zeron.android.sidechats.SideChatsModel
import sh.zeron.android.sidechats.SideChatsSource
import sh.zeron.android.sidechats.SubagentOpenRequest
import sh.zeron.android.sidechats.rememberSubagentOpener
import sh.zeron.android.terminal.TerminalRoute
import sh.zeron.android.terminal.deviceName
import sh.zeron.android.terminal.panelRow
import sh.zeron.android.terminal.rememberChatTerminals
import uniffi.zeron_core.CoreClient

/**
 * A chat's side chats and subagents read through the client alone (#45):
 * the inspector has no session route of its own, so the spawn list comes
 * from the chat's warm handle (`client.session(chatId)`, iOS
 * `sessionSubagents`), each read closing its fresh wrapper. Blocking FFI.
 */
class ClientSideChatsSource(private val client: () -> CoreClient?, private val chatId: String) : SideChatsSource {
    override fun agents(): List<SideChatsAgent> {
        if (chatId.contains("--sub--")) return emptyList()
        val c = client() ?: return emptyList()
        val handle = c.session(chatId) ?: return emptyList()
        return try {
            handle.subagents().map { SideChatsAgent.from(it) }
        } finally {
            handle.close()
        }
    }

    override fun children(parentChatId: String): List<SessionRowVM> =
        client()?.childSessions(parentChatId).orEmpty()
            .filter { !it.archived }
            .map { SessionRowVM.from(it) }

    override fun isSideChat(chatId: String): Boolean = client()?.sessionRow(chatId)?.parentChatId != null
}

/**
 * The inspector column's root (#45, iOS `CompanionsViewController(inSheet:
 * false)`): [chatId]'s companions panel, flat on the page background,
 * headed by the chat's title. Picking a row pushes it on the inspector's
 * own stack (iOS `open` / `openSideChat`); while folded it shows inside
 * a sheet and carries a Close ([LocalInspectorClose]).
 *
 * Identity equality on purpose: the inspector keeps this entry while it
 * stays on the same chat.
 */
class CompanionsInspectorRoute(override val chatId: String) : CompanionsRootRoute {
    override var initialSection: CompanionSection? by mutableStateOf(null)

    override val paneTitle: String get() = CompanionsCopy.Subtitle

    @Composable
    override fun Content() {
        CompanionsInspectorScreen(this)
    }
}

private object ModelKey

private object ForkKey

@Composable
private fun CompanionsInspectorScreen(route: CompanionsInspectorRoute) {
    val app = LocalContext.current.applicationContext as? ZeronApplication ?: return
    val entry = LocalNavEntry.current
    val nav = LocalNavStack.current
    val hooks = LocalRouterHooks.current
    val toaster = LocalToaster.current
    val feedback = LocalFeedback.current
    val scope = rememberCoroutineScope()
    val close = LocalInspectorClose.current
    val chatId = route.chatId

    // The panel's data: one model per entry, stopped when the entry closes.
    val model = remember(entry) {
        entry.retain(ModelKey) {
            val m = SideChatsModel(
                parentChatId = chatId,
                source = ClientSideChatsSource({ app.clientOrNull() }, chatId),
                hub = app.sessionHolder.sessionEvents,
            )
            m.start()
            object : AutoCloseable {
                val model = m

                override fun close() = m.stop()
            }
        }.model
    }
    val ui by model.ui.collectAsState()
    var title by remember(chatId) { mutableStateOf("") }
    LaunchedEffect(chatId) {
        title = withContext(Dispatchers.IO) { runCatching { app.clientOrNull()?.sessionRow(chatId)?.title }.getOrNull() }.orEmpty()
        // iOS `viewDidAppear`: a terminal may be a tap away.
        withContext(Dispatchers.IO) { runCatching { app.clientOrNull()?.warmDirect(chatId) } }
    }
    val fork = remember(entry) {
        entry.retain(ForkKey) {
            SideChatFork(
                fork = { source ->
                    val client = app.clientOrNull() ?: error("signed out")
                    withContext(Dispatchers.IO) { client.forkSideChat(source) }
                },
                scope = app.appScope,
                toaster = toaster,
            )
        }
    }
    val opener = rememberSubagentOpener()
    val isTop: () -> Boolean = { nav.top === entry && nav.transition == null }
    // iOS `openSideChat`: the side chat as a companion over the panel.
    val openSideChat: (String) -> Unit = { id ->
        scope.launch {
            val session = hooks.openSessionRoute(id)
            when {
                session == null -> toaster.show(OPEN_ERROR_TOAST)
                !isTop() -> session.discard()
                else -> nav.push(session as NavRoute, animated = true)
            }
        }
    }
    // #44: the chat's terminals, each opening over the panel (iOS
    // `TerminalViewController(tab:)` through `presentCompanion`).
    val terminals = rememberChatTerminals(chatId)
    val openTerminal: (String?) -> Unit = { id ->
        if (isTop()) nav.push(TerminalRoute(chatId, id), animated = true)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(ZeronTheme.palette.background)
            .then(if (close == null) Modifier.statusBarsPadding() else Modifier)
            .testTag("companions-inspector"),
    ) {
        CompanionsHeader(
            title = title.ifEmpty { "Session" },
            subtitle = CompanionsCopy.subtitle(ui.running.size),
            onClose = close,
        )
        Box(Modifier.weight(1f)) {
            CompanionsList(
                ui = ui,
                forking = fork.forking,
                initialSection = route.initialSection,
                onScrolledToInitial = { route.initialSection = null },
                bottomInset = scrollEndPadding(BottomChromeInsets.None).value,
                onOpenChat = openSideChat,
                onOpenAgent = { agent ->
                    agent.docId?.let { doc ->
                        opener?.open(
                            SubagentOpenRequest(
                                docId = doc,
                                title = agent.title,
                                frozen = !agent.running,
                                spawnerId = chatId,
                                parentTitle = title,
                            ),
                        )
                    }
                },
                onAction = { action ->
                    when (action) {
                        CompanionAction.NewSideChat -> scope.launch {
                            val id = app.freshSideChats.create(chatId)
                            if (id == null) toaster.show(SideChatCopy.StartFailed) else {
                                model.invalidate()
                                openSideChat(id)
                            }
                        }
                        CompanionAction.Fork -> fork.start(chatId) { id ->
                            // iOS `perform(.fork)`: the `.success` notification.
                            feedback.haptic(Haptic.Success)
                            model.invalidate()
                            openSideChat(id)
                        }
                        CompanionAction.NewTerminal -> openTerminal(null)
                    }
                },
                onArchive = { id, onAccepted ->
                    app.rowActions.archive(id) { outcome ->
                        onAccepted(outcome == ActionOutcome.Ok)
                        model.invalidate()
                    }
                },
                terminals = terminals.map { it.panelRow(app.deviceName(it.ref.deviceId)) },
                onOpenTerminal = { id -> openTerminal(id) },
                onCloseTerminal = { id -> terminals.firstOrNull { it.ref.id == id }?.let(app.terminals::close) },
            )
        }
    }
}
