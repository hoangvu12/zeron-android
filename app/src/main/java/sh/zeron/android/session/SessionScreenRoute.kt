package sh.zeron.android.session

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.zeron.android.ZeronApplication
import sh.zeron.android.composer.CoreQueueEditPort
import sh.zeron.android.composer.CoreQuestionPort
import sh.zeron.android.composer.DraftKey
import sh.zeron.android.composer.LocalDrafts
import sh.zeron.android.composer.QuestionPort
import sh.zeron.android.composer.QueueActions
import sh.zeron.android.composer.QueueEditController
import sh.zeron.android.composer.QueueEditPort
import sh.zeron.android.composer.SessionChipMenus
import sh.zeron.android.composer.sessionDraftSaver
import sh.zeron.android.design.Haptic
import sh.zeron.android.design.LocalFeedback
import sh.zeron.android.design.LocalToaster
import sh.zeron.android.sessions.rememberSessionListOpener
import sh.zeron.android.sidechats.FreshSideChats
import sh.zeron.android.sidechats.SideChatCopy
import sh.zeron.android.sidechats.SideChatFork
import sh.zeron.android.companions.CompanionsButton
import sh.zeron.android.companions.CompanionSection
import sh.zeron.android.shell.LocalCompanionsPresenter
import sh.zeron.android.shell.LocalRouterHooks
import sh.zeron.android.shell.OPEN_ERROR_TOAST
import sh.zeron.android.shell.openSessionRoute
import sh.zeron.android.sidechats.SubagentCopy
import sh.zeron.android.sidechats.buildSubagentRoute
import sh.zeron.android.companions.CompanionsOverlay
import sh.zeron.android.companions.CompanionsPanelController
import sh.zeron.android.sessions.ActionOutcome
import sh.zeron.android.sidechats.SideChatsModel
import sh.zeron.android.sidechats.SideChatsUi
import sh.zeron.android.sidechats.SubagentOpenRequest
import sh.zeron.android.sidechats.rememberSubagentOpener
import sh.zeron.android.shell.LaunchOptions
import sh.zeron.android.shell.LocalNavEntry
import sh.zeron.android.shell.LocalNavStack
import sh.zeron.android.shell.NavRoute
import sh.zeron.android.shell.SessionRoute
import sh.zeron.android.terminal.LiveComposers
import sh.zeron.android.terminal.TerminalDemoScript
import sh.zeron.android.terminal.TerminalRoute
import sh.zeron.android.terminal.appendBlock
import sh.zeron.android.terminal.deviceName
import sh.zeron.android.terminal.liveTerminalId
import sh.zeron.android.terminal.panelRow
import sh.zeron.android.terminal.rememberChatTerminals
import sh.zeron.android.transcript.AttachmentReader
import sh.zeron.android.transcript.TranscriptState
import uniffi.zeron_core.TextSystem

/**
 * One opened session (android-ui-parity #76) — the port of
 * `SessionViewController` after a successful `init?`
 * (`ios:Session/SessionViewController.swift:23-30`): the controller, the
 * core seam and the per-screen state a push needs, all built by
 * [CoreSessionRouteFactory] off main and pushed by tickets 32/41/106.
 *
 * **Identity equality on purpose** (not a data class): iOS allows "a
 * second copy of this chat" (`SessionViewController.swift:176-178`) and
 * ticket 24 requires identity equality — two opens of one chat are two
 * routes with two controllers.
 */
