package sh.zeron.android.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import sh.zeron.android.design.ZeronIcons

/**
 * Settings → Agents (#49, iOS `MoreViewController.swift:197-210`): which
 * rows show and what they say. Plain JVM; the credits formatter is faked.
 */
class AgentsRowsTest {

    private val credits: (Double) -> String = { "%,d".format(java.util.Locale.US, it.toLong()) }

    private fun rows(cloud: AgentsCloud, demo: Boolean = false, demoCloud: Boolean = false) =
        agentsRows(cloud, demo, demoCloud, credits)

    @Test
    fun providersAlwaysCloudAndBillingOnlyWhenOffered() {
        val off = rows(AgentsCloud(available = false))
        assertEquals(listOf("providers"), off.map { it.id })
        assertEquals("Agents and their accounts on each device", off.single().subtitle)
        assertEquals(ZeronIcons.Cpu, off.single().icon)

        assertEquals(listOf("providers", "cloud", "billing"), rows(AgentsCloud(available = true)).map { it.id })
    }

    @Test
    fun theDemoOffersCloudOnlyWithTheLaunchFlag() {
        assertEquals(listOf("providers"), rows(AgentsCloud(available = true), demo = true).map { it.id })
        assertEquals(
            listOf("providers", "cloud", "billing"),
            rows(AgentsCloud(available = false), demo = true, demoCloud = true).map { it.id },
        )
    }

    @Test
    fun theCloudRowSaysTheProblemElseGitHub() {
        fun cloud(c: AgentsCloud) = rows(c).first { it.id == "cloud" }.subtitle
        assertEquals("Connect GitHub", cloud(AgentsCloud(available = true)))
        assertEquals("GitHub @octocat", cloud(AgentsCloud(available = true, githubAccount = "octocat")))
        assertEquals("Out of credits", cloud(AgentsCloud(available = true, githubAccount = "octocat", problem = "Out of credits")))
    }

    @Test
    fun theBillingRowSaysPlanAndCredits() {
        fun billing(c: AgentsCloud) = rows(c).first { it.id == "billing" }.subtitle
        assertEquals("Plans and credits", billing(AgentsCloud(available = true)))
        assertEquals("Pro plan · 41,250 credits", billing(AgentsCloud(available = true, planName = "Pro", credits = 41_250.9)))
        assertEquals("Pro plan", billing(AgentsCloud(available = true, planName = "Pro")))
        assertEquals("0 credits", billing(AgentsCloud(available = true, credits = 0.0)))
    }
}
