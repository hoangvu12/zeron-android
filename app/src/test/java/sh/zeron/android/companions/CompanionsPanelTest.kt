package sh.zeron.android.companions

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.zeron.android.design.Appearance
import sh.zeron.android.design.ZeronIcons
import sh.zeron.android.design.ZeronTheme
import sh.zeron.android.design.glass.Glass
import sh.zeron.android.design.glass.GlassMenuHost
import sh.zeron.android.design.glass.MenuIcon
import sh.zeron.android.design.glass.MenuItem
import sh.zeron.android.sessions.SessionRowVM
import sh.zeron.android.sidechats.AgentState
import sh.zeron.android.sidechats.SideChatsAgent
import sh.zeron.android.sidechats.SideChatsUi

/**
 * The companions panel (#41, iOS `CompanionsViewController`): the rows
 * per section ([panelItems] — Agents only when any, live first; Side
 * chats and Terminals always, with their empty lines and action rows),
 * the agent row texts per state, the action titles ("Forking…" while
 * busy), the subtitle, the + menu, the controller's dismiss-then-open
 * handshake, and the list itself (row taps, an unbound agent not
 * selectable, the action rows).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h900dp-xxhdpi")
class CompanionsPanelTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUp() {
        Glass.forceFallback = true
    }

    @After
    fun tearDown() {
        Glass.forceFallback = false
    }

    private val running = SideChatsAgent(
        id = "run",
        docId = "engine:v1:k:chat--sub--run",
        title = "Audit the reduced-motion fallback",
        state = AgentState.Running,
        model = "haiku",
        kind = "Explore",
        spawnedAtMs = 0L,
    )
    private val unbound = SideChatsAgent(id = "new", docId = null, title = "Just spawned", state = AgentState.Running)
    private val done = SideChatsAgent(id = "done", docId = "engine:v1:k:chat--sub--done", title = "Check CJK line breaking", state = AgentState.Done)
    private val failed = SideChatsAgent(id = "fail", docId = "engine:v1:k:chat--sub--fail", title = "Run the veil matrix", state = AgentState.Failed)

    private fun chat(id: String, title: String) = SessionRowVM(
        id = id,
        title = title,
        projectName = "zeron",
        hasProject = true,
        colorIndex = 6,
        harness = "claude-code",
        branch = "main",
        pr = null,
        prNumber = null,
        status = SessionRowVM.Status.Idle,
        timeLabel = "5m",
        unseen = false,
        pinned = false,
        sendFailed = false,
    )

    // ------------------------------------------------------------ the rows

    @Test
    fun anEmptyChatShowsSideChatsAndTerminalsWithTheirActions() {
        assertEquals(
            listOf(
                "header:SideChats", "empty:SideChats", "action:NewSideChat", "action:Fork",
                "header:Terminals", "empty:Terminals", "action:NewTerminal",
            ),
            panelItems(SideChatsUi()).map { it.key },
        )
    }

    @Test
    fun agentsComeFirstLiveThenNewestAndSideChatsReplaceTheirEmptyLine() {
        val ui = SideChatsUi(
            running = listOf(running),
            chats = listOf(chat("side", "Veil under Reduce Motion")),
            finished = listOf(failed, done),
        )
        assertEquals(
            listOf(
                "header:Agents", "agent:run", "agent:fail", "agent:done",
                "header:SideChats", "side:side",
                "header:Terminals", "empty:Terminals", "action:NewTerminal",
            ),
            panelItems(ui).map { it.key },
        )
    }

    @Test
    fun agentRowsReadLikeIos() {
        val r = agentRowText(running, nowMs = 65_000L)
        assertEquals("Haiku · Explore", r.detail)
        assertEquals("1m 5s", r.trailing)
        assertEquals("Audit the reduced-motion fallback, working", r.a11y)
        // A live tail wins over the model.
        assertEquals("Reading RowView.swift…", agentRowText(running.copy(tail = "Reading RowView.swift…"), 0L).detail)
        assertEquals("Working", agentRowText(running.copy(model = null, kind = null), 0L).detail)

        assertEquals(AgentRowText("Finished", null, "Check CJK line breaking, done"), agentRowText(done, 0L))
        assertEquals("Didn't finish", agentRowText(failed, 0L).detail)
        assertEquals("Failed · Haiku", agentRowText(failed.copy(model = "haiku"), 0L).detail)
        assertEquals("Run the veil matrix, failed", agentRowText(failed, 0L).a11y)
    }

    @Test
    fun theActionsAndSubtitleCopy() {
        assertEquals("New side chat", actionTitle(CompanionAction.NewSideChat, busy = false))
        assertEquals("Fork this chat", actionTitle(CompanionAction.Fork, busy = false))
        assertEquals("Forking…", actionTitle(CompanionAction.Fork, busy = true))
        assertEquals("New terminal", actionTitle(CompanionAction.NewTerminal, busy = false))
        assertEquals("Agents, side chats & terminals", CompanionsCopy.subtitle(0))
        assertEquals("1 agent working", CompanionsCopy.subtitle(1))
        assertEquals("3 agents working", CompanionsCopy.subtitle(3))
    }

    @Test
    fun thePlusMenuOffersNewAndForkInOrder() {
        var news = 0
        var forks = 0
        val rows = sideChatsAddMenu(onNew = { news++ }, onFork = { forks++ }).children.map { it as MenuItem }
        assertEquals(listOf("New Side Chat", "Fork This Chat"), rows.map { it.title })
        assertEquals(ZeronIcons.ArrowTriangleBranch, (rows[1].icon as MenuIcon.Symbol).icon)
        rows[0].action()
        rows[1].action()
        assertEquals(1, news)
        assertEquals(1, forks)
    }

    // ------------------------------------------------------------ the controller

    @Test
    fun theControllerOpensAtASectionAndRunsTheWaitingActionOnceDismissed() {
        val c = CompanionsPanelController()
        c.open(CompanionSection.Terminals)
        assertTrue(c.showing)
        assertEquals(CompanionSection.Terminals, c.initialSection)
        var ran = 0
        c.closeThen { ran++ }
        assertTrue("asks the sheet to go", c.closeRequested)
        assertEquals("not before the sheet is gone", 0, ran)
        c.onDismissed()
        assertFalse(c.showing)
        assertFalse(c.closeRequested)
        assertEquals(1, ran)
        // Closed already: runs at once.
        c.closeThen { ran++ }
        assertEquals(2, ran)
        c.open()
        assertNull(c.initialSection)
    }

    // ------------------------------------------------------------ the list

    private val openedChats = mutableListOf<String>()
    private val openedAgents = mutableListOf<SideChatsAgent>()
    private val actions = mutableListOf<CompanionAction>()

    private fun mount(ui: SideChatsUi, forking: Boolean = false) {
        compose.setContent {
            ZeronTheme(Appearance.Light) {
                // The session screen's menu host (the header + opens a GlassMenu).
                GlassMenuHost {
                Box(Modifier.fillMaxSize()) {
                    CompanionsList(
                        ui = ui,
                        forking = forking,
                        initialSection = null,
                        onScrolledToInitial = {},
                        bottomInset = 0f,
                        onOpenChat = { openedChats += it },
                        onOpenAgent = { openedAgents += it },
                        onAction = { actions += it },
                        onArchive = { _, done -> done(false) },
                    )
                }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun rowTapsOpenTheAgentOrTheSideChatAndAnUnboundAgentStaysPut() {
        mount(SideChatsUi(running = listOf(unbound, running), chats = listOf(chat("side", "Veil under Reduce Motion"))))
        compose.onAllNodesWithTag("companion-agent", useUnmergedTree = true).assertCountEquals(2)
        val agents = compose.onAllNodesWithTag("companion-agent", useUnmergedTree = true)
        // The unbound one (first) has no click action.
        assertFalse(SemanticsProperties.Role in agents[0].fetchSemanticsNode().config)
        agents[1].performClick()
        compose.onNodeWithTag("companion-side-chat", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertEquals(listOf(running), openedAgents)
        assertEquals(listOf("side"), openedChats)
    }

    @Test
    fun theActionRowsAndTheForkingRow() {
        mount(SideChatsUi(), forking = true)
        val fork = compose.onNodeWithTag("companion-action-Fork", useUnmergedTree = true)
        assertEquals(listOf("Forking…"), fork.fetchSemanticsNode().config[SemanticsProperties.ContentDescription])
        compose.onNodeWithTag("companion-action-NewSideChat", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("companion-action-NewTerminal", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertEquals(listOf(CompanionAction.NewSideChat, CompanionAction.NewTerminal), actions)
        compose.onNodeWithTag("companion-empty-SideChats").assertExists()
        compose.onNodeWithTag("companions-add-Terminals").assertExists()
    }
}
