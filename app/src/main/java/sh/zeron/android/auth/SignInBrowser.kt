package sh.zeron.android.auth

import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.browser.auth.AuthTabIntent
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import android.content.Intent

/**
 * What a screen calls to sign in (#16's Welcome, #17's Sync step): opens
 * the WorkOS page; progress and errors are on `AuthSession.state`.
 */
fun interface SignIn {
    fun start()

    companion object {
        /** Outside an activity (previews, tests): does nothing. */
        val Unavailable = SignIn { }
    }
}

/** The activity's [SignIn], provided around the app content by `MainActivity`. */
val LocalSignIn = staticCompositionLocalOf { SignIn.Unavailable }

/**
 * The browser half of sign-in (D4; iOS's `ASWebAuthenticationSession`,
 * `SignInViewController.swift:125-141`). Create it in the activity's
 * `onCreate` (the Auth Tab result launcher must be registered before the
 * activity starts).
 *
 * - **Auth Tab** (`androidx.browser`, Chrome 137+) when the browser
 *   supports it: the result comes back here — the `zeron://callback` URI,
 *   or a cancel, which resets quietly like iOS's `.canceledLogin`.
 * - Otherwise a **Custom Tab**: the callback arrives through the
 *   `zeron://callback` intent filter (`MainActivity` → [AuthSession.onCallback]);
 *   there is no cancel signal, so coming back to the app with no callback
 *   within a second counts as one (docs/port/03 §2.1).
 *
 * The browser shares its cookies (no ephemeral session), as on iOS.
 */
class SignInBrowser(
    private val activity: ComponentActivity,
    private val session: AuthSession,
) : SignIn {

    private val launcher: ActivityResultLauncher<Intent> =
        AuthTabIntent.registerActivityResultLauncher(activity, ::onAuthTabResult)

    private val main = Handler(Looper.getMainLooper())
    private var inCustomTab = false
    private val cancelIfNothingCame = Runnable {
        if (inCustomTab) {
            inCustomTab = false
            session.onCancelled()
        }
    }

    init {
        activity.lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onResume(owner: LifecycleOwner) {
                    // onNewIntent (the callback) runs before onResume, so a
                    // callback has already cleared `awaitingBrowser` here.
                    if (inCustomTab && session.awaitingBrowser) {
                        main.postDelayed(cancelIfNothingCame, CUSTOM_TAB_CANCEL_MS)
                    } else {
                        inCustomTab = false
                    }
                }

                override fun onPause(owner: LifecycleOwner) {
                    main.removeCallbacks(cancelIfNothingCame)
                }

                override fun onDestroy(owner: LifecycleOwner) {
                    main.removeCallbacks(cancelIfNothingCame)
                }
            },
        )
    }

    override fun start() {
        val uri = Uri.parse(session.begin())
        try {
            val browser = CustomTabsClient.getPackageName(activity, null)
            if (browser != null && CustomTabsClient.isAuthTabSupported(activity, browser)) {
                inCustomTab = false
                AuthTabIntent.Builder().build().launch(launcher, uri, CALLBACK_SCHEME)
            } else {
                inCustomTab = true
                CustomTabsIntent.Builder().build().launchUrl(activity, uri)
            }
        } catch (error: ActivityNotFoundException) {
            inCustomTab = false
            session.onBrowserFailed("no browser", error)
        }
    }

    private fun onAuthTabResult(result: AuthTabIntent.AuthResult) {
        val uri = result.resultUri
        when {
            result.resultCode == AuthTabIntent.RESULT_OK && uri != null -> session.onCallback(uri.toString())
            result.resultCode == AuthTabIntent.RESULT_CANCELED -> session.onCancelled()
            else -> session.onBrowserFailed("auth tab result ${result.resultCode}", null)
        }
    }

    companion object {
        /** `auth_callback_scheme()`; the manifest's filter uses the same literal. */
        const val CALLBACK_SCHEME = "zeron"
        const val CALLBACK_HOST = "callback"
        private const val CUSTOM_TAB_CANCEL_MS = 1_000L
    }
}
