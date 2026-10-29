package sh.zeron.android.core

import android.content.Context
import android.content.SharedPreferences
import uniffi.zeron_core.authCallbackScheme
import uniffi.zeron_core.authProductionEdgeUrl
import uniffi.zeron_core.workosAuthorizeUrl

/**
 * The edge and the WorkOS client the app talks to (#18, the port of iOS's
 * `Endpoints` and `AppModel.edgeURL`, `SignInViewController.swift:4-44`,
 * `AppModel.swift:208-214`): production, unless a debuggable build was
 * launched with the `edge` / `workos` extras.
 *
 * The overrides are **sticky** (iOS `debugOverride`): a launch value is
 * remembered, so reopening the app from the launcher stays on that stack,
 * and the value `prod` forgets it. Release builds never read or write them
 * ([install] with `debuggable = false`).
 *
 * The core's `workosAuthorizeUrl` hard-codes its client id
 * (`zeron/crates/client/src/auth.rs`), so an overridden client builds the
 * same URL here ([authorizeUrl]).
 */
object Endpoints {

    @Volatile
    private var prefs: SharedPreferences? = null

    /** `ZeronApplication.onCreate`, before the stored account starts. */
    fun install(context: Context, debuggable: Boolean) {
        prefs = if (debuggable) context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE) else null
    }

    /** The edge every client and sign-in call uses (read when each starts). */
    val edgeUrl: String
        get() = prefs?.getString(EDGE, null) ?: authProductionEdgeUrl()

    /** The WorkOS authorize URL the sign-in browser opens. */
    fun authorizeUrl(state: String): String {
        val client = prefs?.getString(WORKOS, null) ?: return workosAuthorizeUrl(state)
        return authorizeUrl(client, "${authCallbackScheme()}://callback", state)
    }

    /**
     * The launch's `edge` / `workos` extras (null = not given), written
     * synchronously before anything reads them. True when the edge changed,
     * so a running account must start again on the new one.
     */
    fun applyLaunch(edge: String?, workos: String?): Boolean {
        val store = prefs ?: return false
        val before = store.getString(EDGE, null)
        val editor = store.edit()
        sticky(edge)?.let { editor.write(EDGE, it) }
        sticky(workos)?.let { editor.write(WORKOS, it) }
        editor.commit()
        return store.getString(EDGE, null) != before
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

    private const val PREFS = "debug_endpoints"
    private const val EDGE = "edge"
    private const val WORKOS = "workos"
    private const val WORKOS_API_BASE = "https://api.workos.com"
    private const val HEX = "0123456789ABCDEF"
}
