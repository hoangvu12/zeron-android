package sh.zeron.android.settings

import android.content.pm.ApplicationInfo
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.zeron.android.ZeronApplication
import sh.zeron.android.core.Endpoints
import sh.zeron.android.core.Face
import sh.zeron.android.core.Relay
import sh.zeron.android.design.AlertButton
import sh.zeron.android.design.AlertButtonRole
import sh.zeron.android.design.AlertField
import sh.zeron.android.design.Alerts
import sh.zeron.android.design.LocalAlerts
import sh.zeron.android.design.LocalTypeScale
import sh.zeron.android.design.ZeronAlert
import sh.zeron.android.design.ZeronTheme
import sh.zeron.android.design.uiStyle
import uniffi.zeron_core.authProductionEdgeUrl

/**
 * The Relay setting (#130, an owner request beyond iOS, which hard-codes
 * the edge): sign-in and sync can go through a self-hosted relay instead
 * of Zeron's. Reached from Settings → Account, Welcome and onboarding's
 * Sync step, all through [rememberRelaySetting]; nothing changes until
 * the user saves a relay.
 *
 * The flow is alerts only (the app's text-input pattern):
 * 1. **Relay**: the URL field. Save checks it ([Relay.parse]), then asks
 *    the relay's `GET /health`; a problem replaces the message in place,
 *    keeping what was typed. "Use Zeron's Relay" resets.
 * 2. **WorkOS Client ID**, for a custom `workos` relay: the edge doesn't
 *    publish its client id and the authorize URL is built here, so the
 *    user confirms it (Zeron's pre-filled).
 * 3. **Change Relay?**, only while signed in: accounts belong to a relay,
 *    so the change signs out and deletes this device's copy first.
 */

/** What the flow needs from the app; [ZeronApplication.relayHost] is the real one. */
interface RelayHost {
    /** The custom relay, or null on Zeron's. */
    val relay: String?

    /** A real account (not the demo) is signed in. */
    val signedIn: Boolean
    val debuggable: Boolean
    val defaultClientId: String
    val productionUrl: String

    suspend fun probe(url: String): Relay.Health

    /** Signs a running account out, then stores the relay (null = Zeron's). */
    fun apply(url: String?, clientId: String?)
}

