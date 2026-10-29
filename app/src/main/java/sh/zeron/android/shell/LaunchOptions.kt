package sh.zeron.android.shell

import java.io.File
import uniffi.zeron_core.Credentials
import uniffi.zeron_core.DemoFixture
import uniffi.zeron_core.DemoOptions
import uniffi.zeron_core.StreamSpeed
import uniffi.zeron_core.TranscriptScale

/**
 * Test seam over `android.util.Log.w` — the plain-JVM tests replace it
 * because `android.util.Log` throws "not mocked" there. `android.util.Log`
 * is fully qualified on purpose: this file keeps no `android.*` **import**
 * (the parse path stays plain JVM; only the `Bundle` extension below
 * touches the framework, also fully qualified).
 */
internal var launchOptionsLog: (String) -> Unit = { message ->
    android.util.Log.w("LaunchOptions", message)
}

/**
 * The launch intent extras (android-ui-parity #36) — the Android form of
 * iOS's launch arguments (`SceneDelegate.swift:34-47,64-72`,
 * `AppModel.swift:62-66`, `-measureopen`), read once per `onCreate` from
 * `intent.extras.toExtrasMap()`:
 *
 * - `route` is **always** parsed (iOS's `-route` is ungated — it does
 *   nothing a deep link can't already do) and again in `onNewIntent`. It
 *   is not a field here: [route] turns it into a [DeepLink] for the hub.
 * - `wallpaper` (`path` | `none`), `wallpaper-effect` (a raw key,
 *   validated by ticket 20's store), `harness` (verbatim, never
 *   validated — ticket 92 reads it) and `measureopen` (ticket 75 reads
 *   it) are honoured **only in debuggable builds** and only in
 *   `onCreate`: an exported activity's extras can be sent by any app in
 *   release.
 *
 * - The account extras (#18, iOS's DEBUG launch args in `AppModel.init`,
 *   `AppModel.swift:124-151`; SPEC D12: debug only, never a button) —
 *   same gate: `signedout` wipes the stored account; `dev "<userId>
 *   <orgId>"` runs `Credentials.Dev` (never persisted); `demo` runs the
 *   offline demo, shaped by `noprojects` / `iosonly`, `big` / `huge`,
 *   `fast`, `longreply`, and `cloud` (the demo's Cloud row, read by the
 *   Cloud tickets); a `route` that parses also runs the demo when nothing
 *   is stored. `edge <url|prod>` / `workos <client|prod>` are the sticky
 *   endpoint overrides (`Endpoints`). Booleans take `--ez x true` or
 *   `--es x true`.
 *
 * Wrong types (an `Int` where a `String` is expected) read as absent;
 * blank strings read as null.
 */
