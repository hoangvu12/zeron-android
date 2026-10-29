package sh.zeron.android.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The pull-to-refresh contract (#62, decision D7 "variant 2"): the list's
 * "Refresh" custom action ([pullToRefreshSemantics], the non-gesture path)
 * starts a refresh, and the indicator then exposes its state — an
 * indeterminate progress reading [RefreshingLabel] in a polite live region
 * — instead of clearing its semantics; while merely pulled it has nothing
 * to say.
 *
 * Robolectric + compose-ui-test with the clock frozen (the fake refresh
 * never returns inside the test), the glyph clock frozen so the spinner
 * never starts a frame loop. The gesture itself is
 * `PullToRefreshGestureTest`'s (still quarantined) and the timing
 * `PullToRefreshTimingTest`'s.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class PullToRefreshSemanticsTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var state: PullToRefreshState
    private var refreshCalls = 0

    @Before
    fun freezeClock() {
        compose.mainClock.autoAdvance = false
        refreshCalls = 0
    }

    private fun host() {
        compose.setContent {
            ZeronTheme(Appearance.Light) {
                ProvideGlyphClock(frozenAtNanos = 0L) {
                    val pull = rememberPullToRefreshState(onRefresh = {
                        refreshCalls++
                        delay(60_000)
                    })
                    state = pull
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize().testTag("list").pullToRefreshSemantics(pull))
                        PullToRefreshIndicator(pull, navBottom = 68.dp)
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** Hands a test-thread state write to the Recomposer and runs the frame that composes it. */
    private fun composeFrame() {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private fun refreshAction() = compose.onNodeWithTag("list").fetchSemanticsNode()
        .config[SemanticsActions.CustomActions]
        .single { it.label == PullToRefreshActionLabel }

    private fun indicator() = compose.onNodeWithTag("pull-to-refresh", useUnmergedTree = true)

    @Test
    fun theListOffersOneRefreshAction() {
        host()
        assertEquals("Refresh", refreshAction().label)
        // At rest the indicator is not composed at all.
        indicator().assertDoesNotExist()
    }

    @Test
    fun theRefreshActionStartsARefreshThatTheIndicatorAnnounces() {
        host()
        assertTrue("the action reports success", refreshAction().action())
        assertEquals(PullPhase.Refreshing, state.phase)
        composeFrame()

        assertEquals("the refresh ran once", 1, refreshCalls)
        indicator()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(RefreshingLabel)))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate))
    }

    @Test
    fun aSecondRefreshActionWhileRefreshingDoesNothing() {
        host()
        refreshAction().action()
        composeFrame()
        refreshAction().action()
        composeFrame()
        assertEquals(1, refreshCalls)
        assertEquals(PullPhase.Refreshing, state.phase)
    }

    @Test
    fun aPulledButIdleIndicatorHasNothingToSay() {
        host()
        state.setOffsetForTest(40f)
        composeFrame()

        indicator()
            .assertExists()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ProgressBarRangeInfo))
    }
}
