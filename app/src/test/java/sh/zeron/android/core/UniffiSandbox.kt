package sh.zeron.android.core

import uniffi.zeron_core.UniffiLib
import uniffi.zeron_core.uniffiCallbackInterfaceClientListener
import uniffi.zeron_core.uniffiCallbackInterfaceLayoutListener
import uniffi.zeron_core.uniffiCallbackInterfacePlatformMeasurer
import uniffi.zeron_core.uniffiCallbackInterfaceUploadProgress

/**
 * Points the Rust callback vtables at *this* Robolectric sandbox.
 *
 * The native library (and its `UniffiForeignPointerCell` vtables) is
 * loaded once per JVM — JNA lives outside the sandbox class loaders — but
 * every sandbox gets its own copy of the generated `uniffi.zeron_core`
 * classes, each with its own `UniffiHandleMap`. `UniffiLib`'s initializer
 * registers the vtables, and the last sandbox to initialize wins. A test
 * in an earlier (cached, reused) sandbox then hands Rust a handle from its
 * own map while Rust calls back into the other sandbox's map: every
 * callback (`frameReady`, `measure`, `onEvent`, …) throws
 * `UniffiHandleMap.get: Invalid handle` and nothing arrives.
 *
 * Call from `@Before` in any test that relies on Kotlin-implemented
 * callback interfaces reaching Kotlin. Idempotent and cheap.
 */
object UniffiSandbox {
    fun claimCallbacks() {
        val lib = UniffiLib
        uniffiCallbackInterfaceLayoutListener.register(lib)
        uniffiCallbackInterfacePlatformMeasurer.register(lib)
        uniffiCallbackInterfaceClientListener.register(lib)
        uniffiCallbackInterfaceUploadProgress.register(lib)
    }
}
