package sh.zeron.android.composer

import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import com.composeunstyled.Tab
import com.composeunstyled.TabList
import com.composeunstyled.TabListScope
import com.composeunstyled.UnstyledTabGroup
import sh.zeron.android.design.IosSpring
import sh.zeron.android.design.LocalReduceMotion
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.zeron.android.core.Face
import sh.zeron.android.design.CapsuleShape
import sh.zeron.android.design.Haptic
import sh.zeron.android.design.HarnessMark
import sh.zeron.android.design.FilledSearchField
import sh.zeron.android.design.Icon
import sh.zeron.android.design.LocalFeedback
import sh.zeron.android.design.LocalTypeScale
import sh.zeron.android.design.ZeronIcons
import sh.zeron.android.design.ZeronTheme
import sh.zeron.android.design.SymbolWeight
import sh.zeron.android.design.glass.GlassCircleButton
import sh.zeron.android.design.sheet.GlassSheet
import sh.zeron.android.design.sheet.SheetDetent
import sh.zeron.android.design.sheet.rememberGlassSheetState
import sh.zeron.android.design.uiStyle

/**
 * The phone's tabbed model picker (wave-10 38, upstream #966's
 * `Session/ModelPickerViewController.swift`, behaviour spec): a glass
 * sheet over the session whose left-aligned "Model" title says where the
 * chat runs; with more than one provider a switcher (brand mark + name)
 * sits over swipeable pages, the current provider first. Models sit on
 * the sheet — no cards, no rules — the current one in a soft accent tint
 * with an accent check; loading shows soft bars; a list longer than
 * [MODEL_SEARCH_THRESHOLD] gets a search field ("Search <agent> models",
 * "No matches"). A pick lands, the sheet closes a beat later.
 *
 * The content is caller-provided ([ModelPickerRequest]): ticket 38 offers
 * the chat's own provider; ticket 39 adds the others when the host can
 * switch provider, with their notes.
 */

/** One provider's page: its tab and what picking there means. */
data class ModelPickerTab(
    val harness: String,
    val label: String,
    /** A notice over this page's list ("Your next message continues this chat with Codex."). */
    val footer: String? = null,
)

/** One model row: its id, name and what it's for. */
data class ModelPickerRow(
    val id: String,
    val label: String,
    val detail: String?,
)

/** Everything one opening of the picker needs; built at tap time. */
class ModelPickerRequest(
    val tabs: List<ModelPickerTab>,
    /** The chat's provider: its page opens first. */
    val harness: String,
    /** The chat's model (ticked), `null` when unknown. */
    val model: String?,
    /** Where the chat runs (the computer's name). */
    val hostName: String,
    /** The SF name of the host's symbol on the subtitle with several tabs (`cloud` on Cloud). */
    val hostSymbol: String = HOST_SYMBOL_COMPUTER,
    /** A notice over the chat's own provider page. */
    val note: String? = null,
    /** One provider's models (loaded when the picker opens; never throws). */
    val load: suspend (harness: String) -> List<ModelPickerRow>,
    /** A pick: the provider page and the row. */
    val onPick: (harness: String, row: ModelPickerRow) -> Unit,
)

/** Who shows the picker (the session screen's [ModelPickerController]). */
fun interface ModelPickerPresenter {
    fun present(request: ModelPickerRequest)
}

/**
 * Whether the picker shows, and with what. Host [ModelPickerOverlay] over
 * the session screen; [present] from a tap (main thread).
 */
@Stable
class ModelPickerController : ModelPickerPresenter {
    var request: ModelPickerRequest? by mutableStateOf(null)
        private set

    override fun present(request: ModelPickerRequest) {
        this.request = request
    }

    /** The sheet reached `Hidden`. */
    internal fun onDismissed() {
        request = null
    }
}

/** The picker's copy (upstream's, verbatim). */
object ModelPickerCopy {
    const val Title = "Model"
    const val NoModels = "No models available"
    const val NoMatches = "No matches"
    const val Close = "Close"

