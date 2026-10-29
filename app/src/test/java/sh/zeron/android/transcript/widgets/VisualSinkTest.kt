package sh.zeron.android.transcript.widgets

import android.graphics.Color
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.zeron.android.transcript.StyleFonts
import uniffi.zeron_core.FrameImage
import uniffi.zeron_core.NoHandle
import uniffi.zeron_core.Rgba
import uniffi.zeron_core.VisualListener
import uniffi.zeron_core.VisualPaint
import uniffi.zeron_core.VisualSession
import uniffi.zeron_core.VisualShape

/**
 * #46: the visual host's platform half — the per-id registry, the
 * attachment count and appearance forwarding of [LiveVisual], and
 * [VisualPainter]'s rasterization (native graphics, so pixels are real).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class VisualSinkTest {

    /** Records the commands a host sends; never touches Rust. */
    private class FakeSession : VisualSession(NoHandle) {
        val calls = ArrayList<String>()
        var listener: VisualListener? = null

        override fun attach(listener: VisualListener, dark: Boolean, reduceMotion: Boolean) {
            this.listener = listener
            calls += "attach(dark=$dark, reduce=$reduceMotion)"
        }

        override fun detach() {
            calls += "detach"
        }

        override fun setAppearance(dark: Boolean) {
            calls += "appearance($dark)"
        }

        override fun close() {}
    }

    // ------------------------------------------------ registry

    @Test
    fun registryRetriesUntilTheSessionExistsThenKeepsOnePerId() {
        var session: VisualSession? = null
        var lookups = 0
        val visuals = TranscriptVisuals { lookups++; session }

        assertNull(visuals.get("v1"))
        session = FakeSession()
        val first = visuals.get("v1")
        assertNotNull(first)
        assertSame(first, visuals.get("v1"))
        assertEquals("a hit never asks the core again", 2, lookups)
    }

    @Test
    fun registryAnswersNothingAfterClose() {
        val visuals = TranscriptVisuals { FakeSession() }
        assertNotNull(visuals.get("v1"))
        visuals.close()
        assertNull(visuals.get("v1"))
    }

    // ------------------------------------------------ attachment

    @Test
    fun attachesOnTheFirstHostAndDetachesAfterTheLast() {
        val session = FakeSession()
        val visual = LiveVisual(session)

        visual.acquire(density = 2f, dark = true, reduceMotion = false)
        // The row moved: the new host composes before the old one leaves.
        visual.acquire(density = 2f, dark = true, reduceMotion = false)
        visual.release()
        assertEquals(listOf("attach(dark=true, reduce=false)"), session.calls)

        visual.release()
        assertEquals(listOf("attach(dark=true, reduce=false)", "detach"), session.calls)
        assertSame(visual.sink, session.listener)

        visual.release() // an extra release is harmless
        assertEquals(2, session.calls.size)
    }

    @Test
    fun appearanceFlipsGoThroughSetAppearanceWhileAttached() {
        val session = FakeSession()
        val visual = LiveVisual(session)
        visual.acquire(density = 2f, dark = false, reduceMotion = true)
        visual.update(density = 2f, dark = false) // the first SideEffect: no change
        visual.update(density = 2f, dark = true)
        assertEquals(listOf("attach(dark=false, reduce=true)", "appearance(true)"), session.calls)

        // Detached, the flip waits for the next attach.
        visual.release()
        visual.update(density = 2f, dark = false)
        visual.acquire(density = 2f, dark = false, reduceMotion = true)
        assertEquals("attach(dark=false, reduce=true)", session.calls.last())
        assertEquals(4, session.calls.size)
    }

    @Test
    fun aNewDensityReattachesWithAFreshSink() {
        val session = FakeSession()
        val visual = LiveVisual(session)
        visual.acquire(density = 2f, dark = false, reduceMotion = false)
        val first = visual.sink
        visual.update(density = 3f, dark = true)

        assertNotSame(first, visual.sink)
        assertEquals(3f, visual.sink!!.density, 0f)
        assertSame(visual.sink, session.listener)
        // The re-attach carries the new appearance; no separate setAppearance.
        assertEquals(listOf("attach(dark=false, reduce=false)", "attach(dark=true, reduce=false)"), session.calls)
    }

    // ------------------------------------------------ painter

    @Test
    fun frameBytesSwapFromBgraToRgba() {
        // Byte-level: a device's ARGB_8888 stores RGBA, but Robolectric's
        // host Skia stores BGRA, so a pixel read here can't check the swap.
        val bytes = byteArrayOf(0x10, 0x20, 0x30, 0xFF.toByte(), 0x01, 0x02, 0x03, 0x04)
        VisualPainter.swapRedBlue(bytes, bytes.size)
        assertArrayEquals(byteArrayOf(0x30, 0x20, 0x10, 0xFF.toByte(), 0x03, 0x02, 0x01, 0x04), bytes)
        val frame = FrameImage(2u, 1u, ByteArray(8))
        val bitmap = VisualPainter.image(frame)!!
        assertEquals(2, bitmap.width)
        assertEquals(1, bitmap.height)
    }

    @Test
    fun frameImageRejectsShortOrEmptyBuffers() {
        assertNull(VisualPainter.image(FrameImage(2u, 2u, ByteArray(4))))
        assertNull(VisualPainter.image(FrameImage(0u, 1u, ByteArray(0))))
    }

    @Test
    fun emptyPaintRendersNothing() {
        assertNull(VisualPainter.render(VisualPaint("", emptyList(), emptyList()), 10f, 10f, 2f, StyleFonts()))
        val rect = VisualShape.Rect(0f, 0f, 1f, 1f, 0f, Rgba(1f, 0f, 0f, 1f))
        assertNull(VisualPainter.render(VisualPaint("", emptyList(), listOf(rect)), 0f, 10f, 2f, StyleFonts()))
    }

    @Test
    fun renderPlacesShapesInPointsTimesDensity() {
        val red = Rgba(1f, 0f, 0f, 1f)
        val blue = Rgba(0f, 0f, 1f, 1f)
        val paint = VisualPaint(
            text = "",
            runs = emptyList(),
            shapes = listOf(
                VisualShape.Rect(x = 5f, y = 0f, w = 5f, h = 10f, radius = 0f, color = red),
                // A closed filled square over the top-left quarter.
                VisualShape.Path(
                    points = listOf(0f, 0f, 4f, 0f, 4f, 4f, 0f, 4f),
                    closed = true,
                    fill = blue,
                    stroke = null,
                    lineWidth = 0f,
                    dash = null,
                ),
            ),
        )
        val bitmap = VisualPainter.render(paint, 10f, 10f, 2f, StyleFonts())!!
        assertEquals(20, bitmap.width)
        assertEquals(20, bitmap.height)
        assertEquals(Color.RED, bitmap.getPixel(15, 10))
        assertEquals(Color.BLUE, bitmap.getPixel(3, 3))
        assertEquals(Color.TRANSPARENT, bitmap.getPixel(3, 15))
    }

    @Test
    fun colorClampsTheCoresStraightAlpha() {
        assertEquals(Color.argb(0, 255, 0, 255), VisualPainter.color(Rgba(2f, -1f, 1f, 0f)))
    }
}
