package sh.zeron.android.design

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import kotlin.coroutines.cancellation.CancellationException

/**
 * Accessibility shared by the in-window modal overlays (#63, the #12
 * component lab's follow-ups): the alert, the action sheet, the glass menu
 * and the glass sheet. They draw in the app's own window (Haze samples
 * only its window), so the platform's dialog-window modality is ours to
 * rebuild:
 *
 * - [modalBackground] on the content behind: hidden from TalkBack while
 *   the overlay is up, and keyboard focus returned to where it was when
 *   the overlay closes (iOS returns VoiceOver to the presenting element);
 * - [ModalBackHandler]: predictive back with progress, the commit running
 *   the overlay's own back rule;
 * - [onEscape]: hardware Escape runs the same rule (iOS
 *   `accessibilityPerformEscape` / Full Keyboard Access).
 */
private class ModalFocus {
    val requester = FocusRequester()

    /** Whether the content held keyboard focus when the overlay opened. */
    var hadFocus = false

    /** Whether it holds focus now (a menu over the composer never takes it). */
    var hasFocus = false
    var wasUp = false
}

/**
 * The modifier for the content behind an overlay: [overlayUp] hides it
 * from accessibility services; when the overlay closes, focus goes back
 * into the content — to the child that had it (`focusRestorer`) — but
 * only if the content held focus when the overlay opened and lost it to
 * the overlay (touch users never get a focus ring they didn't have).
 *
 * Known limit: TalkBack's own accessibility focus isn't restorable from
 * Compose; it lands wherever TalkBack puts it once the content reappears.
 */
@Composable
fun modalBackground(overlayUp: Boolean): Modifier {
    val focus = remember { ModalFocus() }
    LaunchedEffect(overlayUp) {
        if (!overlayUp && focus.wasUp && focus.hadFocus) {
            // One frame: the overlay's focus targets have left the tree.
            withFrameNanos { }
            // Still inside (an overlay that never took focus): leave it be —
            // re-requesting would pull focus off the composer's field.
            if (!focus.hasFocus) runCatching { focus.requester.requestFocus() }
        }
        focus.wasUp = overlayUp
    }
    return Modifier
        // Recorded only while no overlay is up: the overlay taking focus
        // must not erase what to restore.
        .onFocusChanged {
            focus.hasFocus = it.hasFocus
            if (!overlayUp) focus.hadFocus = it.hasFocus
        }
        .focusRequester(focus.requester)
        .focusRestorer()
        .then(if (overlayUp) Modifier.semantics { hideFromAccessibility() } else Modifier)
}

/**
 * Predictive back for an overlay (#63): [onProgress] follows the gesture
 * (0…1) so the overlay can shrink toward its exit, [onBack] runs on
 * commit (the overlay's back rule), and a cancelled gesture reports 0.
 * Composed after the screens' handlers, so it wins while the overlay is up.
 */
@Composable
fun ModalBackHandler(enabled: Boolean, onProgress: (Float) -> Unit, onBack: () -> Unit) {
    PredictiveBackHandler(enabled) { events ->
        try {
            events.collect { onProgress(it.progress) }
            onBack()
        } catch (e: CancellationException) {
            onProgress(0f)
            throw e
        }
    }
}

/** The overlay's scale at full predictive-back progress (Material's dialog shrink). */
const val ModalBackScale = 0.9f

/** 1 → [ModalBackScale] as the back gesture's [progress] goes 0 → 1. */
fun modalBackScale(progress: Float): Float = 1f - (1f - ModalBackScale) * progress.coerceIn(0f, 1f)

/**
 * Hardware Escape on a focused overlay runs [onEscape] (on key-up, the
 * press consumed too). The overlay root must hold focus for keys to arrive.
 */
fun Modifier.onEscape(onEscape: () -> Unit): Modifier = onPreviewKeyEvent { event ->
    if (event.key != Key.Escape) return@onPreviewKeyEvent false
    if (event.type == KeyEventType.KeyUp) onEscape()
    true
}

/**
 * A screen's modal layer (#63): overlays composed inline beside a
 * screen's content — the [GlassSheet][sh.zeron.android.design.sheet.GlassSheet]s
 * (the model picker, the companions panel, the folded inspector) — register
 * here while composed, and the content marked with [modalLayerBackground]
 * is hidden from TalkBack meanwhile. The overlays must be siblings of
 * that content, never inside it.
 */
@Stable
class ModalLayerState {
    var overlays by mutableIntStateOf(0)
        internal set
}

val LocalModalLayer = staticCompositionLocalOf<ModalLayerState?> { null }

/** Provides a [ModalLayerState] to [content] (a screen root and its inline overlays). */
@Composable
fun ModalLayer(content: @Composable () -> Unit) {
    val state = remember { ModalLayerState() }
    CompositionLocalProvider(LocalModalLayer provides state, content = content)
}

/** [modalBackground] for the content inside the nearest [ModalLayer]. */
@Composable
fun modalLayerBackground(): Modifier = modalBackground(overlayUp = (LocalModalLayer.current?.overlays ?: 0) > 0)

/** An inline overlay counts itself in the nearest [ModalLayer] while composed. */
@Composable
fun RegisterModalOverlay() {
    val layer = LocalModalLayer.current ?: return
    DisposableEffect(layer) {
        layer.overlays++
        onDispose { layer.overlays-- }
    }
}