    fun searchPlaceholder(label: String) = "Search $label models"
}

/** The subtitle's host symbol for a computer (iOS `desktopcomputer`). */
const val HOST_SYMBOL_COMPUTER = "desktopcomputer"

/** A list longer than this gets a search field (OpenCode can offer hundreds). */
const val MODEL_SEARCH_THRESHOLD = 12

/** One row per model id, first wins (a catalog can repeat one). Pure. */
fun dedupeModels(rows: List<ModelPickerRow>): List<ModelPickerRow> = rows.distinctBy { it.id }

/** Rows matching the trimmed, case-folded query in their name or id. Pure. */
fun filterModels(rows: List<ModelPickerRow>, query: String): List<ModelPickerRow> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return rows
    return rows.filter { it.label.lowercase().contains(q) || it.id.lowercase().contains(q) }
}

/** Whether a page shows its search field. Pure. */
fun showsModelSearch(rows: List<ModelPickerRow>): Boolean = rows.size > MODEL_SEARCH_THRESHOLD

/** An empty page's line: nothing offered, or nothing matching. Pure. */
fun modelEmptyText(query: String): String =
    if (query.isBlank()) ModelPickerCopy.NoModels else ModelPickerCopy.NoMatches

/** The switcher's segment title — short on room: "Claude", not "Claude Code". Pure. */
fun switcherTitle(label: String): String = if (label == "Claude Code") "Claude" else label

/** The line under "Model": the provider and the host with one tab, the host with several. Pure. */
fun pickerSubtitle(tabs: List<ModelPickerTab>, hostName: String): String {
    val only = tabs.singleOrNull() ?: return hostName
    return "${only.label} · $hostName"
}

/** The page that opens first: the chat's provider, else the first. Pure. */
fun initialPickerPage(tabs: List<ModelPickerTab>, harness: String): Int =
    tabs.indexOfFirst { it.harness == harness }.coerceAtLeast(0)

/** A page's notice: its own footer, else the request's note on the chat's own page. Pure. */
fun pickerPageNotice(tab: ModelPickerTab, page: Int, initial: Int, note: String?): String? =
    tab.footer ?: if (page == initial) note else null

/**
 * The sheet's fitted height (iOS `fittedHeight`): tall enough for the
 * longest provider's list under the header, at least [minHeight], at most
 * 78% of the large height; 55% while no list has measured yet. Pure.
 */
fun fittedPickerHeight(largeHeight: Float, pagerTop: Float, lists: Float?, minHeight: Float): Float {
    if (lists == null || lists <= 0f) return largeHeight * 0.55f
    return min(largeHeight * 0.78f, max(pagerTop + lists, minHeight))
}

/**
 * A lazy list's whole content height from what it has laid out: exact
 * when every item is visible, else the visible items' average extent
 * times the item count, plus the content paddings. `null` with nothing
 * laid out. Pure.
 */
fun lazyContentHeight(info: LazyListLayoutInfo): Float? {
    val visible = info.visibleItemsInfo
    if (visible.isEmpty() || info.totalItemsCount == 0) return null
    val first = visible.first()
    val last = visible.last()
    val span = (last.offset + last.size - first.offset).toFloat()
    val items = if (first.index == 0 && last.index == info.totalItemsCount - 1) {
        span
    } else {
        span / visible.size * info.totalItemsCount
    }
    return info.beforeContentPadding + items + info.afterContentPadding
}

/** One switcher segment's frame on the track (dp, track coordinates). */
data class SegmentFrame(val x: Float, val width: Float) {
    val end: Float get() = x + width
}

/** A segment's natural width: insets, the 17 logo, the gap and its name (iOS `naturalWidth`). Pure. */
fun segmentNaturalWidth(nameWidth: Float): Float =
    14f + SwitcherMetrics.Logo + SwitcherMetrics.Gap + nameWidth + 16f

