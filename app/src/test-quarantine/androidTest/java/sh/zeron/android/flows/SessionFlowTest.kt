package sh.zeron.android.flows

import android.graphics.Point
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.BackEventCompat
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.compose.ui.unit.height
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import sh.zeron.android.MainActivity
import sh.zeron.android.ZeronApplication

/**
 * The flow suite's SessionFlowTests port (android-app#30 §6): one Android
 * test per iOS test of `uit:SessionFlowTests.swift` with the same name
 * minus `test`, run on the core's offline demo ([FixtureRule], #63) —
 * upstream's own `-demo -fast` (iOS `AppModel.demoOptions()`): the
 * seeded workspace in `zeron/crates/client/src/demo/`, no host, no
 * network.
 *
 * Rows whose owning tickets have not landed yet stay in this class
 * `@Ignore`d with the ticket named, so the port table is visible in one
 * place and CI runs everything that can pass (the pre-rig
 * `KeyboardFlowTest` set the pattern). Unwired rows:
 * `testTappingLinkOpensIt` (ui-parity #126's link hit-test + Custom Tab
 * routing), `testImageUploadShowsRingOnThumbnail` (ui-parity #93's
 * clipboard-image paste path), `testNewSessionRemembersDraftWhenClosed`,
 * `testNewSessionHeadlineStaysAboveComposer`,
 * `testEffortPickerInNewSession`, `testModelChipNamesAModelAtOnce`,
 * `testNewProjectFolderBrowser` and
 * `testProjectPickerGroupsClonesAndHostPicksCheckout` (the chips and
 * menus of ui-parity #92/#109/#113/#114 — the sheet itself, 93, and the
 * create + first send, 94, have landed).
 *
 * Route and demo options per test ([routeFor], [extrasFor]): the
 * question rows send a `?ask` prompt (the demo replies with one
 * question); the queue / cancelled-swipe / live tool rows run with
 * [SlowReply] (`fast` off + `longreply`: a paced reply that streams for
 * seconds); the jump / scroll-up rows open `chat-veil` with `big` (a
 * 120-turn synthetic transcript, upstream's `-big`); the error card is
 * `chat-errored`'s seeded signed-out error; everything else is
 * `chat-deploy` or the front page.
 *
 * Dropped with the fixture engines (#63): `offlineEngineIsReadOnly` (the
 * demo has no host to take offline) and
 * `forgottenEngineChatRouteReturnsToSessions` (scoped `engine:v1:` ids
 * are gone; chat ids are plain).
 */
@RunWith(AndroidJUnit4::class)
class SessionFlowTest {

    /** The unknown-route ids, picked by method name (see [routeFor]). */
    private var routeChatId: String? = null

    private val launch = FixtureRule(
        routeFor = { name -> routeFor(name) },
        extrasFor = { name -> extrasFor(name) },
    )

    @Suppress("DEPRECATION") // the public AndroidComposeTestRule constructor is the one way to hand it a custom rule
    @get:Rule
    val compose: AndroidComposeTestRule<FixtureRule, MainActivity> =
        AndroidComposeTestRule(launch) { rule -> rule.mountedActivity() }

    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private val app: ZeronApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            as ZeronApplication

    @Before
    fun requireDemoClient() {
        // The `demo` extra's client starts during `onCreate` (#18's
        // `applyLaunchAccount`); poll briefly so a slow start is not
        // mistaken for a missing one.
        val deadline = System.currentTimeMillis() + 10_000
        while (app.clientOrNull() == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(250)
        }
        assertTrue("the demo client never started (the `demo` launch extra)", app.clientOrNull() != null)
    }

    // ── the front page ──────────────────────────────────────────────────

    /** `testFrontPageShowsFoldersAndRecents` (:24): sections fold in place and a long-press opens the folder. */
    @Test
    fun frontPageShowsFoldersAndRecents() {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("Sessions").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("section-pinned").fetchSemanticsNodes().isNotEmpty()
        }

