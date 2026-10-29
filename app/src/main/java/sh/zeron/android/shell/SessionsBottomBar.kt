package sh.zeron.android.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.placeCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import sh.zeron.android.core.Face
import sh.zeron.android.design.CapsuleShape
import sh.zeron.android.design.Icon
import sh.zeron.android.design.IosSpring
import sh.zeron.android.design.LocalReduceMotion
import sh.zeron.android.design.LocalTypeScale
import sh.zeron.android.design.SymbolWeight
import sh.zeron.android.design.ZeronIcons
import sh.zeron.android.design.ZeronTheme
import sh.zeron.android.design.glass.GlassCircleButton
import sh.zeron.android.design.glass.GlassSurface
import sh.zeron.android.design.glass.GlassTier
import sh.zeron.android.design.glass.LocalGlassTier
import sh.zeron.android.design.uiStyle

/**
 * The phone's one-row bottom bar (upstream #967 + #971's iPhone
 * `MainTabController`, `ios:Shell/MainTabController.swift:73-95`): one row
 * of glass controls on the Sessions list instead of a tab bar — Settings,
 * Search (the system's toolbar-integrated field, filtering the list in
 * place) and a prominent New session, each its own glass shape (the fixed
 * spaces that split the glass). Settings pushes over the list; the bar
 * belongs to the front page alone ([NavStack.chromeOnRootOnly]) and steps
 * aside for sessions, folders and Settings.
 *
 * Search follows iOS 26's toolbar search: activating the field (a tap,
 * `showSearch()`) expands it over the bar — Settings slides out at the
 * leading edge, New session fades, a Close circle takes its place — and
 * Close (or system Back) clears the query and returns the rest layout.
 * The field never hides on scroll (`hidesSearchBarWhenScrolling = false`).
 *
 * #61: the voice item (`waveform`, between Search and New session, when a
 * host can run voice or a call is live) — its own glass circle, leaving
 * with New session while the search is active.
 */
object SessionsBottomBarMetrics {
    /** The row's height, the circles' side and the field's height. */
    const val Height = 48f

    /** The gap between the controls (each control its own glass shape). */
    const val Gap = 8f

    /** The row's side margins inside the safe area. */
    const val SideMargin = 16f

    /** The glyphs in the circles. */
    const val IconPt = 19f

    /** The field's glyphs and text (SF body). */
    const val FieldPt = 17f

    /** The magnifier's leading inset in the field. */
    const val MagnifierLeading = 14f

    /** The text's start from the field's leading edge. */
    const val TextStart = 40f

    /** The text's end inset with no clear button. */
    const val TextEndInset = 14f

    /** The text's end inset beside the clear button (12 + 20.4 glyph box + 6). */
    const val TextEndClear = 38.4f

    /** The clear button's touch target width (44 × 48, at the field's trailing edge). */
    const val ClearTouchWidth = 44f
}

/** The placeholder, verbatim (`searchBar.placeholder = "Search"`). */
const val BottomBarSearchPlaceholder = "Search"

/**
 * The field's expand/collapse spring, interruptible from the current value
 * and velocity. Kept here, not in `Motion`: a system animation tuned on its
 * own (the old search morph's numbers).
 */
val BottomBarSearchSpring = IosSpring("bottomBarSearch", 0.40, 0.85f)

/**
 * The bar's pure geometry over the search expansion `p` (0 = the rest bar,
 * 1 = the active search; unclamped, the spring overshoots slightly), in dp
 * relative to the row's leading edge, for a row [rowWidth] wide.
 *
 * The field's **trailing** edge never moves (`rowWidth − 56`): its leading
 * edge slides from 56 to 0 as Settings slides out past the leading edge,
 * and the trailing circle stays put while New session hands over to Close
 * — so nothing jumps sideways but the pieces that leave.
 */
object SessionsBottomBarGeometry {
    private const val Step = SessionsBottomBarMetrics.Height + SessionsBottomBarMetrics.Gap

    /** Settings' x: 0 at rest, one step past the leading edge when active. */
    fun leadingX(p: Float): Float = -Step * p

    /** The field's x: one step in at rest, the row's leading edge when active. */
    fun fieldX(p: Float): Float = Step * (1f - p)

    /**
     * The field's width: the row minus the trailing circle and gap, minus
     * [fieldX] — and, at rest, minus the voice circle and gap when [voice]
     * shows (#61; the field grows over its slot as it leaves).
     */
    fun fieldWidth(rowWidth: Float, p: Float, voice: Boolean = false): Float =
        (rowWidth - Step - fieldX(p) - if (voice) Step * (1f - p).coerceIn(0f, 1f) else 0f)
            .coerceAtLeast(SessionsBottomBarMetrics.Height)

