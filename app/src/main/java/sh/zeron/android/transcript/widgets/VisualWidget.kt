package sh.zeron.android.transcript.widgets

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.zeron.android.design.Haptic
import sh.zeron.android.design.LocalFeedback
import sh.zeron.android.design.LocalIsDark
import sh.zeron.android.design.LocalReduceMotion
import sh.zeron.android.design.ZeronTheme
import sh.zeron.android.shell.LocalNavEntryOrNull
import sh.zeron.android.shell.LocalNavStack
import sh.zeron.android.transcript.LinkTap
import sh.zeron.android.transcript.RowContext
import sh.zeron.android.transcript.transcriptControl
import uniffi.zeron_core.ControlKind
import uniffi.zeron_core.PointerPhase
import uniffi.zeron_core.SurfaceGesture
import uniffi.zeron_core.VisualControl
import uniffi.zeron_core.VisualSession
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A `WidgetKind.Visual` slot (#46; iOS `RowView.swift:340-342`, port 04
 * F1): the transcript's [LiveVisual] for [id], looked up on every new
 * layout of the row until the core has spawned its worker — the row is
 * re-laid out once the visual's source is set or its height measured; iOS
 * shows an empty view until then.
 */
@Composable
internal fun VisualWidget(ctx: RowContext, id: String) {
    val visual = remember(id, ctx.model) { ctx.state.visuals.get(id) } ?: return
    VisualView(ctx, visual)
}

/**
 * An agent visual (iOS `VisualView`, `VisualView.swift:9-145`): its worker
 * runs the script, lays it out and draws; this only shows what arrives and
 * forwards touches — the display's static paint top-left at its own size
 * (F6), each surface's image under its vector paint (F8) taking touches
 * the way the surface asks (F9, F10), and a touch target over each control
 * the worker painted (F11, F12), later ones above earlier ones.
 *
 * Frames flow only while the visual can be seen (#47, F4): its row on
 * screen (not just realized in the overscan band), its screen shown (the
 * top of its nav stack, or in a transition), and the app started — a
 * covered or backgrounded visual does no GPU work.
 *
 * TalkBack (F13): the visual reads its title (`session.title()`), then its
 * controls in reading order — top to bottom, then left to right
 * (`VisualView.swift:141-144`).
 */
@Composable
internal fun VisualView(ctx: RowContext, visual: LiveVisual) {
    val density = LocalDensity.current.density
    val dark = LocalIsDark.current
    val reduceMotion = LocalReduceMotion.current
    val context = LocalContext.current
    val started = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value.isAtLeast(Lifecycle.State.STARTED)
    val shownScreen = rememberScreenShown()
    val onScreen = rememberRowOnScreen(ctx)
    val host = remember { Any() }
    DisposableEffect(visual) {
        VisualGpu.note(context)
        onDispose { visual.release(host) }
    }
    SideEffect { visual.show(host, started && shownScreen && onScreen, density, dark, reduceMotion) }

    val sink = visual.sink ?: return
    val shown = sink.shown
    val session = visual.session
    val title = remember(session) { session.title() }
    Box(
        Modifier
            .fillMaxSize()
            .testTag("transcript-visual")
            .semantics { contentDescription = title }
            .drawBehind {
                val s = shown ?: return@drawBehind
                // iOS `contentsGravity = .topLeft`: the base paint at the
                // display's size, never stretched to the slot.
                drawBitmap(s.base, s.display.width.dp.toPx(), s.display.height.dp.toPx())
            },
    ) {
        val display = shown?.display ?: return@Box
        for (s in display.surfaces) {
            key(s.id) {
                Box(
                    Modifier
                        .absoluteOffset(s.x.dp, s.y.dp)
                        .size(max(s.w, 0f).dp, max(s.h, 0f).dp)
                        .surfaceInput(session, s.id, s.gesture)
                        .drawBehind {
                            val picture = sink.frames[s.id] ?: return@drawBehind
                            drawBitmap(picture.image, size.width, size.height)
                            drawBitmap(picture.paint, size.width, size.height)
                        },
                )
            }
        }
        // Reading order: (minY, minX), like iOS's accessibilityElements.
        val order = remember(display) {
            display.controls.indices.sortedWith(compareBy({ display.controls[it].y }, { display.controls[it].x }))
                .withIndex().associate { (rank, i) -> i to rank }
        }
        display.controls.forEachIndexed { i, c ->
            key(c.id) {
                // Later controls above earlier ones, as laid out (a tappable
                // stack under the controls inside it).
                VisualControlTarget(ctx, session, c, Modifier.zIndex(1f + i), order[i] ?: i)
            }
        }
    }
}

