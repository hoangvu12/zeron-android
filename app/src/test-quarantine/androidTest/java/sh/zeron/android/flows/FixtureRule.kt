package sh.zeron.android.flows

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import sh.zeron.android.MainActivity

/**
 * The instrumented flow suite's launch rule (#63, moved off the old
 * fixture engines): every test runs on the core's offline demo — iOS's
 * `-demo -fast` (`AppModel.demoOptions()`, #18's `LaunchOptions`): the
 * seeded workspace (`zeron/crates/client/src/demo/fixtures.rs` —
 * `chat-veil`, `chat-deploy`, `chat-picker` working, `chat-tabs` awaiting
 * input, `chat-errored`, the side chats `chat-side` / `chat-side-easing`
 * under `chat-veil`, …), no network, no host, no real agent.
 *
 * For every test it launches [MainActivity] with the debug extras `demo`
 * and `fast` (words stream every 4 ms), the `route` extra ([routeFor] by
 * test name — `chat:<id>` | `new` | `more` | `search`, like
 * `ios:App/SceneDelegate.swift:64-71`), and [extrasFor]'s per-test
 * extras, which win over the defaults:
 *
 * - `"fast" to false` + `"longreply" to true`: a paced, long streaming
 *   reply (what the old `slow` engine gave: queue / Stop / scroll /
 *   notification);
 * - `"big" to true` / `"huge" to true`: `chat-veil` as a 120 / 600-turn
 *   synthetic transcript (the jump-to-latest and scroll-up rows);
 * - a prompt containing `?ask` (typed by the test, not an extra) replies
 *   with one question (`transcripts::asking()`: "Should the fix cover
 *   Android too?" — "Yes, both platforms" / "iOS only"), the old
 *   `question` engine's panel.
 *
 * The extras are debug-gated (`FLAG_DEBUGGABLE`): the flow suite runs on
 * the debug APK. Booleans go as `Boolean` extras (`LaunchOptions` reads
 * both `--ez` and `--es … true`).
 *
 * Usage (only the compose rule is a JUnit rule, so the launch is applied
 * exactly once):
 *
 * ```kotlin
 * private val launch = FixtureRule(routeFor = { "chat:chat-deploy" })
 *
 * @Suppress("DEPRECATION")
 * @get:Rule
 * val compose: AndroidComposeTestRule<FixtureRule, MainActivity> =
 *     AndroidComposeTestRule(launch) { rule -> rule.mountedActivity() }
 * ```
 */
class FixtureRule(
    /** The `route` extra per test method name (`null` = none). */
    private val routeFor: (String) -> String? = { null },
    /** Extra launch extras per test method name (String or Boolean values); they override `demo`/`fast`. */
    private val extrasFor: (String) -> Map<String, Any> = { emptyMap() },
    /** `false` launches without the demo (a signed-out phone shows the Welcome root). */
    private val demo: Boolean = true,
) : TestRule {

    lateinit var scenario: ActivityScenario<MainActivity>
        private set

    /**
     * Set by a test that brings MainActivity back through a NEW intent (a
     * notification tap): MainActivity.onNewIntent calls `setIntent`, and
     * ActivityScenario matches lifecycle events against its own start
     * intent, so from then on it ignores the activity's stage changes and
     * `close()` throws "Current state was null unexpectedly"
     * (NotificationFlowTest, run 38567067554 — the test body had passed).
     * Teardown then finishes the live activities directly.
     */
    var newIntentDelivered = false

    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                val name = description.methodName
                val target = InstrumentationRegistry.getInstrumentation().targetContext
                val intent = Intent(target, MainActivity::class.java)
                if (demo) {
                    intent.putExtra("demo", true)
                    intent.putExtra("fast", true)
                }
                routeFor(name)?.let { route -> intent.putExtra("route", route) }
                for ((key, value) in extrasFor(name)) {
                    when (value) {
                        is Boolean -> intent.putExtra(key, value)
                        else -> intent.putExtra(key, value.toString())
                    }
                }

                scenario = ActivityScenario.launch(intent)
                try {
                    base.evaluate()
                } finally {
                    try {
                        scenario.close()
                    } catch (untracked: NullPointerException) {
                        if (!newIntentDelivered) throw untracked
                        finishLiveActivities()
                    }
                }
            }
        }

    /** Finishes every live activity (the untracked-scenario teardown, see [newIntentDelivered]). */
    private fun finishLiveActivities() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            for (stage in listOf(Stage.RESUMED, Stage.PAUSED, Stage.STARTED, Stage.STOPPED, Stage.CREATED)) {
                monitor.getActivitiesInStage(stage).forEach { it.finish() }
            }
        }
        instrumentation.waitForIdleSync()
    }
}

/** The extras for a paced, long streaming reply (the old `slow` engine). */
val SlowReply: Map<String, Any> = mapOf("fast" to false, "longreply" to true)

/** The rule's launch helper for [androidx.compose.ui.test.junit4.AndroidComposeTestRule]'s activity factory. */
fun FixtureRule.mountedActivity(): MainActivity {
    var activity: MainActivity? = null
    scenario.onActivity { activity = it }
    return checkNotNull(activity) { "FixtureRule did not launch MainActivity" }
}
