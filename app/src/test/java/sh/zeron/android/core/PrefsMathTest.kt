package sh.zeron.android.core

import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import sh.zeron.android.sessions.MemoryPrefsStore
import sh.zeron.android.transcript.MathSettings
import uniffi.zeron_core.MathFace

/**
 * [Prefs.math] (#36): iOS's keys and defaults (`mathRender` on,
 * `mathFont` Fira, `mathSize` 100), the stored values loaded, and a
 * change persisted under all three keys.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PrefsMathTest {

    @Test
    fun absentKeysReadAsTheIosDefaults() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val prefs = Prefs(MemoryPrefsStore(), scope, io = StandardTestDispatcher(testScheduler))
        prefs.awaitLoaded()
        assertEquals(MathSettings.Values(render = true, face = MathFace.FIRA, sizePercent = 100), prefs.math.value)
        scope.cancel()
    }

    @Test
    fun storedValuesLoad() = runTest {
        val store = MemoryPrefsStore(
            mutablePreferencesOf(
                PrefKeys.MATH_RENDER to false,
                PrefKeys.MATH_FONT to "euler",
                PrefKeys.MATH_SIZE to 135,
            ),
        )
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val prefs = Prefs(store, scope, io = StandardTestDispatcher(testScheduler))
        prefs.awaitLoaded()
        assertEquals(MathSettings.Values(render = false, face = MathFace.EULER, sizePercent = 135), prefs.math.value)
        scope.cancel()
    }

    @Test
    fun setMathPublishesAtOnceAndPersistsAllThreeKeys() = runTest {
        val store = MemoryPrefsStore()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val prefs = Prefs(store, scope, io = StandardTestDispatcher(testScheduler))
        prefs.awaitLoaded()
        val next = MathSettings.Values(render = true, face = MathFace.STIX_TWO, sizePercent = 120)
        prefs.setMath(next)
        assertEquals(next, prefs.math.value)
        advanceUntilIdle()
        assertEquals(true, store.current[PrefKeys.MATH_RENDER])
        assertEquals("stixTwo", store.current[PrefKeys.MATH_FONT])
        assertEquals(120, store.current[PrefKeys.MATH_SIZE])
        scope.cancel()
    }
}
