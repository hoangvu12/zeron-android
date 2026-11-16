package sh.zeron.android.core

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The process lifecycle hooks (android-ui-parity #35): a
 * `LifecycleRegistry.createUnsafe` owner driven through ON_START/ON_STOP
 * calls the sink in order (onForeground then afterForeground), `null`
 * sinks are dropped without a crash, a double install returns the same
 * instance and adds no second observer, the real install runs its calls
 * on the `zeron-lifecycle` thread, `clientReady()` re-sends the
 * current online value once, and a network change sends the online value
 * before `networkPathChanged()`.
 *
 * A plain [Application]: `ZeronApplication.onCreate` would load the core
 * and boot the session holder.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AppLifecycleHooksTest {

    /** A sink that records its calls and the threads they ran on. */
    private class RecordingSink : LifecycleSink {
        // Copy-on-write, not Collections.synchronizedList: the test thread
        // iterates (any/count/all) while the zeron-lifecycle thread adds,
        // and a synchronized list's iterator is not guarded
        // (ConcurrentModificationException).
        val calls = CopyOnWriteArrayList<String>()
        val threads = CopyOnWriteArrayList<String>()

        override fun onForeground() {
            calls += "onForeground"
            threads += Thread.currentThread().name
        }

        override fun onBackground() {
            calls += "onBackground"
            threads += Thread.currentThread().name
        }

        override fun onBackgroundIdle() {
            calls += "onBackgroundIdle"
            threads += Thread.currentThread().name
        }

        override fun setNetworkOnline(online: Boolean) {
            calls += "setNetworkOnline($online)"
            threads += Thread.currentThread().name
        }

        override fun networkPathChanged() {
            calls += "networkPathChanged"
            threads += Thread.currentThread().name
        }

        /** Waits (bounded) for a call starting with [prefix] to land — the real executor is async. */
        fun await(prefix: String, timeoutMs: Long = 5_000): Boolean {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (calls.any { it.startsWith(prefix) }) return true
                Thread.sleep(10)
            }
            return calls.any { it.startsWith(prefix) }
        }
    }

    /** Runs the command inline, so the direct-executor tests are synchronous. */
    private object DirectExecutor : Executor {
        override fun execute(command: Runnable) {
            command.run()
        }
    }

    /** A lifecycle owner driven by hand (createUnsafe — no thread checks). */
    private class TestOwner : LifecycleOwner {
        val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private val sink = RecordingSink()
    private val owner = TestOwner()

    @Before
    fun resetInstallState() {
        // Robolectric reuses this JVM across classes: forget the
        // process-wide instance so this class installs its own.
        AppLifecycleHooks.resetForTest()
    }

    @After
    fun resetInstallStateAfter() {
        AppLifecycleHooks.resetForTest()
    }

    private fun start() {
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    }

    private fun stop() {
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }

    // ------------------------------------------------- ON_START / ON_STOP

    @Test
    fun startAndStopCallTheSinkInOrderThenAfterForeground() {
        val afterForeground = Collections.synchronizedList(mutableListOf<Int>())
        val hooks = AppLifecycleHooks(
            sink = { sink },
            executor = DirectExecutor,
            afterForeground = { afterForeground += 1 },
        )
        owner.lifecycle.addObserver(hooks)

        start()
        assertEquals(listOf("onForeground"), sink.calls)
        assertEquals(listOf(1), afterForeground)

        stop()
        assertEquals(listOf("onForeground", "onBackground"), sink.calls)
    }

    @Test
    fun aNullSinkIsDroppedWithoutCrashing() {
        val hooks = AppLifecycleHooks(
            sink = { null },
            executor = DirectExecutor,
            afterForeground = {},
        )
        owner.lifecycle.addObserver(hooks)

        start()
        stop()
        // No crash; nothing to assert on the sink side.
    }

    // ---------------------------------------------------------------- rest

    /** A scheduler driven by hand: [advance] runs what is due, in order. */
    private class ManualScheduler : RestScheduler {
        var now = 0L
        val pending = mutableListOf<Pair<Long, Runnable>>()

        override fun schedule(delayMs: Long, task: Runnable) {
            pending += (now + delayMs) to task
        }

        override fun cancel(task: Runnable) {
            pending.removeAll { it.second === task }
        }

        fun advance(ms: Long) {
            val until = now + ms
            while (true) {
                val next = pending.filter { it.first <= until }.minByOrNull { it.first } ?: break
                pending.remove(next)
                now = next.first
                next.second.run()
            }
            now = until
        }
    }

    private fun restHooks(scheduler: ManualScheduler, busy: () -> Boolean = { false }): AppLifecycleHooks =
        AppLifecycleHooks(
            sink = { sink },
            executor = DirectExecutor,
            afterForeground = {},
            scheduler = scheduler,
            busy = busy,
        ).also { owner.lifecycle.addObserver(it) }

    @Test
    fun thirtySecondsInTheBackgroundLetsGoOfTheSockets() {
        val scheduler = ManualScheduler()
        restHooks(scheduler)
        start()
        stop()
        scheduler.advance(AppLifecycleHooks.REST_AFTER_MS - 1)
        assertEquals(listOf("onForeground", "onBackground"), sink.calls)
        scheduler.advance(1)
        assertEquals(listOf("onForeground", "onBackground", "onBackgroundIdle"), sink.calls)
        // Once: nothing more while it stays in the background.
        scheduler.advance(10 * AppLifecycleHooks.REST_RECHECK_MS)
        assertEquals(1, sink.calls.count { it == "onBackgroundIdle" })
    }

    @Test
    fun returningBeforeTheRestCancelsIt() {
        val scheduler = ManualScheduler()
        restHooks(scheduler)
        start()
        stop()
        scheduler.advance(20_000)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        scheduler.advance(10 * AppLifecycleHooks.REST_AFTER_MS)
        assertEquals(listOf("onForeground", "onBackground", "onForeground"), sink.calls)
    }

    @Test
    fun aSecondBackgroundingRestartsTheClock() {
        val scheduler = ManualScheduler()
        restHooks(scheduler)
        start()
        stop()
        scheduler.advance(20_000)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        stop()
        scheduler.advance(20_000)
        assertTrue("rested early: ${sink.calls}", "onBackgroundIdle" !in sink.calls)
        scheduler.advance(10_000)
        assertEquals("onBackgroundIdle", sink.calls.last())
    }

    @Test
    fun aLiveCallOrDeliveryHoldPutsTheRestOffUntilItEnds() {
        val scheduler = ManualScheduler()
        var busy = true
        restHooks(scheduler) { busy }
        start()
        stop()
        scheduler.advance(AppLifecycleHooks.REST_AFTER_MS + 3 * AppLifecycleHooks.REST_RECHECK_MS)
        assertTrue("rested while busy: ${sink.calls}", "onBackgroundIdle" !in sink.calls)
        busy = false
        scheduler.advance(AppLifecycleHooks.REST_RECHECK_MS)
        assertEquals("onBackgroundIdle", sink.calls.last())
    }

    // ------------------------------------------------------------- network

    @Test
    fun aNetworkChangeSendsOnlineThenThePathChange() {
        val hooks = AppLifecycleHooks(sink = { sink }, executor = DirectExecutor, afterForeground = {})

        hooks.network(NetworkCalls(online = true, pathChanged = true))
        hooks.network(NetworkCalls(online = null, pathChanged = true))
        hooks.network(NetworkCalls(online = false, pathChanged = false))
        hooks.network(NetworkCalls.None)

        assertEquals(
            listOf("setNetworkOnline(true)", "networkPathChanged", "networkPathChanged", "setNetworkOnline(false)"),
            sink.calls,
        )
    }

    @Test
    fun aNetworkChangeWithNoClientIsDropped() {
        val hooks = AppLifecycleHooks(sink = { null }, executor = DirectExecutor, afterForeground = {})
        hooks.network(NetworkCalls(online = true, pathChanged = true))
        // No crash; nothing to assert on the sink side.
    }

    // ------------------------------------------------------------- install

    @Test
    fun installRunsItsCallsOnTheZeronLifecycleThread() {
        AppLifecycleHooks.install(
            app = ApplicationProvider.getApplicationContext<Application>(),
            lifecycle = owner.lifecycle,
            sink = { sink },
        )
        start()
        assertTrue("onForeground never ran: ${sink.calls}", sink.await("onForeground"))
        stop()
        assertTrue("onBackground never ran: ${sink.calls}", sink.await("onBackground"))
        assertTrue(
            "calls ran on ${sink.threads}, expected zeron-lifecycle",
            sink.threads.all { it == "zeron-lifecycle" },
        )
    }

    @Test
    fun aDoubleInstallReturnsTheSameInstanceAndAddsNoObserver() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val first = AppLifecycleHooks.install(app, owner.lifecycle, sink = { sink })
        val before = owner.registry.observerCount

        val second = AppLifecycleHooks.install(app, owner.lifecycle, sink = { sink })

        assertSame(first, second)
        assertEquals(before, owner.registry.observerCount)
    }

    // ---------------------------------------------------------- clientReady

    @Test
    fun clientReadyResendsTheCurrentOnlineValueOnce() {
        val hooks = AppLifecycleHooks.install(
            app = ApplicationProvider.getApplicationContext<Application>(),
            lifecycle = owner.lifecycle,
            sink = { sink },
        )
        // The install seeds the initial reading; let it land.
        assertTrue("the seed call never ran: ${sink.calls}", sink.await("setNetworkOnline"))
        val seedCalls = sink.calls.count { it.startsWith("setNetworkOnline") }

        hooks.clientReady()
        assertTrue("clientReady never re-sent: ${sink.calls}", sink.await("setNetworkOnline"))
        // Exactly one re-send — count only settles once the executor is
        // done; poll briefly past the re-send.
        val deadline = System.currentTimeMillis() + 500
        while (System.currentTimeMillis() < deadline && sink.calls.count { it.startsWith("setNetworkOnline") } < seedCalls + 1) {
            Thread.sleep(10)
        }
        assertEquals(seedCalls + 1, sink.calls.count { it.startsWith("setNetworkOnline") })
    }
}
