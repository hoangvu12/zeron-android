package sh.zeron.android.cloud

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import sh.zeron.android.core.Face
import sh.zeron.android.design.CapsuleShape
import sh.zeron.android.design.Icon
import sh.zeron.android.design.LocalReduceMotion
import sh.zeron.android.design.LocalTypeScale
import sh.zeron.android.design.SymbolWeight
import sh.zeron.android.design.ZeronIcons
import sh.zeron.android.design.ZeronTheme
import sh.zeron.android.design.uiStyle

/** A refused send's nudge counter: each [nudge] shakes the notice once (iOS `nudge()`). */
@Stable
class CreditsNoticeState {
    var nudges by mutableIntStateOf(0)
        private set

    fun nudge() {
        nudges++
    }
}

/**
 * "Out of Cloud credits" with its one way forward, above a Cloud chat's
 * composer while the balance is spent (#53; iOS `CloudCreditsNotice`). A
 * refused send shakes it (the warning haptic is the caller's).
 */
@Composable
fun CloudCreditsNotice(state: CreditsNoticeState, onAddCredits: () -> Unit, modifier: Modifier = Modifier) {
    val palette = ZeronTheme.palette
    val ts = LocalTypeScale.current
    val reduceMotion = LocalReduceMotion.current
    val shake = remember { Animatable(0f) }
    LaunchedEffect(state.nudges) {
        if (state.nudges == 0 || reduceMotion) return@LaunchedEffect
        shake.snapTo(0f)
        shake.animateTo(
            0f,
            keyframes {
                durationMillis = 400
                -8f at 57
                8f at 114
                -5f at 171
                5f at 229
                -2f at 286
                0f at 343
            },
        )
    }
    Row(
        modifier
            .fillMaxWidth()
            .graphicsLayer { translationX = shake.value * density }
            .clip(CapsuleShape)
            .background(palette.elevated)
            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
            .testTag("cloud-credits-notice"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(ZeronIcons.ExclamationmarkTriangleFill, palette.danger, 13f, SymbolWeight.Semibold)
        BasicText(
            "Out of Cloud credits",
            // #63: announced when it appears (and again on a kept send's nudge).
            Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            style = uiStyle(Face.SansMedium, ts.fixed(14f), palette.text),
            maxLines = 1,
        )
        BasicText(
            "Add credits",
            Modifier
                .clip(CapsuleShape)
                .background(palette.text)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClick = onAddCredits,
                )
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .testTag("add-credits"),
            style = uiStyle(Face.SansSemibold, ts.fixed(13f), palette.background),
        )
    }
}
