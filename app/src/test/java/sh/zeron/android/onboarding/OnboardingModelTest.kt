package sh.zeron.android.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The onboarding flow (#17, `OnboardingViewController` navigation and
 * `showOnboardingIfNeeded`) on the plain JVM: the first step from Welcome,
 * appearanceSeen, Back, the hand-over at sign-in, onboarded:<userId>, and
 * the step numbering while the Cloud steps (#54) are out.
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
    private val model = OnboardingModel(prefs)

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

        assertEquals(OnboardingScreen(OnboardingStep.Notifications, animated = false), model.screen.value)
    }

    @Test
    fun aLaunchWithAnAccountThatHasNotFinishedShowsIt() {
        model.onAccount("user_1", demo = false)
        assertEquals(OnboardingStep.Notifications, step)
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
        assertEquals(OnboardingStep.Notifications, step)
    }

    @Test
    fun goingAnywhereWhileHiddenDoesNothing() {
        model.go(OnboardingStep.Done)
        model.back(signedIn = false)
        assertNull(model.screen.value)
        assertFalse(prefs.appearanceSeen)
    }

    @Test
    fun theShownStepsAreNumberedWithoutTheCloudOnes() {
        assertEquals(1, OnboardingStep.Appearance.number())
        assertEquals(2, OnboardingStep.Sync.number())
        assertEquals(3, OnboardingStep.Notifications.number())
        assertNull(OnboardingStep.Done.number())
        assertEquals(3, NumberedSteps.size)
        assertEquals(OnboardingStep.Notifications, OnboardingStep.Sync.next)
        assertEquals(OnboardingStep.Sync, OnboardingStep.Notifications.previous)
        assertNull(OnboardingStep.Appearance.previous)
        assertEquals(OnboardingStep.Done, OnboardingStep.Notifications.next)
    }
}
