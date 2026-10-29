package sh.zeron.android.transcript.widgets

import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import sh.zeron.android.design.LocalIsDark
import sh.zeron.android.design.LocalReduceMotion
import sh.zeron.android.transcript.RowModel
import sh.zeron.android.transcript.TranscriptState
import kotlin.math.max

/**
 * A `WidgetKind.Visual` slot (#46; iOS `RowView.swift:340-342`, port 04
 * F1): the transcript's [LiveVisual] for [id], looked up on every
 * composition until the core has spawned its worker — a new [model] (the
 * row re-laid out once the visual's source is set, or its height measured)
 * recomposes this and asks again; iOS shows an empty view until then.
 */
@Composable
internal fun VisualWidget(state: TranscriptState, model: RowModel, id: String) {
    // `model` is the retry token: each new layout of the row asks again,
    // like iOS's re-configure (a hit is a map lookup).
    val visual = remember(id, model) { state.visuals.get(id) } ?: return
    VisualView(visual)
}

/**
 * An agent visual (iOS `VisualView`, `VisualView.swift:9-145`): its worker
 * runs the script, lays it out and draws; this only shows what arrives —
 * the display's static paint top-left at its own size (F6), then each
 * surface's image under its vector paint, scaled to the surface's frame
 * (F8). Surfaces are drawn in layout order; their pictures are read in the
 * draw phase, so a 60 fps shader redraws without recomposing.
 *
 * Frames flow while composed ([LiveVisual.acquire]); the appearance and
 * Reduce Motion ride the attach, later flips go through `setAppearance`
 * (F5).
 *
 * TODO(#47): touch forwarding on surfaces (`SurfaceGesture`, the 2.5 s
 * chart readout), the controls over painted art (buttons, tabs, toggles,
 * sliders, links through the router), the visual's a11y label
 * (`session.title()`) and its controls in reading order.
 */
@Composable
internal fun VisualView(visual: LiveVisual, modifier: Modifier = Modifier) {
    val density = LocalDensity.current.density
    val dark = LocalIsDark.current
    val reduceMotion = LocalReduceMotion.current
    DisposableEffect(visual) {
        visual.acquire(density, dark, reduceMotion)
        onDispose { visual.release() }
    }
    SideEffect { visual.update(density, dark) }

    val sink = visual.sink ?: return
    val shown = sink.shown
    Box(
        modifier
            .fillMaxSize()
            .testTag("transcript-visual")
            .drawBehind {
                val s = shown ?: return@drawBehind
                // iOS `contentsGravity = .topLeft`: the base paint at the
                // display's size, never stretched to the slot.
                drawBitmap(s.base, s.display.width.dp.toPx(), s.display.height.dp.toPx())
            },
    ) {
        shown?.display?.surfaces.orEmpty().forEach { s ->
            key(s.id) {
                Box(
                    Modifier
                        .absoluteOffset(s.x.dp, s.y.dp)
                        .size(max(s.w, 0f).dp, max(s.h, 0f).dp)
                        .drawBehind {
                            val picture = sink.frames[s.id] ?: return@drawBehind
                            drawBitmap(picture.image, size.width, size.height)
                            drawBitmap(picture.paint, size.width, size.height)
                        },
                )
            }
        }
    }
}

private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

/** [bitmap] scaled into `(0, 0, w, h)` px; nothing when null. */
private fun DrawScope.drawBitmap(bitmap: android.graphics.Bitmap?, w: Float, h: Float) {
    bitmap ?: return
    drawIntoCanvas { it.nativeCanvas.drawBitmap(bitmap, null, RectF(0f, 0f, w, h), bitmapPaint) }
}
