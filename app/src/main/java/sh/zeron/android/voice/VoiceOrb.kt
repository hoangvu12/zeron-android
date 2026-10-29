package sh.zeron.android.voice

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import sh.zeron.android.design.LocalIsDark
import sh.zeron.android.design.LocalReduceMotion
import uniffi.zeron_core.OrbFrame
import uniffi.zeron_core.OrbPreset
import uniffi.zeron_core.OrbRenderer
import uniffi.zeron_core.VoiceOrb

/**
 * The desktop voice orb (`zeron-orb`), iOS `OrbView`
 * (`Voice/OrbView.swift`, port 04 G16–G17): the core runs the same
 * geometry, clock, audio response and 300 ms state crossfades as the GPUI
 * widget, and this canvas only paints the frames — lines, then disks back
 * to front, in monochrome ink on a transparent canvas, mirrored in dark.
 *
 * Redraws at 30 fps (the desktop rate) while composed and the screen is
 * started; stopped, its clock stops too, so motion resumes where it left
 * off (iOS stops its display link hidden or in the background). Reduce
 * Motion shows the desktop's static frame. Decorative: no semantics.
 *
 * Presets: `INLINE`, `AVATAR` (the bar items, 32 dp), `LARGE` (Voice
 * settings, 112 dp), `HERO` (the stage, 340 dp).
 */
@Composable
fun VoiceOrbView(
    orb: VoiceOrb,
    preset: OrbPreset,
    modifier: Modifier = Modifier,
    microphone: Float = 0f,
    speaker: Float = 0f,
) {
    val renderer = remember(preset) { OrbRenderer(preset, orb) }
    val shown = remember(renderer) { ShownOrb(orb) }
    DisposableEffect(renderer) { onDispose { renderer.close() } }
    // Normalized 0…1 peaks: they quicken the motion, like the desktop orb.
    SideEffect { renderer.setAudioLevels(microphone, speaker) }

    val reducedMotion = LocalReduceMotion.current
    val started = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value.isAtLeast(Lifecycle.State.STARTED)
    val running = !reducedMotion && started
    var frame by remember(renderer) { mutableStateOf<OrbFrame?>(null) }

    LaunchedEffect(renderer, orb, running) {
        if (shown.orb != orb) {
            shown.orb = orb
            renderer.setOrb(orb)
        }
        if (!running) {
            // Stop the clock now, so the hidden time never plays back as a jump.
            frame = renderer.nextFrame(false, reducedMotion)
            return@LaunchedEffect
        }
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (now - last >= OrbFrameNanos) {
                    last = now
                    frame = renderer.nextFrame(true, false)
                }
            }
        }
    }

    val dark = LocalIsDark.current
    Canvas(modifier.clearAndSetSemantics {}) {
        val f = frame ?: return@Canvas
        if (f.size <= 0f) return@Canvas
        // The preset's artwork, scaled uniformly to fit and centred.
        val s = minOf(size.width, size.height) / f.size
        translate((size.width - f.size * s) / 2f, (size.height - f.size * s) / 2f) {
            scale(s, s, pivot = Offset.Zero) {
                val lines = f.lines
                var i = 0
                while (i + 6 < lines.size) {
                    drawLine(
                        color = orbInk(lines[i + 5], lines[i + 6], dark),
                        start = Offset(lines[i], lines[i + 1]),
                        end = Offset(lines[i + 2], lines[i + 3]),
                        strokeWidth = lines[i + 4],
                        cap = StrokeCap.Butt,
                    )
                    i += 7
                }
                val dots = f.dots
                i = 0
                while (i + 4 < dots.size) {
                    drawCircle(color = orbInk(dots[i + 3], dots[i + 4], dark), radius = dots[i + 2], center = Offset(dots[i], dots[i + 1]))
                    i += 5
                }
            }
        }
    }
}

/** The desktop's 30 fps, as a frame interval. */
private const val OrbFrameNanos = 1_000_000_000L / 30 - 1_000_000L

private class ShownOrb(var orb: VoiceOrb)

/** Ink `white` on paper; dark appearances mirror it (desktop `ink_color`). Pure. */
internal fun orbInk(white: Float, alpha: Float, dark: Boolean): Color {
    val w = white.coerceIn(0f, 1f)
    val g = if (dark) 1f - w else w
    return Color(g, g, g, alpha.coerceIn(0f, 1f))
}
