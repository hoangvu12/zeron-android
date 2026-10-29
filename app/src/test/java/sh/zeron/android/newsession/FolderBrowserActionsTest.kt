package sh.zeron.android.newsession

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uniffi.zeron_core.CoreException
import uniffi.zeron_core.FolderEntry
import uniffi.zeron_core.FolderListing

/**
 * [FolderBrowserActions] (android-ui-parity #111, #27): the Use action —
 * `create()` in `ios:Threads/NewProjectViewController.swift:153-166` —
 * the create ordering (`onCreated` before the dismiss), the failure copy,
 * the act guard (loading, submitting, another device's listing), the
 * gitDetected seeds and the `Use “…”` title.
 *
 * Robolectric, logic-style (like the controller test): the
 * `FolderListing`/`CoreException` uniffi types are record/exception
 * classes whose construction never loads the native core,
 * `FolderBrowserController` needs a looper for its main-thread assertion,
 * and `android.util.Log` must not throw. `runTest` passes its
 * `backgroundScope` to both the controller and the actions, and the
 * [ScriptedWriteApi] gates every FFI call on its own
 * [CompletableDeferred], so each step's state is asserted mid-flight.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FolderBrowserActionsTest {

    /** The device the sheet browses: `hetzner-01`. */
    private val device = HostOption("dev-htz", "hetzner-01", online = true)

    /** A second, default-picked device (its listing, then a failed switch). */
    private val other = HostOption("dev-mbp", "MacBook Pro", online = true)

    // ------------------------------------------------------------- the fakes

    /** A write API that records every call and gates each on a deferred answer. */
    private class ScriptedWriteApi : ProjectWriteApi {
        val calls = mutableListOf<String>()
        private val gates = mutableListOf<CompletableDeferred<Any>>()

        /** Completes call [index] with a result, or a throwable to throw. */
        fun answer(index: Int, value: Any) = synchronized(gates) { gates[index] }.complete(value)

        override suspend fun createProject(deviceId: String, path: String, gitDetected: Boolean): String {
            calls += "createProject $deviceId $path git=$gitDetected"
            val gate = CompletableDeferred<Any>()
            synchronized(gates) { gates += gate }
            val value = gate.await()
            if (value is Throwable) throw value
            return value as String
        }
    }

    /** A source that echoes the asked path (or hangs / fails on demand). */
    private class EchoSource : FolderBrowserSource {
        var listingFor: (String?) -> FolderListing? = { path -> listing(path ?: "/srv") }
        var hang = false
        val calls = mutableListOf<Pair<String, String?>>()

        override suspend fun listFolders(deviceId: String, path: String?): FolderListing? {
            calls += deviceId to path
            if (hang) kotlinx.coroutines.awaitCancellation()
            return listingFor(path)
        }
    }

    /** The harness under test: everything a test needs, one line to build. */
    private class Harness(
        val controller: FolderBrowserController,
        val actions: FolderBrowserActions,
        val api: ScriptedWriteApi,
        val source: EchoSource,
        val order: MutableList<String>,
    )

    /** A settled browser on `/srv` (the listing already applied) plus its actions. */
    private fun TestScope.harness(
        hosts: List<HostOption> = listOf(device),
        source: EchoSource = EchoSource(),
    ): Harness {
        val controller = FolderBrowserController(source, FakeHostPaths, backgroundScope, initialHosts = hosts)
        val api = ScriptedWriteApi()
        val order = mutableListOf<String>()
        val actions = FolderBrowserActions(
            controller,
            FakeHostPaths,
            api,
            backgroundScope,
            onCreated = { id -> order += "onCreated $id" },
            dismissSheet = { order += "dismiss" },
        )
        controller.updateDevices(hosts)
        runCurrent()
        return Harness(controller, actions, api, source, order)
    }

    // ------------------------------------------------------ the Use action

    @Test
    fun useCreatesTheProjectAndCallsOnCreatedBeforeTheDismiss() = runTest {
        val h = harness()
        h.actions.createProject()
        runCurrent()
        assertEquals(listOf("createProject dev-htz /srv git=false"), h.api.calls)
        assertTrue("submitting while the create runs", h.controller.submitting)
        assertTrue(h.actions.creatingProject)

        h.api.answer(0, "space-1")
        runCurrent()
        // iOS: the draft updates first (`onCreated`), then the sheet dismisses.
        assertEquals(listOf("onCreated space-1", "dismiss"), h.order)
        assertFalse(h.controller.submitting)
        assertFalse(h.actions.creatingProject)
        // The status line still shows the listing path on success.
        assertEquals("/srv", h.controller.status)
    }

    @Test
    fun aFailedCreateShowsTheSingleStatusLineAndNeverDismisses() = runTest {
        val h = harness()
        h.actions.createProject()
        runCurrent()
        h.api.answer(0, CoreException.HostUnavailable("host is offline"))
        runCurrent()
        assertEquals("Couldn't create the project on hetzner-01.", h.controller.status)
        assertTrue("no onCreated, no dismiss", h.order.isEmpty())
        assertFalse(h.controller.submitting)
        // A second tap is allowed again and retries.
        h.actions.createProject()
        runCurrent()
        assertEquals(2, h.api.calls.size)
        h.api.answer(1, "space-2")
        runCurrent()
        assertEquals(listOf("onCreated space-2", "dismiss"), h.order)
    }

    @Test
    fun useIsGuardedWhileALoadRuns() = runTest {
        val h = harness()
        h.source.hang = true
        h.controller.load("/srv/elsewhere")
        assertTrue(h.controller.loading)
        assertFalse(h.actions.canAct)
        h.actions.createProject()
        runCurrent()
        assertTrue(h.api.calls.isEmpty())
    }

    @Test
    fun useIsGuardedWhileSubmitting() = runTest {
        val h = harness()
        h.controller.submitting = true
        h.actions.createProject()
        runCurrent()
        assertTrue(h.api.calls.isEmpty())
    }

    @Test
    fun useIsGuardedWhenTheListingCameFromAnotherDevice() = runTest {
        // The default pick is dev-mbp; its listing applies, then the switch
        // to hetzner-01 fails, so the old rows stay but are not usable.
        val h = harness(hosts = listOf(other, device))
        assertEquals("dev-mbp", h.controller.listingDeviceId)
        h.source.listingFor = { null }
        h.controller.selectDevice(device.id)
        runCurrent()
        // dev-htz is online, so the copy is ReadFailed.
        assertEquals(FolderBrowserCopy.ReadFailed, h.controller.status)
        h.actions.createProject()
        runCurrent()
        assertTrue(h.api.calls.isEmpty())
    }

    @Test
    fun gitDetectedIsSeededFromTheRepoHintAndTheDotGitEntry() = runTest {
        val h = harness()

        // Home: hint null and no .git entry → false.
        h.actions.createProject()
        runCurrent()
        assertEquals("createProject dev-htz /srv git=false", h.api.calls[0])
        h.api.answer(0, "space-1")
        runCurrent()

        // Descended from a repo row (the controller's hint) → true.
        h.controller.load("/srv/zeron", isRepoHint = true)
        runCurrent()
        h.actions.createProject()
        runCurrent()
        assertEquals("createProject dev-htz /srv/zeron git=true", h.api.calls[1])
        h.api.answer(1, "space-2")
        runCurrent()

        // A listing that carries its own .git entry → true, hint or not.
        h.source.listingFor = { path ->
            listing(path ?: "/srv/dots", entries = listOf(FolderEntry(name = ".git", isDir = true, isRepo = false)))
        }
        h.controller.load("/srv/dots")
        runCurrent()
        h.actions.createProject()
        runCurrent()
        assertEquals("createProject dev-htz /srv/dots git=true", h.api.calls[2])
        h.api.answer(2, "space-3")
    }

    // ------------------------------------------------------ the Use title

    @Test
    fun theUseTitleQuotesTheLastPathComponent() = runTest {
        val h = harness()
        assertEquals("Use \u201Csrv\u201D", h.actions.useTitle)
        h.controller.load("C:\\")
        runCurrent()
        assertEquals("Use \u201CC:\\\u201D", h.actions.useTitle)
        h.controller.load("/")
        runCurrent()
        assertEquals("Use \u201C/\u201D", h.actions.useTitle)
        h.controller.load("/home/dev/My App")
        runCurrent()
        assertEquals("Use \u201CMy App\u201D", h.actions.useTitle)
        // A later load keeps the last title until it applies.
        h.source.hang = true
        h.controller.load("/srv/loading")
        assertEquals("Use \u201CMy App\u201D", h.actions.useTitle)
    }

    @Test
    fun theUseTitleIsEmptyBeforeTheFirstListing() = runTest {
        val h = harness(source = EchoSource().apply { hang = true })
        assertTrue(h.controller.loading)
        assertEquals("", h.actions.useTitle)
    }

    // -------------------------------------------------------------- fixture

    /** The default listing: two plain folders at [path]. */
    private companion object {
        fun listing(path: String, entries: List<FolderEntry> = defaultEntries()): FolderListing =
            FolderListing(path = path, entries = entries, truncated = false)

        fun defaultEntries(): List<FolderEntry> = listOf(
            FolderEntry(name = "Documents", isDir = true, isRepo = false),
            FolderEntry(name = "Projects", isDir = true, isRepo = false),
        )
    }
}