/**
 * Whether this composition's screen is showing: the top of its nav stack,
 * or either side of a running transition (iOS: a covered view controller's
 * view leaves the window). `true` outside a nav stack (galleries, tests).
 */
@Composable
private fun rememberScreenShown(): Boolean {
    val entry = LocalNavEntryOrNull.current ?: return true
    val stack = LocalNavStack.current
    val t = stack.transition
    return stack.top === entry || (t != null && (t.from === entry || t.to === entry))
}

/**
 * Whether the row intersects the transcript's viewport — the band realizes
 * rows past both edges, and iOS's pool hides those. Follows the scroll and
 * the row motion through snapshot reads; recomposes only on a flip.
 */
@Composable
private fun rememberRowOnScreen(ctx: RowContext): Boolean {
    var onScreen by remember(ctx.row) { mutableStateOf(false) }
    val row = ctx.row
    val viewport = ctx.state.viewport
    LaunchedEffect(row, viewport) {
        snapshotFlow {
            val top = row.topPx()
            val viewportPx = (viewport.viewportHeight * row.density).roundToInt()
            top < viewportPx && top + row.heightPx() > 0
        }.collect { onScreen = it }
    }
    return onScreen
}

/**
 * A surface's touch forwarding (iOS `VisualSurfaceView`,
 * `VisualView.swift:345-407`), positions in the surface's points:
 * - `tap`: down then up;
 * - `drag`: tracks from touch-down and consumes every move, so the drag
 *   never scrolls the transcript;
 * - `horizontalDrag`: only mostly-horizontal drags scrub (vertical ones
 *   reach the transcript's scroll); a tap shows the chart's readout for
 *   2.5 s (down now, up later).
 */
@Composable
private fun Modifier.surfaceInput(session: VisualSession, id: ULong, gesture: SurfaceGesture): Modifier {
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    val readout = remember { arrayOfNulls<Job>(1) }
    fun send(phase: PointerPhase, p: Offset) = session.pointer(id, phase, p.x / density, p.y / density)
    return when (gesture) {
        SurfaceGesture.NONE -> this
        SurfaceGesture.TAP -> pointerInput(session, id) {
            detectTapGestures { p ->
                send(PointerPhase.DOWN, p)
                send(PointerPhase.UP, p)
            }
        }
        SurfaceGesture.DRAG -> pointerInput(session, id) {
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                send(PointerPhase.DOWN, down.position)
                var last = down.position
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null) {
                        send(PointerPhase.CANCEL, last)
                        break
                    }
                    last = change.position
                    if (!change.pressed) {
                        change.consume()
                        send(PointerPhase.UP, last)
                        break
                    }
                    if (change.isConsumed) {
                        send(PointerPhase.CANCEL, last)
                        break
                    }
                    change.consume()
                    send(PointerPhase.MOVE, last)
                }
            }
        }
        SurfaceGesture.HORIZONTAL_DRAG -> this
            .pointerInput(session, id) {
                detectTapGestures { p ->
                    readout[0]?.cancel()
                    send(PointerPhase.DOWN, p)
                    readout[0] = scope.launch {
                        delay(ReadoutMs)
                        send(PointerPhase.UP, p)
                    }
                }
            }
            .pointerInput(session, id) {
                var last = Offset.Zero
                detectHorizontalDragGestures(
                    onDragStart = { p ->
                        readout[0]?.cancel()
                        last = p
                        send(PointerPhase.DOWN, p)
                    },
                    onDragEnd = { send(PointerPhase.UP, last) },
                    onDragCancel = { send(PointerPhase.CANCEL, last) },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        last = change.position
                        send(PointerPhase.MOVE, last)
                    },
                )
            }
    }
}

/**
 * A touch target over control art the worker painted (iOS
 * `VisualControlView`, `VisualView.swift:410-525`): buttons, tabs,
 * toggles, sliders, links and tappable stacks.
 * - The rect is [ControlTarget]'s (4 pt each side, ≥ 44 pt tall).
 * - Buttons, tabs, taps and links wash with `controlFill` while pressed
 *   (r 6 for a tappable stack, 8 for buttons and tabs, square otherwise).
 * - Tabs and toggles tick the selection haptic; links go through the
 *   transcript's link router, like a painted link.
 * - Sliders track the finger along their track; TalkBack adjusts them by
 *   their step (or 10 %).
 * - Every target claims the tap from the composer dismiss (iOS: a
 *   `UIControl`).
 */
