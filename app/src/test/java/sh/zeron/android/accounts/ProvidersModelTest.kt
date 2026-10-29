package sh.zeron.android.accounts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.zeron.android.core.CloudStore
import uniffi.zeron_core.AgentLoginRules
import uniffi.zeron_core.CloudAccountInfo
import uniffi.zeron_core.CloudUsageInfo
import uniffi.zeron_core.DeviceAccountInfo
import uniffi.zeron_core.DeviceView
import uniffi.zeron_core.HarnessInfo
import uniffi.zeron_core.LoginOptionInfo
import uniffi.zeron_core.UsageWindowInfo

/**
 * Settings → Providers and one agent's accounts (#50, iOS
 * `ProvidersViewController` / `AgentAccountsViewController`): which rows
 * show and what they say. Plain JVM over the generated records.
 */
class ProvidersModelTest {

    private fun device(id: String, online: Boolean) = DeviceView(
        id = id, name = "Mac $id", platform = "macos", online = online, lastSeenMs = null, version = null,
        capabilities = emptyList(), isExecutionHost = true, isSelf = false, sessionCount = 0u,
    )

    private fun harness(id: String, label: String, installed: Boolean = true, offered: Boolean = true) = HarnessInfo(
        id = id, label = label, supportsSteering = null, steeringMode = null, reasoningLevels = emptyList(),
        installed = installed, enabled = null, offered = offered, midTurnSteering = null,
    )

    private fun account(
        id: String,
        harness: String,
        email: String? = null,
        active: Boolean = false,
        apiKey: Boolean = false,
        plan: String? = null,
        org: String? = null,
        provider: String? = null,
        usage: List<UsageWindowInfo> = emptyList(),
        usageError: String? = null,
        switchable: Boolean = true,
    ) = DeviceAccountInfo(
        id = id, harness = harness, email = email, displayName = null, planLabel = plan, active = active,
        switchable = switchable, apiKey = apiKey, organization = org, provider = provider, usage = usage,
        usageError = usageError,
    )

    private fun option(provider: String?, label: String = "Option") =
        LoginOptionInfo(provider = provider, label = label, firstTitle = "Sign in with $label", moreTitle = "Add another $label account")

    private fun rules(
        signsIn: Boolean = true,
        switches: Boolean = true,
        keepsOne: Boolean = false,
        usage: Boolean = true,
        options: List<LoginOptionInfo> = listOf(option("claude", "Claude")),
        note: String? = null,
    ) = AgentLoginRules(
        name = "Claude Code", signsIn = signsIn, switches = switches, keepsOneLogin = keepsOne,
        reportsUsage = usage, note = note, options = options,
    )

    // ------------------------------------------------------------ the target

    @Test
    fun theInitialTargetFollowsIos() {
        val computers = listOf(device("a", online = false), device("b", online = true))
        assertEquals("x", initialProvidersTarget("x", "b", computers))
        assertEquals("a", initialProvidersTarget(null, "a", computers))
        assertEquals(CloudTarget, initialProvidersTarget(null, CloudTarget, computers))
        assertEquals("a stale memory is ignored", "b", initialProvidersTarget(null, "gone", computers))
        assertEquals("b", initialProvidersTarget(null, null, computers))
        assertEquals("a", initialProvidersTarget(null, null, listOf(device("a", online = false))))
        assertEquals(CloudTarget, initialProvidersTarget(null, null, emptyList()))
    }

    @Test
    fun accountTitlesAndSummaries() {
        assertEquals("a@b.c", accountTitle(account("1", "h", email = "a@b.c")))
        assertEquals("API key", accountTitle(account("1", "h", apiKey = true)))
        assertEquals("Account", accountTitle(account("1", "h")))
        assertEquals("a · 3 accounts", accountsSummary("a", 3))
        assertEquals("a", accountsSummary("a", 1))
        assertEquals("No account", accountsSummary(null, 0))
        assertNull(accountsSummary(null, 0, showsNone = false))
        assertEquals("1 account", accountsSummary(null, 1))
        assertEquals("2 accounts", accountsSummary(null, 2))
    }

