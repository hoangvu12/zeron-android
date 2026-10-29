package sh.zeron.android.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composeunstyled.TextInput
import com.composeunstyled.UnstyledTextField
import sh.zeron.android.core.Face

/**
 * The search field contract (#62, decision D7: the Component Lab's
 * "variant 2" — our look on a headless, accessible primitive). Every
 * search field in the app is Compose Unstyled's `UnstyledTextField` +
 * `TextInput` (composeunstyled-text-field: a state-based `BasicTextField`
 * merged into one node, labelled through `accessibilityLabel`) inside our
 * own shape — the glass capsules of the tablet sidebar and the phone's
 * bottom bar (their own files own the geometry), the filled field of the
 * sheets ([FilledSearchField]). [SearchTextField] is the shared inner
 * field; around it every field carries:
 *
 *  - the label (default "Search", or the field's own placeholder copy);
 *  - [SearchKeyboardOptions]: IME "Search", no autocorrect, no
 *    capitalisation (session titles and repositories hold identifiers);
 *  - hardware Escape ([searchEscape]): clears the query, and on an empty
 *    field blurs it (`UISearchTextField`'s cancel; the lab's finding);
 *  - a clear button — `Role.Button` "Clear text" ([SearchClearLabel]) —
 *    that empties the query and puts the focus back in the field;
 *  - where the screen has a result count, a polite live region speaking
 *    it ([SearchResultCount]) — no visible count, as on iOS.
 *
 * Kept from the fields before #62 (the lab's variant lacked them): the
 * accent caret and ZeronTheme's selection colours (the primitive's
 * default selection colours are unspecified, which would hide the
 * handles), the tertiary placeholder (now hidden from TalkBack: the label
 * already says it, and the merged node would read it twice).
 */

/** What TalkBack calls the clear button (UIKit's `UISearchTextField` clear button label). */
const val SearchClearLabel = "Clear text"

/** The default accessibility label of a search field. */
const val SearchFieldLabel = "Search"

/** The search keyboard: IME "Search", no autocorrect, no capitalisation, plain text. */
val SearchKeyboardOptions = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    keyboardType = KeyboardType.Text,
    imeAction = ImeAction.Search,
)

/**
 * Hardware Escape on a search field (#62): the first press clears the
 * query, a press on an empty field runs [onBlur] (the caller's focus drop:
 * the same as its IME "Search" key). Consumed either way, so Escape never
 * also reaches a sheet's or a page's own back handling while the field has
 * the focus. Put it **before** the field's own modifiers (a preview
 * handler on an ancestor of the focus target).
 */
fun Modifier.searchEscape(state: TextFieldState, onBlur: () -> Unit): Modifier =
    onPreviewKeyEvent { event ->
        if (event.key == Key.Escape) {
            if (event.type == KeyEventType.KeyDown) {
                if (state.text.isNotEmpty()) state.clearText() else onBlur()
            }
            true
        } else {
            false
        }
    }

/** The spoken result count. Pure: `SearchFieldTest`. */
fun searchResultCountText(count: Int): String = when (count) {
    0 -> "No results"
    1 -> "1 result"
    else -> "$count results"
}

/**
 * The result count as a polite live region (#62): TalkBack speaks
 * "3 results" after the typing echo whenever the count changes. Draws
 * nothing; size it over the field ([Modifier.matchParentSize] under the
 * field's row) so it is a real, visible node — TalkBack drops live-region
 * changes on zero-size nodes. [count] `null` (no query) says nothing.
 */
@Composable
fun SearchResultCount(count: Int?, modifier: Modifier = Modifier) {
    Box(
        modifier.semantics {
            liveRegion = LiveRegionMode.Polite
            if (count != null) contentDescription = searchResultCountText(count)
        },
    )
}

/**
 * The shared inner field (#62): `UnstyledTextField` over [state], one
 * line, [textStyle] with the accent caret and the theme's selection
 * colours, the [placeholder] in `tertiary` while empty, [SearchKeyboardOptions],
 * [searchEscape]. Labelled [accessibilityLabel] for TalkBack.
 *
 * [onSearch] is the IME "Search" key (every field keeps its text: the
 * list filters as you type); [onBlur] is the Escape on an empty field.
 * [modifier] is the field's own (weight, padding, focus requester, test
 * tag); [decorationModifier] sizes the box the text and placeholder are
 * centred in (the bottom bar's `fillMaxHeight`).
 */
