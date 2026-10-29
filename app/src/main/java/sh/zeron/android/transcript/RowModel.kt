package sh.zeron.android.transcript

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.fonts.Font
import android.util.Log
import sh.zeron.android.core.FontChain
import sh.zeron.android.design.color
import uniffi.zeron_core.FadeEdge
import uniffi.zeron_core.GlyphPrim
import uniffi.zeron_core.RowDisplay

/**
 * One row's display list plus everything cheap to derive from it: the
 * layer grouping of runs and boxes, and which runs each overflow fade
 * masks (iOS `RowModel`, `ios:Transcript/RowModel.swift:7-60`). Built
 * once per (row, version, width) — construction is pure Kotlin over the
 * record, safe on any thread (ticket 117 builds these on the prefetch
 * thread) — and it holds no colours, fonts or [android.graphics.Paint]s,
 * so theme and density changes only repaint. The one lazily filled cache
 * is [widePlan]'s per-run double-width plan: a plain [IntArray] derived
 * only from the text and the style's face, but filled on the drawing
 * (main) thread only — ticket 117's prefetch must not touch it.
 *
 * Unlike iOS, Android pre-builds nothing per run at construction (no
 * CTLines): [draw] paints straight from [RowDisplay.text] through
 * [StyleFonts], so a style missing at build time cannot permanently
 * blank a run.
 */
class RowModel(val display: RowDisplay) {

    /** A fade (index into [RowDisplay.fades]) and the runs it masks, in assignment order. */
    class FadeGroup(val fade: Int, val runs: IntArray)

    /**
     * Which runs to paint — the streaming veil (ticket 118). The iOS cases
     * map exactly: `.settled(v)` = [Chrome] + [Range]`(0, v)` and
     * `.fresh(v)` = [Range]`(v, Int.MAX_VALUE)`; the only ordering
     * difference is that fades paint before settled text instead of
     * after, which is invisible because fade runs and plain runs are
     * disjoint.
     *
     * A formula is one placeholder (U+FFFC) in the text, so it veils whole
     * (#29, RM:185-251): its glyphs and its fills (`BoxPrim.start != null`)
     * belong to the text passes, never to [Chrome].
     */
    sealed interface Pass {
        /** Boxes, every non-faded run, glyphs, fades. */
        data object All : Pass

        /** Settled boxes (no `start`) and fades only; the text comes from [Range] passes. */
        data object Chrome : Pass

        /**
         * Only text in `[from, to)` (UTF-16), clipped at glyph edges, plus
         * the formula fills and glyphs whose placeholder is in the range,
         * unclipped; no settled boxes, no fades.
         */
        data class Range(val from: Int, val to: Int) : Pass {
            init { require(from in 0..to) { "bad range [$from, $to)" } }
        }
    }

    /** Layer 0 is the row canvas; layer n is scroller n−1's content. */
    val layers: Int = display.scrollers.size + 1

    /** Run indices per layer, ascending (run n of scroller s is in layer s+1). */
    val runsByLayer: Array<IntArray>

    /** Box indices per layer, ascending. */
    val boxesByLayer: Array<IntArray>

    /** Math glyph indices per layer, ascending (#29). */
    val glyphsByLayer: Array<IntArray>

    /**
     * The math fonts the glyphs name, by table index — resolved here, off
     * main on the prefetch path (a face's first use copies ~1–2 MB out of
     * the core), so drawing only reads loaded fonts (iOS `mathFonts`). A
     * glyph whose font is missing draws nothing.
     */
    private val mathFonts: Map<Int, Font>

    /** Per layer, in display order: each fade and the runs it masks. */
    val fadesByLayer: Array<Array<FadeGroup>>

    private val faded = BooleanArray(display.runs.size)

    /** Ticket 63's per-run double-width plan cache; see [widePlan]. */
    private val widePlans = arrayOfNulls<IntArray>(display.runs.size)

    /** iOS `fadedRun.contains(i)`: the run paints only through its fade. */
    fun isFaded(run: Int): Boolean = faded[run]

