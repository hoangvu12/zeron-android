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
import android.os.Looper
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.robolectric.Shadows.shadowOf
import sh.zeron.android.transcript.RowFixtures
import uniffi.zeron_core.ControlKind
import uniffi.zeron_core.FrameImage
import uniffi.zeron_core.NoHandle
import uniffi.zeron_core.Rgba
import uniffi.zeron_core.SurfaceGesture
import uniffi.zeron_core.VisualControl
import uniffi.zeron_core.VisualDisplay
import uniffi.zeron_core.VisualListener
import uniffi.zeron_core.VisualPaint
import uniffi.zeron_core.VisualSession
import uniffi.zeron_core.VisualShape
import uniffi.zeron_core.VisualSurface
import uniffi.zeron_core.Widget
import uniffi.zeron_core.WidgetKind

/**
 * #46: the visual host's platform half â€” the per-id registry, the
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

    private fun LiveVisual.present(host: Any, active: Boolean, dark: Boolean = false, density: Float = 2f, reduce: Boolean = false) =
        show(host, active, density, dark, reduce)

    @Test
    fun attachesWhileAnyHostIsActiveAndDetachesAfterTheLast() {
        val session = FakeSession()
        val visual = LiveVisual(session)
        val a = Any()
        val b = Any()

        visual.present(a, active = true, dark = true)
        // The row moved: the new host shows before the old one leaves.
        visual.present(b, active = true, dark = true)
        visual.release(a)
        assertEquals(listOf("attach(dark=true, reduce=false)"), session.calls)

        visual.release(b)
        assertEquals(listOf("attach(dark=true, reduce=false)", "detach"), session.calls)
        assertSame(visual.sink, session.listener)

        visual.release(b) // an extra release is harmless
        assertEquals(2, session.calls.size)
    }

    @Test
    fun anInactiveHostNeverAttachesAndGoingOffScreenDetaches() {
        val session = FakeSession()
        val visual = LiveVisual(session)
        val host = Any()

        // Realized in the overscan band, or the app in the background.
        visual.present(host, active = false)
        assertEquals(emptyList<String>(), session.calls)
        assertNotNull("the sink exists before frames flow", visual.sink)

        visual.present(host, active = true)
        visual.present(host, active = true) // a recomposition: no repeat
        visual.present(host, active = false)
        assertEquals(listOf("attach(dark=false, reduce=false)", "detach"), session.calls)

        visual.release(host) // already detached
        assertEquals(2, session.calls.size)
    }

    @Test
    fun appearanceFlipsGoThroughSetAppearanceWhileAttached() {
        val session = FakeSession()
        val visual = LiveVisual(session)
        val host = Any()
        visual.present(host, active = true, reduce = true)
        visual.present(host, active = true, dark = true, reduce = true)
        assertEquals(listOf("attach(dark=false, reduce=true)", "appearance(true)"), session.calls)

        // Detached, the flip waits for the next attach.
        visual.present(host, active = false, dark = true, reduce = true)
        visual.present(host, active = false, dark = false, reduce = true)
        visual.present(host, active = true, dark = false, reduce = true)
        assertEquals(listOf("attach(dark=false, reduce=true)", "appearance(true)", "detach", "attach(dark=false, reduce=true)"), session.calls)
    }

    @Test
    fun aNewDensityReattachesWithAFreshSink() {
        val session = FakeSession()
        val visual = LiveVisual(session)
        val host = Any()
        visual.present(host, active = true)
        val first = visual.sink
        visual.present(host, active = true, dark = true, density = 3f)

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

    // ------------------------------------------------ #47: bitmap pool

    @Test
    fun poolRecyclesBySizeClearedAndBounded() {
        val pool = BitmapPool(perSize = 2)
        val a = pool.take(4, 4)
        a.eraseColor(Color.RED)
        pool.give(a)
        assertEquals(1, pool.count(4, 4))

        val again = pool.take(4, 4)
        assertSame(a, again)
        assertEquals("a recycled bitmap comes back transparent", Color.TRANSPARENT, again.getPixel(1, 1))
        assertNotSame("another size never reuses it", a, pool.take(4, 5).also { pool.give(again) })

        repeat(3) { pool.give(android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888)) }
        assertEquals(2, pool.count(4, 4))
        pool.give(null)
        assertEquals(2, pool.count(4, 4))
    }

    @Test
    fun renderDrawsIntoTheAllocatorsBitmap() {
        val pool = BitmapPool()
        val target = pool.take(20, 20).also { it.eraseColor(Color.GREEN) }
        pool.give(target)
        val rect = VisualShape.Rect(0f, 0f, 1f, 1f, 0f, Rgba(1f, 0f, 0f, 1f))
        val out = VisualPainter.render(VisualPaint("", emptyList(), listOf(rect)), 10f, 10f, 2f, StyleFonts(), pool::take)
        assertSame(target, out)
        assertEquals(Color.RED, out!!.getPixel(0, 0))
        assertEquals("the old picture was cleared", Color.TRANSPARENT, out.getPixel(10, 10))
    }

    // ------------------------------------------------ #47: controls

    @Test
    fun controlTargetPadsSmallArtToAFingerSizedRect() {
        val small = ControlTarget.of(control(x = 10f, y = 20f, w = 30f, h = 14f))
        assertEquals(6f, small.x, 0f)
        assertEquals(5f, small.y, 0f) // (44 − 14) / 2 = 15 above
        assertEquals(38f, small.w, 0f)
        assertEquals(44f, small.h, 0f)
        assertTrue(small.contains(7f, 6f))
        assertFalse(small.contains(5f, 30f))

        val tall = ControlTarget.of(control(x = 0f, y = 0f, w = 10f, h = 60f))
        assertEquals(0f, tall.padY, 0f)
        assertEquals(60f, tall.h, 0f)
    }

    @Test
    fun sliderStepsFollowTheStepOrTenPercent() {
        assertEquals(9, sliderSteps(slider(min = 0.0, max = 1.0, step = 0.0)))
        assertEquals(4, sliderSteps(slider(min = 0.0, max = 100.0, step = 20.0)))
        assertEquals(9, sliderSteps(slider(min = 5.0, max = 5.0, step = 1.0)))
    }

    @Test
    fun touchTargetsCoverControlsAndDragSurfacesOfShownVisualsOnly() {
        val visuals = TranscriptVisuals { FakeSession() }
        val visual = visuals.get("v1")!!
        visual.present(Any(), active = false)
        val display = VisualDisplay(
            revision = 1uL,
            width = 300f,
            height = 200f,
            dark = false,
            paint = VisualPaint("", emptyList(), emptyList()),
            surfaces = listOf(
                VisualSurface(1uL, 0f, 100f, 100f, 100f, SurfaceGesture.DRAG),
                VisualSurface(2uL, 150f, 100f, 100f, 100f, SurfaceGesture.HORIZONTAL_DRAG),
            ),
            controls = listOf(control(x = 10f, y = 10f, w = 40f, h = 20f)),
            styles = emptyList(),
        )
        val row = RowFixtures.row().copy(
            widgets = listOf(Widget(0u, WidgetKind.Visual("v1"), 16f, 8f, 300f, 200f, null, null)),
        )
        // Not shown yet: nothing counts.
        assertFalse(visuals.isOverTouchTarget(row, 16f + 20f, 8f + 15f))

        visual.sink!!.display(display)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue("on the control", visuals.isOverTouchTarget(row, 16f + 20f, 8f + 15f))
        assertTrue("in the control's slop", visuals.isOverTouchTarget(row, 16f + 20f, 8f + 1f))
        assertTrue("on the drag surface", visuals.isOverTouchTarget(row, 16f + 50f, 8f + 150f))
        assertFalse("a scrub surface keeps the menu", visuals.isOverTouchTarget(row, 16f + 200f, 8f + 150f))
        assertFalse("bare paint", visuals.isOverTouchTarget(row, 16f + 200f, 8f + 20f))
    }

    private fun control(x: Float, y: Float, w: Float, h: Float) =
        VisualControl(7uL, ControlKind.Button(false), x, y, w, h, "Go")

    private fun slider(min: Double, max: Double, step: Double) =
        ControlKind.Slider(min, min, max, step, 0f, 0f, 100f, "")
}