/** The names don't all fit: the others shrink to their logos (iOS `isCompact`). Pure. */
fun switcherCompact(natural: List<Float>, available: Float): Boolean = natural.sum() > available

/**
 * The segments' frames with [selected] chosen (iOS `frames(selected:)`):
 * equal widths when the widest fits that way, else natural widths shared
 * out, else the chosen one named and the others logos (the track then
 * scrolls). Pure.
 */
fun switcherFrames(natural: List<Float>, available: Float, selected: Int): List<SegmentFrame> {
    val n = natural.size
    if (n == 0) return emptyList()
    val widths: List<Float> = when {
        natural.max() * n <= available -> List(n) { available / n }
        natural.sum() <= available -> {
            val extra = (available - natural.sum()) / n
            natural.map { it + extra }
        }
        else -> {
            val tight = natural.indices.map { if (it == selected) natural[it] else SwitcherMetrics.LogoWidth }
            val extra = max(0f, (available - tight.sum()) / n)
            tight.map { it + extra }
        }
    }
    var x = SwitcherMetrics.Inset
    return widths.map { w -> SegmentFrame(x, w).also { x += w } }
}

/** The switcher's geometry (iOS `ProviderSwitcher` / `Segment`), dp. */
object SwitcherMetrics {
    const val Height = 40f
    const val Inset = 3f
    const val Fade = 34f
    const val LogoWidth = 46f
    const val Logo = 17f
    const val Gap = 7f
}

/** The picker over the session screen while [controller] holds a request. */
@Composable
fun ModelPickerOverlay(controller: ModelPickerController) {
    val request = controller.request ?: return
    key(request) {
        ModelPickerSheet(request, onDismissed = { controller.onDismissed() })
    }
}

