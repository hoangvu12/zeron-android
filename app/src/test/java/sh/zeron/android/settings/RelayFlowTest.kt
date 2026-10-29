package sh.zeron.android.settings

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.zeron.android.core.Relay
import sh.zeron.android.design.Alerts
import sh.zeron.android.design.ZeronAlert

/** The Relay setting's alert flow (#130) over fake alerts and a fake app. */
@OptIn(ExperimentalCoroutinesApi::class)
class RelayFlowTest {

    /** One alert up at a time; [dismissAll] drops it, a shown one takes its place once it's gone. */
    private class FakeAlerts : Alerts {
        val shown = mutableListOf<ZeronAlert>()
        var current: ZeronAlert? = null

        override fun show(alert: ZeronAlert) {
            shown += alert
            current = alert
        }

        override fun updateCurrent(alert: ZeronAlert) {
            if (current != null) current = alert
        }

        override fun dismissAll() {
            current = null
        }

        fun press(title: String, text: String = "") {
            val alert = current!!
            val button = alert.buttons.single { it.title == title }
            if (!button.morphsInPlace) current = null
            button.onClick(text)
        }
    }

    private class FakeHost(
        override var relay: String? = null,
        override val signedIn: Boolean = false,
        override val debuggable: Boolean = false,
        var health: Relay.Health = Relay.Health.Ok("workos"),
    ) : RelayHost {
        override val defaultClientId = "client_zeron"
        override val productionUrl = "https://edge.zeron.sh"
        val applied = mutableListOf<Pair<String?, String?>>()
        val probed = mutableListOf<String>()

        override suspend fun probe(url: String): Relay.Health {
            probed += url
            return health
        }

        override fun apply(url: String?, clientId: String?) {
            applied += url to clientId
        }
    }

    private val scope = TestScope(StandardTestDispatcher())
    private val alerts = FakeAlerts()

    private fun flow(host: FakeHost) = RelayFlow(alerts, host, scope).also { it.start() }

    @Test
    fun aCustomRelayAsksForTheClientIdThenApplies() {
        val host = FakeHost()
        flow(host)
        assertEquals("Relay", alerts.current!!.title)
        alerts.press("Save", "zeron-v2.embedez.com")
        assertEquals(RelayFlow.CHECKING, alerts.current!!.message)
        scope.advanceUntilIdle()
        assertEquals(listOf("https://zeron-v2.embedez.com"), host.probed)
        assertEquals("WorkOS Client ID", alerts.current!!.title)
        assertEquals("client_zeron", alerts.current!!.field!!.text)
        alerts.press("Continue", " client_mine ")
        assertEquals(listOf<Pair<String?, String?>>("https://zeron-v2.embedez.com" to "client_mine"), host.applied)
    }

    @Test
    fun keepingZeronsClientIdStoresNone() {
        val host = FakeHost()
        flow(host)
        alerts.press("Save", "https://relay.example.com")
        scope.advanceUntilIdle()
        alerts.press("Continue", "client_zeron")
        assertEquals(listOf<Pair<String?, String?>>("https://relay.example.com" to null), host.applied)
    }

    @Test
    fun problemsStayInTheAlert() {
        val host = FakeHost(health = Relay.Health.Unreachable)
        flow(host)
        alerts.press("Save", "http://relay.example.com")
        assertEquals(Relay.HTTPS_ONLY, alerts.current!!.message)
        alerts.press("Save", "relay.example.com")
        scope.advanceUntilIdle()
        assertEquals(Relay.UNREACHABLE, alerts.current!!.message)
        host.health = Relay.Health.NotRelay
        alerts.press("Save", "relay.example.com")
        scope.advanceUntilIdle()
        assertEquals(Relay.NOT_A_RELAY, alerts.current!!.message)
        host.health = Relay.Health.Ok("dev")
        alerts.press("Save", "relay.example.com")
        scope.advanceUntilIdle()
        assertEquals(Relay.DEV_AUTH, alerts.current!!.message)
        assertTrue(host.applied.isEmpty())
    }

    @Test
    fun signedInAsksBeforeChanging() {
        val host = FakeHost(signedIn = true)
        flow(host)
        alerts.press("Save", "relay.example.com")
        scope.advanceUntilIdle()
        alerts.press("Continue", "client_zeron")
        assertEquals("Change Relay?", alerts.current!!.title)
        assertTrue(host.applied.isEmpty())
        alerts.press("Sign Out and Change")
        assertEquals(listOf<Pair<String?, String?>>("https://relay.example.com" to null), host.applied)
    }

    @Test
    fun useZeronsRelayResets() {
        val host = FakeHost(relay = "https://relay.example.com")
        flow(host)
        assertEquals("https://relay.example.com", alerts.current!!.field!!.text)
        alerts.press("Use Zeron's Relay")
        assertEquals(listOf<Pair<String?, String?>>(null to null), host.applied)
    }

    @Test
    fun typingZeronsRelayIsAResetWithoutAProbe() {
        val host = FakeHost(relay = "https://relay.example.com")
        flow(host)
        alerts.press("Save", "edge.zeron.sh")
        assertTrue(host.probed.isEmpty())
        assertEquals(listOf<Pair<String?, String?>>(null to null), host.applied)
    }

    @Test
    fun noUserButtonForZeronsRelay() {
        flow(FakeHost())
        assertEquals(listOf("Cancel", "Save"), alerts.current!!.buttons.map { it.title })
    }

    @Test
    fun cancellingWhileCheckingDropsTheResult() {
        val host = FakeHost()
        flow(host)
        alerts.press("Save", "relay.example.com")
        alerts.press("Cancel")
        scope.advanceUntilIdle()
        assertNull(alerts.current)
        assertEquals(1, alerts.shown.size)
        assertTrue(host.applied.isEmpty())
    }
}
