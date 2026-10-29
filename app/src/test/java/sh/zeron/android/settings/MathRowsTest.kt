package sh.zeron.android.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.zeron.android.transcript.MathSettings
import uniffi.zeron_core.MathFace

/**
 * The Settings "Math" section's pure parts (#36, `MoreViewController.swift`
 * Math): the rows for each state, iOS's copy, the two checked sheets, and
 * the stored face keys.
 */
class MathRowsTest {

    @Test
    fun renderingShowsFontAndSizeRows() {
        val rows = mathRows(MathSettings.Values())
        assertEquals(listOf("math:render", "math:font", "math:size"), rows.map { it.id })
        assertEquals("Render Math", rows[0].title)
        assertEquals("Off shows LaTeX as its source", rows[0].subtitle)
        assertEquals(true, rows[0].toggle)
        assertEquals("Fira Math — A sans that sits beside the interface font.", rows[1].subtitle)
        assertEquals("100% of the text around it", rows[2].subtitle)
    }

    @Test
    fun renderingOffLeavesOnlyTheSwitch() {
        val rows = mathRows(MathSettings.Values(render = false))
        assertEquals(listOf("math:render"), rows.map { it.id })
        assertEquals(false, rows.single().toggle)
    }

    @Test
    fun theFontSheetListsEveryFaceWithTheCurrentChecked() {
        val picked = mutableListOf<MathFace>()
        val sheet = mathFontSheet(MathFace.EULER) { picked += it }
        assertEquals("Math Font", sheet.title)
        assertEquals(
            listOf("Fira Math", "STIX Two", "Computer Modern", "Libertinus", "Palatino", "Euler"),
            sheet.actions.map { it.title },
        )
        assertEquals(listOf("Euler"), sheet.actions.filter { it.checked }.map { it.title })
        sheet.actions[1].onSelect()
        assertEquals(listOf(MathFace.STIX_TWO), picked)
    }

    @Test
    fun theSizeSheetListsTheDesktopSizes() {
        val picked = mutableListOf<Int>()
        val sheet = mathSizeSheet(120) { picked += it }
        assertEquals("Math Size", sheet.title)
        assertEquals(listOf("90%", "100%", "110%", "120%", "135%", "150%"), sheet.actions.map { it.title })
        assertEquals(listOf("120%"), sheet.actions.filter { it.checked }.map { it.title })
        sheet.actions.last().onSelect()
        assertEquals(listOf(150), picked)
    }

    @Test
    fun faceKeysRoundTripAndUnknownReadsAsFira() {
        for (face in MathSettings.allFaces) assertEquals(face, MathSettings.faceOf(MathSettings.key(face)))
        assertEquals(MathFace.FIRA, MathSettings.faceOf(null))
        assertEquals(MathFace.FIRA, MathSettings.faceOf("comic-sans"))
        assertEquals("stixTwo", MathSettings.key(MathFace.STIX_TWO))
        assertTrue(MathSettings.allFaces.size == 6)
    }

    @Test
    fun mathRowsMapToTheMathSection() {
        for (id in listOf(MathRowIds.Render, MathRowIds.Font, MathRowIds.Size)) {
            assertEquals(SettingsSection.Math, settingsSectionFor(id))
        }
    }
}
