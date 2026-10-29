package sh.zeron.android.transcript.widgets

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import sh.zeron.android.transcript.StyleFonts
import uniffi.zeron_core.FrameImage
import uniffi.zeron_core.Rgba
import uniffi.zeron_core.SurfaceFrame
import uniffi.zeron_core.VisualDisplay
import uniffi.zeron_core.VisualListener
import uniffi.zeron_core.VisualPaint
import uniffi.zeron_core.VisualSession
import uniffi.zeron_core.VisualShape
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.min

/**
 * One transcript's agent visuals by id (#46; iOS `TranscriptListView.visuals`,
 * `TranscriptListView.swift:93-95, 731-741`): one [LiveVisual] each, kept for
 * the transcript's lifetime, so a row that leaves the band and comes back —
 * or a visual whose row is rebuilt — shows its last picture at once instead
 * of a blank rect (port 04 F3). The worker itself lives in the core's hub
 * either way; this keeps the platform half (listener, bitmaps).
 *
 * [lookup] is `TranscriptView.visual(id)`: `null` until the layout worker
 * has spawned the visual's session (it needs the source first), so [get]
 * retries on every call until it answers. Main thread only.
 */
class TranscriptVisuals(private val lookup: (String) -> VisualSession?) {
    private val live = HashMap<String, LiveVisual>()
    private var closed = false

    /** The live visual for [id], or `null` until its session exists (or after [close]). */
    fun get(id: String): LiveVisual? {
        if (closed) return null
        live[id]?.let { return it }
        val session = lookup(id) ?: return null
        return LiveVisual(session).also { live[id] = it }
    }

    /**
     * The transcript's teardown, before the engine closes (its `shutdown`
     * stops every worker): no lookups after this. The sessions are left to
     * their cleaner — a row still animating out may hold one for a frame.
     */
    fun close() {
        closed = true
        live.clear()
    }
}

/**
 * The platform half of one visual (iOS `VisualView` minus the view): its
 * session, the [VisualSink] that receives the worker's output at the
 * current density, and the attachment. Every host composing it
 * [acquire]s it; frames flow while at least one does (iOS: `window != nil`
 * — a visual whose row moved composes in the new row before the old one is
 * released, so counting keeps it attached across the move). Main thread
 * only.
 *
 * TODO(#47): attach only while the row is realized **and** the app is in
 * the foreground (iOS `active && foreground`, `VisualView.swift:65-75`) —
 * today a composed visual stays attached in the background.
 */
@Stable
class LiveVisual internal constructor(val session: VisualSession) {
    /** What the host paints; replaced (and re-attached) when the density changes. */
    var sink: VisualSink? by mutableStateOf(null)
        private set

    private var hosts = 0
    private var attached = false
    private var dark = false
    private var reduceMotion = false

    /** A host composed this visual: attach on the first one, carrying the appearance (`VisualView.swift:71`). */
    fun acquire(density: Float, dark: Boolean, reduceMotion: Boolean) {
        this.dark = dark
        this.reduceMotion = reduceMotion
        sinkFor(density)
        hosts++
        sync()
    }

    /** A host left: detach after the last one. */
    fun release() {
        if (hosts == 0) return
        hosts--
        sync()
    }

    /**
     * The host's latest density and appearance (a SideEffect): a new density
     * rasterizes into a new sink — re-attached, which makes the worker resend
     * everything; an appearance flip goes through `setAppearance`
     * (`VisualView.swift:77-82`) while attached, and rides the next attach
     * otherwise.
     */
    fun update(density: Float, dark: Boolean) {
        val fresh = sinkFor(density)
        if (fresh && attached) session.attach(sink!!, dark, reduceMotion)
        if (dark != this.dark) {
            this.dark = dark
            if (attached && !fresh) session.setAppearance(dark)
        }
    }

    /** Ensures a sink at [density]; true when it had to make one. */
    private fun sinkFor(density: Float): Boolean {
        if (sink?.density == density) return false
        sink = VisualSink(density)
        return true
    }

    private fun sync() {
        val wanted = hosts > 0
        if (wanted == attached) return
        attached = wanted
        if (wanted) session.attach(sink!!, dark, reduceMotion) else session.detach()
    }
}

/** A surface's latest picture: the worker's image, then its vector paint over it. */
class SurfacePicture(val image: Bitmap?, val paint: Bitmap?)

/** A display and its static paint, rasterized. */
class ShownDisplay(val display: VisualDisplay, val base: Bitmap?)

/**
 * Receives the worker's output on its thread, rasterizes it there, and
 * hands the main thread only the latest of each (iOS `VisualSink`,
 * `VisualView.swift:147-207`; port 04 F7). The [fonts] here are this
 * sink's own, confined to the worker's thread: style ids are per visual,
 * never the transcript's.
 */
class VisualSink(val density: Float) : VisualListener {
    private val main = Handler(Looper.getMainLooper())
    /** Worker thread only (paint colours mutate per run). */
    private val fonts = StyleFonts()
    private val lock = Any()
    private var sizes: Map<ULong, Pair<Float, Float>> = emptyMap()
    private var pendingDisplay: ShownDisplay? = null
    private val pendingFrames = HashMap<ULong, SurfacePicture>()
    private var scheduled = false

    /** The latest display, main thread. */
    var shown: ShownDisplay? by mutableStateOf(null)
        private set

    /** Each live surface's latest picture, main thread (read in the draw phase). */
    val frames = mutableStateMapOf<ULong, SurfacePicture>()

