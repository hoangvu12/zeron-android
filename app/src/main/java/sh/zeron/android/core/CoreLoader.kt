package sh.zeron.android.core

import android.util.Log
import sh.zeron.android.BuildConfig

/**
 * The result of loading the core; the placeholder (and later the shell)
 * renders it.
 */
sealed interface CoreStatus {
    data class Loaded(val version: String) : CoreStatus
    data class Failed(val reason: String) : CoreStatus
}

/**
 * Loads the Rust mobile core (`libzeron_mobile.so` via UniFFI/JNA) and
 * checks the version handshake: `coreVersion()` is the `zeron-mobile`
 * crate version, and the app's `versionName` comes from the same
 * `zeron/Cargo.toml`, so a mismatch means stale jniLibs or bindings.
 */
object CoreLoader {

    private const val TAG = "Zeron"

    fun load(): CoreStatus {
        return try {
            // The core links the NDK's shared C++ runtime; load it first so
            // a missing `libc++_shared.so` fails here by name, not as an
            // opaque dlopen error from JNA.
            System.loadLibrary("c++_shared")
            // JNA loads `libzeron_mobile.so` on the first UniFFI call.
            val version = uniffi.zeron_core.coreVersion()
            // #66: the core's version, not versionName (a release build's
            // versionName can carry the tag's build number).
            if (version != BuildConfig.CORE_VERSION) {
                Log.e(TAG, "core $version does not match app ${BuildConfig.CORE_VERSION}")
                CoreStatus.Failed("core $version does not match app ${BuildConfig.CORE_VERSION}")
            } else {
                Log.i(TAG, "core $version loaded")
                CoreStatus.Loaded(version)
            }
        } catch (error: Throwable) {
            // UnsatisfiedLinkError (stale jniLibs — rebuild without
            // -PzeronSkipCore) or a UniFFI checksum mismatch land here.
            Log.e(TAG, "could not load the Zeron core", error)
            CoreStatus.Failed(error.message ?: error.javaClass.simpleName)
        }
    }
}
