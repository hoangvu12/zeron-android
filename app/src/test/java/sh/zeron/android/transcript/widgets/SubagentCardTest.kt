package sh.zeron.android.transcript.widgets

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.zeron.android.design.Appearance
import sh.zeron.android.design.ZeronTheme
import sh.zeron.android.transcript.TranscriptActions
import sh.zeron.android.transcript.isControl
import sh.zeron.android.transcript.isTranscriptControl
import uniffi.zeron_core.WidgetKind

/**
 * The subagent spawn card's control (#31, iOS `SubagentCardControl` and
 * `RowView.swift:323-337`): it opens the subagent once per tap, is a
 * button labelled "Subagent: <title>" with a Working / Failed / Done
 * state, is disabled until the host binds a doc, claims its down from the
 * composer-dismiss tap like every transcript control, and keeps the row
 * menu away (a control kind); the default actions leave a card inert.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h300dp-mdpi")
class SubagentCardTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val card = WidgetKind.Subagent(
        docId = "engine:v1:k:chat--sub--toolu_1",
        title = "Audit the reduced-motion fallback",
        running = true,
        failed = false,
    )

    private fun host(onOpen: (() -> Unit)?, running: Boolean = true, failed: Boolean = false) {
        compose.setContent {
            ZeronTheme(Appearance.Light) {
                Box(Modifier.fillMaxSize()) {
                    // The Rust rect of a 30 pt card in its 38 pt row (T = 1.125).
                    Box(Modifier.absoluteOffset(18.dp, 4.dp).size(324.dp, 33.75.dp)) {
                        SubagentCard(title = card.title, running = running, failed = failed, onOpen = onOpen)
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun aTapOpensTheSubagentOnceAndSpeaksAsAButton() {
        val opened = mutableListOf<String>()
        host(onOpen = { opened += card.docId!! })
        val node = compose.onNodeWithTag("subagent-card")
        val config = node.fetchSemanticsNode().config
        assertEquals(listOf("Subagent: Audit the reduced-motion fallback"), config[SemanticsProperties.ContentDescription])
        assertEquals("Working", config[SemanticsProperties.StateDescription])
        assertEquals(Role.Button, config[SemanticsProperties.Role])
        node.assertIsEnabled()
        node.performClick()
        compose.waitForIdle()
        assertEquals(listOf(card.docId), opened)
    }

    @Test
    fun withoutADocTheCardIsDisabled() {
        host(onOpen = null)
        compose.onNodeWithTag("subagent-card").assertIsNotEnabled()
    }

    @Test
    fun theStateIsWorkingThenFailedOrDone() {
        assertEquals("Working", subagentStatus(running = true, failed = true))
        assertEquals("Failed", subagentStatus(running = false, failed = true))
        assertEquals("Done", subagentStatus(running = false, failed = false))
    }

    @Test
    fun theCardIsATranscriptControl() {
        assertTrue("its down never dismisses the composer", card.isTranscriptControl)
        assertTrue("a long press over it opens no row menu", card.isControl())
        assertFalse(WidgetKind.Spinner.isTranscriptControl)
    }

    @Test
    fun theDefaultActionsLeaveTheCardInert() {
        // No crash, no effect: tests and previews build actions without it.
        TranscriptActions().openSubagent(card.docId!!, card.title, false)
        var seen: Triple<String, String, Boolean>? = null
        TranscriptActions(openSubagent = { d, t, f -> seen = Triple(d, t, f) })
            .openSubagent(card.docId!!, card.title, true)
        assertEquals(Triple(card.docId, card.title, true), seen)
    }
}