    /**
     * iOS `RowModel.swift:28` guard: the run's UTF-16 range inside the text
     * and a known style — else the run is skipped entirely (a nil CTLine on
     * iOS draws no text *and* no bar). Shared by this class's own draw loop
     * and the [RunPainter] facade so both guards agree. The long addition
     * avoids UInt overflow on a malformed `start`/`len` pair.
     */
    internal fun entryFor(i: Int, fonts: StyleFonts): StyleFonts.Entry? {
        val r = display.runs[i]
        if (r.start.toLong() + r.len.toLong() > display.text.length) return null
        return fonts[r.style]
    }

    /**
     * Double-width clusters of run i ([MonoWide.NONE] unless the style's
     * face is Mono and the run has some), computed once and cached: the
     * plan depends only on the text and the style's face, and a style id's
     * face never changes within one view, so the cache survives
     * [StyleFonts] rebuilds. Drawing (main) thread only — the cache array
     * is unsynchronised.
     */
    internal fun widePlan(i: Int, font: StyleFonts.Entry): IntArray {
        widePlans[i]?.let { return it }
        val r = display.runs[i]
        val s = r.start.toInt()
        val p = if (!FontChain.isMono(font.face)) MonoWide.NONE
            else MonoWide.plan(display.text, s, s + r.len.toInt())
        widePlans[i] = p
        return p
    }

    init {
        // A scroller index >= display.scrollers.size is malformed (Rust
        // never emits it; iOS would crash). The primitive is dropped from
        // every layer and the model logs once.
        var malformedScroller = false

        fun layerOf(s: UInt?): Int {
            val layer = if (s == null) 0 else s.toInt() + 1
            if (layer >= layers) malformedScroller = true
            return layer
        }

        runsByLayer = group(display.runs.size) { layerOf(display.runs[it].scroller) }
        boxesByLayer = group(display.boxes.size) { layerOf(display.boxes[it].scroller) }
        glyphsByLayer = group(display.glyphs.size) { layerOf(display.glyphs[it].scroller) }

        val fonts = HashMap<Int, Font>()
        val unknown = HashSet<Int>()
        for (g in display.glyphs) {
            val index = g.font.toInt()
            if (index in fonts || index in unknown) continue
            val font = MathFonts.get(g.font)
            if (font != null) fonts[index] = font else unknown.add(index)
        }
        mathFonts = fonts

        // Fade assignment, iOS RowModel.swift:43-54. Fades are visited in
        // display order, runs in index order within the fade's own layer;
        // a run is masked by at most one fade — the first that claims it.
        // The vertical test is open below and closed above with a half-unit
        // slack; Trailing fades also need the run to start left of the
        // fade's right edge, Bottom fades take any x. A fade that claims
        // no runs is still recorded (it draws nothing).
        val groups = Array(layers) { ArrayList<FadeGroup>() }
        for (fi in display.fades.indices) {
            val f = display.fades[fi]
            val layer = layerOf(f.scroller)
            if (layer >= layers) continue // malformed: dropped
            val candidates = runsByLayer[layer]
            val masked = IntArray(candidates.size)
            var n = 0
            for (i in candidates) {
                if (faded[i]) continue
                val r = display.runs[i]
                if (!(r.baseline > f.y && r.baseline <= f.y + f.h + 0.5f)) continue
                if (f.edge != FadeEdge.BOTTOM && r.x >= f.x + f.w) continue
                masked[n++] = i
            }
            for (k in 0 until n) faded[masked[k]] = true
            groups[layer].add(FadeGroup(fi, masked.copyOf(n)))
        }
        fadesByLayer = Array(layers) { groups[it].toTypedArray() }

        if (malformedScroller) {
            Log.w(TAG, "row ${display.key}: dropping primitives with scroller index >= ${display.scrollers.size}")
        }
    }

