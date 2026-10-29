package sh.zeron.android.background

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import sh.zeron.android.notify.Notifier
import sh.zeron.android.notify.WorkingRun

/**
 * The run-watching foreground service's lifecycle (android-app#28, #57):
 * start (foreground + working + notifications on + signed in), keep while
 * working, the 2 min linger, the immediate stops (notifications off,
 * signed out), the dataSync timeout latch and the foreground restart. D8
 * dropped "Stay connected". Robolectric for the `Intent`s (a plain
 * [Application], so `ZeronApplication.onCreate` never runs), virtual time
 * for the linger — the controller's clock is the scheduler's clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class RunWatchControllerTest {

    private val context: Application
        get() = ApplicationProvider.getApplicationContext()

    private fun workingRun(chatId: String = "engine:v1:a", fromThisDevice: Boolean = false) =
        WorkingRun(
            chatId = chatId,
            title = "A run",
            engineName = "Mac",
            startedAtMs = 1_000L,
            fromThisDevice = fromThisDevice,
        )

    @Test
    fun startingNeedsForegroundWorkingAndNotificationsOn() = runTest {
        val starts = mutableListOf<Intent>()
        val controller = controller(starts)
        // Background, no run: nothing.
        controller.onRunActivity(emptyList(), 0)
        controller.onNotificationsOn(true)
        controller.onSignedIn(true)
        assertEquals(0, controller.starts)
        assertFalse(controller.state.value.active)
        // Foreground + one working run: the service starts, exactly once.
        controller.onForeground(true)
        controller.onRunActivity(listOf(workingRun()), 0)
        assertEquals(1, controller.starts)
        assertTrue(controller.state.value.active)
        // The service acknowledges; more activity does not restart it.
        controller.onServiceStarted()
        controller.onRunActivity(listOf(workingRun(), workingRun("engine:v1:b")), 2)
        assertEquals(1, controller.starts)
        assertTrue(controller.state.value.active)
    }

    @Test
    fun theServiceNeverStartsWhileBackgrounded() = runTest {
        val starts = mutableListOf<Intent>()
        val controller = controller(starts)
        controller.onNotificationsOn(true)
        controller.onSignedIn(true)
        controller.onForeground(false)
        // A run starts on the desktop while the app is backgrounded: the
        // start is caught and dropped (API 31+), retried on the next
        // foreground.
        controller.onRunActivity(listOf(workingRun()), 0)
        assertEquals(0, controller.starts)
        // The service SHOULD run (it would keep one already up), but the
        // platform forbids starting it from the background: no start.
        assertTrue(controller.state.value.active)
        controller.onForeground(true)
        assertEquals(1, controller.starts)
        assertTrue(controller.state.value.active)
    }

    @Test
    fun notificationsOffOrSignedOutStopsAtOnce() = runTest {
        val starts = mutableListOf<Intent>()
        val controller = controller(starts)
        controller.onNotificationsOn(true)
        controller.onSignedIn(true)
        controller.onForeground(true)
        controller.onRunActivity(listOf(workingRun()), 0)
        controller.onServiceStarted()
        assertTrue(controller.state.value.active)
        // Notifications off: stop now.
        controller.onNotificationsOn(false)
        assertFalse(controller.state.value.active)
        // Back on, then signed out: stop now.
        controller.onNotificationsOn(true)
        assertTrue(controller.state.value.active)
        controller.onSignedIn(false)
        assertFalse(controller.state.value.active)
    }

    @Test
    fun theLingerHoldsTheServiceForTwoMinutesAfterTheLastRun() = runTest {
        val starts = mutableListOf<Intent>()
        val controller = controller(starts)
        controller.onNotificationsOn(true)
        controller.onSignedIn(true)
        controller.onForeground(true)
        controller.onRunActivity(listOf(workingRun()), 0)
        controller.onServiceStarted()
        // The user leaves the app; the last run leaves Working: the linger
        // opens (the Done notification goes out now, the tap comes later).
        controller.onForeground(false)
        controller.onRunActivity(emptyList(), 0)
        assertTrue("the linger holds the service", controller.state.value.active)
        // 10 s in, a queue handoff flickers Working again: the window
        // resets.
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(controller.state.value.active)
        controller.onRunActivity(listOf(workingRun()), 0)
        controller.onRunActivity(emptyList(), 0)
        // Well past the old 15 s grace: still up (live sockets for the tap).
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue("still lingering a minute after the last run", controller.state.value.active)
        // The full linger passes: the service stops (the delayed wake flips it).
        advanceTimeBy(RunWatchController.LINGER_MS - 60_000 + 100)
        runCurrent()
        assertFalse("the service stops after the linger", controller.state.value.active)
        assertEquals(1, controller.starts)
    }

    @Test
    fun aRunThatStartsDuringTheLingerIsKeptWhileBackgrounded() = runTest {
        val starts = mutableListOf<Intent>()
        val controller = controller(starts)
        controller.onNotificationsOn(true)
        controller.onSignedIn(true)
        controller.onForeground(true)
        controller.onRunActivity(listOf(workingRun()), 0)
        controller.onServiceStarted()
        controller.onForeground(false)
        controller.onRunActivity(emptyList(), 0)
        advanceTimeBy(90_000)
        runCurrent()
        // A run starts on the desktop while the app is backgrounded and the
        // service still lingers: it is kept (no new start needed)...
        controller.onRunActivity(listOf(workingRun("engine:v1:b")), 0)
        advanceTimeBy(RunWatchController.LINGER_MS)
        runCurrent()
        assertTrue(controller.state.value.active)
        assertEquals(1, controller.starts)
        // ...and gets its own linger when it ends.
        controller.onRunActivity(emptyList(), 0)
        advanceTimeBy(RunWatchController.LINGER_MS + 100)
        runCurrent()
        assertFalse(controller.state.value.active)
    }

    @Test
    fun theTimeoutLatchesAndTheForegroundRestartsTheService() = runTest {
        val starts = mutableListOf<Intent>()
        val controller = controller(starts)
        controller.onNotificationsOn(true)
        controller.onSignedIn(true)
        controller.onForeground(true)
        controller.onRunActivity(listOf(workingRun()), 0)
        assertEquals(1, controller.starts)
        controller.onServiceStarted()
        // onTimeout (API 35+): the service stops inside the callback, the
        // budget latches.
        controller.onServiceTimeout(Notifier(context))
        assertFalse(controller.state.value.active)
        assertTrue(controller.state.value.budgetExhausted)
        // The run still works, but no start while the budget latches.
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, controller.starts)
        // Back to the foreground: the budget resets (the platform's 6 h
        // window resets there too) and the service restarts because a run
        // is still Working.
        controller.onServiceStopped()
        controller.onForeground(false)
        controller.onForeground(true)
        assertEquals(2, controller.starts)
        assertFalse(controller.state.value.budgetExhausted)
        assertTrue(controller.state.value.active)
    }

    @Test
    fun aDismissedLiveUpdateLatchesUntilTheWorkingSetChanges() = runTest {
        val starts = mutableListOf<Intent>()
        val controller = controller(starts)
        controller.onNotificationsOn(true)
        controller.onSignedIn(true)
        controller.onForeground(true)
        controller.onRunActivity(listOf(workingRun()), 0)
        assertFalse(controller.state.value.liveUpdateDismissed)
        // The user swiped the promoted notification away.
        controller.onLiveUpdateDismissed()
        assertTrue(controller.state.value.liveUpdateDismissed)
        // Same working set: nothing changes (no promotion request).
        controller.onRunActivity(listOf(workingRun()), 0)
        assertTrue(controller.state.value.liveUpdateDismissed)
        // The working set changes: promotion may be requested again.
        controller.onRunActivity(listOf(workingRun("engine:v1:b")), 0)
        assertFalse(controller.state.value.liveUpdateDismissed)
    }

    @Test
    fun signingOutStopsAtOnceWithNoLinger() = runTest {
        val starts = mutableListOf<Intent>()
        val controller = controller(starts)
        controller.onNotificationsOn(true)
        controller.onSignedIn(true)
        controller.onForeground(true)
        controller.onRunActivity(listOf(workingRun()), 1)
        controller.onServiceStarted()
        controller.onDeviceCount(2)
        assertEquals(2, controller.state.value.deviceCount)

        controller.signOut()

        assertFalse("nobody is left to tap a Done notification", controller.state.value.active)
        assertTrue(controller.state.value.working.isEmpty())
        assertEquals(0, controller.state.value.awaiting)
        assertEquals(0, controller.state.value.deviceCount)
        // The next account's run starts it again.
        controller.onServiceStopped()
        controller.onSignedIn(true)
        controller.onRunActivity(listOf(workingRun("b")), 0)
        assertEquals(2, controller.starts)
    }

    /** A controller under test: starts recorded, never executed, clock = the scheduler. */
    private fun TestScope.controller(starts: MutableList<Intent>): RunWatchController =
        RunWatchController(
            context = context,
            scope = this,
            clock = { testScheduler.currentTime },
            startService = { intent -> starts.add(intent) },
        )
}
