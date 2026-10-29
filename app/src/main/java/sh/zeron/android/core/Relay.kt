package sh.zeron.android.core

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * The Relay setting's pure parts (#130): what the user typed → a relay URL
 * or the reason it isn't one, and what the relay's `GET /health` says
 * (`zeron/edge/src/index.ts`: `{"ok":true,"auth":"workos"|"dev"}`, the
 * same probe the desktop engine's `Auth::detect` makes).
 */
object Relay {

    /** What the typed text is. */
    sealed interface Input {
        /** A relay URL: lower-case scheme and host, no trailing slash. */
        data class Valid(val url: String) : Input

        data class Invalid(val reason: String) : Input
    }

    /** What the relay's health check said. */
    sealed interface Health {
        /** A Zeron relay; [auth] is `workos`, `dev`, or null when it didn't say. */
        data class Ok(val auth: String?) : Health

        data object NotRelay : Health

        data object Unreachable : Health

        /** Not checked: a debug build's `http` relay, which the app's own HTTP stack refuses. */
        data object Unchecked : Health
    }

    const val NOT_A_URL = "Enter the relay's address, like https://relay.example.com."
    const val HTTPS_ONLY = "The relay must use https."
    const val UNREACHABLE = "Couldn't reach this relay. Check the address and your connection."
    const val NOT_A_RELAY = "This address isn't a Zeron relay."
    const val DEV_AUTH = "This relay uses developer sign-in, which the app doesn't support."

    /**
     * Parses [text]: no scheme means `https://`; `https` always, `http`
     * only to localhost or a private address in a [debuggable] build. No
     * credentials, query or fragment; a path is kept (a relay behind a
     * prefix).
     */
    fun parse(text: String, debuggable: Boolean): Input {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return Input.Invalid(NOT_A_URL)
        val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return Input.Invalid(NOT_A_URL)
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()
        if (host.isNullOrEmpty() || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) {
            return Input.Invalid(NOT_A_URL)
        }
        when (scheme) {
            "https" -> Unit
            "http" -> if (!debuggable || !isLocal(host)) return Input.Invalid(HTTPS_ONLY)
            else -> return Input.Invalid(if (scheme == "ws" || scheme == "wss") HTTPS_ONLY else NOT_A_URL)
        }
        val port = if (uri.port == -1) "" else ":${uri.port}"
        val path = uri.rawPath.orEmpty().trimEnd('/')
        return Input.Valid("$scheme://$host$port$path")
    }

    /** Localhost, the emulator's host, a private IPv4 range, or a `.local` name. */
    internal fun isLocal(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".local") || host == "[::1]") return true
        val parts = host.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4 || host.count { it == '.' } != 3) return false
        val (a, b) = parts[0] to parts[1]
        return a == 127 || a == 10 || (a == 192 && b == 168) || (a == 172 && b in 16..31)
    }

    /** The host to show for [url] (`relay.example.com`, with a port when it has one). */
    fun host(url: String): String = runCatching {
        val uri = URI(url)
        uri.host + if (uri.port == -1) "" else ":${uri.port}"
    }.getOrNull() ?: url

    /** Reads a `/health` reply: a 2xx whose JSON says `"ok": true`. */
    internal fun health(status: Int, body: String): Health {
        if (status !in 200..299) return Health.NotRelay
        if (!Regex("\"ok\"\\s*:\\s*true").containsMatchIn(body)) return Health.NotRelay
        val auth = Regex("\"auth\"\\s*:\\s*\"([^\"]*)\"").find(body)?.groupValues?.get(1)
        return Health.Ok(auth)
    }

    /** `GET <url>/health`, 8 s each way. Blocking: call it off the main thread. */
    fun probe(url: String): Health {
        if (url.startsWith("http://")) return Health.Unchecked
        val connection = try {
            URL("$url/health").openConnection() as HttpURLConnection
        } catch (_: IOException) {
            return Health.Unreachable
        }
        return try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = false
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { reader ->
                CharArray(MAX_BODY).let { buf -> String(buf, 0, reader.read(buf).coerceAtLeast(0)) }
            }.orEmpty()
            health(status, body)
        } catch (_: IOException) {
            Health.Unreachable
        } finally {
            connection.disconnect()
        }
    }

    private const val TIMEOUT_MS = 8_000
    private const val MAX_BODY = 4_096
}