@Composable
fun SearchTextField(
    state: TextFieldState,
    placeholder: String,
    textStyle: TextStyle,
    onSearch: () -> Unit,
    onBlur: () -> Unit,
    modifier: Modifier = Modifier,
    accessibilityLabel: String = SearchFieldLabel,
    interactionSource: MutableInteractionSource? = null,
    decorationModifier: Modifier = Modifier,
) {
    val palette = ZeronTheme.palette
    UnstyledTextField(
        state = state,
        modifier = Modifier.searchEscape(state, onBlur).then(modifier),
        accessibilityLabel = accessibilityLabel,
        cursorBrush = SolidColor(palette.accent),
        // The primitive provides its own LocalTextSelectionColors (both
        // colours unspecified by default): hand it ZeronTheme's.
        selectionColors = LocalTextSelectionColors.current,
        textStyle = textStyle,
        lineLimits = TextFieldLineLimits.SingleLine,
        onKeyboardAction = object : KeyboardActionHandler {
            override fun onKeyboardAction(performKeyboardAction: () -> Unit) {
                onSearch()
            }
        },
        keyboardOptions = SearchKeyboardOptions,
        interactionSource = interactionSource,
    ) {
        Box(decorationModifier, contentAlignment = Alignment.CenterStart) {
            TextInput(
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    BasicText(
                        placeholder,
                        // The label already says it; the merged field
                        // would otherwise read it twice.
                        Modifier.clearAndSetSemantics { },
                        style = textStyle.copy(color = palette.tertiary),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                    )
                },
            )
        }
    }
}

/**
 * The sheets' search field (#62; the repository picker's "Search
 * repositories", the model picker's "Search <agent> models"): the filled
 * 38 dp rounded field of a `UISearchBar` in a sheet — `controlFill`, 12 dp
 * corners, the 14 pt magnifier, Geist [textPt] — on [SearchTextField],
 * labelled with its [placeholder]. The IME "Search" key only puts the
 * keyboard away (#63: the list filters as you type); the clear button
 * appears with text. [resultCount] (the rows the query leaves) is spoken
 * through [SearchResultCount] while there is a query. [modifier] carries
 * the field's outer padding; [fieldTag] tags the text field.
 */
@Composable
fun FilledSearchField(
    state: TextFieldState,
    placeholder: String,
    textPt: Float,
    fieldTag: String,
    modifier: Modifier = Modifier,
    resultCount: Int? = null,
) {
    val palette = ZeronTheme.palette
    val typeScale = LocalTypeScale.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val hasText by remember(state) { derivedStateOf { state.text.isNotEmpty() } }
    val querying by remember(state) { derivedStateOf { state.text.isNotBlank() } }
    Box(modifier.fillMaxWidth().height(38.dp)) {
        // Under the row: the field and the clear button stay on top for
        // explore-by-touch.
        SearchResultCount(if (querying) resultCount else null, Modifier.matchParentSize())
        Row(
            Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(12.dp))
                .background(palette.controlFill)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(ZeronIcons.Magnifyingglass, palette.secondary, 14f, SymbolWeight.Medium)
            Spacer(Modifier.width(8.dp))
            SearchTextField(
                state = state,
                placeholder = placeholder,
                textStyle = uiStyle(Face.Sans, typeScale.fixed(textPt), palette.text),
                onSearch = { keyboard?.hide() },
                onBlur = {
                    keyboard?.hide()
                    focus.clearFocus()
                },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .onFocusChanged { focused = it.isFocused }
                    .testTag(fieldTag),
                accessibilityLabel = placeholder,
            )
            if (hasText) {
                Box(
                    Modifier
                        .size(width = 28.dp, height = 38.dp)
                        .clickable(interactionSource = null, indication = null, role = Role.Button) {
                            state.clearText()
                            // UIKit begins editing on clear.
                            runCatching { focusRequester.requestFocus() }
                            if (!focused) keyboard?.show()
                        }
                        .semantics { contentDescription = SearchClearLabel }
                        .testTag("$fieldTag-clear"),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(ZeronIcons.XmarkCircleFill, palette.tertiary, 14f)
                }
            }
        }
    }
}