class SessionScreenRoute internal constructor(
    /** 51's controller: chrome, attach, markSeen, this route's filler instances. */
    val controller: SessionController,
    /** For sibling adapters (78, 82, 83, 66, 86, 89): cast to [CoreSessionCore] for the client and the handle. */
    val core: SessionCore,
    /** Ticket 75's per-screen state; `openedAtNanos` was stamped at the start of the factory's open. */
    val state: SessionScreenState,
    /**
     * Ticket 60's `TextEngine.shared`, read off main by the factory and
     * captured by the state's transcript factory. Never closed here —
     * the shared engine lives for the process.
     */
    private val text: TextSystem,
    /**
     * Ticket 82's per-route queue actions (send-now / move / remove)
     * over [core]'s handle, built by [CoreSessionRouteFactory] on an
     * application-lifetime scope — fire-and-forget like iOS's `Task {}`,
     * so a Remove tapped just before a committed Back still reaches
     * the engine (never cancelled on pop; the row's fate follows the
     * engine's echo). Null only when this route's [core] is a test fake
     * without a real handle. 78/83 wire the panel: its `onAction`
     * routes Edit to 83's QueueEditController and everything else here.
     */
    val queueActions: QueueActions?,
    /**
     * android-next #06: this chat's side chats and subagents (the header
     * button, the sheet, the ⋯ rows), built by [CoreSessionRouteFactory]
     * over [core]'s handle; started on first composition, stopped on
     * release or discard. Null for a test fake core.
     */
    val sideChats: SideChatsModel? = null,
) : SessionRoute, NavRoute {

    /**
     * Ticket 66's attachment reader over the live client (the ticket's
     * "once 66 lands" line): one stable instance per route, so
     * SessionScreen's `remember(controller, attachments)` never re-keys.
     * The host reads the attachment by path (`readAttachment`). Null for a
     * test fake core without a client.
     */
    private val attachments: AttachmentReader? = (core as? CoreSessionCore)?.let { live ->
        object : AttachmentReader {
            override suspend fun read(engineDeviceId: String, reference: String): ByteArray =
                live.client.readAttachment(engineDeviceId, reference)
        }
    }

    /** The session's scoped chat id (`SessionHandle.chatId()`, through the controller). */
    override val chatId: String
        get() = controller.chatId

    /**
     * iOS `hidesBottomBarWhenPushed = true` (`SessionViewController.swift:29`)
     * — the tab pill and the accessory hide while a session is on top.
     * The only screen that sets it.
     */
    override val hidesBottomChrome: Boolean
        get() = true

    /**
     * TalkBack pane title (ticket 24): the chrome's title when the layer
     * composes, `null` when it is empty. The first refresh already ran
     * synchronously in `SessionController.create`, so the title is there
     * on the first frame of the push (no empty-title flash).
     */
    override val paneTitle: String?
        get() = controller.chrome.value.title.ifEmpty { null }

    /**
     * Ticket 61's transcript, held lazily by 75's [SessionScreenState]
     * (the factory stored `transcriptFactory = { TranscriptState(text) }`).
     * MAIN THREAD ONLY: created on the first access — [Content] here
     * (77's `prepareArrival()` deliberately does not touch it: the own
     * turn is deferred to the screen's `bind`) — never from the factory.
     */
    val transcript: TranscriptState
        get() = state.transcript

    /**
     * Ticket 77's arrival hooks (the new-session handoff): the screen
     * state's [SessionArrival], reachable **before** the push (the
     * routers, 32/106, call [prepareArrival] before `pushSession`) and
     * before the screen is composed. Ticket 95's `arrivalTargetOf`
     * reads exactly this.
     */
    val arrival: SessionArrival
        get() = state.arrival

    /**
     * Teardown of a screen that **was** on screen (a committed pop, a
     * `popToRoot`, a `setRoutes` removal — through the NavEntry close
     * safety net in [Content]): 89's chip-menu dispose, then ticket 85's
     * `lifecycle.release()` — `controller.close()` (`setViewAttached(false)`
     * + `markSeen`, on 51's IO lane) then the transcript — the iOS
     * `viewDidDisappear` sequence. Main thread; idempotent (51's, 61's
     * and 85's `close()` are). Ticket 85 owns the wiring; keep exactly
     * one retained closeable.
     */
    fun release() {
        // Ticket 89: the chip-menu loads resolve empty after this (the
        // in-flight writes still finish — never cancelled).
        controller.filler<SessionChipMenus>()?.dispose()
        sideChats?.stop()
        // #39 (iOS `teardown`): a side chat started here and left without
        // a word goes. The draft was saved by the leave before this.
        fresh?.let { f ->
            f.closed(controller.chatId)
            val live = core as? CoreSessionCore
            if (live != null) {
                f.closeUnused(controller.chatId, draftEmpty = draftEmpty()) {
                    val t = live.handle.transcriptStatus()
                    t.entryCount == 0u && t.pendingCount == 0u && live.handle.composer().queue.isEmpty()
                }
            }
        }
        fresh = null
        val lifecycle = navLifecycle
        if (lifecycle != null) {
            // Ticket 85: detach + mark seen, then the layout engine.
            lifecycle.release()
        } else {
            // Never composed (no lifecycle was retained): the pre-85
            // teardown still holds.
            controller.close()
            if (state.transcriptCreated) state.transcript.close()
        }
    }

    /**
     * Ticket 85's nav lifecycle for this route, retained on the Session
     * nav entry by [Content] (null until the screen first composed —
     * only then do the two adapters have something to reach).
     */
    private var navLifecycle: SessionNavLifecycle? = null

    /** #39: the app's fresh side chats, set once the screen composed (it counts as open until [release]). */
    private var fresh: FreshSideChats? = null

    /** #39: whether this chat's saved draft is empty, read at [release]. */
    private var draftEmpty: () -> Boolean = { true }

    @Composable
    override fun Content() {
        val entry = LocalNavEntry.current
        val stack = LocalNavStack.current

        // Ticket 85: the nav lifecycle — retained per entry (it survives a
        // tab host's disposal and a configuration change), created once.
        // The two adapters reach 83's queue-edit controller (through 78's
        // entry-retained binding — the gate is only set once the screen
        // composed, hence the call-time lookup) and 84's entry-retained
        // saver (the same retain key `SessionScreen` created it under),
        // both at leave() time, never at composition time.
        val drafts = LocalDrafts.current
        val freshSideChats = (LocalContext.current.applicationContext as? ZeronApplication)?.freshSideChats
        remember(entry) {
            if (freshSideChats != null && fresh == null) {
                fresh = freshSideChats
                freshSideChats.opened(controller.chatId)
            }
            draftEmpty = { drafts.peek(DraftKey.Chat(core.engineId, controller.chatId)).isBlank() }
            true
        }
        val lifecycle = remember(entry) {
            entry.retain(SessionNavLifecycleKey) {
                SessionNavLifecycle(
                    chatId = controller.chatId,
                    controller = controller,
                    transcript = state.transcript,
                    edits = QueuedEditCanceller {
                        (entry.sessionQueueEditGate(controller.chatId) as? QueueEditController)?.cancelEdit()
                    },
                    drafts = DraftSaver {
                        entry.sessionDraftSaver(
                            DraftKey.Chat(core.engineId, controller.chatId),
                            drafts,
                        ).saveNow()
                    },
                ).also { navLifecycle = it }
            }
        }

        // The NavEntry close safety net: any removal path (a committed
        // pop, popToRoot, setRoutes — see NavEntry.close) releases the
        // session exactly once, through the lifecycle above.
        remember(entry) {
            entry.retain(CloseKey) {
                object : AutoCloseable {
                    override fun close() {
                        release()
                    }
                }
            }
        }
        // Ticket 75's screen (the scaffold, the first-composition wiring,
        // the layer stack). 66 the attachment reader.
        // 78: the composer's command set, once per route — over this
        // route's core (a CoreSessionCore holds the client and the
        // handle), reading the controller's host device at call time. A
        // test core that is not a CoreSessionCore yields null and the
        // composer slot stays empty.
        val commands: SessionCommands? = remember(entry) {
            (core as? CoreSessionCore)?.let { c ->
                entry.retain(CommandsKey) {
                    CoreSessionCommands(c.client, c.handle, c.chatId) { controller.hostDeviceId }
                }
            }
        }
        // Ticket 83: the queue-edit port over the same handle, retained
        // once per route like the commands (stateless — only the handle
        // matters). A test fake core yields null and the edit lease is
        // never created; the controller itself is entry-retained next to
        // 78's binding in SessionScreen.
        val queueEditPort: QueueEditPort? = remember(entry) {
            (core as? CoreSessionCore)?.let { c ->
                entry.retain(QueueEditPortKey) { CoreQueueEditPort(c.handle) }
            }
        }
        // Ticket 91: the question panel's FFI seam over this route's
        // handle, one per route (a sibling of `commands` above; the route
        // carries no `questionActions` field — 75's screen wraps the port
        // in `QuestionActions` on the app's lifetime scope itself). A
        // test core that is not a CoreSessionCore yields null and the
        // questions slot stays empty.
        val questionPort: QuestionPort? = remember(entry) {
            (core as? CoreSessionCore)?.let { live ->
                entry.retain(QuestionsKey) { CoreQuestionPort(live.handle) }
            }
        }
        // android-next #06: side chats and subagents — the header button
        // and its sheet, the ⋯ rows, the spawn-card opener. The sheet state
        // and the fork in flight live on the entry (a tab switch keeps them).
        val sideChatsModel = sideChats
        remember(entry) {
            sideChatsModel?.start()
            true
        }
        val sideUi = sideChatsModel?.ui?.collectAsState()?.value ?: SideChatsUi()
        val sheet = remember(entry) { entry.retain(SideChatsSheetKey) { CompanionsPanelController() } }
        val subagentOpener = rememberSubagentOpener()
        val openChat = rememberSessionListOpener()
        val app = LocalContext.current.applicationContext as? ZeronApplication
        val toaster = LocalToaster.current
        val feedback = LocalFeedback.current
        val fork: SideChatFork? = remember(entry) {
            val live = core as? CoreSessionCore
            if (live == null || app == null) {
                null
            } else {
                entry.retain(SideChatForkKey) {
                    SideChatFork(
                        fork = { source -> withContext(Dispatchers.IO) { live.client.forkSideChat(source) } },
                        scope = app.appScope,
                        toaster = toaster,
                    )
                }
            }
        }
        val isTopAndIdle: () -> Boolean = { stack.top === entry && stack.transition == null }
        // #45 (iOS `presentCompanion` / `openSideChat`): beside the sidebar,
        // companions open in the inspector column over this session's
        // panel; anywhere else a side chat is pushed and the panel is a sheet.
        val presenter = LocalCompanionsPresenter.current
        val hooks = LocalRouterHooks.current
        val openSideChat: (String) -> Unit = { id ->
            val companions = presenter
            if (companions == null) {
                openChat(id)
            } else {
                app?.appScope?.launch {
                    val route = hooks.openSessionRoute(id)
                    if (route == null) toaster.show(OPEN_ERROR_TOAST) else companions.show(route as NavRoute, controller.chatId)
                }
            }
        }
        val toggleCompanions: (CompanionSection?) -> Unit = { section ->
            val companions = presenter
            if (companions != null) companions.toggle(controller.chatId, section) else sheet.open(section)
        }
        // #39 (iOS `createSideChat`): the side chat is made at once under
        // the root (from a side chat, a sibling), recorded as fresh, and
        // opened while this screen is still shown. The panel says when it
        // couldn't be made; the menu stays silent, as on iOS. On the app
        // scope (main), so a Back right after still records it as fresh.
        val newSideChat: (Boolean) -> Unit = { toastOnFailure ->
            val root = controller.sessionRow?.parentChatId ?: controller.chatId
            val live = app
            if (live != null) {
                live.appScope.launch {
                    val id = live.freshSideChats.create(root)
                    when {
                        id == null -> if (toastOnFailure) toaster.show(SideChatCopy.StartFailed)
                        else -> {
                            sideChatsModel?.invalidate()
                            if (isTopAndIdle()) openSideChat(id)
                        }
                    }
                }
            }
        }
        val forkSideChat: () -> Unit = {
            // iOS `fork()`: "Forking…" first, then the fork, then the
            // `.success` notification.
            if (fork != null && !fork.forking) toaster.show("Forking…")
            fork?.start(controller.chatId) { forkId ->
                feedback.haptic(Haptic.Success)
                sideChatsModel?.invalidate()
                // iOS opens the fork only while this screen is still shown.
                if (isTopAndIdle()) openSideChat(forkId)
            }
        }
        val openSubagent: (String, String, Boolean) -> Unit = { docId, title, frozen ->
            val request = SubagentOpenRequest(
                docId = docId,
                title = title,
                frozen = frozen,
                spawnerId = controller.chatId,
                parentTitle = controller.chrome.value.title,
            )
            val companions = presenter
            if (companions == null || app == null) {
                subagentOpener?.open(request)
            } else {
                app.appScope.launch {
                    val route = buildSubagentRoute(app, request)
                    if (route == null) toaster.show(SubagentCopy.OpenFailed) else companions.show(route, controller.chatId)
                }
            }
        }
        // #44: the chat's terminals (the panel rows and the strip count),
        // and the terminal page over this session (iOS `openTerminal` /
        // `TerminalViewController(tab:)`); null opens a new shell. Beside
        // the sidebar it opens in the inspector (#45, `presentCompanion`).
        val chatTerminals = rememberChatTerminals(controller.chatId)
        // iOS `SessionViewController.live` / `appendToComposer`: while this
        // screen is up, a terminal's Add Output to Message lands in its
        // composer (else in the saved draft).
        DisposableEffect(entry) {
            val registration = LiveComposers.register(controller.chatId) { block ->
                val composer = entry.sessionComposerController(controller.chatId)
                if (composer != null) {
                    composer.text = appendBlock(composer.text, block)
                } else {
                    val key = DraftKey.Chat(core.engineId, controller.chatId)
                    drafts.save(key, appendBlock(drafts.peek(key), block))
                }
            }
            onDispose { registration.close() }
        }
        val openTerminal: (String?, List<String>) -> Unit = { terminalId, script ->
            val route = TerminalRoute(controller.chatId, terminalId, script)
            val companions = presenter
            when {
                companions != null -> companions.show(route, controller.chatId)
                isTopAndIdle() -> stack.push(route)
            }
        }
        val openLiveTerminal: () -> Unit = {
            openTerminal(chatTerminals.liveTerminalId(), emptyList())
        }
        // iOS `debugOpenCompanion` (`-terminal` / `-terminal-demo`): the
        // first session opened shows its terminal once the push landed.
        val launchOptions = LaunchOptions.current
        if (launchOptions.terminal) {
            LaunchedEffect(entry) {
                if (debugTerminalShown) return@LaunchedEffect
                snapshotFlow { isTopAndIdle() }.first { it }
                debugTerminalShown = true
                openTerminal(null, if (launchOptions.terminalDemo) TerminalDemoScript else emptyList())
            }
        }
        // #40: the companions item, always shown, badged while agents run;
        // it opens the panel, a strip chip opens it at its section (#41).
        val companions = sideUi.companions.copy(
            terminals = chatTerminals.size,
            liveTerminals = chatTerminals.count { it.isLive },
        )
        val companionsButton: @Composable () -> Unit = {
            CompanionsButton(companions.runningAgents, onClick = { toggleCompanions(null) })
        }
        // #41: the panel's fork keeps the sheet up ("Forking…" on its row),
        // then puts it away and opens the fork (iOS `perform(.fork)`).
        val panelFork: () -> Unit = {
            fork?.start(controller.chatId) { forkId ->
                // iOS `perform(.fork)`: the `.success` notification.
                feedback.haptic(Haptic.Success)
                sideChatsModel?.invalidate()
                sheet.closeThen { if (isTopAndIdle()) openChat(forkId) }
            }
        }
        val chatTitle = controller.chrome.collectAsState().value.title
        val sideChatsOverlay: @Composable () -> Unit = {
            CompanionsOverlay(
                controller = sheet,
                chatTitle = chatTitle,
                ui = sideUi,
                forking = fork?.forking == true,
                onOpenChat = openChat,
                onOpenAgent = { agent -> agent.docId?.let { openSubagent(it, agent.title, !agent.running) } },
                onNewSideChat = { newSideChat(true) },
                onFork = panelFork,
                // #44 (iOS `TerminalViewController(tab: nil)`): a new shell.
                onNewTerminal = { openTerminal(null, emptyList()) },
                terminals = chatTerminals.map { it.panelRow(app?.deviceName(it.ref.deviceId) ?: "Computer") },
                onOpenTerminal = { id -> openTerminal(id, emptyList()) },
                onCloseTerminal = { id ->
                    chatTerminals.firstOrNull { it.ref.id == id }?.let { app?.terminals?.close(it) }
                },
                onArchive = { id, onAccepted ->
                    val actions = app?.rowActions
                    if (actions == null) {
                        onAccepted(false)
                    } else {
                        actions.archive(id) { outcome ->
                            onAccepted(outcome == ActionOutcome.Ok)
                            sideChatsModel?.invalidate()
                        }
                    }
                },
                onAppear = {
                    // A terminal may be a tap away: pair with the session's computer now.
                    val live = core as? CoreSessionCore
                    if (live != null && app != null) {
                        app.appScope.launch(Dispatchers.IO) {
                            runCatching { live.client.warmDirect(controller.chatId) }
                        }
                    }
                },
            )
        }
        // #33: the ⋯ menu's companion group, always offered as on iOS.
        val onMore = rememberSessionMenu(controller, transcript) {
            CompanionMenuRows(
                onNewSideChat = { newSideChat(false) },
                onForkSideChat = forkSideChat,
                // #44 (iOS `openTerminal`): the chat's live tab, else a new shell.
                onTerminal = openLiveTerminal,
            )
        }
        // Ticket 85: the push/pop choreography (the stack and jump
        // multipliers) and the will/did lifecycle wiring around the
        // screen. The alpha travels through [LocalSessionNavAlpha], so
        // the screen — and 131's jump button when it lands — reads it
        // with no new parameter; the effects sit outside SessionScreen's
        // own effects, so 84's dispose-time save runs before 85's leave.
        val navAlpha = rememberSessionNavAlpha(stack, entry)
        CompositionLocalProvider(LocalSessionNavAlpha provides navAlpha) {
            SessionNavEffects(lifecycle, entry)
            SessionScreen(
                controller,
                state,
                commands = commands,
                attachments = attachments,
                queueEditPort = queueEditPort,
                queueActions = queueActions,
                questionPort = questionPort,
                // Ticket 80: the ⋯ menu — the header's onMore opens the session
                // menu built fresh on every tap (pin state read at open
                // time); android-next #06 adds the side-chat rows.
                onMore = onMore,
                // 84: the engine half of the draft key (the route's core has
                // it; SessionScreen pairs it with the controller's chatId).
                engineId = core.engineId,
                onOpenSubagent = openSubagent,
                headerAccessory = companionsButton,
                companions = companions,
                onCompanionTap = { section -> toggleCompanions(section) },
                overlay = sideChatsOverlay,
                // #41 (iOS ⌘⌥0, Ctrl on Android): the companions panel;
                // #44 (iOS ⌘J): Ctrl+J, the chat's terminal.
                modifier = Modifier.onPreviewKeyEvent { e ->
                    when {
                        e.type != KeyEventType.KeyDown || !e.isCtrlPressed -> false
                        e.isAltPressed && e.key == Key.Zero -> {
                            toggleCompanions(null)
                            true
                        }
                        !e.isAltPressed && e.key == Key.J -> {
                            openLiveTerminal()
                            true
                        }
                        else -> false
                    }
                },
            )
        }
    }

    /**
     * The handoff's hidden-arrival state (iOS `prepareArrival`,
     * `SessionViewController.swift:321-326`), forwarded to the state's
     * [SessionArrival] — valid before the push, before composition. 32's
     * default `HandoffAnimator.Instant` never calls it; 95's animator
     * does, through the router.
     */
    override fun prepareArrival() = arrival.prepareArrival()

    /** Show everything (iOS `finishArrival`, `:343-344`). */
    override fun finishArrival() = arrival.finishArrival()

    /**
     * A route that was **never** pushed (superseded, or the caller was
     * cancelled — ticket 32's `openSessionRoute` and 41's
     * `SessionListOpenGate`): `controller.dispose()`, which makes no
     * controller FFI call, then the transcript only if it was created.
     * Main thread; idempotent. Never `controller.close()` — no
     * `setViewAttached`/`markSeen` for a screen nobody saw.
     */
    override fun discard() {
        // Ticket 89: same rule as release — later loads resolve empty.
        controller.filler<SessionChipMenus>()?.dispose()
        sideChats?.stop()
        controller.dispose()
        if (state.transcriptCreated) state.transcript.close()
    }

    private object CloseKey

    /** Ticket 85's nav-lifecycle retention key. */
    private object SessionNavLifecycleKey

    /** 78's command-set retention key. */
    private object CommandsKey

    /** 83's queue-edit-port retention key. */
    private object QueueEditPortKey

    /** android-next #06's sheet-state retention key. */
    private object SideChatsSheetKey

    /** android-next #06's fork retention key. */
    private object SideChatForkKey
    /** 91's question-port retention key. */
    private object QuestionsKey
}

/** iOS `debugCompanionShown`: the `terminal` launch extra opens one terminal per process. */
private var debugTerminalShown = false