    /** The trailing circle's x (New session at rest, Close when active). */
    fun trailingX(rowWidth: Float): Float = rowWidth - SessionsBottomBarMetrics.Height

    /** #61: the voice circle's x, one step before the trailing circle. */
    fun voiceX(rowWidth: Float): Float = trailingX(rowWidth) - Step

    /** Settings and New session: gone by p = 0.5. */
    fun restAlpha(p: Float): Float = 1f - (p / 0.5f).coerceIn(0f, 1f)

    /** Close: in from p = 0.5. */
    fun closeAlpha(p: Float): Float = ((p - 0.5f) / 0.5f).coerceIn(0f, 1f)
}

/**
 * The search state the one-row bar filters the Sessions list with, or
 * `null` outside it. [ShellRouteContent] passes its query to the Sessions
 * root.
 */
val LocalBottomBarSearch = staticCompositionLocalOf<SearchTabState?> { null }

/**
 * Ends the search (Close, system Back): IME down, focus dropped, then
 * [SearchTabState.close] (the query cleared, the rest layout back).
 */
@Composable
fun rememberCloseSearch(state: SearchTabState): () -> Unit {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val latestKeyboard by rememberUpdatedState(keyboard)
    return remember(state, focusManager) {
        {
            latestKeyboard?.hide()
            focusManager.clearFocus(force = true)
            state.close()
        }
    }
}

/**
 * The bar itself. [onSettings] pushes Settings over the list (the router's
 * `showSettings`), [onNewSession] presents the New Session sheet. The field
 * is [BottomBarSearchField] over [searchState]: typing filters the list in
 * place. [onFrontPage] is `false` while a page is pushed over the list: the
 * bar stays composed (hidden), and a focused field lets go of its focus and
 * the IME.
 */
@Composable
fun SessionsBottomBar(
    searchState: SearchTabState,
    onSettings: () -> Unit,
    onNewSession: () -> Unit,
    modifier: Modifier = Modifier,
    onFrontPage: Boolean = true,
    voice: (@Composable () -> Unit)? = null,
) {
    val palette = ZeronTheme.palette
    val active = searchState.active
    val progress = rememberSearchExpansion(active)
    val closeSearch = rememberCloseSearch(searchState)
    val circle = SessionsBottomBarMetrics.Height.dp

    Layout(
        modifier = modifier.fillMaxWidth().height(circle),
        content = {
            Box(Modifier.graphicsLayer { alpha = SessionsBottomBarGeometry.restAlpha(progress.value) }.inert(active)) {
                GlassCircleButton(
                    icon = ZeronIcons.Gearshape,
                    contentDescription = "Settings",
                    onClick = onSettings,
                    modifier = Modifier.testTag("bottom-settings"),
                    size = circle,
                    iconPt = SessionsBottomBarMetrics.IconPt,
                )
            }
            // The capsule draws on the non-blur glass tier: a live-blurred
            // glass capsule in this always-on bar cost the CI emulator
            // (software GPU) its device (PR #38's bisect); the circles
            // keep the blur.
            CompositionLocalProvider(LocalGlassTier provides GlassTier.Fallback) {
                BottomBarSearchField(state = searchState, held = onFrontPage)
            }
            Box(Modifier.graphicsLayer { alpha = SessionsBottomBarGeometry.restAlpha(progress.value) }.inert(active)) {
                // `.prominent`, tinted the text colour.
                GlassCircleButton(
                    icon = ZeronIcons.SquareAndPencil,
                    contentDescription = "New session",
                    onClick = onNewSession,
                    modifier = Modifier.testTag("new-session"),
                    size = circle,
                    iconPt = SessionsBottomBarMetrics.IconPt,
                    weight = SymbolWeight.Semibold,
                    filled = palette.text,
                    ink = palette.background,
                )
            }
            Box(Modifier.graphicsLayer { alpha = SessionsBottomBarGeometry.closeAlpha(progress.value) }.inert(!active)) {
                GlassCircleButton(
                    icon = ZeronIcons.Xmark,
                    contentDescription = "Close",
                    onClick = closeSearch,
                    modifier = Modifier.testTag("search-close"),
                    size = circle,
                    iconPt = SessionsBottomBarMetrics.IconPt,
                )
            }
            // #61: the voice circle (it renders nothing while voice isn't
            // offered, so the slot measures 0 and the field keeps its width).
            if (voice != null) {
                Box(Modifier.graphicsLayer { alpha = SessionsBottomBarGeometry.restAlpha(progress.value) }.inert(active)) {
                    voice()
                }
            }
        },
    ) { measurables, constraints ->
        // The expansion is read here and in the layers only: the spring
        // relayouts and redraws the row, it never recomposes it.
        val p = progress.value
        val rowWidth = constraints.maxWidth.toDp().value
        val circlePx = circle.roundToPx()
        val fixed = Constraints.fixed(circlePx, circlePx)
        val settings = measurables[0].measure(fixed)
        val voice = measurables.getOrNull(4)?.measure(Constraints(maxWidth = circlePx, maxHeight = circlePx))
        val hasVoice = voice != null && voice.width > 0
        val fieldPx = SessionsBottomBarGeometry.fieldWidth(rowWidth, p, hasVoice).dp.roundToPx()
        val field = measurables[1].measure(Constraints.fixed(fieldPx, circlePx))
        val compose = measurables[2].measure(fixed)
        val close = measurables[3].measure(fixed)
        val settingsX = SessionsBottomBarGeometry.leadingX(p).dp.roundToPx()
        val fieldX = SessionsBottomBarGeometry.fieldX(p).dp.roundToPx()
        val trailingX = SessionsBottomBarGeometry.trailingX(rowWidth).dp.roundToPx()
        val voiceX = SessionsBottomBarGeometry.voiceX(rowWidth).dp.roundToPx()
        val rest = SessionsBottomBarGeometry.restAlpha(p) > 0f
        val closing = SessionsBottomBarGeometry.closeAlpha(p) > 0f
        layout(constraints.maxWidth, circlePx) {
            // An invisible piece is not placed: not drawn, not hit-tested,
            // not in the accessibility tree. placeRelative mirrors in RTL.
            if (rest) settings.placeRelative(settingsX, 0)
            field.placeRelative(fieldX, 0)
            if (rest && hasVoice) voice?.placeRelative(voiceX, 0)
            if (rest) compose.placeRelative(trailingX, 0)
            if (closing) close.placeRelative(trailingX, 0)
        }
    }
}

