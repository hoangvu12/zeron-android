package sh.zeron.android.design

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The search field contract (#62, decision D7 "variant 2") through the
 * sheets' [FilledSearchField] on the headless `UnstyledTextField`: one
 * labelled node (the placeholder not read twice), the "Clear text" button,
 * and the result count as a polite live region while there is a query.
 *
 * Robolectric + compose-ui-test; the query is written through the state
 * (no IME in the test). Escape is not exercised here (key injection into a
 * Robolectric window's focus is not something the suite relies on yet).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class SearchFieldTest {

    @get:Rule
    val compose = createComposeRule()

    private val state = TextFieldState()

    private fun host(resultCount: Int = 3) {
        compose.setContent {
            ZeronTheme(Appearance.Light) {
                FilledSearchField(
                    state = state,
                    placeholder = "Search repositories",
                    textPt = 16f,
                    fieldTag = "search",
                    resultCount = resultCount,
                )
            }
        }
        compose.waitForIdle()
    }

    /** Writes the query through the state (no IME here) and lets it compose. */
    private fun type(text: String) {
        state.edit { append(text) }
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    private fun countNode(text: String) =
        compose.onNode(hasContentDescription(text), useUnmergedTree = true)

    @Test
    fun theCountCopy() {
        assertEquals("No results", searchResultCountText(0))
        assertEquals("1 result", searchResultCountText(1))
        assertEquals("12 results", searchResultCountText(12))
    }

    @Test
    fun theFieldIsOneNodeLabelledWithItsPlaceholder() {
        host()
        compose.onNodeWithTag("search")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Search repositories")))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText))
        // The placeholder is drawn but not read: the label already says it.
        assertEquals(
            0,
            compose.onAllNodesWithText("Search repositories", useUnmergedTree = true).fetchSemanticsNodes().size,
        )
        // No query: no clear button, and the count says nothing.
        compose.onNodeWithTag("search-clear").assertDoesNotExist()
        countNode("3 results").assertDoesNotExist()
    }

    @Test
    fun aQueryShowsTheClearButtonAndSpeaksTheCount() {
        host(resultCount = 3)
        type("zer")

        compose.onNodeWithTag("search-clear")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(SearchClearLabel)))
        countNode("3 results")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    @Test
    fun clearEmptiesTheQueryAndTheButtonLeaves() {
        host()
        type("zer")

        compose.onNodeWithTag("search-clear").performClick()
        compose.waitForIdle()
        assertEquals("", state.text.toString())
        compose.onNodeWithTag("search-clear").assertDoesNotExist()
        countNode("3 results").assertDoesNotExist()
    }
}
