package sh.zeron.android.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.zeron_core.BillingInfo
import uniffi.zeron_core.BillingPlanInfo
import uniffi.zeron_core.GithubRepoInfo

/**
 * Billing's rules and the repo picker's (#52, iOS `BillingViewController` /
 * `CloudRepoPickerViewController`) on the plain JVM.
 */
class BillingAndReposTest {

    private val starter = BillingPlanInfo(id = "starter", name = "Starter", priceCents = 2000u, monthlyCredits = 6000.0)
    private val pro = BillingPlanInfo(id = "pro", name = "Pro", priceCents = 5000u, monthlyCredits = 18000.0)

    private fun billing(
        plan: BillingPlanInfo? = null,
        status: String? = null,
        periodEnd: Long? = null,
        cancel: Boolean = false,
        monthly: Double = 0.0,
        monthlyTotal: Double = 0.0,
        monthlyEnds: Long? = null,
        extra: Double = 0.0,
    ) = BillingInfo(
        available = true,
        plan = plan,
        planStatus = status,
        periodEndMs = periodEnd,
        cancelAtPeriodEnd = cancel,
        creditsTotal = monthly + extra,
        creditsMonthly = monthly,
        creditsMonthlyTotal = monthlyTotal,
        creditsMonthlyEndsMs = monthlyEnds,
        creditsExtra = extra,
        plans = listOf(starter, pro),
        packPriceCents = 1000u,
        packCredits = 3000.0,
        hasCustomer = plan != null,
    )

    @Test
    fun hoursAreCreditsOverSixtyRounded() {
        assertEquals("about 100 hours", BillingRules.hours(6000.0))
        assertEquals("about 1 hours", BillingRules.hours(89.0))
        assertEquals("about 0 hours", BillingRules.hours(10.0))
        assertEquals("credit", BillingRules.unit(1.0))
        assertEquals("credits", BillingRules.unit(0.0))
    }

    @Test
    fun planCreditsSayTheAllowanceOrAskForAPlan() {
        assertEquals("No plan yet — choose one below" to "—", BillingRules.planCredits(billing()))
        val (caption, value) = BillingRules.planCredits(billing(monthly = 1500.0, monthlyTotal = 6000.0))
        assertTrue(caption.startsWith("Of 6"))
        assertFalse(caption.contains("resets"))
        assertTrue(value.startsWith("1"))
        assertTrue(BillingRules.planCredits(billing(monthlyTotal = 6000.0, monthlyEnds = 0L)).first.contains(" · resets "))
    }

    @Test
    fun planButtonsCheckoutWithoutAPlanAndThePortalOnOne() {
        val none0 = BillingRules.planAction(starter, 0, current = null, busy = null, available = true)
        assertEquals(PlanAction("Choose Starter", BillingTone.Solid, true, PlanActionKind.Checkout), none0)
        assertEquals(BillingTone.Filled, BillingRules.planAction(pro, 1, null, null, true).tone)

        val current = BillingRules.planAction(starter, 0, current = "starter", busy = null, available = true)
        assertEquals(PlanAction("Current plan", BillingTone.Filled, false, PlanActionKind.None), current)
        val switch = BillingRules.planAction(pro, 1, current = "starter", busy = null, available = true)
        assertEquals(PlanAction("Switch", BillingTone.Filled, true, PlanActionKind.Portal), switch)

        // Busy: "Opening…" on its own button, everything else disabled.
        assertEquals("Opening…", BillingRules.planAction(pro, 1, "starter", busy = "plan:pro", available = true).title)
        assertFalse(BillingRules.planAction(pro, 1, null, busy = "credits", available = true).enabled)
        assertFalse(BillingRules.planAction(pro, 1, null, busy = null, available = false).enabled)
    }

    @Test
    fun theCurrentPlanLineWarnsWhenPastDue() {
        assertEquals(BillingCopy.PastDue to true, BillingRules.currentPlanDetail(billing(starter, status = "past_due", periodEnd = 0L)))
        assertEquals("Won't renew." to false, BillingRules.currentPlanDetail(billing(starter, cancel = true)))
        assertTrue(BillingRules.currentPlanDetail(billing(starter, cancel = true, periodEnd = 0L)).first.endsWith(". You keep its credits until then."))
        assertEquals("Renews monthly." to false, BillingRules.currentPlanDetail(billing(starter)))
        assertTrue(BillingRules.currentPlanDetail(billing(starter, periodEnd = 0L)).first.startsWith("Renews "))
    }

    @Test
    fun theSplitBarAndTheStepper() {
        assertNull(BillingRules.splitShares(0.0, 0.0))
        assertEquals(listOf(0.75f, 0.25f), BillingRules.splitShares(300.0, 100.0))
        assertEquals(listOf(0f, 1f), BillingRules.splitShares(-5.0, 100.0))
        assertEquals(1, BillingRules.step(1, -1))
        assertEquals(2, BillingRules.step(1, 1))
        assertEquals(BILLING_MAX_PACKS, BillingRules.step(BILLING_MAX_PACKS, 1))
    }

    @Test
    fun theWebhookFingerprintIsThePlanAndTheTotal() {
        assertEquals(null to null, BillingRules.fingerprint(null))
        assertEquals("starter" to 6000.0, BillingRules.fingerprint(billing(starter, monthly = 6000.0)))
    }

    // ── the repo picker ────────────────────────────────────────────────

    private fun repo(name: String, pushed: Long, private: Boolean = false, description: String? = null) =
        GithubRepoInfo(fullName = name, defaultBranch = "main", private = private, description = description, pushedAtMs = pushed)

    @Test
    fun rowsAreNewestFirstAndFilterOnTheName() {
        val repos = listOf(repo("acme/api", 1), repo("acme/Web", 3), repo("me/notes", 2))
        assertEquals(listOf("acme/Web", "me/notes", "acme/api"), repoPickerRows(repos, "").map { it.fullName })
        assertEquals(listOf("acme/Web", "acme/api"), repoPickerRows(repos, "  ACME ").map { it.fullName })
        assertEquals(emptyList<GithubRepoInfo>(), repoPickerRows(null, ""))
    }

    @Test
    fun theEmptyStateAndTheRowDetail() {
        assertEquals(RepoPickerEmpty.Loading, repoPickerEmpty(null, loading = true, githubConnected = true))
        assertEquals(RepoPickerEmpty.Failed, repoPickerEmpty(null, loading = false, githubConnected = true))
        assertEquals(RepoPickerEmpty.NoRepos, repoPickerEmpty(emptyList(), loading = false, githubConnected = true))
        assertEquals(RepoPickerEmpty.Connect, repoPickerEmpty(emptyList(), loading = false, githubConnected = false))

        assertEquals("Private · The API", repoDetail(repo("a/b", 0, private = true, description = "The API")))
        assertEquals("Private", repoDetail(repo("a/b", 0, private = true)))
        assertEquals("The API", repoDetail(repo("a/b", 0, description = "The API")))
        assertNull(repoDetail(repo("a/b", 0, description = "")))
    }
}
