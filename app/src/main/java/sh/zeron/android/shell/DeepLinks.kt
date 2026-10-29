package sh.zeron.android.shell

import java.util.Locale

/**
 * One in-app navigation, from the `route` launch extra (iOS's `-route`)
 * or a notification tap (iOS's `userInfo.chatId`). Each kind maps
 * one-to-one onto an [AppRouter] call. No `zeron://` link produces one:
 * iOS has no navigation links (D11), only the return URLs in [ZeronUrl].
 *
 * Pure Kotlin — no `android.*` — so the grammar is plain-JVM-testable.
 */
sealed interface DeepLink {

    /** A notification tap / `route chat:<id>` — `router.openSession`. */
    data class OpenChat(val chatId: String) : DeepLink

    /**
     * `route new` — `router.presentNewSession`. The prompt only
     * **pre-fills** the composer (iOS `NewSessionViewController.swift:78`);
     * the route never carries one.
     */
    data class NewSession(val prompt: String?) : DeepLink

    /** `route more` — `router.showSettings` (`more` is the Settings tab's iOS id). */
    data object Settings : DeepLink

    /** `route search` — `router.showSearch`. */
    data object Search : DeepLink
}

/**
 * The `zeron://` hosts the manifest's one filter takes (#19; iOS
 * `Info.plist` registers the `zeron` scheme): every one is a return
 * into the app, none navigates.
 */
enum class ZeronUrl {
    /** `zeron://callback?code=…&state=…` — WorkOS sign-in's redirect (#15). */
    Callback,

    /**
     * `zeron://signed-in` — the `doneUrl` of provider and device logins
     * (`AccountSignIn.DONE_URL`). Arriving is the whole job: `singleTask`
     * clears the Custom Tab above the activity, and the waiting sign-in
     * finishes on its own poll.
     */
    SignedIn,

    /** `zeron://voice` — the call notification returns to the voice stage (#61). */
    Voice,
}

/**
 * The `zeron://` grammar and the `route` extra grammar, hand-written over
 * the raw `Intent.getDataString()` — never `android.net.Uri`
 * (Robolectric-bound). Unknown links return null — ignored by the caller
 * and logged there (this file has no `android.*` dependency to log with).
 */
object DeepLinks {

    const val SCHEME = "zeron"

    /**
     * The notification tap's extra (`Notifier`): the chat id, on an
     * explicit intent — iOS reads `userInfo["chatId"]`
     * (`PushNotifications.swift:175`), not a URL.
     */
    const val EXTRA_CHAT_ID = "chatId"

    /**
     * `Intent.dataString` → which return URL it is, or null: other
     * schemes, a missing `://`, and every other host (the dropped
     * `chat` / `new` / `settings` / `search`, the desktop's `open`).
     * Scheme and host compare case-insensitively; the path, query and
     * fragment are the consumer's business (`AuthSession` parses the
     * callback's query itself).
     */
    fun parse(uri: String?): ZeronUrl? {
        val s = uri?.trim() ?: return null
        val separator = s.indexOf("://")
        if (separator <= 0) return null
        if (!s.substring(0, separator).equals(SCHEME, ignoreCase = true)) return null
        val rest = s.substring(separator + 3)
        val end = rest.indexOfAny(charArrayOf('/', '?', '#')).let { if (it < 0) rest.length else it }
        return when (rest.substring(0, end).lowercase(Locale.ROOT)) {
            "callback" -> ZeronUrl.Callback
            "signed-in" -> ZeronUrl.SignedIn
            "voice" -> ZeronUrl.Voice
            else -> null
        }
    }

    /** The notification tap's [EXTRA_CHAT_ID] → an open, or null when blank or absent. */
    fun notificationTap(chatId: String?): DeepLink? =
        chatId?.takeIf { it.isNotBlank() }?.let(DeepLink::OpenChat)

    /** The `route` extra — its grammar lives in [LaunchRoute] (#18). */
    fun parseRoute(route: String?): DeepLink? = LaunchRoute.parse(route)
}
