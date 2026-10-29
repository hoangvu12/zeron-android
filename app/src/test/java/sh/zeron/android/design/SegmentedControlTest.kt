package sh.zeron.android.design

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The segmented control (#62 / D7) on `UnstyledRadioGroup`: the group's
 * radio contract (a named `selectableGroup` of `Role.RadioButton`s with
 * their on/off state) and the pick path (a tap on another segment reports
 * it once; the picked segment is inert), on onboarding's System / Light /
 * Dark options.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w402dp-h420dp-xxhdpi")
class SegmentedControlTest {

    @get:Rule
    val compose = createComposeRule()

    private fun host(picked: MutableState<Appearance>, picks: MutableList<Appearance>) {
        compose.setContent {
            ZeronTheme(Appearance.Light) {
                ZeronSegmentedControl(
                    options = Appearance.entries,
                    selected = picked.value,
                    onSelect = { picked.value = it; picks += it },
                    label = { it.name },
                    modifier = Modifier.testTag("scheme"),
                    accessibilityLabel = "Color scheme",
                    segmentTestTag = { "scheme-${it.name}" },
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun theGroupIsANamedSelectableGroupOfRadioButtons() {
        host(mutableStateOf(Appearance.System), mutableListOf())
        val group = compose.onNodeWithTag("scheme").fetchSemanticsNode().config
        assertTrue("the track is a selectableGroup", SemanticsProperties.SelectableGroup in group)
        assertEquals(listOf("Color scheme"), group[SemanticsProperties.ContentDescription])
        for (option in Appearance.entries) {
            val node = compose.onNodeWithTag("scheme-${option.name}").fetchSemanticsNode()
            assertEquals("${option.name} is a radio button", Role.RadioButton, node.config[SemanticsProperties.Role])
        }
        compose.onNodeWithTag("scheme-System").assertIsOn()
        compose.onNodeWithTag("scheme-Light").assertIsOff()
        compose.onNodeWithTag("scheme-Dark").assertIsOff()
    }

    @Test
    fun aTapPicksAnotherSegmentOnceAndThePickedOneIsInert() {
        val picked = mutableStateOf(Appearance.System)
        val picks = mutableListOf<Appearance>()
        host(picked, picks)

        compose.onNodeWithTag("scheme-Dark").performClick()
        compose.waitForIdle()
        assertEquals(listOf(Appearance.Dark), picks)
        compose.onNodeWithTag("scheme-Dark").assertIsOn()
        compose.onNodeWithTag("scheme-System").assertIsOff()

        compose.onNodeWithTag("scheme-Dark").performClick()
        compose.waitForIdle()
        assertEquals("a tap on the picked segment reports nothing", listOf(Appearance.Dark), picks)
    }
}
