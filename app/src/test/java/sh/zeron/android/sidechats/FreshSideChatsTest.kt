package sh.zeron.android.sidechats

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [FreshSideChats] (#39, iOS `AppModel.freshSideChats` and
 * `SessionViewController.teardown`): created at once and recorded,
 * persisted across instances, deleted when closed unused, and swept a
 * minute on unless open or written to. Robolectric only for
 * SharedPreferences; the core is a fake.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FreshSideChatsTest {

    private class FakeCore : SideChatCore {
        var next = "side-1"
        var fail = false
        val rows = HashMap<String, SideChatRow>()
        val created = mutableListOf<String>()
        val deleted = mutableListOf<String>()

        override fun createSideChat(parentId: String): String {
            if (fail) error("host offline")
            created += parentId
            rows[next] = SideChatRow(preview = null, createdAtMs = 0L)
            return next
        }

        override fun row(chatId: String): SideChatRow? = rows[chatId]

        override fun deleteSession(chatId: String) {
            deleted += chatId
            rows.remove(chatId)
        }
    }

    private val prefs by lazy {
        ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("fresh-test", Context.MODE_PRIVATE)
    }

    @Before
    fun clear() {
        prefs.edit().clear().commit()
    }

    private fun TestScope.store(core: FakeCore, now: Long = 0L, refreshes: MutableList<Unit> = mutableListOf()): FreshSideChats {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return FreshSideChats(
            prefs = prefs,
            core = { core },
            refreshWorkspace = { refreshes += Unit },
            scope = CoroutineScope(dispatcher),
            io = dispatcher,
            now = { now },
            logW = { _, _ -> },
        )
    }

    @Test
    fun aNewSideChatIsMadeAtOnceRecordedAndPersisted() = runTest {
        val core = FakeCore()
        val refreshes = mutableListOf<Unit>()
        val fresh = store(core, refreshes = refreshes)
        assertEquals("side-1", fresh.create("root"))
        assertEquals(listOf("root"), core.created)
        assertTrue(fresh.contains("side-1"))
        assertEquals(1, refreshes.size)
        // A new instance (the next launch) still knows it.
        assertTrue(store(core).contains("side-1"))
    }

    @Test
    fun aFailedCreateRecordsNothing() = runTest {
        val core = FakeCore().apply { fail = true }
        val fresh = store(core)
        assertNull(fresh.create("root"))
        assertTrue(fresh.fresh.isEmpty())
    }

    @Test
    fun closingUnusedDeletesOnlyAnUntouchedFreshChat() = runTest {
        val core = FakeCore()
        val fresh = store(core)
        fresh.create("root")
        // A draft left in it: kept.
        fresh.closeUnused("side-1", draftEmpty = false) { true }
        advanceUntilIdle()
        // Something was sent: kept.
        fresh.closeUnused("side-1", draftEmpty = true) { false }
        advanceUntilIdle()
        assertTrue(core.deleted.isEmpty())
        // Untouched: gone and forgotten.
        fresh.closeUnused("side-1", draftEmpty = true) { true }
        advanceUntilIdle()
        assertEquals(listOf("side-1"), core.deleted)
        assertFalse(fresh.contains("side-1"))
        // A chat that was never fresh is never deleted.
        core.rows["other"] = SideChatRow(null, 0L)
        fresh.closeUnused("other", draftEmpty = true) { true }
        advanceUntilIdle()
        assertEquals(listOf("side-1"), core.deleted)
    }

    @Test
    fun aPreviewKeepsAClosedChat() = runTest {
        val core = FakeCore()
        val fresh = store(core)
        fresh.create("root")
        core.rows["side-1"] = SideChatRow(preview = "hi", createdAtMs = 0L)
        fresh.closeUnused("side-1", draftEmpty = true) { true }
        advanceUntilIdle()
        assertTrue(core.deleted.isEmpty())
    }

    @Test
    fun theSweepDeletesStaleClosedChatsAndForgetsWrittenOnes() = runTest {
        val core = FakeCore()
        // A minute and a bit after creation.
        val fresh = store(core, now = 60_001L)
        fresh.create("root")
        core.next = "side-2"
        fresh.create("root")
        core.next = "side-3"
        fresh.create("root")
        core.rows["side-2"] = SideChatRow(preview = "hello", createdAtMs = 0L)
        fresh.opened("side-3")
        fresh.sweep()
        advanceUntilIdle()
        assertEquals("only the closed, empty one goes", listOf("side-1"), core.deleted)
        assertEquals("the open one stays fresh", setOf("side-3"), fresh.fresh)
    }

    @Test
    fun theSweepWaitsAMinute() = runTest {
        val core = FakeCore()
        val fresh = store(core, now = 60_000L)
        fresh.create("root")
        fresh.sweep()
        advanceUntilIdle()
        assertTrue(core.deleted.isEmpty())
        assertTrue(fresh.contains("side-1"))
    }

    @Test
    fun openCountsNest() = runTest {
        val core = FakeCore()
        val fresh = store(core, now = 120_000L)
        fresh.create("root")
        fresh.opened("side-1")
        fresh.opened("side-1")
        fresh.closed("side-1")
        fresh.sweep()
        advanceUntilIdle()
        assertTrue("still open once", core.deleted.isEmpty())
        fresh.closed("side-1")
        fresh.sweep()
        advanceUntilIdle()
        assertEquals(listOf("side-1"), core.deleted)
    }
}