    // ------------------------------------------------------------ a computer

    @Test
    fun computerRowsSortLockAndSummarize() {
        val list = listOf(
            harness("codex", "Codex", installed = false, offered = false),
            harness("claude-code", "Claude Code"),
            harness("amp", "Amp", offered = false),
        )
        val accounts = listOf(account("1", "claude-code", email = "me@x.io", active = true), account("2", "claude-code"))
        val rows = computerRows("m", list, accounts, null, online = true, toggling = setOf("amp"), rules = { rules() })
        assertEquals("installed first, then by name", listOf("Amp", "Claude Code", "Codex"), rows.map { it.title })
        val claude = rows[1]
        assertEquals("me@x.io · 2 accounts", claude.subtitle)
        assertEquals(true, claude.toggle)
        assertFalse("the last agent on stays on", claude.toggleEnabled)
        assertTrue(claude.disclosure)
        assertTrue("toggling shows a spinner", rows[0].busy)
        val codex = rows[2]
        assertEquals("Not installed", codex.subtitle)
        assertTrue(codex.dimmed)
        assertEquals(false, codex.toggle)
        assertFalse(codex.toggleEnabled)
        assertFalse(codex.disclosure)
    }

    @Test
    fun computerRowsBeforeAccountsLandAndOffline() {
        val list = listOf(harness("claude-code", "Claude Code"), harness("codex", "Codex"))
        assertEquals("Loading…", computerRows("m", null, null, null, true, emptySet()) { rules() }.single().title)
        assertEquals("Installed", computerRows("m", list, null, null, true, emptySet()) { rules() }[0].subtitle)
        assertEquals("boom", computerRows("m", list, null, "boom", true, emptySet()) { rules() }[0].subtitle)
        val offline = computerRows("m", list, null, null, false, emptySet()) { rules() }
        assertEquals("Offline", offline[0].subtitle)
        assertFalse("offline: locked", offline[0].toggleEnabled)
    }

    // ------------------------------------------------------------ Cloud

    private val claude = CloudStore.agents.first()

    private fun cloudAccount(slot: String, kind: String = "login", email: String? = null, plan: String? = null, active: Boolean = false, reconnect: Boolean = false) =
        CloudAccountInfo(slot = slot, active = active, kind = kind, email = email, displayName = null, organization = null, plan = plan, needsReconnect = reconnect)

    @Test
    fun cloudRowsPlaceholdersAndSummaries() {
        assertEquals("Loading…", cloudRows(offered = false, demo = false, infoKnown = false, accounts = emptyMap(), signingIn = null).single().title)
        assertEquals("No computers yet", cloudRows(false, false, true, emptyMap(), null).single().title)

        val accounts = mapOf(claude.login to listOf(cloudAccount("s1", email = "me@x.io", plan = "Max", active = true), cloudAccount("s2")))
        val rows = cloudRows(true, false, true, accounts, signingIn = null)
        assertEquals(CloudStore.agents.size, rows.size)
        val first = rows.first { it.harness == claude.harness }
        assertEquals("me@x.io · Max · 2 accounts", first.subtitle)
        assertFalse(first.signIn)
        assertTrue(first.disclosure)
        val other = rows.first { it.harness != claude.harness }
        assertTrue("nothing signed in: a Sign in capsule", other.signIn)
        assertEquals("No account", other.subtitle)

        val busy = cloudRows(true, false, true, emptyMap(), signingIn = claude.login).first { it.harness == claude.harness }
        assertEquals("Finishing sign-in…", busy.subtitle)
        assertTrue(busy.busy)
    }

    // ------------------------------------------------------------ one agent's accounts

