package sh.zeron.android.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The onboarding flow (#17, `OnboardingViewController` navigation and
 * `showOnboardingIfNeeded`) on the plain JVM: the first step from Welcome,
 * appearanceSeen, Back, the hand-over at sign-in, onboarded:<userId>, the
 * five numbered steps, and the Cloud-withheld skips of GitHub (#54, J2).
 */
class OnboardingModelTest {

    private class MemoryPrefs : OnboardingPrefs {
        override var appearanceSeen = false
        val done = mutableSetOf<String>()

        override fun onboarded(userId: String) = userId in done

        override fun markOnboarded(userId: String) {
            done += userId
        }
    }

    private val prefs = MemoryPrefs()
    private var withheld = false
    private val model = OnboardingModel(prefs, cloudWithheld = { withheld })

    private val step get() = model.screen.value?.step

    @Test
    fun getStartedOpensOnAppearanceTheFirstTime() {
        model.getStarted()
        assertEquals(OnboardingScreen(OnboardingStep.Appearance, animated = true), model.screen.value)
    }

    @Test
    fun leavingAppearanceMarksItSeenSoTheNextTimeStartsOnSync() {
        model.getStarted()
        model.go(OnboardingStep.Sync)
        assertTrue(prefs.appearanceSeen)

        model.back(signedIn = false)
        model.back(signedIn = false) // Appearance → Welcome
        assertNull(model.screen.value)

        model.getStarted()
        assertEquals(OnboardingStep.Sync, step)
    }

    @Test
    fun backOnTheFirstStepSignedOutReturnsToWelcome() {
        prefs.appearanceSeen = true
        model.getStarted()
        model.back(signedIn = false)
        // Sync's previous is Appearance, even when it was skipped.
        assertEquals(OnboardingStep.Appearance, step)
        model.back(signedIn = false)
        assertNull(model.screen.value)
    }

    @Test
    fun backOnTheFirstStepSignedInStays() {
        model.getStarted()
        model.back(signedIn = true)
        assertEquals(OnboardingStep.Appearance, step)
    }

    @Test
    fun signingInPicksUpAtTheFirstSignedInStepWithoutATransition() {
        model.getStarted()
        model.go(OnboardingStep.Sync)

        model.onAccount("user_1", demo = false)

        assertEquals(OnboardingScreen(OnboardingStep.GitHub, animated = false), model.screen.value)
    }

    @Test
    fun aLaunchWithAnAccountThatHasNotFinishedShowsIt() {
        model.onAccount("user_1", demo = false)
        assertEquals(OnboardingStep.GitHub, step)
    }

    @Test
    fun aUserWhoFinishedHereSkipsIt() {
        prefs.done += "user_1"
        model.getStarted()
        model.go(OnboardingStep.Sync)

        model.onAccount("user_1", demo = false)

        assertNull(model.screen.value)
    }

    @Test
    fun theDemoNeverShowsIt() {
        model.onAccount("demo", demo = true)
        assertNull(model.screen.value)
    }

    @Test
    fun signingOutClosesIt() {
        model.onAccount("user_1", demo = false)
        model.onAccount(null, demo = false)
        assertNull(model.screen.value)
    }

    @Test
    fun finishingMarksThisUserOnly() {
        model.onAccount("user_1", demo = false)
        model.go(OnboardingStep.Done)

        model.finish()

        assertNull(model.screen.value)
        assertEquals(setOf("user_1"), prefs.done)
        model.onAccount("user_2", demo = false)
        assertEquals(OnboardingStep.GitHub, step)
    }

    @Test
    fun goingAnywhereWhileHiddenDoesNothing() {
        model.go(OnboardingStep.Done)
        model.back(signedIn = false)
        assertNull(model.screen.value)
        assertFalse(prefs.appearanceSeen)
    }

    @Test
    fun theFiveStepsAreNumberedAsOnIos() {
        assertEquals(1, OnboardingStep.Appearance.number())
        assertEquals(2, OnboardingStep.Sync.number())
        assertEquals(3, OnboardingStep.GitHub.number())
        assertEquals(4, OnboardingStep.Agents.number())
        assertEquals(5, OnboardingStep.Notifications.number())
        assertNull(OnboardingStep.Done.number())
        assertEquals(5, NumberedSteps.size)
        assertEquals(OnboardingStep.GitHub, OnboardingStep.Sync.next)
        assertEquals(OnboardingStep.Agents, OnboardingStep.GitHub.next)
        assertEquals(OnboardingStep.Notifications, OnboardingStep.Agents.next)
        assertEquals(OnboardingStep.Done, OnboardingStep.Notifications.next)
        assertEquals(OnboardingStep.Agents, OnboardingStep.Notifications.previous)
    }

    // ── Cloud withheld (J2) ──────────────────────────────────────────────

    @Test
    fun withheldSigningInLandsOnAgents() {
        withheld = true
        model.onAccount("user_1", demo = false)
        assertEquals(OnboardingStep.Agents, step)
    }

    @Test
    fun withheldContinueFromSyncSkipsGitHubAndBackFromAgentsReturnsToSync() {
        withheld = true
        prefs.appearanceSeen = true
        model.getStarted()
        model.go(OnboardingStep.Sync.next)
        assertEquals(OnboardingStep.Agents, step)
        model.back(signedIn = true)
        assertEquals(OnboardingStep.Sync, step)
    }

    @Test
    fun aStatusLandingOnGitHubMovesOnOnlyWhenWithheld() {
        model.onAccount("user_1", demo = false)
        model.onCloudStatus()
        assertEquals("offered (or unknown): stay", OnboardingStep.GitHub, step)
        withheld = true
        model.onCloudStatus()
        assertEquals(OnboardingStep.Agents, step)
        // Anywhere else, a status changes nothing.
        model.go(OnboardingStep.Notifications)
        model.onCloudStatus()
        assertEquals(OnboardingStep.Notifications, step)
    }

    @Test
    fun offeredBackFromAgentsIsGitHub() {
        model.onAccount("user_1", demo = false)
        model.go(OnboardingStep.Agents)
        model.back(signedIn = true)
        assertEquals(OnboardingStep.GitHub, step)
    }

    // ── The Agents heading (`onSummary`) ─────────────────────────────────

    @Test
    fun theAgentsHeadingFollowsWhatProvidersFound() {
        assertEquals("Looking for coding agents on Studio\u2026", agentsDetail("Studio", cloud = false, names = null))
        assertEquals(
            "Cloud runs Claude Code and Codex with your own subscriptions. Sign in to any you'd like to use.",
            agentsDetail("Cloud", cloud = true, names = listOf("Claude Code", "Codex")),
        )
        assertEquals(
            "Zeron runs the coding agents you already use, and none are on Studio yet.",
            agentsDetail("Studio", cloud = false, names = emptyList()),
        )
        assertEquals(
            "Found Claude Code on Studio. Turn on the ones you'd like to use.",
            agentsDetail("Studio", cloud = false, names = listOf("Claude Code")),
        )
        assertEquals(
            "Found 4 agents on Studio. Turn on the ones you'd like to use.",
            agentsDetail("Studio", cloud = false, names = listOf("A", "B", "C", "D")),
        )
    }

    @Test
    fun namesJoinTheIosWay() {
        assertEquals("", joinNames(emptyList()))
        assertEquals("A", joinNames(listOf("A")))
        assertEquals("A and B", joinNames(listOf("A", "B")))
        assertEquals("A, B and C", joinNames(listOf("A", "B", "C")))
    }
}
