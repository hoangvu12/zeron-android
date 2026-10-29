package sh.zeron.android.accounts

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import sh.zeron.android.design.Appearance
import sh.zeron.android.design.ZeronTheme
import sh.zeron.android.design.ZeronIcons
import sh.zeron.android.sessions.SwipeAction
import uniffi.zeron_core.AgentLoginRules
import uniffi.zeron_core.DeviceAccountInfo
import uniffi.zeron_core.HarnessInfo
import uniffi.zeron_core.LoginOptionInfo

/**
 * #133: Providers said "nguyenvu@vexly.app · 3 accounts" for Claude Code
 * while its accounts page showed only "Add account". The data agreed; the
 * page's switchable rows were laid out at 0 height (a `SwipeRow` sized by
 * `IntrinsicSize.Min`). Both halves are pinned here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h900dp-xxhdpi")
class AccountRowsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun account(id: String, email: String, active: Boolean) = DeviceAccountInfo(
        id = id, harness = "claude-code", email = email, displayName = null, planLabel = "Max", active = active,
        switchable = true, apiKey = false, organization = null, provider = "claude", usage = emptyList(),
        usageError = null,
    )

    /** The device's logins as the owner's Providers row saw them: three Claude Code accounts, one active. */
    private val logins = listOf(
        account("1", "nguyenvu@vexly.app", active = true),
        account("2", "a@example.com", active = false),
        account("3", "b@example.com", active = false),
    )

    private val rules = AgentLoginRules(
        name = "Claude Code", signsIn = true, switches = true, keepsOneLogin = false, reportsUsage = true, note = null,
        options = listOf(LoginOptionInfo(provider = "claude", label = "Claude", firstTitle = "Add account", moreTitle = "Add another account")),
    )

    @Test
    fun theSummaryAndThePageCountTheSameAccounts() {
        val harness = HarnessInfo(
            id = "claude-code", label = "Claude Code", supportsSteering = null, steeringMode = null, reasoningLevels = emptyList(),
            installed = true, enabled = null, offered = true, midTurnSteering = null,
        )
        val row = computerRows("laptop", listOf(harness), logins, null, online = true, toggling = emptySet(), rules = { rules }).single()
        assertEquals("nguyenvu@vexly.app · 3 accounts", row.subtitle)
        val items = deviceAccountItems(logins, row.harness!!, rules)
        assertEquals(3, items.size)
        val page = accountsPageItems(
            device = true, loaded = true, loadError = null, accounts = items,
            adds = addRows(items.isEmpty(), rules, null), signingIn = false,
        )
        assertEquals(3, page.count { it is AccountsPageItem.Account })
        assertEquals(listOf("Add another account"), page.filterIsInstance<AccountsPageItem.Add>().map { it.row.title })
    }

    @Test
    fun aSwipeableRowKeepsItsContentsHeight() {
        compose.setContent {
            ZeronTheme(Appearance.Dark) {
                LazyColumn {
                    item {
                        FittedSwipeRow(
                            id = "1",
                            trailing = listOf(SwipeAction(title = "Remove", icon = ZeronIcons.Trash, background = Color.Red, testTag = "remove", perform = { it(true) })),
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Box(Modifier.height(40.dp))
                                Box(Modifier.height(18.dp))
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val heightPx = compose.onNodeWithTag("swipe-row-1", useUnmergedTree = true).fetchSemanticsNode().size.height
        val density = compose.activity.resources.displayMetrics.density
        assertEquals(58f * density, heightPx.toFloat(), 1.5f)
    }
}
