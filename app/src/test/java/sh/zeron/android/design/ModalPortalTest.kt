package sh.zeron.android.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.zeron.android.design.glass.GlassMenuHost
import sh.zeron.android.design.glass.GlassMenuState
import sh.zeron.android.design.glass.Menu
import sh.zeron.android.design.glass.MenuItem
import sh.zeron.android.design.glass.rememberGlassMenuState
import sh.zeron.android.design.sheet.GlassSheet

/**
 * Decision D7 (#62): every overlay is a Compose Unstyled `Modal` rendered
 * **in-window** — never a separate dialog window, which would cost the
 * glass its blur. These pin it on the real hosts, standalone (each mounts
 * its own `ModalHost` through `EnsureModalHost`) and under the root
 * layer: one compose root, the overlay in it, `dialog()` semantics on the
 * modal root. They also pin the outside-tap rules that now run through
 * `UnstyledDialog` (the alert's "cancel action or nothing", the action
 * sheet's cancel). The contract notes are the #1 lab findings, moved onto
 * the components the app ships (the lab's catalog test is gone with it).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class ModalPortalTest {

    @get:Rule
    val compose = createComposeRule()

    private fun assertOneWindow() {
        compose.onAllNodes(isRoot()).assertCountEquals(1)
    }

    /** A tap far outside any centred card or bottom sheet: the top-left corner. */
    private fun tapOutside() {
        compose.onRoot().performTouchInput { click(Offset(8f, 8f)) }
        compose.waitForIdle()
    }

    @Test
    fun theAlertIsAnInWindowDialogAndItsOutsideTapRunsTheCancelAction() {
        val alerts = AlertHostState()
        var cancelled = 0
        compose.setContent {
            ZeronTheme(Appearance.Light) { AlertHost(alerts) { Box(Modifier.fillMaxSize()) } }
        }
        compose.runOnIdle {
            alerts.show(
                ZeronAlert(
                    title = "Delete session?",
                    buttons = listOf(
                        AlertButton("Cancel", role = AlertButtonRole.Cancel, onClick = { cancelled++ }),
                        AlertButton("Delete", role = AlertButtonRole.Destructive),
                    ),
                ),
            )
        }
        compose.waitForIdle()
        compose.onNodeWithTag("alert").assertExists()
        assertOneWindow()
        compose.onAllNodes(isDialog()).assertCountEquals(1)

        tapOutside()
        assertEquals("the outside tap runs the cancel action", 1, cancelled)
        compose.onNodeWithTag("alert").assertDoesNotExist()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
    }

    @Test
    fun anAlertWithoutACancelActionIgnoresTheOutsideTap() {
        val alerts = AlertHostState()
        compose.setContent {
            ZeronTheme(Appearance.Light) { AlertHost(alerts) { Box(Modifier.fillMaxSize()) } }
        }
        // Two plain buttons: no cancel action (a lone button would be one —
        // the acknowledgement "OK", AlertRules' cancelActionIndex).
        compose.runOnIdle {
            alerts.show(ZeronAlert(title = "Update ready", buttons = listOf(AlertButton("Later"), AlertButton("Update"))))
        }
        compose.waitForIdle()

        tapOutside()
        compose.onNodeWithTag("alert").assertExists()
    }

    @Test
    fun theActionSheetIsAnInWindowDialogAndItsOutsideTapCancels() {
        val sheets = ActionSheetHostState()
        var cancelled = 0
        compose.setContent {
            ZeronTheme(Appearance.Light) { ActionSheetHost(sheets) { Box(Modifier.fillMaxSize()) } }
        }
        compose.runOnIdle {
            sheets.show(
                ActionSheetModel(
                    title = "Wallpaper Effect",
                    actions = listOf(ActionSheetAction("None") {}, ActionSheetAction("Dither") {}),
                    onCancel = { cancelled++ },
                ),
            )
        }
        compose.waitForIdle()
        compose.onNodeWithTag("action-sheet").assertExists()
        assertOneWindow()
        compose.onAllNodes(isDialog()).assertCountEquals(1)

        tapOutside()
        assertEquals("the outside tap cancels", 1, cancelled)
        compose.onNodeWithTag("action-sheet").assertDoesNotExist()
    }

    @Test
    fun theGlassMenuOpensInWindow() {
        lateinit var menus: GlassMenuState
        compose.setContent {
            ZeronTheme(Appearance.Light) {
                GlassMenuHost {
                    menus = rememberGlassMenuState()
                    Box(Modifier.fillMaxSize())
                }
            }
        }
        compose.runOnIdle {
            menus.open(
                Rect(100f, 100f, 200f, 150f),
                Menu(children = listOf(MenuItem("Pin") {}, MenuItem("Archive", destructive = true) {})),
            )
        }
        compose.waitForIdle()
        compose.onNodeWithTag("glass-menu").assertExists()
        assertOneWindow()
        compose.onAllNodes(isDialog()).assertCountEquals(1)
    }

    @Test
    fun theGlassSheetDrawsInlineWithoutAHostAndInTheLayerUnderOne() {
        compose.setContent {
            ZeronTheme(Appearance.Light) {
                Box(Modifier.fillMaxSize()) {
                    GlassSheet(onDismiss = {}, title = "Inline") { BasicText("inline") }
                }
            }
        }
        compose.waitForIdle()
        compose.onAllNodesWithTag("glass-sheet").assertCountEquals(1)
        assertOneWindow()
    }

    @Test
    fun theGlassSheetUnderAModalHostStaysInTheSameWindow() {
        compose.setContent {
            ZeronTheme(Appearance.Light) {
                ZeronModalHost {
                    GlassSheet(onDismiss = {}, title = "Portal") { BasicText("portal") }
                }
            }
        }
        compose.waitForIdle()
        compose.onAllNodesWithTag("glass-sheet").assertCountEquals(1)
        assertOneWindow()
        compose.onAllNodes(isDialog()).assertCountEquals(1)
    }
}
