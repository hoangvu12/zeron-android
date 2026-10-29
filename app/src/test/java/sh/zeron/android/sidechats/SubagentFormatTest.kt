package sh.zeron.android.sidechats

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The subagent screen's labels (#42, iOS `SubagentFormat` and
 * `SubagentStatusBar.configure`): model ids, the subtitle, the elapsed
 * time and the bar's texts per state. Plain JVM; the core's catalog
 * (`modelLabel`) is faked.
 */
class SubagentFormatTest {

    /** The catalog knows one id; everything else comes back unchanged (as the core does). */
    private val catalog: (String, String) -> String = { _, model -> if (model == "gpt-5.1-codex") "GPT-5.1 Codex" else model }

    @Test
    fun modelIdsReadLikeIos() {
        assertEquals("Haiku", SubagentFormat.model("haiku", catalog))
        assertEquals("Opus", SubagentFormat.model("OPUS", catalog))
        assertEquals("GPT-5.1 Codex", SubagentFormat.model("gpt-5.1-codex", catalog))
        // Unknown Claude ids: family plus short numeric parts.
        assertEquals("Haiku 5", SubagentFormat.model("claude-haiku-5", catalog))
        assertEquals("Sonnet 4.5", SubagentFormat.model("claude-sonnet-4-5-20250929", catalog))
        assertEquals("Opus", SubagentFormat.model("claude-opus", catalog))
        // Anything else passes through.
        assertEquals("mystery-1", SubagentFormat.model("mystery-1", catalog))
    }

    @Test
    fun aThrowingCatalogFallsBackToTheId() {
        assertEquals("Haiku 5", SubagentFormat.model("claude-haiku-5") { _, _ -> error("no core") })
    }

    @Test
    fun theDetailAndSubtitleJoinWhatThereIs() {
        assertEquals("Haiku 5 · Explore", SubagentFormat.detail("claude-haiku-5", "Explore", catalog))
        assertEquals("Explore", SubagentFormat.detail(null, "Explore", catalog))
        assertEquals("", SubagentFormat.detail(null, null, catalog))
        assertEquals("Subagent · Haiku 5 · Explore", SubagentFormat.subtitle("Haiku 5 · Explore"))
        assertEquals("Subagent", SubagentFormat.subtitle(""))
    }

    @Test
    fun elapsedUsesThePillFormatAndNeverGoesNegative() {
        assertEquals("1m 5s", SubagentFormat.elapsed(startedAtMs = 0, nowMs = 65_000))
        assertEquals("0s", SubagentFormat.elapsed(startedAtMs = 10_000, nowMs = 0))
    }

    @Test
    fun theBarTextsFollowTheState() {
        val now = 65_000L
        assertEquals(
            SubagentBarText("Working · 1m 5s", "Haiku 5"),
            subagentBarText(SubagentBarState.Working, "Haiku 5", 0L, now),
        )
        assertEquals(
            SubagentBarText("Working", "Runs on its own"),
            subagentBarText(SubagentBarState.Working, "", null, now),
        )
        assertEquals(SubagentBarText("Finished", null), subagentBarText(SubagentBarState.Done, "", 0L, now))
        assertEquals(SubagentBarText("Failed", "Haiku 5"), subagentBarText(SubagentBarState.Failed, "Haiku 5", 0L, now))
        assertEquals(
            SubagentBarText("Waiting for its computer…", null),
            subagentBarText(SubagentBarState.Unavailable, "Haiku 5", 0L, now),
        )
        assertEquals("Working · 1m 5s, Haiku 5", subagentBarText(SubagentBarState.Working, "Haiku 5", 0L, now).accessibilityLabel)
        assertEquals("Finished", subagentBarText(SubagentBarState.Done, "", 0L, now).accessibilityLabel)
    }
}
