package sh.zeron.android.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import sh.zeron.android.composer.ChipTint

/**
 * [contextChip] (#34, docs/port/02 C16) — `ios:Session/CoreSessionSource.
 * swift:117-127` on the plain JVM: the chip needs a token count and a
 * window `> 0`, shows from 50 % and warns from 85 % of the **unrounded**
 * fraction.
 */
class ContextUsageTest {

    @Test
    fun theChipNeedsTokensAndAPositiveWindow() {
        assertNull(contextChip(null, null))
        assertNull(contextChip(null, 1000uL))
        assertNull(contextChip(900uL, null))
        assertNull(contextChip(900uL, 0uL))
    }

    @Test
    fun theHalfThresholdUsesTheUnroundedFraction() {
        assertNull("49.9 % rounds to 50 but stays hidden", contextChip(499uL, 1000uL))
        assertEquals("50% context", contextChip(500uL, 1000uL)?.title)
    }

    @Test
    fun theWarningStartsAt85Percent() {
        val calm = contextChip(849uL, 1000uL)!!
        assertEquals(CONTEXT_SYMBOL, calm.symbol)
        assertNull(calm.tint)
        val warn = contextChip(850uL, 1000uL)!!
        assertEquals(CONTEXT_WARNING_SYMBOL, warn.symbol)
        assertEquals(ChipTint.Warning, warn.tint)
    }

    @Test
    fun theChipIsTheContextIdWithNoIconAndNoSeparateLabel() {
        val chip = contextChip(1_048_000uL, 1_048_576uL)!!
        assertEquals(SessionChipIds.Context, chip.id)
        assertEquals("100% context", chip.title)
        assertNull(chip.icon)
        assertNull(chip.contentDescription)
    }
}