/**
 * The expansion `p` as a [State]: the initial value is the target (a bar
 * that composes with the search active shows it expanded, no motion);
 * every later flip springs with [BottomBarSearchSpring], interruptibly.
 * Under Reduce Motion it snaps.
 */
@Composable
private fun rememberSearchExpansion(active: Boolean): State<Float> {
    val reduce = LocalReduceMotion.current
    val animatable = remember { Animatable(if (active) 1f else 0f) }
    LaunchedEffect(active, reduce) {
        val target = if (active) 1f else 0f
        if (reduce) animatable.snapTo(target) else animatable.animateTo(target, BottomBarSearchSpring.spec())
    }
    return animatable.asState()
}

/**
 * A piece on its way out: its taps are swallowed (the parent consumes the
 * pointer events before the child's `clickable` sees them) and it leaves
 * the accessibility tree, while its fade finishes.
 */
private fun Modifier.inert(inert: Boolean): Modifier =
    if (!inert) {
        this
    } else {
        this
            .clearAndSetSemantics { }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                }
            }
    }

// ---------------------------------------------------------------------------
// The field
// ---------------------------------------------------------------------------

/**
 * The search field: a 48 dp glass capsule — the magnifier (17 pt,
 * `secondary`) at 14, the text from 40 in Geist Regular 17 `fixed` over the
 * state-based `BasicTextField` (IME action Search, no autocorrect: session
 * titles hold identifiers), the placeholder "Search" in `tertiary`, the
 * caret `accent`, and the clear button (17 pt `XmarkCircleFill`,
 * `tertiary`) while there is text. A tap anywhere on the capsule focuses
 * the field and raises the IME; focus makes the search active.
 *
 * [SearchTabState.activationToken] focuses it one frame after composition
 * (`activateSearch()`'s next run-loop turn), the caret at the end. The IME
 * "Search" key hides the IME and drops the focus (the text and the active
 * search stay). The IME hidden **while focused** (system back) drops the
 * focus too — no orphan caret — but only once the IME has been seen up.
 * [held] `false` (a page pushed over the list) lets go of the focus.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BottomBarSearchField(
    state: SearchTabState,
    modifier: Modifier = Modifier,
    held: Boolean = true,
) {
    val palette = ZeronTheme.palette
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val focusRequester = remember { FocusRequester() }
    val hasText by remember(state) { derivedStateOf { state.text.text.isNotEmpty() } }
    val imeVisible = WindowInsets.isImeVisible

    // One token per activation, one frame after the field is composed
    // (requesting focus on the frame a requester is first attached throws),
    // the caret at the end of the surviving text. A recreated composition
    // (rotation) does not refocus: the token was consumed.
    LaunchedEffect(state.activationToken) {
        val token = state.activationToken
        if (token == 0 || token == state.consumedToken) return@LaunchedEffect
        state.consumedToken = token
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
        keyboard?.show()
        state.text.edit { placeCursorAtEnd() }
    }

    LaunchedEffect(focused) {
        if (focused) state.onFieldFocused()
    }

    // A push over the bar: the field lets go before the page lands.
    LaunchedEffect(held) {
        if (!held && focused) {
            keyboard?.hide()
            focusManager.clearFocus(force = true)
        }
    }

    // The orphan-caret guard: `sawIme` is only set once the IME has been
    // seen up with the field focused — never right after focus, while it is
    // still rising.
    var sawIme by remember { mutableStateOf(false) }
    LaunchedEffect(focused, imeVisible) {
        if (focused && imeVisible) sawIme = true
    }
    LaunchedEffect(imeVisible) {
        if (!imeVisible && sawIme) {
            focusManager.clearFocus()
            sawIme = false
        }
    }

    GlassSurface(
        modifier = modifier
            .fillMaxSize()
            .clickable(interactionSource = source, indication = null) {
                runCatching { focusRequester.requestFocus() }
                keyboard?.show()
            },
        shape = CapsuleShape,
        interactionSource = source,
    ) {
        Icon(
            ZeronIcons.Magnifyingglass,
            tint = palette.secondary,
            sizePt = SessionsBottomBarMetrics.FieldPt,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = SessionsBottomBarMetrics.MagnifierLeading.dp),
        )
        SearchFieldText(
            state = state,
            hasText = hasText,
            focusRequester = focusRequester,
            interactionSource = source,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxSize()
                .padding(
                    start = SessionsBottomBarMetrics.TextStart.dp,
                    end = if (hasText) SessionsBottomBarMetrics.TextEndClear.dp else SessionsBottomBarMetrics.TextEndInset.dp,
                ),
        )
        if (hasText) {
            SearchClearButton(
                state = state,
                focused = focused,
                focusRequester = focusRequester,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

/** The text, placeholder and IME wiring over [SearchTabState.text]. */
@Composable
private fun SearchFieldText(
    state: SearchTabState,
    hasText: Boolean,
    focusRequester: FocusRequester,
    interactionSource: MutableInteractionSource,
    modifier: Modifier,
) {
    val palette = ZeronTheme.palette
    val typeScale = LocalTypeScale.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val style = uiStyle(Face.Sans, typeScale.fixed(SessionsBottomBarMetrics.FieldPt), palette.text)

    BasicTextField(
        state = state.text,
        modifier = modifier
            .focusRequester(focusRequester)
            .testTag("search-field")
            .semantics { contentDescription = BottomBarSearchPlaceholder },
        textStyle = style,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Search,
        ),
        onKeyboardAction = object : KeyboardActionHandler {
            override fun onKeyboardAction(performKeyboardAction: () -> Unit) {
                // The IME "Search" key: the IME down, the focus dropped; the
                // text and the active search stay.
                keyboard?.hide()
                focusManager.clearFocus()
            }
        },
        lineLimits = TextFieldLineLimits.SingleLine,
        interactionSource = interactionSource,
        cursorBrush = SolidColor(palette.accent),
        decorator = TextFieldDecorator { innerTextField ->
            Box(Modifier.fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                if (!hasText) {
                    BasicText(
                        BottomBarSearchPlaceholder,
                        style = style.copy(color = palette.tertiary),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                    )
                }
                innerTextField()
            }
        },
    )
}

/**
 * The clear button: the 44 × 48 touch target at the field's trailing edge,
 * the glyph centred in it. Tapping it empties the query at once — and
 * focuses the field if it was not focused (UIKit's `UITextField` begins
 * editing on clear).
 */
@Composable
private fun SearchClearButton(
    state: SearchTabState,
    focused: Boolean,
    focusRequester: FocusRequester,
    modifier: Modifier,
) {
    val palette = ZeronTheme.palette
    val keyboard = LocalSoftwareKeyboardController.current
    Box(
        modifier
            .width(SessionsBottomBarMetrics.ClearTouchWidth.dp)
            .fillMaxHeight()
            .testTag("search-clear")
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
            ) {
                state.text.clearText()
                if (!focused) {
                    runCatching { focusRequester.requestFocus() }
                    keyboard?.show()
                }
            }
            .clearAndSetSemantics {
                contentDescription = "Clear text"
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(ZeronIcons.XmarkCircleFill, tint = palette.tertiary, sizePt = SessionsBottomBarMetrics.FieldPt)
    }
}