/** One run of the flow, from the Relay alert to the change. */
class RelayFlow(
    private val alerts: Alerts,
    private val host: RelayHost,
    private val scope: CoroutineScope,
) {
    private var checking = false
    private var cancelled = false

    fun start() = alerts.show(relayAlert(MESSAGE))

    private fun relayAlert(message: String): ZeronAlert = ZeronAlert(
        title = "Relay",
        message = message,
        field = AlertField(
            text = host.relay.orEmpty(),
            placeholder = Relay.host(host.productionUrl),
            capitalization = KeyboardCapitalization.None,
            autocorrect = false,
        ),
        buttons = buildList {
            add(AlertButton("Cancel", AlertButtonRole.Cancel) { cancelled = true })
            if (host.relay != null) {
                add(
                    AlertButton("Use Zeron's Relay") {
                        cancelled = true
                        confirmThenApply(null, null)
                    },
                )
            }
            add(AlertButton("Save", requiresText = true, morphsInPlace = true, onClick = ::save))
        },
    )

    private fun save(text: String) {
        if (checking) return
        val url = when (val input = Relay.parse(text, host.debuggable)) {
            is Relay.Input.Invalid -> return alerts.updateCurrent(relayAlert(input.reason))
            is Relay.Input.Valid -> input.url
        }
        if (url == host.relay) return alerts.dismissAll()
        if (url == (Relay.parse(host.productionUrl, debuggable = false) as? Relay.Input.Valid)?.url) {
            // Typing Zeron's own address is "Use Zeron's Relay".
            alerts.dismissAll()
            if (host.relay != null) confirmThenApply(null, null)
            return
        }
        checking = true
        alerts.updateCurrent(relayAlert(CHECKING))
        scope.launch {
            val health = host.probe(url)
            checking = false
            if (cancelled) return@launch
            when (health) {
                Relay.Health.Unreachable -> alerts.updateCurrent(relayAlert(Relay.UNREACHABLE))
                Relay.Health.NotRelay -> alerts.updateCurrent(relayAlert(Relay.NOT_A_RELAY))
                is Relay.Health.Ok -> if (health.auth == "dev" && !host.debuggable) {
                    alerts.updateCurrent(relayAlert(Relay.DEV_AUTH))
                } else {
                    alerts.dismissAll()
                    // A dev-auth relay (debug builds) signs in with the `dev` extra, not WorkOS.
                    if (health.auth == "dev") confirmThenApply(url, null) else askClientId(url)
                }
                Relay.Health.Unchecked -> {
                    alerts.dismissAll()
                    askClientId(url)
                }
            }
        }
    }

    private fun askClientId(url: String) = alerts.show(
        ZeronAlert(
            title = "WorkOS Client ID",
            message = CLIENT_ID_MESSAGE,
            field = AlertField(
                text = host.defaultClientId,
                placeholder = host.defaultClientId,
                capitalization = KeyboardCapitalization.None,
                autocorrect = false,
            ),
            buttons = listOf(
                AlertButton("Cancel", AlertButtonRole.Cancel),
                AlertButton("Continue", requiresText = true) { text ->
                    confirmThenApply(url, text.trim().takeIf { it != host.defaultClientId })
                },
            ),
        ),
    )

    private fun confirmThenApply(url: String?, clientId: String?) {
        if (!host.signedIn) return host.apply(url, clientId)
        alerts.show(
            ZeronAlert(
                title = "Change Relay?",
                message = CONFIRM_MESSAGE,
                buttons = listOf(
                    AlertButton("Cancel", AlertButtonRole.Cancel),
                    AlertButton("Sign Out and Change", AlertButtonRole.Destructive) { host.apply(url, clientId) },
                ),
            ),
        )
    }

    companion object {
        const val MESSAGE = "Sign-in and sync go through this relay. Keep Zeron's unless you run your own."
        const val CHECKING = "Checking the relay…"
        const val CLIENT_ID_MESSAGE =
            "Sign-in uses your relay's WorkOS app. Enter its client ID, or keep Zeron's if your relay uses it."
        const val CONFIRM_MESSAGE =
            "Accounts belong to the relay they signed in on, so you'll be signed out and this device's copy of your chats removed."
    }
}

/** The app's [RelayHost]: [Endpoints], the session holder and [ZeronApplication.changeRelay]. */
fun ZeronApplication.relayHost(): RelayHost = object : RelayHost {
    override val relay: String? get() = Endpoints.relay.value
    override val signedIn: Boolean get() = sessionHolder.clientOrNull() != null && !sessionHolder.isDemo
    override val debuggable: Boolean = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    override val defaultClientId: String get() = Endpoints.defaultWorkosClientId
    override val productionUrl: String get() = authProductionEdgeUrl()

    override suspend fun probe(url: String): Relay.Health = withContext(Dispatchers.IO) { Relay.probe(url) }

    override fun apply(url: String?, clientId: String?) = changeRelay(url, clientId)
}

/** Opens the Relay setting in the nearest alert host. */
@Composable
fun rememberRelaySetting(): () -> Unit {
    val app = LocalContext.current.applicationContext as ZeronApplication
    val alerts = LocalAlerts.current
    val scope = rememberCoroutineScope()
    return remember(app, alerts, scope) { { RelayFlow(alerts, app.relayHost(), scope).start() } }
}

/** What the relay rows and links show: "Zeron", or the custom relay's host. */
fun relayLabel(relay: String?): String = relay?.let(Relay::host) ?: "Zeron"

/**
 * The small "Relay: Zeron" link under sign-in (Welcome, onboarding's Sync
 * step): secondary 14, a 48 dp touch target, opening the Relay setting.
 */
@Composable
fun RelayLink(modifier: Modifier = Modifier) {
    val relay by Endpoints.relay.collectAsState()
    val open = rememberRelaySetting()
    val label = "Relay: ${relayLabel(relay)}"
    BasicText(
        label,
        style = uiStyle(Face.Sans, LocalTypeScale.current.fixed(14f), ZeronTheme.palette.secondary),
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .clickable(role = Role.Button, onClickLabel = "Change relay", onClick = open)
            .padding(horizontal = 8.dp)
            .wrapContentSize(Alignment.Center)
            .semantics { contentDescription = label }
            .testTag("relay-link"),
    )
}
