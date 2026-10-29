package sh.zeron.android.core

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.browser.auth.AuthTabIntent
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import sh.zeron.android.shell.ZeronUrl

/**
 * How a page in the browser is shown, from the activity (#48). The Auth
 * Tab result launcher must be registered in `onCreate`, so the activity
 * owns it; [BrowserReturns] only asks.
 */
interface BrowserTabs {
    /**
     * Opens [url] in an Auth Tab that closes itself on a `zeron://` return
     * and calls [onResult] with true for that return, false for a close.
     * Returns false (and never calls back) when there is no Auth Tab.
     */
    fun authTab(url: String, onResult: (returned: Boolean) -> Unit): Boolean

    /** Opens [url] in a Custom Tab. */
    fun customTab(url: String)
}

/**
 * Pages opened in the browser that come back through a `zeron://` link (a
 * provider's sign-in, GitHub, Stripe; Zeron Android's `BrowserReturns`):
 * tells a waiting flow whether the browser returned through its link or was
 * just closed.
 *
 * With an **Auth Tab** (Chrome 137+) the answer is the tab's own result,
 * the closest match to iOS's `ASWebAuthenticationSession`. Otherwise a
 * **Custom Tab**: `MainActivity` feeds [links] (`zeron://signed-in`, which
 * `singleTask` delivers after clearing the tab) and [resumes]; a resume with
 * no link soon after counts as a close. The browser keeps its cookies, as
 * on iOS (`prefersEphemeralWebBrowserSession = false`).
 */
class BrowserReturns {
    /** `zeron://` links the app was opened with (not the account callback). */
    val links = MutableSharedFlow<ZeronUrl>(extraBufferCapacity = 8)

    /** The app came back to the foreground. */
    val resumes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    /** The activity's tabs; null while no activity is alive. Main thread. */
    var tabs: BrowserTabs? = null

    /**
     * Open [url] and wait until the user is back: true when the browser came
     * back through a `zeron://` [until] link, false when it was closed.
     */
    suspend fun browse(url: String, until: ZeronUrl = ZeronUrl.SignedIn): Boolean {
        val t = tabs ?: return false
        val viaAuthTab = CompletableDeferred<Boolean>()
        if (t.authTab(url) { viaAuthTab.complete(it) }) return viaAuthTab.await()
        return coroutineScope {
            val returned = CompletableDeferred<Boolean>()
            val link = launch {
                links.first { it == until }
                returned.complete(true)
            }
            val resume = launch {
                resumes.first()
                // The link (if any) is delivered just before the resume.
                delay(LINK_GRACE_MS)
                returned.complete(false)
            }
            t.customTab(url)
            val result = returned.await()
            link.cancel()
            resume.cancel()
            result
        }
    }

    /**
     * Like [browse], but also done when [finished] completes first (the
     * server saw the sign-in). Null when the browser was closed and the
     * server didn't answer within a moment; [finished]'s error is thrown.
     */
    suspend fun <T> browseUntil(url: String, until: ZeronUrl = ZeronUrl.SignedIn, finished: suspend () -> T): T? =
        coroutineScope {
            val work = async { runCatching { finished() } }
            val closed = async { !browse(url, until) }
            // Whichever lands first; a closed browser gives the server a
            // moment to catch up.
            while (!work.isCompleted && !closed.isCompleted) delay(100)
            if (closed.isCompleted && closed.await() && !work.isCompleted) {
                withTimeoutOrNull(CLOSED_GRACE_MS) { work.await() }?.getOrThrow() ?: run {
                    work.cancel()
                    null
                }
            } else {
                closed.cancel()
                work.await().getOrThrow()
            }
        }

    companion object {
        /** Where a provider sign-in sends the browser once it's done. */
        const val SIGNED_IN = "zeron://signed-in"

        private const val LINK_GRACE_MS = 900L
        private const val CLOSED_GRACE_MS = 4_000L
    }
}

/**
 * The activity's [BrowserTabs]: an Auth Tab when the default browser has
 * one (its result launcher registered here, so construct this in
 * `onCreate`), else a Custom Tab.
 */
class ActivityBrowserTabs(private val activity: ComponentActivity) : BrowserTabs {
    private var pending: ((Boolean) -> Unit)? = null

    private val launcher: ActivityResultLauncher<Intent> =
        AuthTabIntent.registerActivityResultLauncher(activity) { result ->
            val callback = pending
            pending = null
            callback?.invoke(result.resultCode == AuthTabIntent.RESULT_OK && result.resultUri != null)
        }

    override fun authTab(url: String, onResult: (returned: Boolean) -> Unit): Boolean {
        val browser = CustomTabsClient.getPackageName(activity, null) ?: return false
        if (!CustomTabsClient.isAuthTabSupported(activity, browser)) return false
        return try {
            pending = onResult
            AuthTabIntent.Builder().build().launch(launcher, Uri.parse(url), SCHEME)
            true
        } catch (e: ActivityNotFoundException) {
            pending = null
            false
        }
    }

    override fun customTab(url: String) {
        try {
            CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(activity, Uri.parse(url))
        } catch (e: ActivityNotFoundException) {
            // No browser at all: the caller's resume heuristic reads it as closed.
        }
    }

    private companion object {
        const val SCHEME = "zeron"
    }
}
