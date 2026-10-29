package sh.zeron.android

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.Menu
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import sh.zeron.android.auth.LocalSignIn
import sh.zeron.android.auth.SignInBrowser
import sh.zeron.android.core.WallpaperStore
import sh.zeron.android.composer.LocalDrafts
import sh.zeron.android.design.GalleryLaunch
import sh.zeron.android.notify.NotificationPermission
import sh.zeron.android.notify.NotificationPrefs
import sh.zeron.android.shell.AppRootState
import sh.zeron.android.shell.DeepLinks
import sh.zeron.android.shell.LaunchOptions
import sh.zeron.android.shell.LocalShortcutSink
import sh.zeron.android.shell.RootInput
import sh.zeron.android.shell.ZeronAppContent
import sh.zeron.android.shell.ShellShortcuts
import sh.zeron.android.shell.ShortcutSink
import sh.zeron.android.shell.WallpaperTarget
import sh.zeron.android.shell.ZeronUrl
import sh.zeron.android.shell.applyZeronEdgeToEdge
import sh.zeron.android.shell.holdFirstFrame
import sh.zeron.android.shell.toExtrasMap

/**
 * The single activity (android-ui-parity #35): edge-to-edge with
 * transparent system bars, then the app root — [ZeronAppContent], which
 * mounts `ZeronTheme`, the font gate, the overlay hosts and
 * [sh.zeron.android.shell.AppRoot] (welcome / phone tab shell / tablet
 * split, with the 350 ms cross-fade).
 *
 * The platform splash is held ([holdFirstFrame]) until the root input
 * leaves `Loading` or 2000 ms pass, so a signed-in user never sees the
 * welcome root flash.
 *
 * Lifecycle observers (ProcessLifecycleOwner hooks) and the network
 * callback are registered in `ZeronApplication`, never here — #609
 * added one observer per `onCreate` (review bug 2) and #621 redialed on
 * every rotation. **No `onStart`/`onStop` overrides** call the core.
 *
 * The activity declares `configChanges` (orientation … navigation), so
 * rotation, fold/unfold, resize and appearance changes do **not**
 * recreate it — iOS controllers survive size changes; the open sheet,
 * nav stacks and scroll offsets survive too (ticket 108's carry-over
 * depends on this).
 *
 * Launch extras and deep links (android-ui-parity #36): `launchMode` is
 * `singleTask`, so every warm link arrives through [onNewIntent] with no
 * second activity instance. `zeron://` return URLs (#19: `callback`,
 * `signed-in`, `voice`) are handled in [handleIntent]; the `route` extra
 * and a notification tap's `chatId` extra go to the app-wide
 * [sh.zeron.android.shell.DeepLinkHub] (never straight to a router —
 * the shell is not composed yet at cold start), which dispatches after
 * the shell's first frame and drops links while the welcome root is on
 * screen. The four debug extras (`wallpaper`, `wallpaper-effect`,
 * `harness`, `measureopen`) are parsed here **only**, in `onCreate`,
 * gated on the manifest's `FLAG_DEBUGGABLE` — never in `onNewIntent`.
 * Recreation and Recents re-delivery are guarded with
 * `savedInstanceState == null` + `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`.
 *
 * Hardware shortcuts (android-ui-parity #33): the activity owns the one
 * [ShortcutSink]; the mounted shell registers on it, and the two overrides
 * below reach it from outside Compose — `onKeyShortcut` when no Compose
 * node has focus, `onProvideKeyboardShortcuts` for the system helper
 * (Meta+/).
 */
class MainActivity : ComponentActivity() {

    /** The observed root (ticket 36 reads `kind` to gate deep links). */
    private val rootState = AppRootState()

    /** One per activity: the bridge from the framework to the mounted shell. */
    private val shortcutSink = ShortcutSink()