@Composable
private fun ModelPickerSheet(request: ModelPickerRequest, onDismissed: () -> Unit) {
    val detents = remember { listOf(SheetDetent.Medium, SheetDetent.Large) }
    val initial = remember(request) { initialPickerPage(request.tabs, request.harness) }
    val pager = rememberPagerState(initialPage = initial, pageCount = { request.tabs.size })
    var current by remember(request) { mutableStateOf(request.harness to request.model) }
    var picked by remember(request) { mutableStateOf(false) }
    val rows = remember(request) { mutableStateMapOf<String, List<ModelPickerRow>>() }
    val scope = rememberCoroutineScope()
    val feedback = LocalFeedback.current
    val focus = LocalFocusManager.current
    val reduceMotion = LocalReduceMotion.current
    val density = LocalDensity.current

    // The fitted detent (iOS `fittedHeight`): the header's height over the
    // pager plus the longest page's list, re-fitted as the lists load.
    var pagerTop by remember(request) { mutableFloatStateOf(0f) }
    val listHeights = remember(request) { mutableStateMapOf<String, Float>() }
    val minHeightPx = with(density) { ModelPickerMinHeight.toPx() }
    val sheetState = rememberGlassSheetState(detents) { large ->
        fittedPickerHeight(large, pagerTop, listHeights.values.maxOrNull(), minHeightPx)
    }

    // Settling on another provider, by swipe or tap: a selection tick and
    // the keyboard away (iOS `settle(on:)`).
    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }.drop(1).collect {
            feedback.haptic(Haptic.Select)
            focus.clearFocus()
        }
    }

    // The current provider first, then the rest, so a swipe lands on a
    // loaded list.
    LaunchedEffect(request) {
        val order = listOf(initial) + request.tabs.indices.filter { it != initial }
        for (ix in order) {
            val tab = request.tabs[ix]
            launch {
                val loaded = try {
                    request.load(tab.harness)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    emptyList()
                }
                rows[tab.harness] = dedupeModels(loaded)
            }
        }
    }

    GlassSheet(
        onDismiss = onDismissed,
        modifier = Modifier.testTag("model-picker"),
        state = sheetState,
        grabber = true,
        title = ModelPickerCopy.Title,
        // The close circle is glass: it sits after the sheet's haze source
        // (GlassSheet's rule), level with the title row in the content.
        glass = {
            val sheet = this
            Box(Modifier.fillMaxSize()) {
                GlassCircleButton(
                    icon = ZeronIcons.Xmark,
                    contentDescription = ModelPickerCopy.Close,
                    onClick = { sheet.dismiss() },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = ModelPickerHeaderMetrics.CloseTop, end = ModelPickerHeaderMetrics.EndPadding)
                        .testTag("model-picker-close"),
                    size = ModelPickerHeaderMetrics.CloseSize,
                    iconPt = 13f,
                    weight = SymbolWeight.Semibold,
                    ink = ZeronTheme.palette.secondary,
                )
            }
        },
    ) { bottomInset ->
        val sheet = this
        Column(Modifier.fillMaxSize()) {
            ModelPickerHeader(
                subtitle = pickerSubtitle(request.tabs, request.hostName),
                harness = request.tabs.singleOrNull()?.harness,
                hostSymbol = request.hostSymbol,
            )
            if (request.tabs.size > 1) {
                ProviderSwitcher(
                    tabs = request.tabs,
                    // Fractional, read in the switcher: the thumb follows
                    // the pages finger for finger.
                    position = { pager.currentPage + pager.currentPageOffsetFraction },
                    selected = pager.currentPage,
                    onSelect = { ix ->
                        if (ix != pager.currentPage) {
                            scope.launch {
                                if (reduceMotion) {
                                    pager.scrollToPage(ix)
                                } else {
                                    pager.animateScrollToPage(ix, animationSpec = SwitchSpring.spec())
                                }
                            }
                        }
                    },
                )
            }
            HorizontalPager(
                state = pager,
                // Every page composes, so each list can report its height
                // to the fit (a handful of providers at most).
                beyondViewportPageCount = request.tabs.size,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onPlaced { pagerTop = it.positionInParent().y },
            ) { page ->
                val tab = request.tabs[page]
                ModelPickerPage(
                    tab = tab,
                    rows = rows[tab.harness],
                    current = current,
                    notice = pickerPageNotice(tab, page, initial, request.note),
                    bottomInset = bottomInset,
                    onContentHeight = { listHeights[tab.harness] = it },
                ) { row ->
                    if (!picked) {
                        picked = true
                        current = tab.harness to row.id
                        feedback.haptic(Haptic.Light)
                        request.onPick(tab.harness, row)
                        // A beat to see the selection land.
                        scope.launch {
                            delay(220)
                            sheet.dismiss()
                        }
                    }
                }
            }
        }
    }
}

/** The fitted sheet's floor (iOS `max(…, 280)`). */
private val ModelPickerMinHeight = 280.dp

/** A tapped provider: iOS's 0.45 s spring, damping 0.86. */
private val SwitchSpring = IosSpring("model-picker-switch", durationSec = 0.45, damping = 0.86f)

/** The header's geometry, shared by the title row and the close circle laid over it. */
private object ModelPickerHeaderMetrics {
    val TopPadding = 18.dp
    val EndPadding = 18.dp
    val CloseSize = 36.dp

    /** The title column is ~49 dp (22 + 3 + 13 sp lines): the circle centred on it. */
    val CloseTop = TopPadding + 6.dp
}

/**
 * "Model" over where it runs: with one provider, its mark first; with
 * several, the host's symbol (11 pt medium). Room on the right for the
 * close circle.
 */
