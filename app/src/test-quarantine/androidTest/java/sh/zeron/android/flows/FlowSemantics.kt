package sh.zeron.android.flows

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Shared semantics matchers and artefact helpers for the instrumented
 * flow suite (android-app#30) — the pieces every ported iOS test needs.
 */
object FlowSemantics {

    /**
     * Matches a node whose text **or** content description starts with
     * [prefix] — the transcript's user-message label prefix ("You: ",
     * ui-parity #129) is exposed both ways depending on the row, so the
     * iOS `label BEGINSWITH 'You: …'` predicate ports to this.
     */
    fun startsWith(prefix: String): SemanticsMatcher =
        SemanticsMatcher("text or content description starts with '$prefix'") { node ->
            val config = node.config
            (config.contains(SemanticsProperties.Text) &&
                config[SemanticsProperties.Text].any { it.text.startsWith(prefix) }) ||
                (config.contains(SemanticsProperties.ContentDescription) &&
                    config[SemanticsProperties.ContentDescription].any { it.startsWith(prefix) })
        }

    /** [startsWith] over "You: " — the optimistic echo + settled user rows. */
    fun userEcho(): SemanticsMatcher = startsWith("You: ")

    /**
     * A session row by its chat id. `SessionRow` tags `session-<vm.id>`;
     * Zeron's chat ids are plain (the demo's `chat-veil`, …), so this is
     * the exact tag.
     */
    fun sessionRow(chatId: String): SemanticsMatcher =
        SemanticsMatcher("session row for chat id '$chatId'") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag) == "session-$chatId"
        }

    /**
     * Writes a PNG artefact into the app's external files dir, where the
     * flow script's artefact copy step picks it up (`adb pull
     * /sdcard/Android/data/sh.zeron.android/files/<name>`), like the
     * pre-rig `SessionFlowTest` did.
     */
    fun saveScreenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = context.getExternalFilesDir(null) ?: return
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        try {
            dir.resolve(name).outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        } finally {
            bitmap.recycle()
        }
    }
}

/**
 * Waits for [matcher] in the transcript, paging toward OLDER rows through
 * the host's accessibility `scrollBy` (TranscriptSemantics: a negative y
 * moves toward the top) while it is not composed. The demo (`fast`)
 * streams its multi-row reply almost at once, so a just-sent prompt can leave the
 * realized band (viewport + 700 dp overscan) before a plain wait polls it
 * (run 38552405016). The page (300 dp) is below the overscan, so no row
 * is skipped.
 */
fun AndroidComposeTestRule<*, *>.awaitTranscriptNode(matcher: SemanticsMatcher, timeoutMillis: Long = 15_000) {
    val deadline = SystemClock.uptimeMillis() + timeoutMillis
    val page = 300f * density.density
    while (onAllNodes(matcher).fetchSemanticsNodes().isEmpty()) {
        if (SystemClock.uptimeMillis() > deadline) {
            throw AssertionError("no transcript node: ${matcher.description}")
        }
        onNodeWithTag("transcript").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -page) }
        waitForIdle()
    }
}