    /**
     * Paints one layer (0 = the row's top-left, n = scroller n−1's content
     * top-left — the host applies the scroller offset) onto a plain [Canvas]
     * in px, in the fixed order boxes → non-faded runs → math glyphs →
     * fades (each fade then paints its own masked runs), iOS RM:185-253.
     *
     * Drawing (main) thread only: the first statement syncs [fonts] to
     * [density] (one float compare when unchanged, so hosts pass no other
     * density plumbing) and the element painters mutate shared scratch.
     * [palette] is read per call — theme switches only repaint, the model
     * is never rebuilt. [layer] out of range is a no-op, not a crash.
     */
    fun draw(
        layer: Int,
        canvas: Canvas,
        density: Float,
        palette: IntArray,
        fonts: StyleFonts,
        pass: Pass = Pass.All,
    ) {
        fonts.useDensity(density)
        if (layer !in 0 until layers) return

        /**
         * Runs run [i] with its guards: skipped (no text, no bar) when the
         * run's UTF-16 range exceeds [RowDisplay.text] or when [fonts] holds
         * no style for it (logged inside [StyleFonts.get]) — iOS
         * `RowModel.swift:28`. Local to [draw] — the only [StyleFonts]
         * reference this class makes outside its parameter — and called
         * directly for plain runs and through [FadePainter]'s lambda for
         * faded runs, so bars fade and clip with their text. Both are on
         * the drawing thread.
         */
        fun drawRun(i: Int) {
            val run = display.runs[i]
            val font = entryFor(i, fonts) ?: return
            TextRunPainter.draw(canvas, display.text, run, font, palette.color(run.color), density, widePlan(i, font))
        }

        // 1. Boxes: settled ones in Chrome, a formula's fills with its text.
        for (i in boxesByLayer[layer]) {
            val box = display.boxes[i]
            if (!paintsBox(box.start, pass)) continue
            BoxPainter.draw(canvas, box, palette.color(box.color), density)
        }

        // 2. Non-faded runs (never in a Chrome pass: text comes from Range).
        if (pass !is Pass.Chrome) {
            val range = if (pass is Pass.Range) pass else null
            for (i in runsByLayer[layer]) {
                if (faded[i]) continue // faded runs paint only through their fade
                if (range == null) {
                    drawRun(i)
                    continue
                }
                val run = display.runs[i]
                val start = run.start.toInt()
                val end = start + run.len.toInt()
                if (end > display.text.length) continue // nil line on iOS: never drawn
                if (end <= range.from || start >= range.to) continue // no intersection
                if (start < range.from || end > range.to) {
                    // Split a straddling run at the glyph edge, as iOS clips
                    // at CTLineGetOffsetForStringIndex; the span covers any
                    // row (a full-height clip, ±CLIP_EXTENT).
                    val font = entryFor(i, fonts) ?: continue
                    val plan = widePlan(i, font)
                    val x = run.x * density
                    val left =
                        if (start < range.from) x + TextRunPainter.offset(display.text, run, font, range.from, plan)
                        else -CLIP_EXTENT
                    val right =
                        if (end > range.to) x + TextRunPainter.offset(display.text, run, font, range.to, plan)
                        else CLIP_EXTENT
                    val save = canvas.save()
                    canvas.clipRect(left, -CLIP_EXTENT, right, CLIP_EXTENT)
                    drawRun(i)
                    canvas.restoreToCount(save)
                } else {
                    drawRun(i)
                }
            }
        }

        // 3. Math glyphs. A formula is one placeholder in the text: it
        //    veils whole, never split by the range.
        for (i in glyphsByLayer[layer]) {
            val g = display.glyphs[i]
            if (!paintsGlyph(g.start, pass)) continue
            val font = mathFonts[g.font.toInt()] ?: continue
            GlyphPainter.draw(canvas, g, font, palette.color(g.color), density)
        }

        // 4. Fades with their masked runs (never in a Range pass).
        if (pass !is Pass.Range) {
            for (g in fadesByLayer[layer]) {
                if (g.runs.isEmpty()) continue // a fade that claimed nothing draws nothing
                val fade = display.fades[g.fade]
                FadePainter.draw(canvas, fade, density) {
                    for (i in g.runs) drawRun(i)
                }
            }
        }
    }

