package sh.zeron.android.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.StateFlow
import sh.zeron.android.ZeronApplication
import sh.zeron.android.design.ActionSheetAction
import sh.zeron.android.design.ActionSheetModel
import sh.zeron.android.design.ActionSheetPresenter
import sh.zeron.android.design.GroupedAccessory
import sh.zeron.android.design.GroupedPosition
import sh.zeron.android.design.GroupedRow
import sh.zeron.android.design.LocalActionSheet
import sh.zeron.android.design.ZeronIcon
import sh.zeron.android.design.ZeronIcons
import sh.zeron.android.design.ZeronSwitch
import sh.zeron.android.design.groupedSection
import sh.zeron.android.design.settingsTag
import sh.zeron.android.design.toggleTag
import sh.zeron.android.transcript.MathSettings
import uniffi.zeron_core.MathFace

/**
 * The Settings "Math" section (#36), the port of
 * `MoreViewController.swift:233-241, 154-157, 280-305`: "Render Math" (a
 * switch), and while it is on "Math Font" and "Math Size", each opening a
 * checked action sheet. Every change goes through `Prefs.setMath`, which
 * persists it under iOS's keys and — through the app's bridge — calls
 * [MathSettings.update], so open transcripts reflow at once.
 */

/** The section's copy, verbatim from iOS. */
object MathCopy {
    const val Header = "Math"
    const val Render = "Render Math"
    const val RenderSubtitle = "Off shows LaTeX as its source"
    const val Font = "Math Font"
    const val Size = "Math Size"

    /** `"\(size)% of the text around it"`. */
    fun sizeSubtitle(percent: Int) = "$percent% of the text around it"

    /** A size sheet row: `"\(size)%"`. */
    fun sizeOption(percent: Int) = "$percent%"

    /** `"\(label) — \(detail)"`. */
    fun fontSubtitle(face: MathFace) = "${MathSettings.label(face)} — ${MathSettings.detail(face)}"
}

/** The row ids (`settings-math:<…>` tags; `settingsSectionFor` maps the `math:` prefix). */
object MathRowIds {
    const val Render = "math:render"
    const val Font = "math:font"
    const val Size = "math:size"
}

/** One row, pure data. */
@Immutable
data class MathRowSpec(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: ZeronIcon,
    /** Non-null for the Render switch row. */
    val toggle: Boolean? = null,
)

/** The section's rows for [values]: Render always, Font and Size only while rendering (iOS). */
fun mathRows(values: MathSettings.Values): List<MathRowSpec> = buildList {
    add(MathRowSpec(MathRowIds.Render, MathCopy.Render, MathCopy.RenderSubtitle, ZeronIcons.Function, toggle = values.render))
    if (values.render) {
        add(MathRowSpec(MathRowIds.Font, MathCopy.Font, MathCopy.fontSubtitle(values.face), ZeronIcons.Textformat))
        add(MathRowSpec(MathRowIds.Size, MathCopy.Size, MathCopy.sizeSubtitle(values.sizePercent), ZeronIcons.TextformatSize))
    }
}

/** The "Math Font" sheet: every face in iOS order, the current one checked. */
fun mathFontSheet(current: MathFace, onPick: (MathFace) -> Unit): ActionSheetModel = ActionSheetModel(
    title = MathCopy.Font,
    actions = MathSettings.allFaces.map { face ->
        ActionSheetAction(MathSettings.label(face), checked = face == current) { onPick(face) }
    },
)

/** The "Math Size" sheet: the desktop's sizes, the current one checked. */
fun mathSizeSheet(current: Int, onPick: (Int) -> Unit): ActionSheetModel = ActionSheetModel(
    title = MathCopy.Size,
    actions = MathSettings.allSizes.map { size ->
        ActionSheetAction(MathCopy.sizeOption(size), checked = size == current) { onPick(size) }
    },
)

/**
 * The section's state: the rows over the collected values, and the row
 * actions. Sheets read the values at tap time, never from a stale row.
 */
@Stable
class MathSectionState internal constructor(
    private val current: State<MathSettings.Values>,
    private val set: (MathSettings.Values) -> Unit,
    private val sheets: ActionSheetPresenter,
) {
    val rows: List<MathRowSpec> get() = mathRows(current.value)

    fun onRenderChange(on: Boolean) = set(current.value.copy(render = on))

    fun onRowClick(id: String) {
        when (id) {
            MathRowIds.Font -> sheets.show(mathFontSheet(current.value.face) { set(current.value.copy(face = it)) })
            MathRowIds.Size -> sheets.show(mathSizeSheet(current.value.sizePercent) { set(current.value.copy(sizePercent = it)) })
        }
    }
}

/** The section's state holder; call at screen level (the sheets host is the screen's). */
@Composable
fun rememberMathSectionState(
    values: StateFlow<MathSettings.Values> =
        (LocalContext.current.applicationContext as ZeronApplication).prefs.math,
    set: (MathSettings.Values) -> Unit =
        (LocalContext.current.applicationContext as ZeronApplication).prefs::setMath,
): MathSectionState {
    val current = values.collectAsState()
    val sheets = LocalActionSheet.current
    return remember(current, set, sheets) { MathSectionState(current, set, sheets) }
}

/** One row: ticket 70's [GroupedRow]; the switch row's body is not tappable (iOS). */
@Composable
fun MathRow(spec: MathRowSpec, position: GroupedPosition, state: MathSectionState) {
    val toggle = spec.toggle
    GroupedRow(
        title = spec.title,
        subtitle = spec.subtitle,
        icon = spec.icon,
        position = position,
        accessories = if (toggle != null) {
            listOf(
                GroupedAccessory.Custom {
                    ZeronSwitch(
                        checked = toggle,
                        onCheckedChange = state::onRenderChange,
                        testTag = toggleTag(spec.id),
                    )
                },
            )
        } else {
            listOf(GroupedAccessory.Disclosure)
        },
        testTag = settingsTag(spec.id),
        onClick = if (toggle != null) null else ({ state.onRowClick(spec.id) }),
    )
}

/** Emits the "Math" section; no item animation (iOS reloads without animating). */
fun LazyListScope.mathSection(state: MathSectionState) {
    groupedSection(
        key = "math",
        header = MathCopy.Header,
        items = state.rows,
        itemKey = { it.id },
    ) { row, position ->
        MathRow(row, position, state)
    }
}