@Composable
private fun VisualControlTarget(ctx: RowContext, session: VisualSession, c: VisualControl, modifier: Modifier, rank: Int) {
    val feedback = LocalFeedback.current
    val density = LocalDensity.current.density
    val kind = c.kind
    val target = ControlTarget.of(c)
    val radius = when (kind) {
        is ControlKind.Tap -> 6.dp
        is ControlKind.Button, is ControlKind.Tab -> 8.dp
        else -> 0.dp
    }
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val box = modifier
        .absoluteOffset(target.x.dp, target.y.dp)
        .size(target.w.dp, target.h.dp)
        .onGloballyPositioned { coordinates[0] = it }
        .transcriptControl()
        .semantics {
            traversalIndex = rank.toFloat()
            contentDescription = c.label
            when (kind) {
                is ControlKind.Button, is ControlKind.Tap -> role = Role.Button
                is ControlKind.Tab -> {
                    role = Role.Tab
                    selected = kind.selected
                }
                is ControlKind.Toggle -> {
                    role = Role.Switch
                    stateDescription = if (kind.on) "On" else "Off"
                }
                is ControlKind.Slider -> {
                    stateDescription = kind.shown
                    progressBarRangeInfo = ProgressBarRangeInfo(kind.fraction, 0f..1f, sliderSteps(kind))
                    setProgress { v ->
                        session.slide(c.id, v.coerceIn(0f, 1f))
                        true
                    }
                }
                is ControlKind.Link -> Unit
            }
        }
    when (kind) {
        is ControlKind.Slider -> Box(
            box.pointerInput(c.id, kind.trackX, kind.trackW) {
                // x in the visual's points: the box starts padX left of the art.
                fun slide(px: Float) {
                    if (kind.trackW <= 0f) return
                    val x = target.x + px / density
                    session.slide(c.id, ((x - kind.trackX) / kind.trackW).coerceIn(0f, 1f))
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    slide(down.position.x)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        change.consume()
                        slide(change.position.x)
                    }
                }
            },
        )
        else -> {
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            val wash = ZeronTheme.palette.controlFill
            Box(
                box.clickable(interactionSource = interaction, indication = null) {
                    when (kind) {
                        is ControlKind.Link -> {
                            val bounds = coordinates[0]?.takeIf { it.isAttached }?.boundsInWindow() ?: Rect.Zero
                            ctx.actions().openLink(LinkTap(kind.url, ctx.row.boundsInWindow(), bounds))
                        }
                        is ControlKind.Tab, is ControlKind.Toggle -> {
                            feedback.haptic(Haptic.Select) // UISelectionFeedbackGenerator
                            session.activate(c.id)
                        }
                        else -> session.activate(c.id)
                    }
                },
            ) {
                if (pressed && kind !is ControlKind.Toggle) {
                    Box(
                        Modifier
                            .padding(horizontal = target.padX.dp, vertical = target.padY.dp)
                            .fillMaxSize()
                            .clip(RoundedCornerShape(radius))
                            .background(wash),
                    )
                }
            }
        }
    }
}

/**
 * TalkBack's adjust steps for a slider in fraction space: one per `step`
 * of its range (iOS `nudge`, `VisualView.swift:520-524`), else 10 %.
 * `ProgressBarRangeInfo.steps` counts the stops between the ends.
 */
internal fun sliderSteps(s: ControlKind.Slider): Int {
    val range = s.max - s.min
    if (s.step <= 0.0 || range <= 0.0) return 9
    val stops = (range / s.step).roundToInt()
    return (stops - 1).coerceIn(0, 1000)
}

/** iOS's chart readout: a tap shows it this long (`VisualView.swift:393`). */
private const val ReadoutMs = 2_500L

private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

/** [bitmap] scaled into `(0, 0, w, h)` px; nothing when null. */
private fun DrawScope.drawBitmap(bitmap: android.graphics.Bitmap?, w: Float, h: Float) {
    bitmap ?: return
    drawIntoCanvas { it.nativeCanvas.drawBitmap(bitmap, null, RectF(0f, 0f, w, h), bitmapPaint) }
}

/**
 * SPEC D14 / port 04 F14: scenes and shaders render offscreen through the
 * core's wgpu on **Vulkan** only. Without it the core falls back by itself
 * — scenes use the software renderer, shaders show their error — so the
 * app only notes the device's capability once, for bug reports. The
 * manifest declares the feature as optional.
 */
internal object VisualGpu {
    /** Vulkan 1.1 (`0x401000`), what wgpu's Vulkan backend wants. */
    private const val VulkanVersion = 0x401000

    @Volatile
    private var noted = false

    fun hasVulkan(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, VulkanVersion)

    fun note(context: Context) {
        if (noted) return
        noted = true
        if (!hasVulkan(context)) {
            Log.i("ZeronVisual", "no Vulkan 1.1: agent visual scenes render in software, shaders show their error (D14)")
        }
    }
}
