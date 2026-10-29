package sh.zeron.android.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uniffi.zeron_core.authCallbackScheme
import uniffi.zeron_core.authProductionEdgeUrl
import uniffi.zeron_core.workosAuthorizeUrl

/**
 * The relay (edge) and the WorkOS client the app talks to: production
 * unless the user picked their own relay (#130), or a debuggable build was
 * launched with the `edge` / `workos` extras (#18, the port of iOS's
 * `Endpoints` and `AppModel.edgeURL`, `SignInViewController.swift:4-44`,
 * `AppModel.swift:208-214`). One store for both, so there is one source of
 * truth: `CoreConfig`, `CoreAuthApi` and the authorize URL all read here.
 *
 * Nothing stored means production, so a default install behaves exactly
 * as before #130. The launch overrides are **sticky** (iOS
 * `debugOverride`): a launch value is remembered, and the value `prod`
 * forgets it; release builds never parse them (`LaunchOptions.parse`).
 *
 * The core's `workosAuthorizeUrl` hard-codes Zeron's client id
 * (`zeron/crates/client/src/auth.rs`) and the edge doesn't publish its own
 * (it exchanges codes with its `WORKOS_CLIENT_ID` server-side), so a relay
 * with its own WorkOS app stores that client id here and [authorizeUrl]
 * builds the same URL with it.
 */
object Endpoints {

    @Volatile
    private var prefs: SharedPreferences? = null

    private val _relay = MutableStateFlow<String?>(null)

    /** The custom relay, or null on Zeron's (the Settings row, Welcome's link). */
    val relay: StateFlow<String?> = _relay.asStateFlow()

    /** `ZeronApplication.onCreate`, before the stored account starts. */
    fun install(context: Context, debuggable: Boolean) {
        val app = context.applicationContext
        val store = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // #18 kept the debug overrides in their own file: carried over once.
        if (debuggable) {
            val legacy = app.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
            if (legacy.all.isNotEmpty()) {
                store.edit().apply {
                    legacy.getString(EDGE, null)?.let { putString(EDGE, it) }
                    legacy.getString(WORKOS, null)?.let { putString(WORKOS, it) }
                }.commit()
                legacy.edit().clear().commit()
            }
        }
        prefs = store
        _relay.value = store.getString(EDGE, null)
    }

    /** The edge every client and sign-in call uses (read when each starts). */
    val edgeUrl: String
        get() = prefs?.getString(EDGE, null) ?: authProductionEdgeUrl()

    /** The stored WorkOS client id, or null for Zeron's. */
    val workosClientId: String?
        get() = prefs?.getString(WORKOS, null)

    /** Zeron's own WorkOS client id, read off the core's authorize URL (it has no other export). */
    val defaultWorkosClientId: String by lazy { clientIdOf(workosAuthorizeUrl("x")).orEmpty() }

    /** The WorkOS authorize URL the sign-in browser opens. */
    fun authorizeUrl(state: String): String {
        val client = workosClientId ?: return workosAuthorizeUrl(state)
        return authorizeUrl(client, "${authCallbackScheme()}://callback", state)
    }

    /**
     * The Relay setting (#130): [url] null is Zeron's relay; [clientId]
     * null is Zeron's WorkOS app. Written synchronously; the caller has
     * already signed a running account out (accounts are per relay).
     */
    fun setRelay(url: String?, clientId: String?) {
        val store = prefs ?: return
        store.edit().apply {
            if (url == null) remove(EDGE) else putString(EDGE, url)
            if (clientId == null) remove(WORKOS) else putString(WORKOS, clientId)
        }.commit()
        _relay.value = url
    }

    /**
     * The launch's `edge` / `workos` extras (null = not given), written
     * synchronously before anything reads them. True when the edge changed,
     * so the stored account (which belongs to the old one) must go.
     */
    fun applyLaunch(edge: String?, workos: String?): Boolean {
        val store = prefs ?: return false
        val before = store.getString(EDGE, null)
        val editor = store.edit()
        sticky(edge)?.let { editor.write(EDGE, it) }
        sticky(workos)?.let { editor.write(WORKOS, it) }
        editor.commit()
        val after = store.getString(EDGE, null)
        _relay.value = after
        return after != before
    }

    private fun SharedPreferences.Editor.write(key: String, value: Sticky) {
        when (value) {
            Sticky.Clear -> remove(key)
            is Sticky.Set -> putString(key, value.value)
        }
    }

    /** What a launch value does to its stored override. */
    internal sealed interface Sticky {
        data object Clear : Sticky

        data class Set(val value: String) : Sticky
    }

    /** `prod` forgets the override, anything else replaces it; null leaves it. */
    internal fun sticky(launch: String?): Sticky? = when (launch) {
        null -> null
        "prod" -> Sticky.Clear
        else -> Sticky.Set(launch)
    }

    /** The core's `workos_authorize_url`, with [clientId] in place of its own. */
    internal fun authorizeUrl(clientId: String, redirectUri: String, state: String): String =
        "$WORKOS_API_BASE/user_management/authorize?response_type=code" +
            "&client_id=${percentEncode(clientId)}" +
            "&redirect_uri=${percentEncode(redirectUri)}" +
            "&provider=authkit" +
            "&state=${percentEncode(state)}"

    /** The `client_id` of an authorize URL (the core's ids are unreserved characters only). */
    internal fun clientIdOf(url: String): String? =
        Regex("[?&]client_id=([^&]*)").find(url)?.groupValues?.get(1)

    /** The core's `percent_encode`: RFC 3986 unreserved bytes stay, the rest are `%XX`. */
    private fun percentEncode(value: String): String = buildString {
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val b = byte.toInt() and 0xFF
            val c = b.toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' || c == '.' || c == '~') {
                append(c)
            } else {
                append('%').append(HEX[b shr 4]).append(HEX[b and 0xF])
            }
        }
    }

    private const val PREFS = "endpoints"
    private const val LEGACY_PREFS = "debug_endpoints"
    private const val EDGE = "edge"
    private const val WORKOS = "workos"
    private const val WORKOS_API_BASE = "https://api.workos.com"
    private const val HEX = "0123456789ABCDEF"
}
