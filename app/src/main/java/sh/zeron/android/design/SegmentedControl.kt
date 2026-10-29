package sh.zeron.android.design

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.composeunstyled.RadioButton
import com.composeunstyled.UnstyledRadioGroup
import sh.zeron.android.core.Face

/**
 * The `UISegmentedControl` geometry (#62 / D7). The first app use is
 * onboarding's "Color scheme" picker (`OnboardingViewController.swift:262-272`,
 * `UISegmentedControl(items: ["System", "Light", "Dark"])`), whose
 * stand-in set these numbers: a 32 dp track with 9 dp corners and a 2 dp
 * inset, a 7 dp-cornered thumb, Geist 13 fixed labels.
 */
object ZeronSegmentedControlTokens {

    /** The track's height (`UISegmentedControl`'s 32 pt default). */
    val Height = 32.dp

    /** The track's corner radius. */
    val TrackCorner = 9.dp

    /** The thumb's inset from the track on every side. */
    val Inset = 2.dp

    /** The thumb's corner radius (track corner − inset). */
    val ThumbCorner = 7.dp

    /** The labels' fixed size (pt). */
    const val LabelPt = 13f

    /**
     * The thumb sliding to the picked segment: 0.3 s, ζ 0.9 — the
     * `UISegmentedControl` selection slide, no visible overshoot. ★ tuning
     * constant, not a measurement.
     */
    val Slide = IosSpring("segmentSlide", 0.3, 0.9f)
}

/**
 * The segmented control (#62 / D7, the Component Lab's variant 2): our
 * `UISegmentedControl` look on Compose Unstyled's `UnstyledRadioGroup` +
 * `RadioButton`. The primitive gives the group a `selectableGroup` and its
 * [accessibilityLabel], each segment `Role.RadioButton` with its on/off
 * state, focus, Enter/Space to pick, and ←/→/↑/↓ moving focus within the
 * group. The look is ours: the `controlFill` track, equal segments, and an
 * `elevated` thumb that slides to the picked segment on
 * [ZeronSegmentedControlTokens.Slide] (snapping under Reduce Motion); the
 * picked label is semibold. A tap on the picked segment does nothing.
 *
 * The thumb is drawn behind the segments and its position is read only in
 * the draw phase, so the slide redraws without recomposing.
 *
 * @param options the segments, in order
 * @param selected the picked option (no thumb lands anywhere else)
 * @param onSelect a user pick of another option
 * @param label each segment's text (also its spoken name)
 * @param modifier the track's modifier (the caller's test tag goes here)
 * @param accessibilityLabel the group's spoken name ("Color scheme")
 * @param segmentTestTag optional per-segment test tags
 */
@Composable
fun <T> ZeronSegmentedControl(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    accessibilityLabel: String? = null,
    segmentTestTag: ((T) -> String)? = null,
) {
    val palette = ZeronTheme.palette
    val typeScale = LocalTypeScale.current
    val reduce = LocalReduceMotion.current
    val index = options.indexOf(selected).coerceAtLeast(0)
    val thumb = remember { Animatable(index.toFloat()) }
    LaunchedEffect(index, reduce) {
        val target = index.toFloat()
        if (reduce) thumb.snapTo(target) else thumb.animateTo(target, ZeronSegmentedControlTokens.Slide.spec())
    }
    val count = options.size.coerceAtLeast(1)
    val thumbColor = palette.elevated
    UnstyledRadioGroup<T>(
        value = selected,
        onValueChange = onSelect,
        modifier = modifier
            .fillMaxWidth()
            .height(ZeronSegmentedControlTokens.Height)
            .clip(RoundedCornerShape(ZeronSegmentedControlTokens.TrackCorner))
            .background(palette.controlFill)
            .padding(ZeronSegmentedControlTokens.Inset),
        accessibilityLabel = accessibilityLabel,
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    val segment = size.width / count
                    val start = thumb.value * segment
                    // A Row mirrors in RTL: the first segment is on the right.
                    val left = if (layoutDirection == LayoutDirection.Rtl) size.width - start - segment else start
                    val radius = ZeronSegmentedControlTokens.ThumbCorner.toPx()
                    drawRoundRect(
                        color = thumbColor,
                        topLeft = Offset(left, 0f),
                        size = Size(segment, size.height),
                        cornerRadius = CornerRadius(radius, radius),
                    )
                },
        ) {
            for (option in options) {
                val picked = option == selected
                RadioButton(
                    value = option,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(ZeronSegmentedControlTokens.ThumbCorner))
                        .then(if (segmentTestTag != null) Modifier.testTag(segmentTestTag(option)) else Modifier),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        BasicText(
                            label(option),
                            style = uiStyle(
                                if (picked) Face.SansSemibold else Face.SansMedium,
                                typeScale.fixed(ZeronSegmentedControlTokens.LabelPt),
                                palette.text,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