@Composable
private fun ModelPickerHeader(subtitle: String, harness: String?, hostSymbol: String) {
    val palette = ZeronTheme.palette
    val typeScale = LocalTypeScale.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = ModelPickerHeaderMetrics.EndPadding, top = ModelPickerHeaderMetrics.TopPadding, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).testTag("model-picker-title")) {
            BasicText(
                ModelPickerCopy.Title,
                style = uiStyle(Face.SansSemibold, typeScale.fixed(22f), palette.text),
            )
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (harness != null) {
                    HarnessMark(harness, 13.dp, palette.secondary)
                    Spacer(Modifier.width(5.dp))
                } else {
                    ZeronIcons.forSfName(hostSymbol)?.let { icon ->
                        Icon(icon, palette.secondary, 11f, SymbolWeight.Medium, Modifier.testTag("model-picker-host-symbol"))
                        Spacer(Modifier.width(5.dp))
                    }
                }
                BasicText(
                    subtitle,
                    style = uiStyle(Face.Sans, typeScale.fixed(13f), palette.secondary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // The close circle's slot ([ModelPicker]'s `glass`, over this row).
        Spacer(Modifier.width(12.dp + ModelPickerHeaderMetrics.CloseSize))
    }
}

/**
 * The provider switcher (iOS `ProviderSwitcher`): a capsule track of
 * segments (brand mark + name) with a thumb that follows the pages under
 * it finger for finger ([position] is fractional: 1.4 = 40% of the way
 * from the second provider to the third). When the names don't all fit,
 * the others shrink to their logos and the chosen one keeps its name;
 * when even that doesn't fit, the track scrolls, keeps the chosen one in
 * the middle and fades out at the edges with more to see.
 *
 * #62 / D7: the tabs sit on Compose Unstyled's `UnstyledTabGroup` /
 * `TabList` / `Tab` (the Component Lab's variant 2). The primitive gives
 * each segment `Role.Tab` + selected, the list a `selectableGroup` (TalkBack
 * reads "n of m"), a focus group that Tab enters on the chosen provider,
 * and ←/→ (wrapping) / Home / End between providers, which select as they
 * focus (`activateOnFocus`) and so page the pager like a tap. The look,
 * the measured variable-width frames, the scrolling track and the thumb
 * interpolating between the frames on the pager's fractional [position]
 * (audit 03 row 71) stay ours; the primitive's tabs are placed on our
 * frames, never laid out at equal widths.
 */
@Composable
private fun ProviderSwitcher(
    tabs: List<ModelPickerTab>,
    position: () -> Float,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    val palette = ZeronTheme.palette
    val typeScale = LocalTypeScale.current
    val density = LocalDensity.current
    val dark = ZeronTheme.isDark
    val thumbColor = if (dark) Color.White.copy(alpha = 0.14f) else Color.White
    val fullStyle = uiStyle(Face.SansMedium, typeScale.fixed(14f), palette.text)
    val mutedStyle = fullStyle.copy(color = palette.secondary)
    val titles = remember(tabs) { tabs.map { switcherTitle(it.label) } }
    val measurer = rememberTextMeasurer()
    val nameWidths = remember(titles, fullStyle, density) {
        titles.map { title ->
            val px = measurer.measure(title, fullStyle, maxLines = 1, softWrap = false).size.width
            ceil(with(density) { px.toDp().value })
        }
    }
    val natural = remember(nameWidths) { nameWidths.map(::segmentNaturalWidth) }
    val scroll = rememberScrollState()
    // The primitive's tab keys: the providers' harness ids, in page order.
    val keys = remember(tabs) { tabs.map { it.harness } }
    val latestOnSelect by rememberUpdatedState(onSelect)
    UnstyledTabGroup(
        selectedTab = keys[selected.coerceIn(0, keys.lastIndex)],
        // A tap, or a key that moved focus (activate on focus): the page's
        // index, through the sheet's one pager-driving path.
        onSelectedTabChange = { harness: String ->
            val ix = keys.indexOf(harness)
            if (ix >= 0) latestOnSelect(ix)
        },
        tabs = keys,
        modifier = Modifier.fillMaxWidth(),
    ) {
        BoxWithConstraints(
            Modifier
                .padding(start = 20.dp, end = 20.dp, top = 14.dp)
                .fillMaxWidth()
                .height(SwitcherMetrics.Height.dp)
                .clip(CapsuleShape)
                .background(palette.controlFill)
                .testTag("model-picker-switcher"),
        ) {
            val width = maxWidth.value
            val available = width - SwitcherMetrics.Inset * 2
            val compact = switcherCompact(natural, available)
            val p = position().coerceIn(0f, tabs.lastIndex.toFloat())
            val lower = floor(p).toInt().coerceIn(0, tabs.lastIndex)
            val upper = min(tabs.lastIndex, lower + 1)
            val t = (p - lower).coerceIn(0f, 1f)
            val a = switcherFrames(natural, available, lower)
            val b = switcherFrames(natural, available, upper)
            val frames = a.indices.map { lerpFrame(a[it], b[it], t) }
            val thumb = lerpFrame(a[lower], b[upper], t)
            val content = max(width, frames.last().end + SwitcherMetrics.Inset)
            // Keep the chosen provider in the middle of the track.
            val centred = (thumb.x + thumb.width / 2 - width / 2).coerceIn(0f, max(0f, content - width))
            val centredPx = with(density) { centred.dp.roundToPx() }
            LaunchedEffect(centredPx) { scroll.scrollTo(centredPx) }
            val segmentHeight = (SwitcherMetrics.Height - SwitcherMetrics.Inset * 2).dp
            Box(
                Modifier
                    .fillMaxSize()
                    .switcherEdges(scroll)
                    .horizontalScroll(scroll),
            ) {
                // The primitive's tab list (its `selectableGroup`, focus
                // group and arrow keys) spans the scrolled content.
                TabList(
                    Modifier
                        .width(content.dp)
                        .fillMaxHeight(),
                ) {
                    Box(
                        Modifier
                            .offset(x = thumb.x.dp, y = SwitcherMetrics.Inset.dp)
                            .size(thumb.width.dp, segmentHeight)
                            .then(
                                if (dark) {
                                    Modifier
                                } else {
                                    Modifier.shadow(3.dp, CapsuleShape, ambientColor = ThumbShadow, spotColor = ThumbShadow)
                                },
                            )
                            .clip(CapsuleShape)
                            .background(thumbColor),
                    )
                    tabs.forEachIndexed { k, tab ->
                        SwitcherSegment(
                            harness = tab.harness,
                            title = titles[k],
                            frame = frames[k],
                            height = segmentHeight,
                            nameWidth = nameWidths[k],
                            nameVisibility = if (compact) lerp(if (k == lower) 1f else 0f, if (k == upper) 1f else 0f, t) else 1f,
                            emphasis = max(0f, 1f - abs(p - k)),
                            fullStyle = fullStyle,
                            mutedStyle = mutedStyle,
                        )
                    }
                }
            }
        }
    }
}

private val ThumbShadow = Color.Black.copy(alpha = 0.1f)

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

private fun lerpFrame(a: SegmentFrame, b: SegmentFrame, t: Float) =
    SegmentFrame(lerp(a.x, b.x, t), lerp(a.width, b.width, t))

/** Fades the side(s) of the track with more providers past them (iOS `updateEdges`). */
private fun Modifier.switcherEdges(scroll: ScrollState): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val maxOffset = scroll.maxValue
        if (maxOffset <= 0 || maxOffset == Int.MAX_VALUE || size.width <= 0f) return@drawWithContent
        val f = (SwitcherMetrics.Fade.dp.toPx() / size.width).coerceIn(0f, 0.5f)
        val start = if (scroll.value > 0) Color.Transparent else Color.Black
        val end = if (scroll.value < maxOffset) Color.Transparent else Color.Black
        drawRect(
            Brush.horizontalGradient(0f to start, f to Color.Black, 1f - f to Color.Black, 1f to end),
            blendMode = BlendMode.DstIn,
        )
    }

