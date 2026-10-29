package sh.zeron.android.transcript.widgets

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import sh.zeron.android.transcript.StyleFonts
import uniffi.zeron_core.FrameImage
import uniffi.zeron_core.Rgba
import uniffi.zeron_core.RowDisplay
import uniffi.zeron_core.SurfaceFrame
import uniffi.zeron_core.SurfaceGesture
import uniffi.zeron_core.VisualControl
import uniffi.zeron_core.VisualDisplay
import uniffi.zeron_core.VisualListener
import uniffi.zeron_core.VisualPaint
import uniffi.zeron_core.VisualSession
import uniffi.zeron_core.VisualShape
import uniffi.zeron_core.WidgetKind
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.max
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
     * #47: whether row-local `(xDp, yDp)` lands on a touch target of one of
     * [d]'s visuals — a control (with its finger-sized slop) or a `drag`
     * surface, which tracks from touch-down (iOS: its recognizer begins at
     * once, so the row's long press never fires). The row menu's "not on a
     * control" rule reads it. Only visuals already shown count: no lookup.
     */
    fun isOverTouchTarget(d: RowDisplay, xDp: Float, yDp: Float): Boolean {
        for (w in d.widgets) {
            val k = w.kind as? WidgetKind.Visual ?: continue
            val shown = live[k.id]?.sink?.shown?.display ?: continue
            val x = xDp - w.x
            val y = yDp - w.y
            if (shown.controls.any { ControlTarget.of(it).contains(x, y) }) return true
            if (shown.surfaces.any { s ->
                    s.gesture == SurfaceGesture.DRAG && x >= s.x && x < s.x + s.w && y >= s.y && y < s.y + s.h
                }
            ) {
                return true
            }
        }
        return false
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
 * A control's touch rect in the visual's points (iOS
 * `VisualControlView.point(inside:)`, `VisualView.swift:480-484`): the
 * painted art plus 4 pt each side and enough above and below for a 44 pt
 * finger target.
 */
internal class ControlTarget(val padX: Float, val padY: Float, val x: Float, val y: Float, val w: Float, val h: Float) {
    fun contains(px: Float, py: Float): Boolean = px >= x && px < x + w && py >= y && py < y + h

    companion object {
        fun of(c: VisualControl): ControlTarget {
            val padX = 4f
            val padY = max(0f, (44f - c.h) / 2)
            return ControlTarget(padX, padY, c.x - padX, c.y - padY, max(c.w, 0f) + padX * 2, max(c.h, 0f) + padY * 2)
        }
    }
}

/**
 * The platform half of one visual (iOS `VisualView` minus the view): its
 * session, the [VisualSink] that receives the worker's output at the
 * current density, and the attachment.
 *
 * Frames flow only while the visual can be seen (iOS `window != nil &&
 * active && foreground`, `VisualView.swift:65-75`; port 04 F4): each host
 * composing it reports whether it is **active** — its row on screen, its
 * screen shown, the app started — and the worker is attached while at
 * least one is. A visual whose row moved shows in the new row before the
 * old one is released, so the set keeps it attached across the move.
 * Main thread only.
 */
@Stable
class LiveVisual internal constructor(val session: VisualSession) {
    /** What the host paints; replaced (and re-attached) when the density changes. */
    var sink: VisualSink? by mutableStateOf(null)
        private set

    private val active = HashSet<Any>()
    private var attached = false
    private var dark = false

    /**
     * A host's state (a SideEffect, every composition): attach when the
     * first host turns active, carrying the appearance and Reduce Motion
     * (`VisualView.swift:71`); detach when none is. A new density rasterizes
     * into a new sink — re-attached, which makes the worker resend
     * everything. An appearance flip goes through `setAppearance`
     * (`VisualView.swift:77-82`) while attached and rides the next attach
     * otherwise.
     */
    fun show(host: Any, isActive: Boolean, density: Float, dark: Boolean, reduceMotion: Boolean) {
        val fresh = sinkFor(density)
        if (isActive) active.add(host) else active.remove(host)
        val wanted = active.isNotEmpty()
        when {
            wanted && (!attached || fresh) -> {
                attached = true
                this.dark = dark
                session.attach(sink!!, dark, reduceMotion)
            }
            !wanted && attached -> {
                attached = false
                this.dark = dark
                session.detach()
            }
            dark != this.dark -> {
                this.dark = dark
                if (attached) session.setAppearance(dark)
            }
        }
    }

    /** A host left composition: detach after the last active one. */
    fun release(host: Any) {
        active.remove(host)
        if (active.isEmpty() && attached) {
            attached = false
            session.detach()
        }
    }

    /** Ensures a sink at [density]; true when it had to make one. */
    private fun sinkFor(density: Float): Boolean {
        if (sink?.density == density) return false
        sink = VisualSink(density)
        return true
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

    /**
     * #47 (port 04 §2.6 step 9): surface frames arrive at up to 60 fps, so
     * their bitmaps are recycled — a replaced picture goes back to the pool
     * two frames after it left the screen ([retire]), never while it may
     * still be drawn. Display paint (rare) is not pooled.
     */
    private val pool = BitmapPool()

    override fun frame(frame: SurfaceFrame) {
        fonts.update(frame.styles)
        val size = synchronized(lock) { sizes[frame.surface] } ?: return
        val image = frame.image?.let { VisualPainter.image(it, pool::take) }
        val paint = VisualPainter.render(frame.paint, size.first, size.second, density, fonts, pool::take)
        val replaced = synchronized(lock) {
            val previous = pendingFrames.put(frame.surface, SurfacePicture(image, paint))
            schedule()
            previous
        }
        // Superseded before main ever saw it: straight back to the pool.
        replaced?.let { pool.give(it.image); pool.give(it.paint) }
    }

    /** Two vsyncs later the frame that last drew [old] has synced: its bitmaps are free. */
    private fun retire(old: List<SurfacePicture>) {
        if (old.isEmpty()) return
        val choreographer = Choreographer.getInstance()
        choreographer.postFrameCallback {
            choreographer.postFrameCallback {
                for (p in old) {
                    pool.give(p.image)
                    pool.give(p.paint)
                }
            }
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
            val old = ArrayList<SurfacePicture>(latest.size)
            for ((id, picture) in latest) frames.put(id, picture)?.let(old::add)
            retire(old)
        }
    }
}

/**
 * Mutable `ARGB_8888` bitmaps by size, a few each (#47). [take] runs on
 * the worker, [give] on either thread.
 */
internal class BitmapPool(private val perSize: Int = 3) {
    private val lock = Any()
    private val free = HashMap<Long, ArrayDeque<Bitmap>>()

    /** A transparent `w × h` bitmap: a recycled one when there is one. */
    fun take(w: Int, h: Int): Bitmap {
        val reused = synchronized(lock) { free[key(w, h)]?.removeLastOrNull() }
        return reused?.apply { eraseColor(Color.TRANSPARENT) } ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    }

    fun give(bitmap: Bitmap?) {
        if (bitmap == null || bitmap.isRecycled || !bitmap.isMutable) return
        synchronized(lock) {
            val q = free.getOrPut(key(bitmap.width, bitmap.height)) { ArrayDeque() }
            if (q.size < perSize) q.addLast(bitmap)
        }
    }

    /** How many are waiting at `w × h` (tests). */
    fun count(w: Int, h: Int): Int = synchronized(lock) { free[key(w, h)]?.size ?: 0 }

    private fun key(w: Int, h: Int): Long = (w.toLong() shl 32) or h.toLong()
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
     * place: the frame's array is ours. [alloc] makes (or recycles) the
     * `w × h` bitmap; every pixel is overwritten.
     */
    fun image(frame: FrameImage, alloc: (Int, Int) -> Bitmap = ::newBitmap): Bitmap? {
        val w = frame.width.toInt()
        val h = frame.height.toInt()
        val bytes = frame.bgra
        val n = w * h * 4
        if (w <= 0 || h <= 0 || bytes.size < n) return null
        swapRedBlue(bytes, n)
        return alloc(w, h).apply {
            copyPixelsFromBuffer(ByteBuffer.wrap(bytes, 0, n))
        }
    }

    private fun newBitmap(w: Int, h: Int): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

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

    /**
     * [paint] over a `width × height` pt bitmap from [alloc] (transparent);
     * `null` when empty (nothing to show).
     */
    fun render(
        paint: VisualPaint,
        width: Float,
        height: Float,
        density: Float,
        fonts: StyleFonts,
        alloc: (Int, Int) -> Bitmap = ::newBitmap,
    ): Bitmap? {
        val w = ceil(width * density).toInt()
        val h = ceil(height * density).toInt()
        if (w <= 0 || h <= 0) return null
        if (paint.shapes.isEmpty() && paint.runs.isEmpty()) return null
        val bitmap = alloc(w, h)
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
