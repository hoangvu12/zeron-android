package sh.zeron.android.transcript.widgets

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import sh.zeron.android.design.Haptic
import sh.zeron.android.design.LocalFeedback
import sh.zeron.android.design.Motion
import sh.zeron.android.design.ZeronTheme
import sh.zeron.android.design.rememberSmoothRoundedShape

/**
 * `WidgetKind.Subagent`'s view (#31; iOS `SubagentCardControl`,
 * `ios:Transcript/ToolViews.swift:317-335`, wired in `RowView.swift:323-337`):
 * an invisible control over a whole subagent spawn card. The core paints
 * the card art (icon, title, spinner, chevron) underneath; this adds only
 * the press wash, the tap and the accessibility element.
 *
 * - Pressed: the card fills with `controlFill`, rounded 10 dp — in over
 *   0.12 s ([Motion.SubagentPressIn], damping 1), out over 0.28 s
 *   ([Motion.SubagentPressOut], damping 0.7). The wash is read in the draw
 *   phase, so a press redraws, never recomposes.
 * - [onOpen] `null` (the host hasn't bound a doc yet, `docId == nil`): the
 *   control is disabled — no wash, no tap, announced as disabled. Like
 *   iOS's `isEnabled = false`, it looks the same otherwise.
 * - A tap plays the Light haptic, then [onOpen].
 * - TalkBack: a button labelled "Subagent: <title>" whose state is
 *   Working / Failed / Done.
 */
@Composable
fun SubagentCard(
    title: String,
    running: Boolean,
    failed: Boolean,
    onOpen: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val feedback = LocalFeedback.current
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val wash = remember { Animatable(0f) }
    LaunchedEffect(pressed) {
        if (pressed) {
            wash.animateTo(1f, Motion.SubagentPressIn.spec())
        } else {
            wash.animateTo(0f, Motion.SubagentPressOut.spec())
        }
    }
    val fill = ZeronTheme.palette.controlFill
    val shape = rememberSmoothRoundedShape(10.dp)
    val status = subagentStatus(running, failed)
    Box(
        modifier
            .fillMaxSize()
            .testTag("subagent-card")
            .drawWithCache {
                val outline = shape.createOutline(size, layoutDirection, this)
                onDrawBehind {
                    // The out spring overshoots below 0: clamp the alpha.
                    val a = wash.value.coerceIn(0f, 1f)
                    if (a > 0f) drawOutline(outline, fill, alpha = a)
                }
            }
            .clickable(
                interactionSource = interactions,
                indication = null,
                enabled = onOpen != null,
                role = Role.Button,
            ) {
                feedback.haptic(Haptic.Light)
                onOpen?.invoke()
            }
            .semantics {
                contentDescription = "Subagent: $title"
                stateDescription = status
            },
    )
}

/** iOS `accessibilityValue`: running beats failed. */
internal fun subagentStatus(running: Boolean, failed: Boolean): String =
    when {
        running -> "Working"
        failed -> "Failed"
        else -> "Done"
    }
