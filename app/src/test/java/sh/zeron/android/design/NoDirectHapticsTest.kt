package sh.zeron.android.design

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Every haptic must go through `LocalFeedback`, so the gate and the ladder
 * apply (android-ui-parity #05): no main source may touch the vibrator, the
 * view haptics API, or Compose's own `LocalHapticFeedback` directly.
 *
 * Debug sources (`src/debug`, e.g. ticket 22's `HapticsGalleryHost`, which
 * looks up the `VibratorManager` to call `probe`) are not scanned. A bare
 * `Vibrator` token is deliberately not used: `FeedbackGate.kt` legitimately
 * contains `hasVibrator` / `NoVibrator`.
 */
class NoDirectHapticsTest {

    private data class Token(val text: String, val allowed: Set<String>)

    private val androidFeedbackOnly = setOf("AndroidFeedback.kt")
    private val androidFeedbackAndTable = setOf("AndroidFeedback.kt", "HapticTable.kt")

    private val tokens = listOf(
        Token("performHapticFeedback(", androidFeedbackOnly),
        Token("VibratorManager", androidFeedbackOnly),
        Token("android.os.Vibrator", androidFeedbackOnly),
        Token(".vibrate(", androidFeedbackOnly),
        Token("VibrationAttributes", androidFeedbackOnly),
        Token("VibrationEffect", androidFeedbackAndTable),
        Token("HapticFeedbackConstants", androidFeedbackAndTable),
        Token("LocalHapticFeedback", emptySet()),
        Token("HapticFeedbackType", emptySet()),
    )

    @Test
    fun mainSourcesGoThroughLocalFeedbackOnly() {
        val violations = violations(kotlinSources(appRoot()))
        assertEquals("Every haptic must go through LocalFeedback: $violations", emptyList<String>(), violations)
    }

    @Test
    fun aStrayDirectHapticCallIsCaughtWithFileAndToken() {
        val strayDir = appRoot().resolve("src/main/java/sh/zeron/android")
        val stray = File.createTempFile("stray", ".kt", strayDir)
        try {
            stray.writeText(
                """
                package sh.zeron.android

                import androidx.compose.ui.platform.LocalHapticFeedback

                fun stray() {
                    LocalHapticFeedback.current
                }
                """.trimIndent(),
            )
            val violations = violations(kotlinSources(appRoot()))
            assertEquals(listOf("${stray.name}: LocalHapticFeedback"), violations)
        } finally {
            stray.delete()
        }
    }

    /** The `app` module: `user.dir` under Gradle unit tests, else found walking up from it. */
    private fun appRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")!!)
        while (dir != null) {
            if (dir.resolve("src/main/java").isDirectory) return dir
            val app = dir.resolve("app")
            if (app.resolve("src/main/java").isDirectory) return app
            dir = dir.parentFile
        }
        throw IllegalStateException("cannot locate the app module from ${System.getProperty("user.dir")}")
    }

    private fun kotlinSources(root: File): List<File> =
        root.resolve("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun violations(files: List<File>): List<String> = buildList {
        for (file in files) {
            val text = file.readText()
            for (token in tokens) {
                if (token.text in text && file.name !in token.allowed) add("${file.name}: ${token.text}")
            }
        }
    }
}