/**
 * One provider on the track (iOS `Segment`): its logo, then its name
 * (shown by [nameVisibility] in compact mode); two stacked names, muted
 * and full, cross-fade with [emphasis] so the colour follows the finger
 * too. A press scales it to 0.96 and dims the logo.
 *
 * #62: the segment is the tab list's `Tab` (keyed by [harness]); the
 * primitive owns its click, selected state, focus and keys, and our
 * modifiers place it on its measured [frame].
 */
@Composable
private fun TabListScope<String>.SwitcherSegment(
    harness: String,
    title: String,
    frame: SegmentFrame,
    height: Dp,
    nameWidth: Float,
    nameVisibility: Float,
    emphasis: Float,
    fullStyle: TextStyle,
    mutedStyle: TextStyle,
) {
    val palette = ZeronTheme.palette
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = tween(if (pressed) 80 else 250),
        label = "segment-press",
    )
    val contentWidth = SwitcherMetrics.Logo + (SwitcherMetrics.Gap + nameWidth) * nameVisibility
    val x0 = ((frame.width - contentWidth) / 2).roundToInt().toFloat()
    // The primitive appends its focus handling and `selectable(role = Tab)`
    // after these; our press source drives the scale and the logo dim.
    Tab(
        key = harness,
        modifier = Modifier
            .offset(x = frame.x.dp, y = SwitcherMetrics.Inset.dp)
            .size(frame.width.dp, height)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clipToBounds()
            .semantics { contentDescription = title }
            .testTag("model-picker-tab-$harness"),
        indication = null,
        interactionSource = interaction,
        contentAlignment = Alignment.CenterStart,
    ) {
        HarnessMark(
            harness,
            SwitcherMetrics.Logo.dp,
            palette.text,
            Modifier
                .offset(x = x0.dp)
                .alpha(if (pressed) 0.45f else 0.55f + 0.45f * emphasis)
                .clearAndSetSemantics { },
        )
        if (nameVisibility > 0f) {
            Box(
                Modifier
                    .offset(x = (x0 + SwitcherMetrics.Logo + SwitcherMetrics.Gap).dp)
                    .requiredWidth(nameWidth.dp)
                    .clearAndSetSemantics { },
            ) {
                BasicText(title, Modifier.alpha(nameVisibility * (1f - emphasis)), style = mutedStyle, maxLines = 1, softWrap = false)
                BasicText(title, Modifier.alpha(nameVisibility * emphasis), style = fullStyle, maxLines = 1, softWrap = false)
            }
        }
    }
}

