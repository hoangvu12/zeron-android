package sh.zeron.android.design.lab

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import sh.zeron.android.core.Face
import sh.zeron.android.design.CapsuleShape
import sh.zeron.android.design.GalleryBackdrop
import sh.zeron.android.design.LocalTypeScale
import sh.zeron.android.design.ZeronTheme
import sh.zeron.android.design.glass.LocalHazeState
import sh.zeron.android.design.uiStyle

/**
 * The stage's haze source, for overlays that render through a Compose
 * Unstyled `Modal`: in-window (under `ModalHost`) they sample it like any
 * glass; in a separate window they get `null` (the flat fallback fill), which
 * is exactly what the app would see — Haze cannot sample across windows.
 */
internal val LocalStageHaze = staticCompositionLocalOf<HazeState?> { null }

/**
 * The busy striped backdrop as the stage's haze source, with the glass
 * content drawn as a sibling after it (ticket 07's rule: glass never sits
 * inside the node that carries `hazeSource`).
 */
@Composable
internal fun HazeStage(content: @Composable BoxScope.() -> Unit) {
    val haze = rememberHazeState()
    Box(Modifier.fillMaxSize()) {
        GalleryBackdrop(Modifier.matchParentSize().hazeSource(haze))
        CompositionLocalProvider(LocalHazeState provides haze, LocalStageHaze provides haze) {
            content()
        }
    }
}

/** The demo controls column every stage puts over its backdrop. */
@Composable
internal fun StageControls(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/** A plain capsule action button: Role.Button, text label. */
@Composable
internal fun LabButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val palette = ZeronTheme.palette
    val typeScale = LocalTypeScale.current
    Box(
        modifier
            .height(40.dp)
            .clip(CapsuleShape)
            .background(palette.text)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text, style = uiStyle(Face.SansMedium, typeScale.fixed(15f), palette.background), maxLines = 1)
    }
}

/** A caption on an opaque chip, readable over the striped backdrop. */
@Composable
internal fun LabCaption(text: String, modifier: Modifier = Modifier) {
    val palette = ZeronTheme.palette
    val typeScale = LocalTypeScale.current
    BasicText(
        text,
        style = uiStyle(Face.Sans, typeScale.fixed(13f), palette.secondary),
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(palette.background.copy(alpha = 0.92f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/** A plain single-line field on an opaque chip, so stages can raise the IME. */
@Composable
internal fun LabTextField(placeholder: String, modifier: Modifier = Modifier) {
    val palette = ZeronTheme.palette
    val typeScale = LocalTypeScale.current
    val state = rememberTextFieldState()
    BasicTextField(
        state = state,
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(palette.background)
            .semantics { contentDescription = placeholder },
        textStyle = uiStyle(Face.Sans, typeScale.fixed(16f), palette.text),
        cursorBrush = SolidColor(palette.accent),
        lineLimits = TextFieldLineLimits.SingleLine,
        decorator = { inner ->
            Box(Modifier.fillMaxSize().padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                if (state.text.isEmpty()) {
                    BasicText(placeholder, style = uiStyle(Face.Sans, typeScale.fixed(16f), palette.tertiary))
                }
                inner()
            }
        },
    )
}

/** A row of mutually exclusive capsules (the lab's own chrome, not a component under test). */
@Composable
internal fun <T> LabChoice(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val palette = ZeronTheme.palette
    val typeScale = LocalTypeScale.current
    Row(modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (value, label) ->
            val on = value == selected
            Box(
                Modifier
                    .height(32.dp)
                    .clip(CapsuleShape)
                    .background(if (on) palette.accent else palette.controlFill)
                    .selectable(selected = on, role = Role.RadioButton) { onSelect(value) }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    label,
                    style = uiStyle(Face.SansMedium, typeScale.fixed(13f), if (on) Color.White else palette.text),
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * The headless overlay stages' host switch: Compose Unstyled renders a
 * `Modal` in-window when a `ModalHost` is above it, otherwise in its own
 * `ComponentDialog` window. Flipping it shows the blur loss directly.
 */
@Composable
internal fun HostChoice(inWindow: Boolean, onChange: (Boolean) -> Unit) {
    LabChoice(
        options = listOf(true to "In-window (ModalHost)", false to "Separate window"),
        selected = inWindow,
        onSelect = onChange,
    )
}
