package sh.zeron.android.composer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wave-10 38's picker rules (upstream #966 `ModelPickerViewController`)
 * on the plain JVM: dedupe, the search threshold and filter, the empty
 * lines, the switcher's short Claude, the header line, the first page and
 * the page notices.
 */
class ModelPickerRulesTest {

    private fun row(id: String, label: String = id) = ModelPickerRow(id = id, label = label, detail = null)

    @Test
    fun dedupeKeepsTheFirstOfEachId() {
        assertEquals(listOf("a", "b"), dedupeModels(listOf(row("a", "A1"), row("b"), row("a", "A2"))).map { it.id })
        assertEquals("A1", dedupeModels(listOf(row("a", "A1"), row("a", "A2"))).single().label)
    }

    @Test
    fun searchShowsPastTwelveAndMatchesNameOrIdCaseFolded() {
        assertFalse(showsModelSearch((1..12).map { row("m$it") }))
        assertTrue(showsModelSearch((1..13).map { row("m$it") }))
        val rows = listOf(row("gpt-5", "GPT 5"), row("claude-fable-5", "Fable 5"), row("o4-mini", "o4 mini"))
        assertEquals(listOf("claude-fable-5"), filterModels(rows, "  FABLE ").map { it.id })
        assertEquals(listOf("o4-mini"), filterModels(rows, "o4-").map { it.id })
        assertEquals(rows, filterModels(rows, "   "))
        assertEquals(ModelPickerCopy.NoModels, modelEmptyText(""))
        assertEquals(ModelPickerCopy.NoMatches, modelEmptyText("zzz"))
        assertEquals("Search Codex models", ModelPickerCopy.searchPlaceholder("Codex"))
    }

    @Test
    fun theHeaderTheSwitcherAndTheFirstPage() {
        val claude = ModelPickerTab("claude-code", "Claude Code")
        val codex = ModelPickerTab("codex", "Codex", footer = "Your next message continues this chat with Codex.")
        assertEquals("Claude Code · Studio", pickerSubtitle(listOf(claude), "Studio"))
        assertEquals("Studio", pickerSubtitle(listOf(claude, codex), "Studio"))
        assertEquals("Claude", switcherTitle("Claude Code"))
        assertEquals("Codex", switcherTitle("Codex"))
        assertEquals(1, initialPickerPage(listOf(claude, codex), "codex"))
        assertEquals(0, initialPickerPage(listOf(claude, codex), "pi"))
        assertEquals("note", pickerPageNotice(claude, page = 0, initial = 0, note = "note"))
        assertNull(pickerPageNotice(claude, page = 0, initial = 1, note = "note"))
        assertEquals(codex.footer, pickerPageNotice(codex, page = 1, initial = 0, note = "note"))
    }

    @Test
    fun theFitIsTheListUnderTheHeaderBetweenTheFloorAndTheCap() {
        // Nothing measured yet: 55% of the large height.
        assertEquals(550f, fittedPickerHeight(1000f, 120f, null, 280f), 0.01f)
        assertEquals(550f, fittedPickerHeight(1000f, 120f, 0f, 280f), 0.01f)
        assertEquals(420f, fittedPickerHeight(1000f, 120f, 300f, 280f), 0.01f)
        assertEquals("the floor", 280f, fittedPickerHeight(1000f, 120f, 60f, 280f), 0.01f)
        assertEquals("the cap", 780f, fittedPickerHeight(1000f, 120f, 5000f, 280f), 0.01f)
    }

    @Test
    fun switcherSegmentsShareTheTrackThenShareOutThenShrinkToLogos() {
        assertEquals(54f + 40f, segmentNaturalWidth(40f), 0.01f)
        // The widest fits n times: equal widths after the 3 dp inset.
        val equal = switcherFrames(listOf(80f, 100f), 300f, selected = 0)
        assertEquals(listOf(SegmentFrame(3f, 150f), SegmentFrame(153f, 150f)), equal)
        // The sum fits: natural widths plus an equal share of the rest.
        val shared = switcherFrames(listOf(80f, 200f), 300f, selected = 0)
        assertEquals(listOf(SegmentFrame(3f, 90f), SegmentFrame(93f, 210f)), shared)
        assertFalse(switcherCompact(listOf(80f, 200f), 300f))
        // Compact: the chosen one named, the others logos (46 dp).
        val natural = listOf(120f, 120f, 120f)
        assertTrue(switcherCompact(natural, 300f))
        val compact = switcherFrames(natural, 300f, selected = 1)
        val share = (300f - (SwitcherMetrics.LogoWidth * 2 + 120f)) / 3
        val expected = listOf(SwitcherMetrics.LogoWidth + share, 120f + share, SwitcherMetrics.LogoWidth + share)
        expected.zip(compact) { w, f -> assertEquals(w, f.width, 0.01f) }
        assertEquals(3f, compact.first().x, 0.01f)
        assertEquals(303f, compact.last().end, 0.01f)
        // Past even that, the track scrolls: no negative share.
        val wide = switcherFrames(List(8) { 120f }, 300f, selected = 0)
        assertEquals(120f + 7 * SwitcherMetrics.LogoWidth + 3f, wide.last().end, 0.01f)
        assertTrue(switcherFrames(emptyList(), 300f, 0).isEmpty())
    }
}
