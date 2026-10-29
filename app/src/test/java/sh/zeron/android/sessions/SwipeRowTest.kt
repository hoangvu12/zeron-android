package sh.zeron.android.sessions

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onParent
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.zeron.android.design.ActionSheetModel
import sh.zeron.android.design.ActionSheetPresenter
import sh.zeron.android.design.Alerts
import sh.zeron.android.design.Appearance
import sh.zeron.android.design.ContextMenuHost
import sh.zeron.android.design.Cue
import sh.zeron.android.design.Feedback
import sh.zeron.android.design.Haptic
import sh.zeron.android.design.LocalFeedback
import sh.zeron.android.design.ProvideReduceMotion
import sh.zeron.android.design.ReduceMotion
import sh.zeron.android.design.ZeronAlert
import sh.zeron.android.design.ZeronTheme
import uniffi.zeron_core.SessionRow
import uniffi.zeron_core.SectionView

/**
 * The swipe behaviour (android-ui-parity #44) on 41's real list: rows
 * hosted through [SessionList] with the [rememberSessionRowActions]
 * wrapper, `Modifier.sessionRowActions` on the host and
 * [sh.zeron.android.design.ContextMenuHost] around everything (16 embeds
 * 15's panel). 393 × 852 dp at xxhdpi (1 dp = 3 px, row width 1179 px),
 * the same geometry ticket 41's tests pin.
 *
 * The commands are a recording fake (a synchronous Archive refusal — the
 * gallery shows the 300 ms late one); the feedback records haptics; the
 * sections fake answers two engines so the "Move to Section" drill-in can
 * prove the engine filter end to end.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class SwipeRowTest {

    @get:Rule
    val compose = createComposeRule()

    @After
    fun resetReduceMotion() {
        ReduceMotion.debugOverride = null
    }

    // ------------------------------------------------------------ the fakes

    private class RecordingFeedback : Feedback {
        val haptics = CopyOnWriteArrayList<Haptic>()
        val thresholdCount: Int get() = haptics.count { it == Haptic.Threshold }
        override fun haptic(haptic: Haptic) {
            haptics += haptic
        }

        override fun cue(cue: Cue) {}
    }

    private class RecordingCommands : SessionRowCommands {
        val calls = CopyOnWriteArrayList<String>()
        var refuseArchive = false

        override fun setPinned(chatId: String, pinned: Boolean) {
            calls += "setPinned $chatId $pinned"
        }

        override fun archive(chatId: String, onAccepted: (Boolean) -> Unit) {
            calls += "archive $chatId"
            // Synchronous, like a refusal that already arrived: the
            // destructive slide must still spring back.
            onAccepted(!refuseArchive)
        }

        override fun unarchive(chatId: String) {
            calls += "unarchive $chatId"
        }

        override fun deleteSession(row: SessionRow) {
            calls += "delete ${row.id}"
        }

        override fun move(chatId: String, sectionId: String?) {
            calls += "move $chatId ${sectionId ?: "-"}"
        }

        override fun moveToSectionSheet(row: SessionRow) {
            calls += "sheet ${row.id}"
        }

        override fun renameSession(row: SessionRow) {
            calls += "rename ${row.id}"
        }

        override fun newSection() {
            calls += "newSection"
        }

        override fun sections(): List<SectionView> = listOf(
            SectionView(id = "sec-1", name = "Work", collapsed = false, sessions = emptyList()),
            SectionView(id = "sec-2", name = "Home", collapsed = false, sessions = emptyList()),
        )
    }

    private val feedback = RecordingFeedback()
    private val cmd = RecordingCommands()
    private val opened = CopyOnWriteArrayList<String>()

    // -------------------------------------------------------------- the host

    /**
     * 41's list with 44's wrapper: `SessionListFixtures.recentRows` by
     * default (i18n, downloads, deploy, launch-post — 62 dp rows under one
     * "Recent" header), engine "A"; tests copy rows to pin or re-engine
     * them.
     */
    private fun host(
        rows: List<SessionRow> = SessionListFixtures.recentRows,
        editing: () -> Boolean = { false },
        archivedRow: (String) -> Boolean = { false },
        rtl: Boolean = false,
        commands: SessionRowCommands = cmd,
    ) {
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(3f, 1f),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                ZeronTheme(Appearance.Light) {
                    ContextMenuHost {
                        ProvideReduceMotion {
                            CompositionLocalProvider(LocalFeedback provides feedback) {
                                val listState = rememberLazyListState()
                                val hookup = rememberSessionRowActions(listState, commands)
                                val model = rememberSessionListModel(
                                    sections = listOf(ListSection(id = "recent", header = "Recent", rows = rows)),
                                )
                                SessionList(
                                    model = model,
                                    state = listState,
                                    onOpenSession = { opened += it },
                                    archivedRow = archivedRow,
                                    editing = editing(),
                                    rowWrapper = hookup.rowWrapper,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .sessionRowActions(hookup),
                                )
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** Swipes [dpToTheRight] (negative = left) from the row centre. */
    private fun swipeRight(node: SemanticsNodeInteraction, dpToTheRight: Float) {
        node.performTouchInput {
            down(center)
            moveBy(Offset(dpToTheRight * 3f, 0f))
            up()
        }
        compose.waitForIdle()
    }

    // --------------------------------------------------- leading swipes

    @Test
    fun swipingRightPastHalfAButtonSettlesOpenAtExactly74dp() {
        host()
        swipeRight(compose.onNodeWithTag("session-chat-i18n"), dpToTheRight = 40f)
        // 40 dp > 37 dp (half of 74): open.
        compose.onNodeWithTag("swipe-action-pin").assertExists()
        compose.onNodeWithTag("swipe-action-pin").assertWidthIsEqualTo(74.dp)
        compose.onNodeWithTag("swipe-action-pin").assertLeftPositionInRootIsEqualTo(0.dp)
        // The row is still open: the catcher swallows content taps.
        compose.onNodeWithTag("swipe-close").assertExists()
    }

    @Test
    fun aShortRightSwipeClosesAndAPinnedRowSaysUnpin() {
        host(rows = SessionListFixtures.recentRows.map { it.copy(pinned = true) })
        swipeRight(compose.onNodeWithTag("session-chat-i18n"), dpToTheRight = 20f)
        compose.onNodeWithTag("swipe-action-unpin").assertDoesNotExist()
        // Open it for real: the pane is Unpin (PinSlash glyph, accent).
        swipeRight(compose.onNodeWithTag("session-chat-i18n"), dpToTheRight = 60f)
        compose.onNodeWithTag("swipe-action-unpin").assertExists()
        compose.onNodeWithTag("swipe-action-pin").assertDoesNotExist()
    }

    @Test
    fun releasingAnArmedLeadingSwipePinsOnceAndReturnsToZero() {
        host()
        // 70 % of 393 dp = 275 dp.
        swipeRight(compose.onNodeWithTag("session-chat-i18n"), dpToTheRight = 280f)
        assertEquals(listOf("setPinned chat-i18n true"), cmd.calls)
        assertEquals(1, feedback.thresholdCount)
        // The non-destructive commit came back: no panes, no catcher.
        compose.onNodeWithTag("swipe-action-pin").assertDoesNotExist()
        compose.onNodeWithTag("swipe-close").assertDoesNotExist()
    }

    // -------------------------------------------------- trailing swipes

    @Test
    fun swipingLeftRevealsArchiveThenMoveAndSettlesAt148dp() {
        host()
        swipeRight(compose.onNodeWithTag("session-chat-i18n"), dpToTheRight = -80f)
        // From the right edge inward: Archive (outermost, 393 − 74) then
        // Move (inner, 393 − 148).
        compose.onNodeWithTag("swipe-action-archive").assertExists()
        compose.onNodeWithTag("swipe-action-archive").assertWidthIsEqualTo(74.dp)
        compose.onNodeWithTag("swipe-action-archive").assertLeftPositionInRootIsEqualTo(319.dp)
        compose.onNodeWithTag("swipe-action-move").assertExists()
        compose.onNodeWithTag("swipe-action-move").assertWidthIsEqualTo(74.dp)
        compose.onNodeWithTag("swipe-action-move").assertLeftPositionInRootIsEqualTo(245.dp)
        assertTrue(cmd.calls.isEmpty())
    }

    @Test
    fun releasingAnArmedTrailingSwipeArchivesOnceAndStaysOut() {
        host()
        swipeRight(compose.onNodeWithTag("session-chat-i18n"), dpToTheRight = -280f)
        assertEquals(listOf("archive chat-i18n"), cmd.calls)
        // Slid fully out and armed: the Archive pane covers the whole row.
        compose.onNodeWithTag("swipe-action-archive").assertWidthIsEqualTo(393.dp)
    }

    @Test
    fun aRefusedArchiveSpringsTheRowBack() {
        cmd.refuseArchive = true
        host()
        swipeRight(compose.onNodeWithTag("session-chat-i18n"), dpToTheRight = -280f)
        assertEquals(listOf("archive chat-i18n"), cmd.calls)
        // Refused: back to 0, panes gone, catcher gone.
        compose.onNodeWithTag("swipe-action-archive").assertDoesNotExist()
        compose.onNodeWithTag("swipe-close").assertDoesNotExist()
    }

    @Test
    fun tappingTheArchivePaneBehavesLikeTheFullSwipe() {
        host()
        swipeRight(compose.onNodeWithTag("session-chat-i18n"), dpToTheRight = -80f)
        compose.onNodeWithTag("swipe-action-archive").performClick()
        compose.waitForIdle()
        assertEquals(listOf("archive chat-i18n"), cmd.calls)
        compose.onNodeWithTag("swipe-action-archive").assertWidthIsEqualTo(393.dp)
    }

    @Test
    fun tappingTheMovePaneCallsTheSheetAndCloses() {
        host()
        swipeRight(compose.onNodeWithTag("session-chat-i18n"), dpToTheRight = -80f)
        compose.onNodeWithTag("swipe-action-move").performClick()
        compose.waitForIdle()
        assertEquals(listOf("sheet chat-i18n"), cmd.calls)
        compose.onNodeWithTag("swipe-action-move").assertDoesNotExist()
    }

    @Test
    fun tappingThePinPanePinsAndCloses() {
        host()
        swipeRight(compose.onNodeWithTag("session-chat-i18n"), dpToTheRight = 40f)
        compose.onNodeWithTag("swipe-action-pin").performClick()
        compose.waitForIdle()
        assertEquals(listOf("setPinned chat-i18n true"), cmd.calls)
        compose.onNodeWithTag("swipe-action-pin").assertDoesNotExist()
    }

    // ------------------------------------------- one side at a time (#136)

    @Test
    fun anOpenLeadingRowNeverComposesTheTrailingPanes() {
        host()
        swipeRight(compose.onNodeWithTag("session-chat-i18n"), dpToTheRight = 40f)
        compose.onNodeWithTag("swipe-action-pin").assertExists()
        // The ghost Archive/Move panes that used to show through the row
        // (and swallowed the Move tap) are gone.
        compose.onNodeWithTag("swipe-action-archive").assertDoesNotExist()
        compose.onNodeWithTag("swipe-action-move").assertDoesNotExist()
    }

    @Test
    fun anOpenTrailingRowNeverComposesThePinPane() {
        host()
        swipeRight(compose.onNodeWithTag("session-chat-i18n"), dpToTheRight = -80f)
        compose.onNodeWithTag("swipe-action-move").assertExists()
        compose.onNodeWithTag("swipe-action-pin").assertDoesNotExist()
    }

    @Test
    fun anOppositeDragClosesTheOpenSideWithoutRevealingTheOther() {
        host()
        swipeRight(compose.onNodeWithTag("session-chat-i18n"), dpToTheRight = 40f)
        compose.onNodeWithTag("swipe-action-pin").assertExists()
        // 74 dp open, dragged 200 dp left: stops at 0 and closes.
        swipeRight(compose.onNodeWithTag("session-chat-i18n"), dpToTheRight = -200f)
        compose.onNodeWithTag("swipe-action-pin").assertDoesNotExist()
        compose.onNodeWithTag("swipe-action-archive").assertDoesNotExist()
        compose.onNodeWithTag("swipe-action-move").assertDoesNotExist()
        assertTrue(cmd.calls.isEmpty())
        // A fresh swipe left then opens the trailing side.
        swipeRight(compose.onNodeWithTag("session-chat-i18n"), dpToTheRight = -80f)
        compose.onNodeWithTag("swipe-action-move").assertExists()
    }

    @Test
    fun theMovePaneOpensTheMoveToSectionSheetThroughTheRealPrompts() {
        val sheets = CopyOnWriteArrayList<ActionSheetModel>()
        val presenter = object : ActionSheetPresenter {
            override fun show(model: ActionSheetModel) {
                sheets += model
            }
        }
        val alerts = object : Alerts {
            override fun show(alert: ZeronAlert) {}
        }
        val real = SessionRowCommands(RowActions.Noop, DefaultSessionPrompts(RowActions.Noop, alerts, presenter))
        host(commands = real)
        swipeRight(compose.onNodeWithTag("session-chat-i18n"), dpToTheRight = -80f)
        compose.onNodeWithTag("swipe-action-move").performClick()
        compose.waitForIdle()
        assertEquals(1, sheets.size)
        assertEquals("Move to Section", sheets.single().title)
        assertEquals(listOf("No Section"), sheets.single().actions.map { it.title })
    }

    // ------------------------------------------------------ archived rows

    @Test
    fun anArchivedRowCannotMoveRightAndArchivesByFullSwipe() {
        host(archivedRow = { it == "chat-deploy" })
        // Right: no leading action — the row never moves.
        swipeRight(compose.onNodeWithTag("session-chat-deploy"), dpToTheRight = 60f)
        compose.onNodeWithTag("swipe-action-unarchive").assertDoesNotExist()
        // Left: Unarchive only, one pane.
        swipeRight(compose.onNodeWithTag("session-chat-deploy"), dpToTheRight = -40f)
        compose.onNodeWithTag("swipe-action-unarchive").assertExists()
        compose.onNodeWithTag("swipe-action-unarchive").assertWidthIsEqualTo(74.dp)
        assertTrue(cmd.calls.isEmpty())
        // The armed full swipe commits the unarchive.
        swipeRight(compose.onNodeWithTag("session-chat-deploy"), dpToTheRight = -280f)
        assertEquals(listOf("unarchive chat-deploy"), cmd.calls)
    }

    // -------------------------------------------------------- the haptic

    @Test
    fun crossingTheFullSwipeZonePlaysExactlyOneThresholdPerArm() {
        host()
        compose.onNodeWithTag("session-chat-i18n").performTouchInput {
            down(center)
            moveTo(Offset(center.x - 850f, center.y)) // armed (≥ 707 px)
            moveTo(Offset(center.x - 300f, center.y)) // back out of the zone
            moveTo(Offset(center.x - 850f, center.y)) // armed again
            moveTo(Offset(center.x - 300f, center.y)) // out: no commit on release
            up()
        }
        compose.waitForIdle()
        assertEquals(2, feedback.thresholdCount)
        assertTrue(cmd.calls.isEmpty())
    }

    // -------------------------------------------------- one row at a time

    @Test
    fun aSwipeOnASecondRowOnlyClosesTheFirstThenTheNextOneOpensIt() {
        host()
        swipeRight(compose.onNodeWithTag("session-chat-i18n"), dpToTheRight = 40f)
        compose.onAllNodesWithTag("swipe-action-pin").assertCountEquals(1)
        val firstTop = compose.onNodeWithTag("swipe-action-pin").getBoundsInRoot().top
        val secondTop = compose.onNodeWithTag("session-chat-downloads").getBoundsInRoot().top
        // #136, as UITableView: the touch closes the open row, nothing opens.
        swipeRight(compose.onNodeWithTag("session-chat-downloads"), dpToTheRight = 40f)
        compose.onAllNodesWithTag("swipe-action-pin").assertCountEquals(0)
        swipeRight(compose.onNodeWithTag("session-chat-downloads"), dpToTheRight = 40f)
        compose.onAllNodesWithTag("swipe-action-pin").assertCountEquals(1)
        // The surviving pane belongs to the second row.
        compose.onNodeWithTag("swipe-action-pin").assertTopPositionInRootIsEqualTo(secondTop)
        assertTrue(compose.onNodeWithTag("swipe-action-pin").getBoundsInRoot().top > firstTop)
    }

    @Test
    fun tappingElsewhereInTheListClosesTheRowAndSwallowsTheTap() {
        host()
        swipeRight(compose.onNodeWithTag("session-chat-i18n"), dpToTheRight = 40f)
        compose.onNodeWithTag("swipe-action-pin").assertExists()
        // A real tap on another row: closes the open one, opens nothing.
        compose.onNodeWithTag("session-chat-downloads").performTouchInput { click() }
        compose.waitForIdle()
        compose.onNodeWithTag("swipe-action-pin").assertDoesNotExist()
        assertTrue("the tap was swallowed", opened.isEmpty())
    }

    @Test
    fun tappingAnOpenRowsContentClosesItWithoutOpeningTheSession() {
        host()
        swipeRight(compose.onNodeWithTag("session-chat-i18n"), dpToTheRight = 40f)
        // The catcher covers the slid content: a real tap closes, no open.
        compose.onNodeWithTag("session-chat-i18n").performTouchInput { click() }
        compose.waitForIdle()
        compose.onNodeWithTag("swipe-action-pin").assertDoesNotExist()
        assertTrue(opened.isEmpty())
    }

    // ------------------------------------------------------ the lock rule

    @Test
    fun aDiagonalDragNeverMovesTheRow() {
        host()
        compose.onNodeWithTag("session-chat-i18n").performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(10f, 10f)) } // 45°: |dx| ≤ 1.5·|dy| forever
            up()
        }
        compose.waitForIdle()
        compose.onAllNodesWithTag("swipe-action-pin").assertCountEquals(0)
        compose.onAllNodesWithTag("swipe-action-archive").assertCountEquals(0)
        assertTrue(opened.isEmpty())
    }

    // -------------------------------------------------------- edit mode

    @Test
    fun editModeDisablesTheSwipeAndClosesAnOpenRow() {
        var editing by mutableStateOf(false)
        host(editing = { editing })
        swipeRight(compose.onNodeWithTag("session-chat-i18n"), dpToTheRight = 40f)
        compose.onNodeWithTag("swipe-action-pin").assertExists()
        compose.runOnUiThread { editing = true }
        compose.waitForIdle()
        compose.onNodeWithTag("swipe-action-pin").assertDoesNotExist()
        // No swipe and no custom actions while editing.
        swipeRight(compose.onNodeWithTag("session-chat-downloads"), dpToTheRight = 40f)
        compose.onAllNodesWithTag("swipe-action-pin").assertCountEquals(0)
        val config = compose.onNodeWithTag("swipe-row-chat-downloads").fetchSemanticsNode().config
        assertTrue(config.getOrNull(SemanticsActions.CustomActions) == null)
    }

    // ---------------------------------------------------------- semantics

    @Test
    fun theRowExposesPinArchiveMoveAsCustomActions() {
        host()
        val node = compose.onNodeWithTag("swipe-row-chat-i18n").fetchSemanticsNode()
        val actions = node.config[SemanticsActions.CustomActions]
        assertEquals(listOf("Pin", "Archive", "Move"), actions.map { it.label })
        // Each runs the same path as a pane tap.
        actions.first { it.label == "Move" }.action()
        compose.waitForIdle()
        assertEquals(listOf("sheet chat-i18n"), cmd.calls)
    }

    @Test
    fun anArchivedRowExposesOnlyUnarchive() {
        host(archivedRow = { it == "chat-deploy" })
        val actions = compose.onNodeWithTag("swipe-row-chat-deploy").fetchSemanticsNode()
            .config[SemanticsActions.CustomActions]
        assertEquals(listOf("Unarchive"), actions.map { it.label })
        actions.single().action()
        compose.waitForIdle()
        assertEquals(listOf("unarchive chat-deploy"), cmd.calls)
    }

    // ----------------------------------------------------- the long press

    @Test
    fun theLongPressMenuDrillsIntoTheSections() {
        // Every section in order, then "No Section" and "New Section…".
        host()
        // The long-click action lives on the contextMenu wrapper, the row
        // node's semantics parent.
        compose.onNodeWithTag("session-chat-deploy").onParent()
            .performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1000) // past 16's lift and 15's open
        compose.waitForIdle()
        compose.onNodeWithTag("row-menu-pin").assertExists()
        compose.onNodeWithTag("row-menu-rename").assertExists()
        compose.onNodeWithTag("row-menu-archive").assertExists()
        // Drill in through the submenu row (no testTag on MenuSection).
        compose.onNodeWithText("Move to Section").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("row-menu-section-sec-1").assertExists()
        compose.onNodeWithTag("row-menu-section-sec-2").assertExists()
        compose.onNodeWithTag("row-menu-no-section").assertExists()
        compose.onNodeWithTag("row-menu-new-section").assertExists()
        // "New Section…" creates only and never moves.
        compose.onNodeWithTag("row-menu-new-section").performClick()
        compose.waitForIdle()
        assertEquals(listOf("newSection"), cmd.calls)
    }

    @Test
    fun theArchivedLongPressMenuIsUnarchiveAndRenameOnly() {
        host(archivedRow = { it == "chat-deploy" })
        compose.onNodeWithTag("session-chat-deploy").onParent()
            .performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        compose.onNodeWithTag("row-menu-unarchive").assertExists()
        compose.onNodeWithTag("row-menu-rename").assertExists()
        compose.onNodeWithTag("row-menu-pin").assertDoesNotExist()
        compose.onNodeWithTag("row-menu-archive").assertDoesNotExist()
    }

    // --------------------------------------------------------------- RTL

    @Test
    fun rtlMirrorsTheSwipeDirectionsAndEdges() {
        host(rtl = true)
        // Physically right = trailing in RTL: the Archive pane appears at
        // the physical LEFT edge (the end edge).
        swipeRight(compose.onNodeWithTag("session-chat-i18n"), dpToTheRight = 80f)
        compose.onNodeWithTag("swipe-action-archive").assertExists()
        compose.onNodeWithTag("swipe-action-archive").assertLeftPositionInRootIsEqualTo(0.dp)
        compose.onNodeWithTag("swipe-action-move").assertLeftPositionInRootIsEqualTo(74.dp)
        // Physically left = leading: the Pin pane appears at the physical
        // RIGHT edge (the start edge). The first touch only closes i18n.
        swipeRight(compose.onNodeWithTag("session-chat-downloads"), dpToTheRight = -40f)
        swipeRight(compose.onNodeWithTag("session-chat-downloads"), dpToTheRight = -40f)
        compose.onNodeWithTag("swipe-action-pin").assertExists()
        compose.onNodeWithTag("swipe-action-pin").assertLeftPositionInRootIsEqualTo(319.dp)
    }

    // ------------------------------------------------------ reduce motion

    @Test
    fun reduceMotionSnapsTheSettleWhileTheDragStillFollowsTheFinger() {
        host()
        ReduceMotion.debugOverride = true
        compose.waitForIdle() // the tree re-reads the reduce-motion state
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("session-chat-i18n").performTouchInput {
            down(center)
            moveBy(Offset(120f, 0f))
        }
        // The drag follows the finger: the panes are already composed.
        compose.mainClock.advanceTimeBy(48)
        compose.onAllNodesWithTag("swipe-action-pin").assertCountEquals(1)
        compose.onNodeWithTag("session-chat-i18n").performTouchInput { up() }
        // Three frames — far short of the 0.35 s spring: reduce motion
        // snapped straight to the open offset.
        compose.mainClock.advanceTimeBy(48)
        compose.onNodeWithTag("swipe-action-pin").assertWidthIsEqualTo(74.dp)
        compose.mainClock.autoAdvance = true
    }
}
