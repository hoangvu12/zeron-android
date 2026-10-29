package sh.zeron.android.design.lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The component lab's catalog (#12) is the source of the D7 findings: every
 * component must have all three variants, each with exactly one row per
 * contract item in [Contract] order, and a non-blank basis line.
 */
class LabCatalogTest {

    @Test
    fun coversTheTicketsTenComponents() {
        assertEquals(
            listOf(
                "Alert", "Action sheet", "Menu", "Bottom sheet", "Switch", "Toast / snackbar",
                "Segmented control", "Provider tab switcher", "Pull to refresh", "Search field",
            ),
            LabComponent.entries.map { it.title },
        )
    }

    @Test
    fun everyVariantChecksTheWholeContractInOrder() {
        for (component in LabComponent.entries) {
            for (variant in LabVariant.entries) {
                val rows = component.findings(variant)
                assertEquals("$component/$variant", Contract.entries.toList(), rows.map { it.item })
                assertTrue("$component/$variant has a blank note", rows.all { it.note.isNotBlank() })
                assertTrue("$component/$variant has no basis", component.basis[variant].orEmpty().isNotBlank())
            }
        }
    }
}
