package sh.zeron.android.newsession

/**
 * Debug-only fake of the New Project write seam (android-ui-parity #111)
 * — the gallery's, the previews' and 110's screen test's stand-in for
 * [CoreProjectWriteApi]. Every call is recorded (a readable one-line op,
 * so assertions and the gallery fixture page can share them); project
 * creates return a synthetic scoped id.
 *
 * Main code must never reference it (the debug source set is absent from
 * release builds; it is visible to `testDebugUnitTest`, which is why it
 * lives here).
 */
class FakeProjectWriteApi : ProjectWriteApi {

    /** One line per FFI call, in order. */
    val calls = mutableListOf<String>()

    override suspend fun createProject(deviceId: String, path: String, gitDetected: Boolean): String {
        calls += "createProject $deviceId $path git=$gitDetected"
        return "gallery-space"
    }
}