/** One provider's models: the notice, the search once long, the rows (or the loading bars). */
@Composable
private fun ModelPickerPage(
    tab: ModelPickerTab,
    rows: List<ModelPickerRow>?,
    current: Pair<String, String?>,
    notice: String?,
    bottomInset: Dp,
    /** The list's whole content height (px), for the sheet's fit. */
    onContentHeight: (Float) -> Unit,
    onPick: (ModelPickerRow) -> Unit,
) {
    val palette = ZeronTheme.palette
    val typeScale = LocalTypeScale.current
    // #62: the search field's state (the headless UnstyledTextField is state-based).
    val search = remember(tab.harness) { TextFieldState() }
    val query = search.text.toString()
    val visible = rows?.let { filterModels(it, query) }.orEmpty()
    val listState = rememberLazyListState()
    val reportHeight by rememberUpdatedState(onContentHeight)
    LaunchedEffect(listState) {
        snapshotFlow { lazyContentHeight(listState.layoutInfo) }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { reportHeight(it) }
    }
    LazyColumn(
        Modifier
            .fillMaxSize()
            .testTag("model-picker-list-${tab.harness}"),
        state = listState,
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 10.dp, bottom = bottomInset + 20.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (rows != null && showsModelSearch(rows)) {
            item(key = "search") {
                ModelSearchField(search, ModelPickerCopy.searchPlaceholder(tab.label), resultCount = visible.size)
            }
        }
        if (notice != null) {
            item(key = "notice") { ModelPickerNotice(notice) }
        }
        if (rows == null) {
            items(4, key = { "skeleton-$it" }) { ix -> ModelSkeletonRow(ix) }
        } else if (visible.isEmpty()) {
            item(key = "empty") {
                BasicText(
                    modelEmptyText(query),
                    style = uiStyle(Face.Sans, typeScale.fixed(15f), palette.tertiary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 40.dp)
                        .testTag("model-picker-empty"),
                )
            }
        } else {
            items(visible, key = { "model:${it.id}" }) { row ->
                ModelRow(
                    row = row,
                    current = current.first == tab.harness && current.second == row.id,
                    onClick = { onPick(row) },
                )
            }
        }
    }
}

