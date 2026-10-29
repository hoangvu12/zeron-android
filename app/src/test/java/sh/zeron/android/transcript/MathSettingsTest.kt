package sh.zeron.android.transcript

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.zeron_core.MathFace

/**
 * [MathSettings]' defaults (iOS: on, Fira Math, 100 %) and the change
 * channel every live transcript listens on (#29). Plain JVM.
 */
class MathSettingsTest {

    @After
    fun restoreDefaults() {
        MathSettings.update(MathSettings.Values())
    }

    @Test
    fun theDefaultsAreTheIosOnes() {
        assertEquals(MathSettings.Values(render = true, face = MathFace.FIRA, sizePercent = 100), MathSettings.current)
        assertEquals(listOf(90, 100, 110, 120, 135, 150), MathSettings.allSizes)
    }

    @Test
    fun observersSeeEachChangeOnceAndNothingAfterUnregistering() {
        val seen = mutableListOf<MathSettings.Values>()
        val stop = MathSettings.observe { seen += it }
        val bigger = MathSettings.Values(sizePercent = 135)
        MathSettings.update(bigger)
        MathSettings.update(bigger) // unchanged: not re-sent
        stop()
        MathSettings.update(MathSettings.Values(render = false))
        assertEquals(listOf(bigger), seen)
        assertEquals(false, MathSettings.current.render)
    }
}