    /** #15: the browser half of sign-in (Auth Tab launcher, Custom Tab fallback). */
    private lateinit var signIn: SignInBrowser

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before the first frame (the androidx edge-to-edge template):
        // transparent bars, icons following the effective night mode.
        // SystemBarsEffect re-applies it whenever the appearance changes
        // (ticket 96). The contrast scrims are off for good — see
        // applyZeronEdgeToEdge.
        applyZeronEdgeToEdge(isDark = resources.configuration.isNightModeActive)
        super.onCreate(savedInstanceState)
        val app = application as ZeronApplication
        // #15: the Auth Tab result launcher is registered on every create
        // (before STARTED), so a result delivered after a recreation lands.
        signIn = SignInBrowser(this, app.authSession)
        // Ticket 36: the launch extras, on every `onCreate` (after a
        // recreation the restored intent still carries them). The debug
        // gate is the manifest's FLAG_DEBUGGABLE — the installed APK's
        // property, not BuildConfig.DEBUG (a per-variant compile-time
        // constant).
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        LaunchOptions.current = LaunchOptions.parse(intent.extras.toExtrasMap(), debuggable)
        if (savedInstanceState == null && !intent.isFromHistory()) {
            // Fresh launch only: a rotation or a Recents relaunch must not
            // re-import the wallpaper (bumping the store's generation) or
            // re-open the chat the link already opened. Wallpaper first,
            // effect second (iOS order) — before `setContent`, like iOS
            // applies them before `showRoot`.
            if (debuggable) {
                LaunchOptions.current.applyWallpaper(wallpaperTarget(WallpaperStore.get(this)))
                // #18: signedout / dev / demo / edge / workos (and the demo a
                // route implies); the root holds Loading until applied.
                app.applyLaunchAccount(LaunchOptions.current)
            }
            handleIntent(intent)
        }
        setContent {
            CompositionLocalProvider(
                LocalShortcutSink provides shortcutSink,
                // Ticket 84: the app's one draft store, behind the seam the
                // composer reads; previews and hosts without the app use
                // the in-memory default.
                LocalDrafts provides app.drafts,
                // #15: what the Welcome / Sync screens call to sign in.
                LocalSignIn provides signIn,
            ) {
                ZeronAppContent(app, rootState)
            }
        }
        // The splash stays until the root is known (max 2000 ms).
        holdFirstFrame(timeoutMs = 2_000) { app.rootInput.value !is RootInput.Loading }
        // The debug design gallery (android-ui-parity #22): adb am start with
        // `--es route gallery` opens it on top of the app. Debuggable builds
        // only; the activity does not exist in release.
        if (savedInstanceState == null) GalleryLaunch.maybeStart(this, intent)
    }

    /**
     * The first-session ask's result (android-app#28): record whether the
     * denial was final (a second denial stops the system dialog from ever
     * showing again) — #99's Settings path reuses the same forwarder.
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        // `Array<String>`, not `Array<out String>`: androidx.activity's
        // ComponentActivity is Kotlin (invariant `Array<String>`), and
        // `Array<String>` also matches a Java `String[]` declaration.
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != NotificationPermission.REQUEST_CODE) return
        val granted = grantResults.isNotEmpty() &&
            grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
        NotificationPermission.recordRequestResult(
            NotificationPrefs.get(this),
            granted,
            shouldShowRequestPermissionRationale(NotificationPermission.PERMISSION),
        )
        // The run watch's notifications-on gate: an Allow here must reach it
        // now, not on the next foreground edge.
        (application as ZeronApplication).refreshNotificationsOn()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Ticket 36: warm links only — the data URI and the `route` extra,
        // never LaunchOptions (no re-parse, no wallpaper re-import).
        if (!intent.isFromHistory()) handleIntent(intent)
        GalleryLaunch.maybeStart(this, intent)
    }

    /**
     * #19: a `zeron://` return URL is handled here; a notification tap
     * (the `chatId` extra) and/or the `route` extra go to the app-wide
     * [sh.zeron.android.shell.DeepLinkHub] — never straight to a router
     * (the shell is not composed yet at cold start). An intent carrying
     * both submits both; the hub's single slot keeps the last one, the
     * route.
     */
    private fun handleIntent(intent: Intent) {
        val app = application as ZeronApplication
        val hub = app.deepLinks
        if (intent.action == Intent.ACTION_VIEW) {
            when (DeepLinks.parse(intent.dataString)) {
                // #15: the Custom Tab fallback's WorkOS redirect (the Auth
                // Tab returns its result to SignInBrowser instead).
                ZeronUrl.Callback -> app.authSession.onCallback(intent.dataString!!)
                // A provider or device login's done page: `singleTask`
                // already cleared the Custom Tab, and the waiting sign-in
                // finishes on its own poll.
                ZeronUrl.SignedIn -> Unit
                // TODO(#61): back to the voice stage while a call is live.
                ZeronUrl.Voice -> Unit
                // Unknown link: ignored, logged.
                null -> Log.i("DeepLinks", "ignored ${intent.dataString}")
            }
        }
        DeepLinks.notificationTap(intent.getStringExtra(DeepLinks.EXTRA_CHAT_ID))?.let(hub::submit)
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        LaunchOptions.route(intent.extras.toExtrasMap(), debuggable)?.let(hub::submit)
    }

    /**
     * The real [WallpaperTarget] over ticket 20's store — the seam that
     * keeps `LaunchOptions.applyWallpaper`'s call order unit-testable.
     * The import runs on the store's own lane and its failure only warns
     * (iOS: "if the file can't be read, nothing happens"); never
     * `runBlocking`.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun wallpaperTarget(store: WallpaperStore): WallpaperTarget = object : WallpaperTarget {
        override fun remove() {
            store.remove()
        }

        override fun set(path: File) {
            val import = store.set(path)
            import.invokeOnCompletion { cause ->
                // getCompleted is safe here: the job is complete.
                if (cause == null && !import.getCompleted()) {
                    Log.w("LaunchOptions", "wallpaper: could not read ${path.path}")
                }
            }
        }

        override fun setEffect(key: String): Boolean = store.setEffect(key)
    }

    /** A Relaunch from Recents re-delivers the original launch intent; ignore those. */
    private fun Intent.isFromHistory(): Boolean =
        (flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0

    /**
     * Reached only when the view tree didn't consume a Ctrl combo — e.g.
     * nothing in Compose has focus (fresh launch, after `clearFocus()`),
     * because Compose dispatches key events only to the focused node and
     * its ancestors, so the shell root's `onPreviewKeyEvent` never fires.
     * The framework calls this for an unhandled `ACTION_DOWN` with Ctrl
     * held and `repeatCount == 0`, via `ViewRootImpl` →
     * `dispatchKeyShortcutEvent`. No double handling: a consumed
     * `onPreviewKeyEvent` makes `dispatchKeyEvent` return `true` and the
     * shortcut path is skipped.
     */
    override fun onKeyShortcut(keyCode: Int, event: KeyEvent?): Boolean =
        (event != null && shortcutSink.dispatch(event)) || super.onKeyShortcut(keyCode, event)

    /** The system shortcut helper (Meta+/): the "Zeron" group while a shell is mounted. */
    override fun onProvideKeyboardShortcuts(
        data: MutableList<KeyboardShortcutGroup>,
        menu: Menu?,
        deviceId: Int,
    ) {
        super.onProvideKeyboardShortcuts(data, menu, deviceId)
        data.addAll(ShellShortcuts.helperGroups(this, shortcutSink))
    }
}