data class LaunchOptions(
    val wallpaper: String? = null,
    val wallpaperEffect: String? = null,
    val harness: String? = null,
    val measureOpen: Boolean = false,
    val signedOut: Boolean = false,
    /** `dev "<userId> <orgId>"`: the dev stack's account. */
    val dev: Credentials.Dev? = null,
    val demo: Boolean = false,
    /** A `route` that parses was given (it implies the demo when nothing is stored). */
    val route: Boolean = false,
    val noProjects: Boolean = false,
    val iosOnly: Boolean = false,
    val big: Boolean = false,
    val huge: Boolean = false,
    val fast: Boolean = false,
    val longReply: Boolean = false,
    /** iOS `-cloud`: the demo offers Cloud (`MoreViewController.swift:201`). */
    val cloud: Boolean = false,
    val edge: String? = null,
    val workos: String? = null,
) {

    /** Any extra that changes which account (or edge) this launch runs. */
    val touchesAccount: Boolean
        get() = signedOut || dev != null || demo || route || edge != null || workos != null

    /**
     * The account this launch starts instead of the stored one, iOS
     * order: `dev` wins, then `demo` (or a `route` with [hasStored]
     * false); null keeps the stored account. Asked after `signedout` ran.
     */
    fun launchCredentials(hasStored: Boolean): Credentials? = when {
        dev != null -> dev
        demo || (route && !hasStored) -> Credentials.Demo(demoOptions())
        else -> null
    }

    /** iOS `AppModel.demoOptions()`: `huge` beats `big`, `noprojects` beats `iosonly`. */
    fun demoOptions(): DemoOptions = DemoOptions(
        fixture = when {
            noProjects -> DemoFixture.NO_PROJECTS
            iosOnly -> DemoFixture.IOS_ONLY
            else -> DemoFixture.STANDARD
        },
        transcriptScale = when {
            huge -> TranscriptScale.Huge
            big -> TranscriptScale.Big
            else -> TranscriptScale.Normal
        },
        streamSpeed = if (fast) StreamSpeed.FAST else StreamSpeed.REALISTIC,
        longReply = longReply,
    )

    /**
     * Wallpaper first, effect second (iOS order,
     * `SceneDelegate.swift:34-47`): `"none"` is an exact, case-sensitive
     * match that clears the stored image (any other value is a file path
     * — `"None"` is a path, like iOS); an effect is applied even without
     * a wallpaper, and an unknown key only warns. A failed file import
     * warns from the adapter (`MainActivity`), not here.
     */
    fun applyWallpaper(target: WallpaperTarget) {
        when {
            wallpaper == "none" -> target.remove()
            wallpaper != null -> target.set(File(wallpaper))
        }
        wallpaperEffect?.let { key ->
            if (!target.setEffect(key)) {
                launchOptionsLog("wallpaper-effect: unknown key $key")
            }
        }
    }

    companion object {

        /**
         * Set once per `onCreate` (`MainActivity`), so it survives
         * recreation; after process death it is re-parsed from the
         * restored intent. Read by ticket 92 (`harness`) and ticket 75
         * (`measureOpen`). `@Volatile`: process-wide, written from the
         * activity, read anywhere.
         */
        @Volatile
        var current: LaunchOptions = LaunchOptions()
            internal set

        /**
         * The debug-gated extras. In a non-debuggable build the result is
         * [LaunchOptions] with every field empty whatever the extras say
         * — the release-flag behaviour is exactly this. Debuggable means
         * `ApplicationInfo.FLAG_DEBUGGABLE` (the installed APK's
         * property), not `BuildConfig.DEBUG` (a per-variant compile-time
         * constant).
         */
        fun parse(extras: Map<String, Any?>, debuggable: Boolean): LaunchOptions {
            if (!debuggable) return LaunchOptions()
            fun string(key: String): String? =
                (extras[key] as? String)?.takeIf { it.isNotBlank() }
            fun flag(key: String): Boolean = when (val value = extras[key]) {
                is Boolean -> value
                is String -> value.equals("true", ignoreCase = true)
                else -> false
            }
            return LaunchOptions(
                wallpaper = string("wallpaper"),
                wallpaperEffect = string("wallpaper-effect"),
                harness = string("harness"),
                measureOpen = flag("measureopen"),
                signedOut = flag("signedout"),
                dev = string("dev")?.let(::devCredentials),
                demo = flag("demo"),
                route = route(extras, debuggable = true) != null,
                noProjects = flag("noprojects"),
                iosOnly = flag("iosonly"),
                big = flag("big"),
                huge = flag("huge"),
                fast = flag("fast"),
                longReply = flag("longreply"),
                cloud = flag("cloud"),
                edge = string("edge")?.trim(),
                workos = string("workos")?.trim(),
            )
        }

        /** `"<userId> <orgId>"` (iOS `-dev <userId> <orgId>`); anything else is absent. */
        private fun devCredentials(value: String): Credentials.Dev? {
            val parts = value.trim().split(Regex("\\s+"))
            if (parts.size != 2) {
                launchOptionsLog("dev: expected \"<userId> <orgId>\"")
                return null
            }
            return Credentials.Dev(parts[0], parts[1])
        }

        /**
         * The `route` extra as a [DeepLink] — always parsed, in both
         * `onCreate` and `onNewIntent`; only [debuggable] builds take the
         * debug Cloud / providers / accounts routes (#19, iOS `#if DEBUG`).
         * Non-string, absent or unknown values read as nothing.
         */
        fun route(extras: Map<String, Any?>, debuggable: Boolean = false): DeepLink? =
            LaunchRoute.parse(extras["route"] as? String, debug = debuggable)
    }
}

/**
 * Seam over ticket 20's `WallpaperStore` so `LaunchOptions.applyWallpaper`
 * call order stays unit-testable. The real adapter (in `MainActivity`)
 * runs the import on the store's own lane and logs a read failure.
 */
interface WallpaperTarget {

    /** `wallpaper none` — clears the stored image (the effect pref survives). */
    fun remove()

    /** Imports the file (name = file name **with** extension); async, fire-and-forget. */
    fun set(path: File)

    /**
     * Sets the effect by key; `false` when the key is not one of the five
     * (the caller then logs the warning). Main-safe.
     */
    fun setEffect(key: String): Boolean
}

/**
 * `Bundle` → plain map. Never throws: a hostile caller's unknown
 * Parcelable reads as no extras (`BadParcelableException` → empty map).
 * Wrong-typed values survive here; `LaunchOptions.parse`'s `as?` casts
 * are what turn them into "absent".
 */
@Suppress("DEPRECATION") // BaseBundle.get(String): the one untyped read the ticket prescribes
fun android.os.Bundle?.toExtrasMap(): Map<String, Any?> {
    if (this == null) return emptyMap()
    return runCatching {
        keySet().associateWith { key -> get(key) }
    }.getOrElse { emptyMap() }
}