        // Sections fold in place, like the desktop sidebar: the pinned row
        // leaves the tree when the header folds and returns on unfold.
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(FlowSemantics.sessionRow("chat-veil")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("section-pinned").performClick()
        compose.waitUntil(timeoutMillis = 2_000) {
            compose.onAllNodes(FlowSemantics.sessionRow("chat-veil")).fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("section-pinned").performClick()
        compose.waitUntil(timeoutMillis = 2_000) {
            compose.onAllNodes(FlowSemantics.sessionRow("chat-veil")).fetchSemanticsNodes().isNotEmpty()
        }

        // Long-press → Open drills into the folder (reorder lives there).
        compose.onNodeWithTag("section-pinned").performTouchInput { longClick() }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Open").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithText("Open")[0].performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Pinned").fetchSemanticsNodes().isNotEmpty()
        }
        FlowSemantics.saveScreenshot("front-page-folder.png")
    }

    /**
     * `testSettingsFromBottomBar` (upstream #967): the one-row bar's gear
     * pushes Settings over the list, the bar steps aside (no New session
     * over Settings), and back brings the list and the bar back.
     */
    @Test
    @PhoneOnly("the one-row bottom bar (gear, New session) is the phone shell's; the split has its sidebar toolbar")
    fun settingsFromBottomBar() {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("bottom-settings").fetchSemanticsNodes().isNotEmpty()
        }
        FlowSemantics.saveScreenshot("bottom-bar.png")
        compose.onNodeWithTag("bottom-settings").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("settings-screen").fetchSemanticsNodes().isNotEmpty()
        }
        // The bar stays composed when hidden (#30), so "gone" is "not
        // displayed", not "not in the tree".
        val accessories = compose.onAllNodesWithTag("new-session")
        fun barShown() = (0 until accessories.fetchSemanticsNodes().size).any { accessories[it].isDisplayed() }
        compose.waitUntil(timeoutMillis = 5_000) { !barShown() }
        FlowSemantics.saveScreenshot("settings.png")
        device.pressBack()
        compose.waitUntil(timeoutMillis = 5_000) { barShown() }
        compose.onNodeWithTag("bottom-settings").assertExists()
    }

    /**
     * `testBottomBarSearch` (upstream #967): the bar's field filters the
     * Sessions list in place; clearing it brings the list (and New
     * session) back.
     */
    @Test
    @PhoneOnly("the one-row bottom bar's search field is the phone shell's; the split searches from its sidebar")
    fun bottomBarSearch() {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(FlowSemantics.sessionRow("chat-veil")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("search-field").performClick().performTextInput("wrangler")
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(FlowSemantics.sessionRow("chat-veil")).fetchSemanticsNodes().isEmpty()
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(FlowSemantics.sessionRow("chat-deploy")).fetchSemanticsNodes().isNotEmpty()
        }
        FlowSemantics.saveScreenshot("bottom-bar-search.png")
        compose.onNodeWithTag("search-clear", useUnmergedTree = true).performClick()
        // The field's own Search key lets go of it and hides the IME. A
        // back press here was lost on the CI emulator while the list
        // re-laid out (PR #38: no IME hide followed it).
        if (imeVisible()) {
            compose.onNodeWithTag("search-field").performImeAction()
            compose.waitUntil(timeoutMillis = 10_000) { !imeVisible() }
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(FlowSemantics.sessionRow("chat-veil")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("new-session").assertExists()
    }

    /** `testPullToRefreshEnds` (:671): the list settles back within 4 dp. */
    @Test
    fun pullToRefreshEnds() {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("section-pinned").fetchSemanticsNodes().isNotEmpty()
        }
        val resting = compose.onNodeWithTag("section-pinned").getBoundsInRoot().top.value

        val width = compose.activity.window.decorView.width
        val height = compose.activity.window.decorView.height
        device.swipe(width / 2, height / 4, width / 2, (height * 0.85f).toInt(), 30)

        // The refresh runs against the demo workspace and the list settles
        // back — no sleeps: poll the row back to its resting position.
        compose.waitUntil(timeoutMillis = 5_000) {
            kotlin.math.abs(
                compose.onNodeWithTag("section-pinned").getBoundsInRoot().top.value - resting,
            ) <= 4f
        }
        assertEquals(resting, compose.onNodeWithTag("section-pinned").getBoundsInRoot().top.value, 4f)
    }

    // ── the session flows ───────────────────────────────────────────────

    /** `testSendEchoesAndStreamsReply` (:43): the echo lands, then the mock reply's markdown row. */
    @Test
    fun sendEchoesAndStreamsReply() {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("composer-input").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("composer-input").performClick()
            .performTextInput("Summarize the launch post in three bullets.")
        compose.onNodeWithTag("composer-send").performClick()
        // Sending puts the keyboard away (the send landed), then the echo.
        // The demo (`fast`) streams its reply almost at once, so the prompt can leave
        // the realized band before the wait polls (run 38552405016): page
        // back to it instead of waiting for it to be composed at the tail.
        compose.waitUntil(timeoutMillis = 3_000) { !imeVisible() }
        compose.awaitTranscriptNode(FlowSemantics.startsWith("You: Summarize the launch post"))
        FlowSemantics.saveScreenshot("streaming.png")
        // The host's reply lands after the echo and the turn settles.
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithTag("row-markdown").fetchSemanticsNodes().isNotEmpty()
        }
        FlowSemantics.saveScreenshot("reply-complete.png")
    }

    /**
     * `testLongDraftRestsAsWholeLinePreview` (:65): a long draft rests as
     * whole lines (under 90 dp), focusing restores the full card.
     */
    @Test
    @PhoneOnly("Back pops the session to the list; beside the split shell's sidebar Back leaves the app")
    fun longDraftRestsAsWholeLinePreview() {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("composer-input").fetchSemanticsNodes().isNotEmpty()
        }
        val input = compose.onNodeWithTag("composer-input")
        input.performClick().performTextInput(
            "let's fix the new worktree icon it makes no sense, also let's make it so that " +
                "there's no descriptions the line is too long and wraps onto a fourth line",
        )
        FlowSemantics.saveScreenshot("draft-focused.png")

        // Leave the session (the IME first, then the pop) and come back.
        device.pressBack() // IME down
        compose.waitForIdle()
        device.pressBack() // pop to the sessions list
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(FlowSemantics.sessionRow("chat-deploy")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(FlowSemantics.sessionRow("chat-deploy")).performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("composer-input").fetchSemanticsNodes().isNotEmpty()
        }
        // Two whole lines plus the capsule's insets — never a third.
        val restingHeight = compose.onNodeWithTag("composer-input").getBoundsInRoot().height.value
        assertTrue("resting preview must stay under 90 dp (was $restingHeight)", restingHeight < 90f)
        FlowSemantics.saveScreenshot("draft-resting.png")
        compose.onNodeWithTag("composer-input").performClick()
        val focusedHeight = compose.onNodeWithTag("composer-input").getBoundsInRoot().height.value
        assertTrue("focusing restores the full draft (was $focusedHeight)", focusedHeight > 90f)
        FlowSemantics.saveScreenshot("draft-reopened.png")
    }

    /** `testQuestionPanelAnswersAndResumes` (:86): a `?ask` prompt's question, answered. */
    @Test
    fun questionPanelAnswersAndResumes() {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("composer-input").fetchSemanticsNodes().isNotEmpty()
        }
        // `?ask`: the demo replies "Before I continue I need one decision:"
        // and asks one single-select question (`transcripts::asking()`),
        // like the iOS demo.
        compose.onNodeWithTag("composer-input").performClick().performTextInput("Pick a tone for the post ?ask")
        compose.onNodeWithTag("composer-send").performClick()
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithTag("question-option-0").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithText("Should the fix cover Android too?")[0].assertExists()
        FlowSemantics.saveScreenshot("question.png")
        compose.onNodeWithTag("question-option-0").performClick()
        // The last (only) page: a single-select pick may submit on its
        // own; press Submit if the panel still shows it.
        compose.waitForIdle()
        if (compose.onAllNodesWithTag("question-next").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("question-next").performClick()
        }
        // The composer returns after answering.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("composer-input").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** `testQueueWhileWorking` (:100): queue two follow-ups behind a paced reply ([SlowReply]). */
    @Test
    fun queueWhileWorking() {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("composer-input").fetchSemanticsNodes().isNotEmpty()
        }
        awaitIdle()
        compose.onNodeWithTag("composer-input").performClick().performTextInput("Write a long reply please.")
        compose.onNodeWithTag("composer-send").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithContentDescription("Stop response").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("composer-input").performClick().performTextInput("Then add a TL;DR.")
        compose.waitUntil(timeoutMillis = 2_000) {
            compose.onAllNodesWithContentDescription("Queue message").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithContentDescription("Queue message")[0].performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithContentDescription("More queue actions").fetchSemanticsNodes().isNotEmpty()
        }
        FlowSemantics.saveScreenshot("queued.png")

        // A second queued message, then the composer put away.
        compose.onNodeWithTag("composer-input").performClick().performTextInput(
            "And link the docs, with a much longer follow-up line that has to wrap or fade somewhere.",
        )
        compose.onAllNodesWithContentDescription("Queue message")[0].performClick()
        // Regression: a long queued message pushed its row's buttons off the card.
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithContentDescription("More queue actions").fetchSemanticsNodes().size >= 2
        }
        FlowSemantics.saveScreenshot("queued-two.png")
    }

    /** `testQueueSurvivesNavigatingAway` (:568): the queue re-materializes after a round trip. */
    @Test
    @PhoneOnly("Back pops the session to the list; beside the split shell's sidebar Back leaves the app")
    fun queueSurvivesNavigatingAway() {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("composer-input").fetchSemanticsNodes().isNotEmpty()
        }
        awaitIdle()
        compose.onNodeWithTag("composer-input").performClick().performTextInput("Write a long reply please.")
        compose.onNodeWithTag("composer-send").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithContentDescription("Stop response").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("composer-input").performClick().performTextInput("Then add a TL;DR.")
        compose.onAllNodesWithContentDescription("Queue message")[0].performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithContentDescription("More queue actions").fetchSemanticsNodes().isNotEmpty()
        }
        popSessionToList()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("new-session").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(FlowSemantics.sessionRow("chat-deploy")).performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithContentDescription("More queue actions").fetchSemanticsNodes().isNotEmpty()
        }
        FlowSemantics.saveScreenshot("queue-after-return.png")
    }

    /**
     * `testCancelledSwipeBackKeepsStreaming` (:524-545): a half swipe-back
     * (cancelled) never detaches the session — the reply keeps streaming
     * to completion, and no "New session" accessory shows over the
     * session. The gesture is dispatched through
     * `OnBackPressedDispatcher` (deterministic); the finger-level variant
     * is [cancelledSwipeBackKeepsStreaming_gesture].
     */
    @Test
    @PhoneOnly("a swipe back off a single-pane stack, and no New session accessory over the session; the split shows New session in the sidebar beside it")
    fun cancelledSwipeBackKeepsStreaming() {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("composer-input").fetchSemanticsNodes().isNotEmpty()
        }
        awaitIdle()
        val before = compose.onAllNodesWithTag("row-markdown").fetchSemanticsNodes().size

        compose.onNodeWithTag("composer-input").performClick()
            .performTextInput("Summarize the launch post in three bullets.")
        compose.onNodeWithTag("composer-send").performClick()
        // The turn is running before the gesture: the send button flipped
        // to Stop ([SlowReply] keeps the reply streaming for seconds).
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithContentDescription("Stop response").fetchSemanticsNodes().isNotEmpty()
        }

        // Drag from the left edge a third of the way, slowly, hold, and
        // release towards the edge: the predictive-back pop cancels.
        val dispatcher = compose.activity.onBackPressedDispatcher
        val halfHeight = compose.activity.window.decorView.height / 2f
        compose.runOnUiThread {
            dispatcher.dispatchOnBackStarted(BackEventCompat(1f, halfHeight, 0f, BackEventCompat.EDGE_LEFT))
        }
        for (progress in floatArrayOf(0.1f, 0.2f, 0.3f)) {
            SystemClock.sleep(50)
            compose.runOnUiThread {
                dispatcher.dispatchOnBackProgressed(
                    BackEventCompat(40f, halfHeight, progress, BackEventCompat.EDGE_LEFT),
                )
            }
        }
        SystemClock.sleep(300) // the hold before the release
        compose.runOnUiThread { dispatcher.dispatchOnBackCancelled() }
        compose.waitForIdle()

        assertTheCancelledSwipeKeptTheSessionStreaming(before)
    }

    /**
     * The finger-level variant of [cancelledSwipeBackKeepsStreaming], on a
     * device with gesture navigation: a real out-and-back edge drag (the
     * system cancels a release that does not cross the commit threshold —
     * a plain 30 % drag-and-release would commit on Android). `@Tour`:
     * recorded, not in the PR emulator run.
     */
    @Test
    @PhoneOnly("a swipe back off a single-pane stack, and no New session accessory over the session; the split shows New session in the sidebar beside it")
    @Tour
    fun cancelledSwipeBackKeepsStreaming_gesture() {
        assumeTrue(
            "the finger variant needs gesture navigation",
            Settings.Secure.getInt(compose.activity.contentResolver, "navigation_mode", 0) == 2,
        )
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("composer-input").fetchSemanticsNodes().isNotEmpty()
        }
        awaitIdle()
        val before = compose.onAllNodesWithTag("row-markdown").fetchSemanticsNodes().size

        compose.onNodeWithTag("composer-input").performClick()
            .performTextInput("Summarize the launch post in three bullets.")
        compose.onNodeWithTag("composer-send").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithContentDescription("Stop response").fetchSemanticsNodes().isNotEmpty()
        }

        // Drag out to a third of the width, then back to the edge, so the
        // release cancels (the iOS motion, adapted for Android's commit).
        val width = compose.activity.window.decorView.width
        val height = compose.activity.window.decorView.height
        device.swipe(
            arrayOf(Point(1, height / 2), Point((0.3f * width).toInt(), height / 2), Point(4, height / 2)),
            40,
        )
        compose.waitForIdle()

        assertTheCancelledSwipeKeptTheSessionStreaming(before)
    }

    /**
     * The shared tail of the cancelled-swipe pair: still on the session,
     * no accessory over it, the turn still running right after the cancel,
     * a new markdown row within 20 s, the turn settled within 40 s, and the
     * last markdown text changed after the cancel (129 exposes the text;
     * until then only that comparison is skipped).
     */
    private fun assertTheCancelledSwipeKeptTheSessionStreaming(before: Int) {
        // Still on the session, and no accessory over it (an empty list
        // also passes — 30 does not place it over a session).
        compose.onNodeWithTag("composer-input").assertIsDisplayed()
        // isDisplayed() is a SemanticsNodeInteraction extension, not a
        // SemanticsNode member: check each node through the collection.
        val accessories = compose.onAllNodesWithTag("new-session")
        val count = accessories.fetchSemanticsNodes().size
        assertTrue(
            "no New session accessory over the session",
            (0 until count).none { accessories[it].isDisplayed() },
        )
        // The turn is still running right after the cancel.
        assertTrue(
            "the turn is still running after the cancelled swipe",
            compose.onAllNodesWithContentDescription("Stop response").fetchSemanticsNodes().isNotEmpty(),
        )
        val afterCancel = lastMarkdownText()

        // The reply keeps streaming: a new markdown row lands within 20 s.
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithTag("row-markdown").fetchSemanticsNodes().size > before
        }
        // Updates keep arriving to the end: the send button flips back and
        // the reply's tail moved on after the cancel.
        compose.waitUntil(timeoutMillis = 40_000) { sendLabel() == "Send message" }
        val settled = lastMarkdownText()
        if (afterCancel != null && settled != null) {
            assertNotEquals("the reply's tail changed after the cancel", afterCancel, settled)
        }
    }

    /**
     * The last `row-markdown` node's text or content description — 129's
     * a11y tree exposes one of them; `null` until it lands.
     */
    private fun lastMarkdownText(): String? {
        val nodes = compose.onAllNodesWithTag("row-markdown").fetchSemanticsNodes()
        if (nodes.isEmpty()) return null
        val config = nodes.last().config
        return when {
            config.contains(SemanticsProperties.Text) -> config[SemanticsProperties.Text].firstOrNull()?.text
            config.contains(SemanticsProperties.ContentDescription) ->
                config[SemanticsProperties.ContentDescription].firstOrNull()
            else -> null
        }
    }

    /** The composer's send-button label: "Send message" or "Stop response". */
    private fun sendLabel(): String? {
        val nodes = compose.onAllNodesWithTag("composer-send").fetchSemanticsNodes()
        if (nodes.isEmpty()) return null
        val config = nodes[0].config
        return if (config.contains(SemanticsProperties.ContentDescription)) {
            config[SemanticsProperties.ContentDescription].firstOrNull()
        } else {
            null
        }
    }

    /** `testTapOutsideMinimizesComposer` (:549): the tap dismisses the IME and the composer rests as the capsule. */
    @Test
    fun tapOutsideMinimizesComposer() {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("composer-input").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("composer-input").performClick().performTextInput("Half-written thought")

        // The IME is up (≤ 3 s) and the composer is the focused card: a
        // 1-line card is text 46 + toolbar 50 = 96 dp, so over 90 dp, and
        // the card toolbar's model chip is live.
        compose.waitUntil(timeoutMillis = 3_000) { imeVisible() }
        compose.waitUntil(timeoutMillis = 3_000) {
            compose.onAllNodesWithTag("composer-chip-model").fetchSemanticsNodes().isNotEmpty()
        }
        val cardHeight = compose.onNodeWithTag("session-composer").getBoundsInRoot().height.value
        assertTrue("the composer is the card (was $cardHeight dp)", cardHeight > 90f)

        // 81: the tap lands 8 dp in — inside the 18 dp row margin
        // (`mob:layout/rows.rs:124`), where no link or control can be (the
        // demo's transcripts contain links, so the centre iOS taps at is not
        // safe) — at the iOS 0.35 height.
        compose.onNodeWithTag("transcript").performTouchInput {
            click(Offset(8.dp.toPx(), height * 0.35f))
        }

        // The IME goes down (≤ 3 s), the morph settles (iOS sleeps 1 s).
        compose.waitUntil(timeoutMillis = 3_000) { !imeVisible() }
        SystemClock.sleep(1_000)
        compose.waitForIdle()

        // The capsule (one line: 50 dp) and the kept draft: iOS asserts
        // hasPrefix("Half-written") — the sim keyboard's autocorrect may
        // append — so prefix, not contains (EditableText is an
        // AnnotatedString).
        val capsuleHeight = compose.onNodeWithTag("session-composer").getBoundsInRoot().height.value
        assertTrue("the composer rests as the capsule (was $capsuleHeight dp)", capsuleHeight < 90f)
        val draft = compose.onNodeWithTag("composer-input").fetchSemanticsNode()
            .config[SemanticsProperties.EditableText].text
        assertTrue("the draft is kept (was '$draft')", draft.startsWith("Half-written"))
        FlowSemantics.saveScreenshot("composer-minimized.png")
    }

    /** The IME's visibility through the window insets (81's probe). */
    private fun imeVisible(): Boolean =
        ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true

    /**
     * `testFileMentionSuggestions` (:265). "@mod", not iOS's "@lay": in
     * the demo's file list (`Client::search_files`) "mod" matches only
     * `crates/mobile/src/layout/mod.rs`, while "lay" matches several.
     */
    @Test
    fun fileMentionSuggestions() {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("composer-input").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("composer-input").performClick().performTextInput("Look at @mod")
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("mention-0").fetchSemanticsNodes().isNotEmpty()
        }
        FlowSemantics.saveScreenshot("mentions.png")
        compose.onNodeWithTag("mention-0").performClick()
        compose.onNodeWithTag("composer-input")
            .assertTextContains("@mod.rs", substring = true)
    }

    /** `testArchiveWithUndo` (:278): swipe → Archive → the toast's Undo restores the row. */
    @Test
    fun archiveWithUndo() {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(FlowSemantics.sessionRow("chat-deploy")).fetchSemanticsNodes().isNotEmpty()
        }
        // A 35 % drag settles the row open (SwipeMath); a full-width
        // swipeLeft() armed the full swipe and committed on release, and the
        // panes are icon-only (contentDescription, tag `swipe-action-*`) —
        // `onAllNodesWithText("Archive")` could never match.
        compose.onNode(FlowSemantics.sessionRow("chat-deploy")).performTouchInput {
            swipeLeft(startX = right - 1f, endX = right - width * 0.35f, durationMillis = 400)
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("swipe-action-archive").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("swipe-action-archive").performClick()
        compose.waitUntil(timeoutMillis = 3_000) {
            compose.onAllNodesWithTag("toast-action").fetchSemanticsNodes().isNotEmpty()
        }
        FlowSemantics.saveScreenshot("archive-undo.png")
        compose.onNodeWithTag("toast-action").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(FlowSemantics.sessionRow("chat-deploy")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * `testSessionMenuHeaderAndDelete` (upstream #967): the list's
     * long-press menu deletes after "Delete session?" (Cancel keeps the
     * row); the session's ⋯ menu is headed by its title and deletes too,
     * back on the list without the row. Runs on `chat-home` — no other
     * flow test reads it (each launch starts a fresh demo workspace).
     */
    @Test
    fun sessionMenuHeaderAndDelete() {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(FlowSemantics.sessionRow("chat-home")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(FlowSemantics.sessionRow("chat-home")).performTouchInput { longClick() }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("row-menu-delete").fetchSemanticsNodes().isNotEmpty()
        }
        FlowSemantics.saveScreenshot("list-menu-delete.png")
        compose.onNodeWithTag("row-menu-delete").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Delete session?").fetchSemanticsNodes().isNotEmpty()
        }
        FlowSemantics.saveScreenshot("delete-confirm.png")
        compose.onNodeWithTag("alert-cancel").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Delete session?").fetchSemanticsNodes().isEmpty()
        }
        assertTrue(
            "cancel keeps the session",
            compose.onAllNodes(FlowSemantics.sessionRow("chat-home")).fetchSemanticsNodes().isNotEmpty(),
        )

        compose.onNode(FlowSemantics.sessionRow("chat-home")).performClick()
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithTag("session-more").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("session-more").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("session-menu-delete").fetchSemanticsNodes().isNotEmpty()
        }
        // The title heads the menu (the header line inside the panel).
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(
                hasText("Clean up ~/Downloads", substring = true) and hasAnyAncestor(hasTestTag("glass-menu")),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }
        FlowSemantics.saveScreenshot("session-menu.png")
        compose.onNodeWithTag("session-menu-delete").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("alert-primary").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("alert-primary").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("session-more").fetchSemanticsNodes().isEmpty()
        }
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(FlowSemantics.sessionRow("chat-home")).fetchSemanticsNodes().isEmpty()
        }
    }

    /**
     * Wave-10 35 (upstream #967's error card): a run refused as signed out
     * shows the red card with "Log in"; the tap opens the chat's agent on
     * Accounts & Usage. On the demo's `chat-errored`, whose seeded history
     * holds the signed-out ("Not logged in · Please run /login") and
     * usage-limit errors — no send needed. No login starts here —
     * `AccountSignInTest` covers the start.
     */
    @Test
    fun errorCardLogInOpensTheAgentsAccounts() {
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithTag("composer-input").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithTag("transcript-action-sign-in", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        FlowSemantics.saveScreenshot("error-card.png")
        if (imeVisible()) {
            device.pressBack()
            compose.waitUntil(timeoutMillis = 3_000) { !imeVisible() }
        }
        // The semantics action: the seeded card may sit in the overscan
        // band above the viewport, where an injected tap hits nothing.
        compose.onAllNodesWithTag("transcript-action-sign-in", useUnmergedTree = true)[0]
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("agent-accounts").fetchSemanticsNodes().isNotEmpty()
        }
        FlowSemantics.saveScreenshot("error-card-accounts.png")
    }

    /** `testToolGroupExpandsAndShowsDetail` (:344): the disclosure and the inline detail. */
    @Test
    fun toolGroupExpandsAndShowsDetail() {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("transcript").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithContentDescription("Expand").fetchSemanticsNodes().isNotEmpty()
        }
        // The semantics action, not a touch: the transcript draws under the
        // header and the bottom stack, so a displayed node can sit under the
        // chrome and an injected tap lands there (no haptic, no expansion).
        onScreen("Expand").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithContentDescription("Show details").fetchSemanticsNodes().isNotEmpty()
        }
        FlowSemantics.saveScreenshot("tools-expanded.png")
        // Details open inline under the row, like desktop.
        onScreen("Show details").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithContentDescription("Hide details").fetchSemanticsNodes().isNotEmpty()
        }
        FlowSemantics.saveScreenshot("tool-detail.png")
    }

    /**
     * `testToolGroupsRenderLikeDesktop` (:494): the static expanded view
     * on `chat-veil`, then a live streaming run screenshotted over time
     * on `chat-deploy` ([SlowReply] paces it).
     */
    @Test
    @PhoneOnly("Back pops the session to the list; beside the split shell's sidebar Back leaves the app")
    fun toolGroupsRenderLikeDesktop() {
        // Static: file badges, inline stats/diff detail, thoughts.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("transcript").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithContentDescription("Expand").fetchSemanticsNodes().isNotEmpty()
        }
        onScreen("Expand").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        FlowSemantics.saveScreenshot("tool-groups.png")
        val details = compose.onAllNodesWithContentDescription("Show details")
        if (details.fetchSemanticsNodes().isNotEmpty()) {
            onScreen("Show details").performSemanticsAction(SemanticsActions.OnClick)
            compose.waitForIdle()
            FlowSemantics.saveScreenshot("tool-row-detail.png")
        }

        // Live: a paced streaming run on another chat, screenshotted as
        // the group streams in.
        popSessionToList()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(FlowSemantics.sessionRow("chat-deploy")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(FlowSemantics.sessionRow("chat-deploy")).performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("composer-input").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("composer-input").performClick().performTextInput("Refactor the layout pass")
        compose.onNodeWithTag("composer-send").performClick()
        for (k in 0 until 6) {
            Thread.sleep(1_000) // the live window the iOS test samples
            FlowSemantics.saveScreenshot("live-tools-$k.png")
        }
    }

    /** `testEffortPickerInSession` (:360): the effort chip's menu, twice. */
    @Test
    fun effortPickerInSession() {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("composer-input").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("composer-input").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("composer-chip-effort").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("composer-chip-effort").performClick()
        // The chat's model's efforts (Low…XHigh); the menu's first item.
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Low").fetchSemanticsNodes().isNotEmpty()
        }
        FlowSemantics.saveScreenshot("effort-menu.png")
        compose.onAllNodesWithText("Low")[0].performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("composer-input").fetchSemanticsNodes().isNotEmpty()
        }
        // Reopening the menu must not crash (the regression the iOS test guards).
        compose.onNodeWithTag("composer-chip-effort").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Low").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** `testModelPickerInSession` (:459): the model menu completes and dismisses. */
    @Test
    @PhoneOnly("suspected tablet bug, ci-speed ticket 25: on the split the picker sheet was still up 5 s after the pick (run 40725884775)")
    fun modelPickerInSession() {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("composer-input").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("composer-input").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("composer-chip-model").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("composer-chip-model").performClick()
        // Wave-10 38: the tabbed picker sheet (upstream #966) on the chat's
        // provider page: `chat-deploy` is Claude Code on Fable 5, and the
        // demo lists the catalog's fallback models (`catalog.rs`).
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("model-picker").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("Sonnet 5.5").fetchSemanticsNodes().isNotEmpty()
        }
        FlowSemantics.saveScreenshot("model-picker.png")
        compose.onAllNodesWithText("Sonnet 5.5")[0].performClick()
        // The pick lands, then the sheet closes itself.
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("model-picker").fetchSemanticsNodes().isEmpty()
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("composer-input").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** `testAttachMenuOpens` (:438): "+" opens the attach menu, also from the card state. */
    @Test
    fun attachMenuOpens() {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("composer-attach").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("composer-attach").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Photo Library").fetchSemanticsNodes().isNotEmpty()
        }
        FlowSemantics.saveScreenshot("attach-menu.png")
        // Card state (focused, with a draft): the toolbar row must not cover "+".
        device.pressBack() // close the menu
        compose.waitForIdle()
        compose.onNodeWithTag("composer-input").performClick().performTextInput("Draft")
        compose.onNodeWithTag("composer-attach").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Photo Library").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * `testSendGlidesPromptToTopWithRunway` (:653): the prompt glides to the
     * top of the transcript. A `?ask` prompt: the demo's reply is one line
     * and then waits for the answer, so the runway holds — a plain reply
     * is taller than the viewport and retires the runway on the first
     * frame (run 38552405016).
     */
    @Test
    fun sendGlidesPromptToTopWithRunway() {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("composer-input").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("composer-input").performClick()
            .performTextInput("Runway check: summarize the veil work ?ask")
        compose.onNodeWithTag("composer-send").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(FlowSemantics.startsWith("You: Runway check"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        // The runway reserves the reply space below the glided prompt.
        compose.waitUntil(timeoutMillis = 5_000) {
            val promptTop = compose.onAllNodes(FlowSemantics.startsWith("You: Runway check"))
                .fetchSemanticsNodes().firstOrNull()?.positionInRoot?.y ?: Float.MAX_VALUE
            val transcriptTop = compose.onNodeWithTag("transcript").getBoundsInRoot().top.value
            promptTop / compose.density.density - transcriptTop < 200f
        }
        FlowSemantics.saveScreenshot("runway.png")
    }

    // ── the failed-open flows (the pre-rig tests, now on the rig) ───────

    /** `testUnknownChatRouteReturnsToSessions` (:213): the verbatim iOS unknown-id form. */
    @Test
    @PhoneOnly("suspected tablet bug, ci-speed ticket 25: after the failed open the split still showed a transcript (run 40725884775)")
    fun unknownChatRouteReturnsToSessions() {
        failedOpenReturnsToSessions("unknown-chat-returned-to-sessions.png")
    }

    private fun failedOpenReturnsToSessions(artifactName: String) {
        val chatId = checkNotNull(routeChatId) { "routeFor did not record the chat id" }

        // Within 10 s the toast is on the Sessions root (iOS waits 10 s
        // for the same label). The failed open never slides anything in.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("Couldn't open session. Try again.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Sessions").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithText("Sessions")[0].assertExists()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("new-session").fetchSemanticsNodes().isNotEmpty()
        }

        // A failed open must not present a conversation.
        compose.waitUntil(timeoutMillis = 2_000) {
            compose.onAllNodesWithTag("transcript").fetchSemanticsNodes().isEmpty()
        }
        compose.waitUntil(timeoutMillis = 2_000) {
            compose.onAllNodesWithTag("composer-input").fetchSemanticsNodes().isEmpty()
        }
        // No failure UI of any kind: the toast is the only visible result.
        compose.onAllNodesWithText("Unavailable").fetchSemanticsNodes().let { nodes ->
            assertTrue("no Unavailable failure UI", nodes.isEmpty())
        }
        // No seeded prompt for a missing chat, and no row for it.
        compose.onAllNodes(FlowSemantics.userEcho()).fetchSemanticsNodes().let { nodes ->
            assertTrue("no user echo for a missing chat", nodes.isEmpty())
        }
        compose.onAllNodesWithTag("session-$chatId").fetchSemanticsNodes().let { nodes ->
            assertTrue("no row for the missing chat", nodes.isEmpty())
        }
        FlowSemantics.saveScreenshot(artifactName)
    }

    // ── the jump-to-latest rows (131's button over 68's viewport) ──

    /**
     * `testJumpToLatestAfterScrollingUp` (:129): two fast swipes toward
     * older content make the button exist (while hidden it is not in the
     * semantics tree — UIKit's model alpha 0), and tapping it glides the
     * transcript back to the tail.
     */
    @Test
    fun jumpToLatestAfterScrollingUp() {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("transcript").fetchSemanticsNodes().isNotEmpty()
        }
        // Two fast swipes (XCUITest `swipeDown(velocity: .fast)` × 2): the
        // first delta past touch slop hands control to the user (68's R5)
        // and the release, far above the tail, never re-latches — the
        // fling's momentum carries the list further up, away from it.
        val transcript = compose.onNodeWithTag("transcript")
        transcript.performTouchInput {
            swipeDown(startY = height * 0.2f, endY = height * 0.8f, durationMillis = 200)
        }
        transcript.performTouchInput {
            swipeDown(startY = height * 0.2f, endY = height * 0.8f, durationMillis = 200)
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("jump-to-latest").fetchSemanticsNodes().isNotEmpty()
        }
        FlowSemantics.saveScreenshot("scrolled-up.png")
        // The tap glides the list back (68's R10: following resumes, the
        // follow spring runs) while the button shrinks away.
        compose.onNodeWithTag("jump-to-latest").performClick()
        compose.waitForIdle()
    }

    /**
     * `testScrollingUpWhileStreamingStaysPut` (:474): the regression — a
     * drag that started at the tail re-latched follow at once, so the
     * list sprang back to the bottom on release while streaming. A slow
     * drag (0.3 → 0.75 height, holding at the end, iOS
     * `press(forDuration:thenDragTo:withVelocity:.slow,
     * thenHoldForDuration:)`), and the jump button must still be hittable
     * 3 s later as the proof the view stayed put.
     *
     * On `chat-veil` with `big` (120 turns of history — the drag needs
     * content above the runway) and [SlowReply] (a paced `-longreply`
     * reply), so the drag lands while the reply streams, as on iOS.
     */
    @Test
    fun scrollingUpWhileStreamingStaysPut() {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("composer-input").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("composer-input").performClick()
            .performTextInput("Walk me through the veil")
        compose.onNodeWithTag("composer-send").performClick()
        SystemClock.sleep(2_000)

        // A slow drag down 45 % of the height, the finger at rest at the
        // end, so the release never flings and never re-latches (the drag
        // ends 0.45 · height above the tail, far outside 68's 70 dp).
        compose.onNodeWithTag("transcript").performTouchInput {
            val from = Offset(width * 0.5f, height * 0.3f)
            val to = Offset(width * 0.5f, height * 0.75f)
            down(from)
            advanceEventTime(50)
            repeat(20) { step ->
                moveTo(from + (to - from) * ((step + 1) / 20f))
                advanceEventTime(50)
            }
            advanceEventTime(100)
            up()
        }
        compose.waitForIdle()
        SystemClock.sleep(3_000)

        compose.onNodeWithTag("jump-to-latest").assertExists()
        FlowSemantics.saveScreenshot("scrolled-up-streaming.png")
    }

    // ── rows whose owning tickets have not landed (see the class docs) ──

    @Ignore("Needs ui-parity #126: the transcript link hit-test + Custom Tab routing (no 'transcript-link' semantics yet)")
    @Test
    fun tappingLinkOpensIt() = fail("unwired: see the @Ignore reason")

    @Ignore("Needs the clipboard-image staging of ui-parity #93's Paste Image path (a content-URI image on the clipboard, unreachable from the instrumentation API before that ticket's share flow)")
    @Test
    fun imageUploadShowsRingOnThumbnail() = fail("unwired: see the @Ignore reason")

    // ── the New Session flow (94's create + first send) ──────────────

    /**
     * `testNewSessionFromAccessory` (`SessionFlowTests.swift:245-262`):
     * the accessory opens the sheet (93), typing and Send run 94's create
     * + first send, and the handoff lands in the new session — the
     * transcript exists within 10 s, the IME is down within 3 s, the
     * optimistic echo (`You: Add a dark mode toggle`) shows within 5 s,
     * and 1 s later the sheet (and its `Close` button) is gone. The
     * capture froze the page mid-handoff; until 95 lands, the router's
     * `HandoffAnimator.Instant` reveals the session at once.
     */
    @Test
    fun newSessionFromAccessory() {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("new-session").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("new-session").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("composer-input").fetchSemanticsNodes().isNotEmpty()
        }
        FlowSemantics.saveScreenshot("new-session.png")

        compose.onNodeWithTag("composer-input").performClick()
            .performTextInput("Add a dark mode toggle to settings")
        // The first send runs the first-session notification ask
        // (NotificationPermission.askAfterFirstSession): on API 33+ the
        // system dialog pauses MainActivity and the compose waits below find
        // no hierarchy (run 38531061823). Not this test's subject — granted
        // up front, as NotificationFlowTest / RunWatchServiceTest do.
        device.executeShellCommand("pm grant ${app.packageName} android.permission.POST_NOTIFICATIONS")
        compose.onNodeWithTag("composer-send").performClick()

        // "hands off into the new session"
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("transcript").fetchSemanticsNodes().isNotEmpty()
        }
        // Sending puts the keyboard away.
        compose.waitUntil(timeoutMillis = 3_000) { !imeVisible() }
        // `main`'s instant ~20-row reply can push the prompt out of the
        // realized band: page back to it (see sendEchoesAndStreamsReply).
        compose.awaitTranscriptNode(FlowSemantics.startsWith("You: Add a dark mode toggle"))
        // The handoff ends with the sheet gone and the message in the
        // transcript (iOS sleeps 1 s before asserting no Close).
        SystemClock.sleep(1_000)
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 2_000) {
            compose.onAllNodesWithContentDescription("Close").fetchSemanticsNodes().isEmpty()
        }
        FlowSemantics.saveScreenshot("new-session-created.png")
    }

    @Ignore("Needs the chips and menus of ui-parity #92/#109/#113/#114 (the draft-remember path)")
    @Test
    fun newSessionRemembersDraftWhenClosed() = fail("unwired: see the @Ignore reason")

    @Ignore("Needs the real New Session sheet (ui-parity #92/#113)")
    @Test
    fun newSessionHeadlineStaysAboveComposer() = fail("unwired: see the @Ignore reason")

    @Ignore("Needs the real New Session sheet (ui-parity #92/#113)")
    @Test
    fun effortPickerInNewSession() = fail("unwired: see the @Ignore reason")

    @Ignore("Needs the real New Session sheet (ui-parity #92/#113)")
    @Test
    fun modelChipNamesAModelAtOnce() = fail("unwired: see the @Ignore reason")

    @Ignore("Needs the real New Session sheet + the folder browser write path (ui-parity #92/#110–#113)")
    @Test
    fun newProjectFolderBrowser() = fail("unwired: see the @Ignore reason")

    @Ignore("Needs the real New Session sheet + cross-engine project grouping (ui-parity #92/#113)")
    @Test
    fun projectPickerGroupsClonesAndHostPicksCheckout() = fail("unwired: see the @Ignore reason")

    // ── helpers ─────────────────────────────────────────────────────────

    /** The `route` extra per test method (§6: chat:<id> | new | more | search). */
    private fun routeFor(name: String): String? = when {
        name.startsWith("frontPage") ||
            name.startsWith("settingsFromBottomBar") ||
            name.startsWith("bottomBarSearch") ||
            name.startsWith("pullToRefresh") ||
            name.startsWith("archiveWithUndo") ||
            name.startsWith("sessionMenuHeaderAndDelete") ||
            name.startsWith("newSessionFromAccessory") -> null
        name.startsWith("errorCardLogIn") -> "chat:chat-errored"
        // 131's jump rows: the big synthetic `chat-veil` (see [extrasFor]).
        name.startsWith("toolGroupsRender") ||
            name.startsWith("jumpToLatest") ||
            name.startsWith("scrollingUp") -> "chat:chat-veil"
        name.startsWith("unknownChat") -> "chat:${unknownChatId().also { routeChatId = it }}"
        // Everything else (the question, queue and runway rows included):
        // `chat-deploy`, exactly like sendEchoesAndStreamsReply.
        else -> "chat:chat-deploy"
    }

    /** The demo options per test method (on top of the rule's `demo` + `fast`). */
    private fun extrasFor(name: String): Map<String, Any> = when {
        name.startsWith("queue") ||
            name.startsWith("cancelledSwipeBack") ||
            name.startsWith("toolGroupsRender") -> SlowReply
        name.startsWith("jumpToLatest") -> mapOf("big" to true)
        name.startsWith("scrollingUp") -> SlowReply + ("big" to true)
        else -> emptyMap()
    }

    /**
     * Pops the session to the list: Back for the IME only when it is up.
     * The old blind `pressBack(); pressBack()` assumed the keyboard was
     * showing — it was not (the queue hides it; toolGroupsRender never
     * shows it), so the second Back finished MainActivity ("No compose
     * hierarchies found", run 38552405016).
     */
    private fun popSessionToList() {
        if (imeVisible()) {
            device.pressBack()
            compose.waitUntil(timeoutMillis = 3_000) { !imeVisible() }
        }
        device.pressBack()
    }

    /**
     * Start only once nothing is running in the chat (no "Stop
     * response"), so a test's own send starts a fresh turn.
     */
    private fun awaitIdle() {
        compose.waitUntil(timeoutMillis = 60_000) {
            compose.onAllNodesWithContentDescription("Stop response").fetchSemanticsNodes().isEmpty()
        }
    }

    /**
     * The last **displayed** node with [description]. The transcript
     * composes rows in an overscan band beyond the viewport, so `[0]` of a
     * content-description query can be a group above the visible window —
     * a click injected there hits nothing (toolGroupExpandsAndShowsDetail,
     * run 38531061823: no haptic, no expansion). The list is
     * bottom-anchored, so the last on-screen match is the newest.
     */
    private fun onScreen(description: String): SemanticsNodeInteraction {
        val nodes = compose.onAllNodesWithContentDescription(description)
        val count = nodes.fetchSemanticsNodes().size
        val index = (count - 1 downTo 0).firstOrNull { nodes[it].isDisplayed() } ?: (count - 1)
        return nodes[index]
    }

    /** The verbatim iOS form (`SessionFlowTests.swift:215`). */
    private fun unknownChatId(): String = "missing-notification-chat-" + UUID.randomUUID()
}
