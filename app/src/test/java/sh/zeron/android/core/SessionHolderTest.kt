package sh.zeron.android.core

import java.io.File
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import sh.zeron.android.session.SessionEventHub
import uniffi.zeron_core.AuthTokens
import uniffi.zeron_core.ClientEvent
import uniffi.zeron_core.ClientListener
import uniffi.zeron_core.Credentials
import uniffi.zeron_core.DemoFixture
import uniffi.zeron_core.DemoOptions
import uniffi.zeron_core.StreamSpeed
import uniffi.zeron_core.TranscriptScale

/**
 * The session holder (#13, `AppModel`'s client lifecycle) on the plain
 * JVM, over a fake client: boot, the per-account core dir, credential
 * persistence (synchronous `AuthRefreshed`), `AuthExpired`, sign-out, and
 * stale clients being ignored. No core call is made — the uniffi types
 * used here are plain Kotlin data.
 */
class SessionHolderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeClient(val dir: File, val credentials: Credentials, val listener: ClientListener) {
        @Volatile
        var shutDown = false
    }

    private val store = InMemoryCredentialStore()
    private val built = mutableListOf<FakeClient>()
    private val hops = mutableListOf<Runnable>()
    private val events = mutableListOf<ClientEvent>()
    private val signOuts = mutableListOf<Boolean>()
    private var refuse = false
    private var relay: String? = null

    private val holder: SessionHolder<FakeClient> by lazy {
        SessionHolder<FakeClient>(
            root = tmp.root,
            credentials = store,
            newClient = { dir, credentials, listener ->
                if (refuse) throw IllegalStateException("refused")
                FakeClient(dir, credentials, listener).also { built += it }
            },
            shutdown = { it.shutDown = true },
            hop = { hops += it },
            sessionEvents = SessionEventHub { },
            log = { _, _ -> },
            relay = { relay },
        ).also { h ->
            h.addEventSink { events += it }
            h.addSignOutListener { signOuts += it }
        }
    }

    private fun account(user: String = "user_1", org: String = "org_1", refresh: String = "r1") =
        Credentials.WorkOs(user, org, AuthTokens("access-$refresh", refresh))

    private val demo = Credentials.Demo(
        DemoOptions(DemoFixture.STANDARD, TranscriptScale.Normal, StreamSpeed.FAST, false),
    )

    private val coreDir get() = File(tmp.root, "core")

    private fun runHops() {
        val pending = hops.toList()
        hops.clear()
        pending.forEach { it.run() }
    }

    // ---------------------------------------------------------------- boot

    @Test
    fun bootWithNothingStoredIsSignedOut() {
        assertFalse(holder.booted.value)
        holder.boot()
        assertTrue(holder.booted.value)
        assertNull(holder.client.value)
        assertTrue(built.isEmpty())
    }

    @Test
    fun bootStartsTheStoredAccountOnce() {
        store.store(account())
        holder.boot()
        holder.boot()
        assertTrue(holder.booted.value)
        assertEquals(1, built.size)
        assertSame(built.single(), holder.clientOrNull())
        assertEquals(account(), built.single().credentials)
    }

    @Test
    fun aRefusedBootStaysSignedOutButKeepsTheStoredAccount() {
        store.store(account())
        refuse = true
        holder.boot()
        assertTrue(holder.booted.value)
        assertNull(holder.client.value)
        // The next launch tries again, like iOS (the Keychain is untouched).
        assertEquals(account(), store.stored())
    }

    // ------------------------------------------------- launch extras (#18)

    @Test
    fun aDemoLaunchBeforeBootNeverStartsTheStoredAccount() {
        store.store(account())
        holder.relaunch(clearStored = false) { demo }
        holder.boot()
        assertTrue(holder.booted.value)
        assertEquals(listOf<Credentials>(demo), built.map { it.credentials })
        assertTrue(holder.isDemo)
        assertEquals("starting the demo keeps the stored account", account(), store.stored())
    }

    @Test
    fun aDemoLaunchAfterBootReplacesTheStoredClient() {
        store.store(account())
        holder.boot()
        holder.relaunch(clearStored = false) { demo }
        assertTrue(built[0].shutDown)
        assertSame(built[1], holder.clientOrNull())
        assertTrue(holder.isDemo)
    }

    @Test
    fun noLaunchAccountBootsTheStoredOne() {
        store.store(account())
        holder.relaunch(clearStored = false) { null }
        assertEquals(account(), built.single().credentials)
        assertTrue(holder.booted.value)
    }

    @Test
    fun signedOutWipesTheStoredAccountBeforeChoosing() {
        store.store(account())
        var sawStored: Boolean? = null
        holder.relaunch(clearStored = true) { stored -> sawStored = stored; null }
        assertEquals(false, sawStored)
        assertNull(store.stored())
        assertNull(holder.client.value)
        assertTrue(built.isEmpty())
    }

    @Test
    fun signedOutAfterBootSignsTheRunningAccountOutKeepingItsStore() {
        store.store(account())
        holder.boot()
        File(coreDir, "outbox.db").writeText("unsent")
        holder.relaunch(clearStored = true) { Credentials.Dev("alice", "org1") }
        assertTrue(built[0].shutDown)
        assertEquals(listOf(false), signOuts)
        assertNull(store.stored())
        assertEquals(Credentials.Dev("alice", "org1"), holder.clientOrNull()?.credentials)
    }

    @Test
    fun aRelaunchAfterBootNeverRestartsTheStoredAccount() {
        store.store(account())
        holder.boot()
        holder.relaunch(clearStored = false) { null }
        assertEquals(1, built.size)
        assertFalse(built[0].shutDown)
    }

    // ------------------------------------------------------- relay (#130)

    @Test
    fun zeronsRelayKeepsThePreRelayOwner() {
        relay = null
        File(coreDir, "outbox.db").also { it.parentFile!!.mkdirs() }.writeText("unsent")
        File(coreDir, ".owner").writeText("user_1/org_1")
        assertTrue(holder.start(account()))
        assertEquals("unsent", File(coreDir, "outbox.db").readText())
        assertEquals("user_1/org_1", File(coreDir, ".owner").readText())
    }

    @Test
    fun theSameAccountOnAnotherRelayStartsEmpty() {
        assertTrue(holder.start(account()))
        File(coreDir, "outbox.db").writeText("unsent")
        holder.signOutLocally(forget = false)
        relay = "https://relay.example.com"
        assertTrue(holder.start(account()))
        assertFalse(File(coreDir, "outbox.db").exists())
        assertEquals("https://relay.example.com user_1/org_1", File(coreDir, ".owner").readText())
    }

    // --------------------------------------------------------------- start

    @Test
    fun aWorkOsStartPersistsAndClaimsTheCoreDir() {
        assertTrue(holder.start(account()))
        assertEquals(account(), store.stored())
        assertEquals(coreDir, built.single().dir)
        assertEquals("user_1/org_1", File(coreDir, ".owner").readText())
        assertFalse(holder.isDemo)
    }

    @Test
    fun anotherAccountStartsFromAnEmptyStore() {
        holder.start(account())
        File(coreDir, "docs.db").writeText("user 1's cache")
        holder.signOutLocally(forget = false)
        assertTrue("an expiry keeps the store", File(coreDir, "docs.db").exists())

        holder.start(account(user = "user_2"))
        assertFalse(File(coreDir, "docs.db").exists())
        assertEquals("user_2/org_1", File(coreDir, ".owner").readText())
    }

    @Test
    fun theSameAccountKeepsItsStore() {
        holder.start(account())
        File(coreDir, "docs.db").writeText("cache")
        holder.signOutLocally(forget = false)
        holder.start(account(refresh = "r2"))
        assertTrue(File(coreDir, "docs.db").exists())
    }

    @Test
    fun theDemoUsesItsOwnDirAndPersistsNothing() {
        assertTrue(holder.start(demo))
        assertEquals(File(tmp.root, "demo"), built.single().dir)
        assertNull(store.stored())
        assertFalse(File(coreDir, ".owner").exists())
        assertTrue(holder.isDemo)
    }

    @Test
    fun devCredentialsClaimTheDirButAreNotPersisted() {
        assertTrue(holder.start(Credentials.Dev("dev_user", "dev_org")))
        assertNull(store.stored())
        assertEquals("dev_user/dev_org", File(coreDir, ".owner").readText())
    }

    @Test
    fun aRefusedSignInLeavesNothingStored() {
        refuse = true
        assertFalse(holder.start(account()))
        assertNull(holder.client.value)
        assertNull(store.stored())
    }

    @Test
    fun startingAgainShutsThePreviousClientDown() {
        holder.start(demo)
        holder.start(account())
        assertTrue(built[0].shutDown)
        assertSame(built[1], holder.clientOrNull())
        assertFalse(holder.isDemo)
    }

    // ------------------------------------------------------ AuthRefreshed

    @Test
    fun aRefreshIsPersistedBeforeTheCallbackReturns() {
        holder.start(account())
        val rotated = AuthTokens("access-r2", "r2")
        var seenOnReturn: Credentials? = null
        thread(name = "rust-thread") {
            built.single().listener.onEvent(ClientEvent.AuthRefreshed(rotated))
            // Synchronous: stored before the core's thread moves on.
            seenOnReturn = store.stored()
        }.join()
        assertEquals(rotated, (seenOnReturn as Credentials.WorkOs).tokens)
        assertEquals(1, events.size)
    }

    @Test
    fun aRefreshFromASignedOutClientIsIgnored() {
        holder.start(account())
        val stale = built.single().listener
        holder.signOutLocally(forget = true)

        stale.onEvent(ClientEvent.AuthRefreshed(AuthTokens("access-r9", "r9")))

        assertNull("a late refresh must not bring the account back", store.stored())
        assertTrue(events.isEmpty())
    }

    @Test
    fun aStaleClientsRefreshNeverOverwritesTheNewAccount() {
        holder.start(account())
        val stale = built.single().listener
        holder.signOutLocally(forget = false)
        holder.start(account(user = "user_2", refresh = "u2"))

        stale.onEvent(ClientEvent.AuthRefreshed(AuthTokens("access-r9", "r9")))

        assertEquals(account(user = "user_2", refresh = "u2"), store.stored())
    }

    // -------------------------------------------------------- AuthExpired

    @Test
    fun anExpiryHopsThenSignsOutKeepingTheStore() {
        holder.start(account())
        File(coreDir, "outbox.db").writeText("unsent")
        val client = built.single()

        client.listener.onEvent(ClientEvent.AuthExpired("refresh token revoked"))
        assertNotNull("nothing happens on the core's thread", holder.clientOrNull())

        runHops()
        assertNull(holder.client.value)
        assertTrue(client.shutDown)
        assertNull(store.stored())
        assertTrue(File(coreDir, "outbox.db").exists())
        assertEquals(listOf(false), signOuts)
    }

    @Test
    fun anExpiryOvertakenByANewSignInDoesNothing() {
        holder.start(account())
        built.single().listener.onEvent(ClientEvent.AuthExpired("expired"))
        holder.signOutLocally(forget = false)
        holder.start(account(user = "user_2"))

        runHops()
        assertSame(built[1], holder.clientOrNull())
        assertEquals(account(user = "user_2"), store.stored())
    }

    // ----------------------------------------------------------- sign-out

    @Test
    fun aForgettingSignOutDeletesTheStore() {
        holder.start(account())
        File(coreDir, "docs.db").writeText("cache")

        holder.signOutLocally(forget = true)

        assertNull(holder.client.value)
        assertTrue(built.single().shutDown)
        assertNull(store.stored())
        assertFalse(coreDir.exists())
        assertEquals(listOf(true), signOuts)
    }

    /** iOS `signOutLocally` has no demo exception: the stored real account goes too (#18). */
    @Test
    fun leavingTheDemoClearsTheStoredAccountButKeepsItsStore() {
        store.store(account())
        File(coreDir, "docs.db").apply { parentFile?.mkdirs() }.writeText("the real account's cache")
        holder.start(demo)

        holder.signOutLocally(forget = false)

        assertNull(holder.client.value)
        assertTrue(built.single().shutDown)
        assertNull("the stored account is cleared", store.stored())
        assertTrue("an unasked sign-out keeps core/", File(coreDir, "docs.db").exists())
        assertEquals(listOf(false), signOuts)
    }

    @Test
    fun aForgettingSignOutFromTheDemoAlsoDeletesTheRealStoreButNotTheDemos() {
        store.store(account())
        File(coreDir, "docs.db").apply { parentFile?.mkdirs() }.writeText("the real account's cache")
        holder.start(demo)
        val demoDir = built.single().dir
        File(demoDir, "seed").writeText("demo data")

        holder.signOutLocally(forget = true)

        assertNull(store.stored())
        assertFalse("iOS deletes core/ on any asked-for sign-out", coreDir.exists())
        assertTrue("the demo's own dir is never touched", File(demoDir, "seed").exists())
    }

    // ------------------------------------------------------------- events

    @Test
    fun eventsFanOutAndAThrowingSinkStopsNobody() {
        val after = mutableListOf<ClientEvent>()
        holder.addEventSink { throw IllegalStateException("sink exploded") }
        holder.addEventSink { after += it }
        holder.start(account())

        built.single().listener.onEvent(ClientEvent.WorkspaceChanged(7uL))

        assertEquals(1, events.size)
        assertEquals(1, after.size)
    }

    @Test
    fun eventsFromARetiredClientAreDropped() {
        holder.start(demo)
        val stale = built.single().listener
        holder.start(account())

        stale.onEvent(ClientEvent.WorkspaceChanged(1uL))
        stale.onEvent(ClientEvent.AuthExpired("old"))

        assertTrue(events.isEmpty())
        assertTrue(hops.isEmpty())
    }
}