/** A model: its name over one or two lines of what it's for; the current one tinted and checked. */
@Composable
private fun ModelRow(row: ModelPickerRow, current: Boolean, onClick: () -> Unit) {
    val palette = ZeronTheme.palette
    val typeScale = LocalTypeScale.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (current) palette.accentSoft else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)
            .semantics { selected = current }
            .testTag("model-picker-row-${row.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(
                row.label,
                style = uiStyle(Face.SansMedium, typeScale.fixed(16f), palette.text),
            )
            val detail = row.detail
            if (!detail.isNullOrBlank()) {
                Spacer(Modifier.height(3.dp))
                BasicText(
                    detail,
                    style = uiStyle(Face.Sans, typeScale.fixed(13f), palette.secondary),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Box(Modifier.width(20.dp), contentAlignment = Alignment.Center) {
            Icon(
                ZeronIcons.Checkmark,
                palette.accent,
                14f,
                SymbolWeight.Semibold,
                Modifier.alpha(if (current) 1f else 0f),
            )
        }
    }
}

/** What to know before picking: a quiet tinted line over the list. */
@Composable
private fun ModelPickerNotice(text: String) {
    val palette = ZeronTheme.palette
    val typeScale = LocalTypeScale.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(palette.controlFill)
            .padding(horizontal = 14.dp, vertical = 11.dp)
            .testTag("model-picker-notice"),
    ) {
        Icon(ZeronIcons.InfoCircle, palette.secondary, 13f, SymbolWeight.Medium)
        Spacer(Modifier.width(8.dp))
        BasicText(text, style = uiStyle(Face.Sans, typeScale.fixed(13f), palette.secondary))
    }
}

/** A loading row: two soft bars (static — no infinite animation to keep tests idle). */
@Composable
private fun ModelSkeletonRow(seed: Int) {
    val palette = ZeronTheme.palette
    val title = listOf(0.3f, 0.38f, 0.26f, 0.34f)[seed % 4]
    val detail = listOf(0.6f, 0.48f, 0.55f, 0.42f)[seed % 4]
    Column(Modifier.fillMaxWidth().padding(start = 14.dp, top = 16.dp, bottom = 16.dp)) {
        Box(
            Modifier
                .fillMaxWidth(title)
                .height(13.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(palette.controlFill),
        )
        Spacer(Modifier.height(9.dp))
        Box(
            Modifier
                .fillMaxWidth(detail)
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(palette.controlFill),
        )
    }
}

/**
 * The page's search field, once the list is long. #62 (D7 variant 2): the
 * shared [FilledSearchField] on the headless `UnstyledTextField` — labelled
 * with its placeholder, the search keyboard, Escape, a "Clear text" button
 * and the spoken model count; the look is the field's own from before.
 */
@Composable
private fun ModelSearchField(search: TextFieldState, placeholder: String, resultCount: Int) {
    FilledSearchField(
        state = search,
        placeholder = placeholder,
        textPt = 15f,
        fieldTag = "model-picker-search",
        modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 8.dp),
        resultCount = resultCount,
    )
}
