package sh.zeron.android.sidechats

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.zeron.android.companions.CompanionsButton
import sh.zeron.android.companions.CompanionsButtonLabel
import sh.zeron.android.design.Appearance
import sh.zeron.android.design.Toaster
import sh.zeron.android.design.ZeronTheme
import sh.zeron.android.design.glass.Glass
import sh.zeron.android.session.SessionHeader
import sh.zeron.android.session.SessionHeaderMetrics
import uniffi.zeron_core.CoreException

/**
 * The companions' entry points (#40/#41, iOS `companionsItem` and
 * `fork()`): the header's always-shown companions item with its running
 * badge (the title cap narrowed for it), and the fork's one-at-a-time run
 * and its failure copy. The ⋯ group itself is `SessionMenuTest`'s.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h900dp-xxhdpi")
@OptIn(ExperimentalCoroutinesApi::class)
class SideChatsEntryTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun fallbackGlass() {
        Glass.forceFallback = true
    }

    @After
    fun restoreGlass() {
        Glass.forceFallback = false
    }

    private class RecordingToaster : Toaster {
        val texts = mutableListOf<String>()
        override fun show(text: String, action: String?, onAction: (() -> Unit)?) {
            texts += text
        }
    }

    private fun header(running: Int, onClick: () -> Unit = {}) {
        compose.setContent {
            ZeronTheme(Appearance.Light) {
                SessionHeader(
                    title = "",
                    subtitle = "",
                    edgeEngaged = false,
                    onMore = {},
                    showBack = true,
                    onBack = {},
                    statusBarTop = 24.dp,
                    accessory = { CompanionsButton(running, onClick = onClick) },
                )
            }
        }
        compose.waitForIdle()
    }

    // ------------------------------------------------------------ header item

    @Test
    fun theCompanionsItemCarriesTheRunningBadgeAndOpensThePanel() {
        var taps = 0
        header(running = 4) { taps++ }
        val button = compose.onNodeWithTag("session-companions")
        val config = button.fetchSemanticsNode().config
        assertEquals(listOf(CompanionsButtonLabel), config[SemanticsProperties.ContentDescription])
        assertEquals("4 running", config[SemanticsProperties.StateDescription])
        compose.onNodeWithTag("session-companions-badge", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("session-more").assertExists()
        button.performClick()
        compose.waitForIdle()
        assertEquals(1, taps)
    }

    @Test
    fun theItemShowsWithNothingRunningButNoBadge() {
        header(running = 0)
        compose.onNodeWithTag("session-companions").assertExists()
        compose.onNodeWithTag("session-companions-badge", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun theTitleCapClearsTheSecondBarItem() {
        assertEquals(240f, SessionHeaderMetrics.titleMaxWidth(402f, showBack = true), 0.001f)
        val extra = SessionHeaderMetrics.AccessoryExtent
        assertEquals(52f, extra, 0.001f)
        assertEquals(162f, SessionHeaderMetrics.titleMaxWidth(402f, showBack = true, extraTrailing = extra), 0.001f)
    }

    // ------------------------------------------------------------ the fork

    @Test
    fun aForkRunsOnceAtATimeAndOpensTheNewChat() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val gate = CompletableDeferred<String>()
        val sources = mutableListOf<String>()
        val opened = mutableListOf<String>()
        val toaster = RecordingToaster()
        val fork = SideChatFork(
            fork = { source ->
                sources += source
                gate.await()
            },
            scope = CoroutineScope(SupervisorJob() + dispatcher),
            toaster = toaster,
            logW = { _, _ -> },
        )
        fork.start("engine:v1:k:chat") { opened += it }
        fork.start("engine:v1:k:chat") { opened += it }
        advanceUntilIdle()
        assertTrue(fork.forking)
        assertEquals("the second tap is ignored", listOf("engine:v1:k:chat"), sources)

        gate.complete("engine:v1:k:fork")
        advanceUntilIdle()
        assertFalse(fork.forking)
        assertEquals(listOf("engine:v1:k:fork"), opened)
        assertTrue(toaster.texts.isEmpty())
    }

    @Test
    fun aFailedForkToastsIosCopy() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val toaster = RecordingToaster()
        val opened = mutableListOf<String>()
        val fork = SideChatFork(
            fork = { throw CoreException.HostUnavailable("Engine connection lost") },
            scope = CoroutineScope(SupervisorJob() + dispatcher),
            toaster = toaster,
            logW = { _, _ -> },
        )
        fork.start("engine:v1:k:chat") { opened += it }
        advanceUntilIdle()
        assertFalse(fork.forking)
        assertTrue(opened.isEmpty())
        assertEquals(listOf("Its computer isn't reachable right now"), toaster.texts)
    }

    @Test
    fun forkFailureCopyFollowsTheErrorKind() {
        assertEquals(SideChatForkCopy.Unreachable, forkErrorText(CoreException.HostUnavailable("x")))
        assertEquals(SideChatForkCopy.Unsupported, forkErrorText(CoreException.Unsupported("ForkSideChat")))
        assertEquals(SideChatForkCopy.Failed, forkErrorText(IllegalStateException("boom")))
    }
}