    /**
     * Unions into [out] (px, row-local, padded 2 dp) the bounds of the
     * layer-0 formula glyphs and fills whose placeholder is in
     * `[from, to)` — the part of a veil chunk's layer that runs don't
     * cover. Returns whether there was any; [out] is left alone if not.
     * Drawing (main) thread only (the glyph paint is shared scratch).
     */
    internal fun formulaBounds(from: Int, to: Int, density: Float, out: RectF): Boolean {
        val range = Pass.Range(from, to)
        var have = false
        fun add(l: Float, t: Float, r: Float, b: Float) {
            val pad = 2f * density
            if (!have) {
                out.set(l - pad, t - pad, r + pad, b + pad)
                have = true
            } else {
                out.union(l - pad, t - pad, r + pad, b + pad)
            }
        }
        for (i in boxesByLayer[0]) {
            val b = display.boxes[i]
            if (!paintsBox(b.start, range)) continue
            add(b.x * density, b.y * density, (b.x + b.w) * density, (b.y + b.h) * density)
        }
        for (i in glyphsByLayer[0]) {
            val g = display.glyphs[i]
            if (!paintsGlyph(g.start, range)) continue
            val font = mathFonts[g.font.toInt()] ?: continue
            GlyphPainter.bounds(g, font, density, scratch)
            add(scratch.left, scratch.top, scratch.right, scratch.bottom)
        }
        return have
    }

    /** [formulaBounds]' per-glyph scratch; allocated on first use (drawing thread). */
    private val scratch by lazy(LazyThreadSafetyMode.NONE) { RectF() }

    /** Two counting passes into per-layer [IntArray]s, ascending index order. */
    private fun group(n: Int, layerAt: (Int) -> Int): Array<IntArray> {
        val counts = IntArray(layers)
        for (i in 0 until n) layerAt(i).let { if (it in 0 until layers) counts[it]++ }
        val out = Array(layers) { IntArray(counts[it]) }
        val fill = IntArray(layers)
        for (i in 0 until n) layerAt(i).let { if (it in 0 until layers) out[it][fill[it]++] = i }
        return out
    }

    internal companion object {
        private const val TAG = "ZeronTranscript"

        /** Half-extent of a Range split: ±100 000 px spans any row (iOS uses ±10 000 pt). */
        private const val CLIP_EXTENT = 100_000f

        /**
         * Whether [pass] paints a box whose formula placeholder is [start]
         * (`null`: a settled box). iOS RM:189-195.
         */
        fun paintsBox(start: UInt?, pass: Pass): Boolean = when (pass) {
            Pass.All -> true
            Pass.Chrome -> start == null
            is Pass.Range -> start != null && inRange(start, pass)
        }

        /** Whether [pass] paints a glyph of the formula placeholder at [start]. iOS RM:243-249. */
        fun paintsGlyph(start: UInt, pass: Pass): Boolean = when (pass) {
            Pass.All -> true
            Pass.Chrome -> false
            is Pass.Range -> inRange(start, pass)
        }

        private fun inRange(start: UInt, range: Pass.Range): Boolean =
            start.toLong() >= range.from && start.toLong() < range.to
    }
}

/**
 * Draws and measures one math [GlyphPrim] (#29, iOS `drawGlyph`,
 * RM:100-118). The scratch [Paint] and arrays live in this object, so it
 * is drawing (main) thread only — never touched from [RowModel]
 * construction, like [BoxPainter].
 */
internal object GlyphPainter {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ids = IntArray(1)
    private val positions = FloatArray(2)
    private val bounds = RectF()

    /**
     * The glyph at its baseline origin (`x`, `y`), the font at `size`
     * (iOS draws its reference-size font scaled; same thing). `y` is
     * already the baseline in a y-down space, so no flip.
     */
    fun draw(canvas: Canvas, g: GlyphPrim, font: Font, color: Int, density: Float) {
        paint.color = color
        paint.textSize = g.size * density
        ids[0] = g.glyph.toInt()
        positions[0] = g.x * density
        positions[1] = g.y * density
        canvas.drawGlyphs(ids, 0, positions, 0, 1, font, paint)
    }

    /** The glyph's ink bounds in px, row-local, into [out]. */
    fun bounds(g: GlyphPrim, font: Font, density: Float, out: RectF) {
        paint.textSize = g.size * density
        font.getGlyphBounds(g.glyph.toInt(), paint, bounds)
        out.set(bounds)
        out.offset(g.x * density, g.y * density)
    }
}