    @Test
    fun deviceAccountItemsFollowTheAgentsRules() {
        val window = UsageWindowInfo(label = "Session", usedFraction = 0.34f, resetsAtMs = null)
        val list = listOf(
            account("1", "claude-code", email = "me@x.io", plan = "Max", org = "Acme", provider = "claude", usage = listOf(window), active = true),
            account("2", "codex"),
        )
        val two = rules(options = listOf(option("claude", "Claude"), option("console", "Console")))
        val item = deviceAccountItems(list, "claude-code", two).single()
        assertEquals("Max · Acme · Claude", item.detail)
        assertEquals(listOf(window), item.usage)
        assertTrue(item.switchable)
        // One option: no option label; no usage when the agent doesn't report it; no switching when it doesn't switch.
        val plain = deviceAccountItems(list, "claude-code", rules(usage = false, switches = false)).single()
        assertEquals("Max · Acme", plain.detail)
        assertTrue(plain.usage.isEmpty())
        assertFalse(plain.switchable)
    }

    @Test
    fun cloudAccountItemsPreferTheLiveUsageAndFlagReconnects() {
        val key = "${claude.login}:s1"
        val accounts = mapOf(
            claude.login to listOf(cloudAccount("s1", email = "me@x.io", plan = "Pro", active = true), cloudAccount("s2", reconnect = true)),
            claude.key to listOf(cloudAccount("k1", kind = "key")),
        )
        val usage = mapOf(key to CloudUsageInfo(windows = emptyList(), plan = "Max"))
        val items = cloudAccountItems(claude, accounts, usage, mapOf(key to "no usage"))
        assertEquals(listOf("me@x.io", "${claude.name} account", claude.keyName), items.map { it.title })
        assertEquals("Max", items[0].detail)
        assertEquals("no usage", items[0].usageNote)
        assertEquals("Sign in again", items[1].usageNote)
        assertTrue(items[2].isKey)
        assertEquals(claude.key, items[2].cloudProvider)
    }

    @Test
    fun addRowsAndTheCardsItems() {
        val r = rules(options = listOf(option("claude", "Claude")))
        assertEquals(listOf("Sign in with Claude"), addRows(empty = true, rules = r, cloudKeyName = null).map { it.title })
        assertEquals(listOf("Add another Claude account"), addRows(false, r, null).map { it.title })
        assertTrue("keeps one login and has it", addRows(false, rules(keepsOne = true), null).isEmpty())
        assertEquals(listOf("add:claude", "add-key"), addRows(false, r, "Anthropic API key").map { it.id })

        val adds = addRows(true, r, null)
        assertEquals(
            listOf("placeholder", "add:claude"),
            accountsPageItems(device = true, loaded = false, loadError = null, accounts = emptyList(), adds = adds, signingIn = false).map { it.key },
        )
        val failed = accountsPageItems(true, false, "offline", emptyList(), adds, false).first() as AccountsPageItem.Placeholder
        assertEquals("Couldn't load accounts", failed.text)
        assertEquals("offline", failed.detail)
        assertEquals(
            listOf("add:claude", "waiting"),
            accountsPageItems(true, true, null, emptyList(), adds, signingIn = true).map { it.key },
        )
    }

    @Test
    fun meterTextAndDeviceCodes() {
        val now = 1_000_000L
        assertEquals("34%", usageMeterText(UsageWindowInfo("Session", 0.34f, null), now))
        assertEquals("100% · 45m", usageMeterText(UsageWindowInfo("Week", 1.4f, now + 45 * 60_000L), now))
        assertEquals("5% · 3h", usageMeterText(UsageWindowInfo("Week", 0.05f, now + 3 * 3_600_000L + 1_000L), now))
        assertEquals("0% · 4d", usageMeterText(UsageWindowInfo("Month", 0f, now + 4 * 86_400_000L), now))
        assertEquals("ABCD-1234", deviceCodeIn("Enter the code ABCD-1234 when asked"))
        assertNull(deviceCodeIn("Waiting for the browser"))
    }

    @Test
    fun theFootersReadLikeIos() {
        assertTrue(ProvidersCopy.footer(onCloud = true, online = true, name = "Cloud").startsWith("Cloud agents use the account marked in use."))
        assertEquals("Mac is offline — showing what it last reported.", ProvidersCopy.footer(false, false, "Mac"))
        assertEquals(
            "A note\n\nAccount changes affect new sessions on Mac. Signing in runs on Mac; you finish in the browser here.",
            AccountsCopy.footer("A note", cloud = false, deviceName = "Mac"),
        )
    }
}
