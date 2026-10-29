package sh.zeron.android.onboarding

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The desktop's first-run steps (`OnboardingViewController.Step`): make it
 * yours, sync (log in), GitHub, your agents, notifications, then "You're
 * all set". The first two run signed out from the Welcome page; the rest
 * pick up after logging in.
 */
enum class OnboardingStep { Appearance, Sync, GitHub, Agents, Notifications, Done }

/** The steps, in order (#54 brought GitHub and Agents back). */
internal val ShownSteps: List<OnboardingStep> = OnboardingStep.entries

/** The numbered steps ("2 of 5"): every step but Done. */
internal val NumberedSteps: List<OnboardingStep> = ShownSteps - OnboardingStep.Done

/** "N of total" for [step]; null for Done (`Step.number`). */
internal fun OnboardingStep.number(): Int? =
    NumberedSteps.indexOf(this).takeIf { it >= 0 }?.plus(1)

internal val OnboardingStep.previous: OnboardingStep?
    get() = ShownSteps.getOrNull(ShownSteps.indexOf(this) - 1)

internal val OnboardingStep.next: OnboardingStep
    get() = ShownSteps.getOrNull(ShownSteps.indexOf(this) + 1) ?: OnboardingStep.Done

/** The first step after logging in (`.github`; Agents when Cloud is withheld). */
internal val FirstSignedInStep: OnboardingStep = ShownSteps[ShownSteps.indexOf(OnboardingStep.Sync) + 1]

/**
 * What onboarding remembers: the look is picked once per phone
 * (`onboarding.appearanceSeen`), the flow is finished once per user on
 * this phone (`onboarded:<userId>`).
 */
interface OnboardingPrefs {
    var appearanceSeen: Boolean

    fun onboarded(userId: String): Boolean

    fun markOnboarded(userId: String)
}

/** [OnboardingPrefs] in the `onboarding` prefs file, iOS's keys. */
class SharedOnboardingPrefs(context: Context) : OnboardingPrefs {
    private val prefs = context.applicationContext.getSharedPreferences("onboarding", Context.MODE_PRIVATE)

    override var appearanceSeen: Boolean
        get() = prefs.getBoolean(APPEARANCE_SEEN, false)
        set(value) {
            prefs.edit().putBoolean(APPEARANCE_SEEN, value).apply()
        }

    override fun onboarded(userId: String): Boolean = prefs.getBoolean(key(userId), false)

    override fun markOnboarded(userId: String) {
        prefs.edit().putBoolean(key(userId), true).apply()
    }

    private fun key(userId: String) = "onboarded:$userId"

    private companion object {
        const val APPEARANCE_SEEN = "onboarding.appearanceSeen"
    }
}

/** Where onboarding is: hidden, or a step, and whether it got there animated. */
data class OnboardingScreen(val step: OnboardingStep, val animated: Boolean)

/**
 * The onboarding flow's state (#17): the port of `OnboardingViewController`'s
 * navigation plus `SceneDelegate.showOnboardingIfNeeded` and
 * `SignInViewController.getStarted`. App-scoped, so the step survives the
 * activity and the Welcome → shell root swap underneath it.
 *
 * - Welcome's Get started opens it on Appearance, or on Sync once this
 *   phone has seen Appearance.
 * - A client appearing (sign-in, or a launch with a stored account) moves
 *   it to the first signed-in step without animation — or closes it when
 *   this user has already finished onboarding on this phone (and for the
 *   demo). A sign-out closes it, as iOS's root swap dismisses it.
 * - Finishing marks the user onboarded.
 * - [cloudWithheld] (the edge answered that this account has no Cloud,
 *   `OnboardingViewController.cloudWithheld`): GitHub is skipped both ways,
 *   and a status landing while it is on screen moves on to Agents (J2).
 *   The numbering stays "N of 5", as on iOS.
 */
class OnboardingModel(
    private val prefs: OnboardingPrefs,
    private val cloudWithheld: () -> Boolean = { false },
) {

    private val _screen = MutableStateFlow<OnboardingScreen?>(null)

    /** The step on screen; null while onboarding is not shown. */
    val screen: StateFlow<OnboardingScreen?> = _screen.asStateFlow()

    private var userId: String? = null

    /** "Get started" on the Welcome page: a cross-dissolve into the first step that applies. */
    fun getStarted() {
        val first = if (prefs.appearanceSeen) OnboardingStep.Sync else OnboardingStep.Appearance
        _screen.value = OnboardingScreen(first, animated = true)
    }

    /** Moves to [step] (Continue, Back, Skip); leaving Appearance marks it seen. */
    fun go(step: OnboardingStep) {
        val current = _screen.value ?: return
        if (current.step == OnboardingStep.Appearance) prefs.appearanceSeen = true
        _screen.value = OnboardingScreen(forward(step), animated = true)
    }

    /** Cloud's status arrived (`refresh()`): on GitHub with Cloud withheld, move past it. */
    fun onCloudStatus() {
        if (_screen.value?.step == OnboardingStep.GitHub && cloudWithheld()) go(OnboardingStep.Agents)
    }

    /** GitHub is a Cloud step: withheld, the way forward lands on Agents. */
    private fun forward(step: OnboardingStep): OnboardingStep =
        if (step == OnboardingStep.GitHub && cloudWithheld()) OnboardingStep.Agents else step

    /**
     * Back (the footer's or the system's): the previous step, or — on the
     * first step while signed out — back to the Welcome page. Signed in on
     * the first step there is nowhere to go back to.
     */
    fun back(signedIn: Boolean) {
        val current = _screen.value ?: return
        // Back from Agents skips a withheld GitHub, to Sync.
        val previous = current.step.previous
            ?.let { if (it == OnboardingStep.GitHub && cloudWithheld()) OnboardingStep.Sync else it }
        when {
            previous != null -> go(previous)
            !signedIn -> _screen.value = null
        }
    }

    /**
     * The session holder's client changed: [userId] is the signed-in user
     * (null when signed out), [demo] the demo workspace.
     */
    fun onAccount(userId: String?, demo: Boolean) {
        this.userId = userId
        _screen.value = when {
            userId == null -> null
            demo || prefs.onboarded(userId) -> null
            // Straight on from the sign-in steps: no transition.
            else -> OnboardingScreen(forward(FirstSignedInStep), animated = false)
        }
    }

    /** "Start a session" / "I'll do this later" on Done: never shown again for this user here. */
    fun finish() {
        userId?.let(prefs::markOnboarded)
        _screen.value = null
    }
}