    override fun display(display: VisualDisplay) {
        fonts.update(display.styles)
        val base = VisualPainter.render(display.paint, display.width, display.height, density, fonts)
        synchronized(lock) {
            sizes = display.surfaces.associate { it.id to (it.w to it.h) }
            pendingDisplay = ShownDisplay(display, base)
            schedule()
        }
    }

    override fun frame(frame: SurfaceFrame) {
        fonts.update(frame.styles)
        val size = synchronized(lock) { sizes[frame.surface] } ?: return
        val image = frame.image?.let(VisualPainter::image)
        val paint = VisualPainter.render(frame.paint, size.first, size.second, density, fonts)
        synchronized(lock) {
            pendingFrames[frame.surface] = SurfacePicture(image, paint)
            schedule()
        }
    }

    /** Called with the lock held: one main-thread hop drains whatever is newest. */
    private fun schedule() {
        if (scheduled) return
        scheduled = true
        main.post {
            val display: ShownDisplay?
            val latest: Map<ULong, SurfacePicture>
            synchronized(lock) {
                display = pendingDisplay
                latest = HashMap(pendingFrames)
                pendingDisplay = null
                pendingFrames.clear()
                scheduled = false
            }
            if (display != null) {
                shown = display
                val live = display.display.surfaces.mapTo(HashSet()) { it.id }
                frames.keys.retainAll(live)
            }
            frames.putAll(latest)
        }
    }
}

/**
 * Draws Rust-placed shapes and runs into bitmaps (iOS `VisualPainter`,
 * `VisualView.swift:209-303`). Points × density = px; safe off the main
 * thread as long as each [StyleFonts] stays on one thread.
 */
object VisualPainter {
    /** sRGB, straight alpha (the core's [Rgba]). */
    fun color(c: Rgba): Int = android.graphics.Color.argb(
        c.a.coerceIn(0f, 1f),
        c.r.coerceIn(0f, 1f),
        c.g.coerceIn(0f, 1f),
        c.b.coerceIn(0f, 1f),
    )

    /**
     * A worker frame (premultiplied BGRA, top row first) as a bitmap.
     * `ARGB_8888` stores its bytes as RGBA, so red and blue swap first — in
     * place: the frame's array is ours.
     */
    fun image(frame: FrameImage): Bitmap? {
        val w = frame.width.toInt()
        val h = frame.height.toInt()
        val bytes = frame.bgra
        val n = w * h * 4
        if (w <= 0 || h <= 0 || bytes.size < n) return null
        swapRedBlue(bytes, n)
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            copyPixelsFromBuffer(ByteBuffer.wrap(bytes, 0, n))
        }
    }

    /** BGRA ↔ RGBA over the first [n] bytes, in place. */
    internal fun swapRedBlue(bytes: ByteArray, n: Int) {
        var i = 0
        while (i < n) {
            val b = bytes[i]
            bytes[i] = bytes[i + 2]
            bytes[i + 2] = b
            i += 4
        }
    }

    /** [paint] over a `width × height` pt bitmap; `null` when empty (nothing to show). */
    fun render(paint: VisualPaint, width: Float, height: Float, density: Float, fonts: StyleFonts): Bitmap? {
        val w = ceil(width * density).toInt()
        val h = ceil(height * density).toInt()
        if (w <= 0 || h <= 0) return null
        if (paint.shapes.isEmpty() && paint.runs.isEmpty()) return null
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        draw(paint, Canvas(bitmap), density, fonts)
        return bitmap
    }

    /** Shapes, then text over them, top-left origin in px. */
    fun draw(paint: VisualPaint, canvas: Canvas, d: Float, fonts: StyleFonts) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        val rect = RectF()
        for (shape in paint.shapes) {
            when (shape) {
                is VisualShape.Rect -> {
                    rect.set(shape.x * d, shape.y * d, (shape.x + shape.w) * d, (shape.y + shape.h) * d)
                    val r = min(shape.radius * d, min(rect.width(), rect.height()) / 2)
                    fill.color = color(shape.color)
                    if (r > 0f) canvas.drawRoundRect(rect, r, r, fill) else canvas.drawRect(rect, fill)
                }
                is VisualShape.Path -> {
                    val pts = shape.points
                    if (pts.size < 4) continue
                    val path = Path()
                    path.moveTo(pts[0] * d, pts[1] * d)
                    var i = 2
                    while (i + 1 < pts.size) {
                        path.lineTo(pts[i] * d, pts[i + 1] * d)
                        i += 2
                    }
                    if (shape.closed) path.close()
                    shape.fill?.let {
                        fill.color = color(it)
                        canvas.drawPath(path, fill)
                    }
                    shape.stroke?.let {
                        stroke.color = color(it)
                        stroke.strokeWidth = shape.lineWidth * d
                        stroke.pathEffect = shape.dash?.let { dash -> DashPathEffect(floatArrayOf(dash * d, dash * d), 0f) }
                        canvas.drawPath(path, stroke)
                    }
                }
            }
        }
        if (paint.runs.isEmpty()) return
        fonts.useDensity(d)
        val text = paint.text
        for (run in paint.runs) {
            val start = run.start.toInt()
            val end = start + run.len.toInt()
            if (end > text.length) continue
            val p = fonts[run.style]?.paint ?: continue
            p.color = color(run.color)
            canvas.drawText(text, start, end, run.x * d, run.baseline * d, p)
        }
    }
}
