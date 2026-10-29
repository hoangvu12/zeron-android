package sh.zeron.android.companions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import sh.zeron.android.R
import sh.zeron.android.core.Face
import sh.zeron.android.design.CapsuleShape
import sh.zeron.android.design.Haptic
import sh.zeron.android.design.Icon
import sh.zeron.android.design.LocalFeedback
import sh.zeron.android.design.LocalTypeScale
import sh.zeron.android.design.StatusGlyph
import sh.zeron.android.design.StatusGlyphKind
import sh.zeron.android.design.SymbolWeight
import sh.zeron.android.design.ZeronIcon
import sh.zeron.android.design.ZeronIcons
import sh.zeron.android.design.ZeronTheme
import sh.zeron.android.design.glass.GlassCircleButton
import sh.zeron.android.design.glass.GlassSurface
import sh.zeron.android.design.uiStyle
import sh.zeron.android.session.SessionHeaderDefaults

/** The companions panel's sections (iOS `CompanionsViewController.Section`); a chip opens its own. */
enum class CompanionSection { Agents, SideChats, Terminals }

/**
 * What's working alongside a session (iOS `CompanionStrip.State`): its
 * subagents (and how many run), its side chats, its terminals (and how
 * many are live). Pure.
 */
@Immutable
data class CompanionCounts(
    val agents: Int = 0,
    val runningAgents: Int = 0,
    val sideChats: Int = 0,
    val terminals: Int = 0,
    val liveTerminals: Int = 0,
) {
    /** Nothing alongside: the strip shows no chip. */
    val isEmpty: Boolean get() = agents == 0 && sideChats == 0 && terminals == 0

    /** The agents chip: "N working" while any run, else the total (iOS `apply`). */
    val agentsText: String get() = if (runningAgents > 0) "$runningAgents working" else "$agents"

    val agentsLabel: String get() = if (runningAgents > 0) "$runningAgents subagents working" else "$agents subagents"

    val sideChatsLabel: String get() = "$sideChats side chats"

    val terminalsLabel: String get() = "$terminals terminals"
}

/** The chip's leading art. */
private sealed interface ChipArt {
    data object Spinner : ChipArt
    data object Bot : ChipArt
    data class Symbol(val icon: ZeronIcon) : ChipArt
}

/**
 * Glass chips above the composer for what's working alongside the session
 * (iOS `CompanionStrip`, `ios:Companions/CompanionStrip.swift`): "2
 * working" while subagents run (else the bot glyph and a count), side
 * chats, terminals (a green dot while any is live). Each chip is hidden at
 * 0 and opens [onTap] with its section after the selection haptic. Sits
 * at the trailing end of the status pill row, 6 dp apart.
 */
@Composable
fun CompanionStrip(counts: CompanionCounts, onTap: (CompanionSection) -> Unit, modifier: Modifier = Modifier) {
    val feedback = LocalFeedback.current
    val tap: (CompanionSection) -> Unit = { section ->
        feedback.haptic(Haptic.Select)
        onTap(section)
    }
    Row(
        modifier.testTag("companion-strip"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChipSlot(counts.agents > 0) {
            CompanionChip(
                art = if (counts.runningAgents > 0) ChipArt.Spinner else ChipArt.Bot,
                text = counts.agentsText,
                label = counts.agentsLabel,
                dot = false,
                tag = "chip-agents",
                onClick = { tap(CompanionSection.Agents) },
            )
        }
        ChipSlot(counts.sideChats > 0) {
            CompanionChip(
                art = ChipArt.Symbol(ZeronIcons.BubbleLeftAndBubbleRight),
                text = "${counts.sideChats}",
                label = counts.sideChatsLabel,
                dot = false,
                tag = "chip-side-chats",
                onClick = { tap(CompanionSection.SideChats) },
            )
        }
        ChipSlot(counts.terminals > 0) {
            CompanionChip(
                art = ChipArt.Symbol(ZeronIcons.Terminal),
                text = "${counts.terminals}",
                label = counts.terminalsLabel,
                dot = counts.liveTerminals > 0,
                tag = "chip-terminals",
                onClick = { tap(CompanionSection.Terminals) },
            )
        }
    }
}

/** A chip's show/hide: UIStackView hides it; the row's own reflow carries the motion. */
@Composable
private fun ChipSlot(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(visible, enter = fadeIn() + scaleIn(initialScale = 0.9f), exit = fadeOut() + scaleOut(targetScale = 0.9f)) {
        content()
    }
}

/**
 * One chip (iOS `CompanionChip`): a 30 dp interactive glass capsule, 11 dp
 * side insets, the art (a 12 dp spinner, or a 15 dp glyph in `secondary`),
 * the count in Medium 13 (`text` while spinning, else `secondary`), and an
 * optional 7 dp done dot. 0.7 alpha while pressed; one TalkBack button.
 */
@Composable
private fun CompanionChip(
    art: ChipArt,
    text: String,
    label: String,
    dot: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    val palette = ZeronTheme.palette
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val spinning = art == ChipArt.Spinner
    GlassSurface(
        Modifier
            .height(30.dp)
            .alpha(if (pressed) 0.7f else 1f)
            .testTag(tag)
            .clickable(interactionSource = interactions, indication = null, role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = label },
        shape = CapsuleShape,
        interactive = true,
        interactionSource = interactions,
        contentAlignment = Alignment.Center,
    ) {
        // The full 30 dp: GlassSurface's content box hands its children no
        // minimum, so a hugging row sat at the capsule's top (iOS centres
        // the row: `row.centerYAnchor == contentView.centerYAnchor`).
        Row(
            Modifier.fillMaxHeight().padding(horizontal = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (art) {
                ChipArt.Spinner -> StatusGlyph(StatusGlyphKind.Spinner)
                ChipArt.Bot -> Image(
                    painterResource(R.drawable.zi_bot),
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    contentScale = ContentScale.Fit,
                    colorFilter = ColorFilter.tint(palette.secondary),
                )
                is ChipArt.Symbol -> Box(Modifier.size(15.dp), contentAlignment = Alignment.Center) {
                    Icon(art.icon, palette.secondary, sizePt = 12f, weight = SymbolWeight.Semibold, contentDescription = null)
                }
            }
            BasicText(
                text,
                style = uiStyle(Face.SansMedium, LocalTypeScale.current.fixed(13f), if (spinning) palette.text else palette.secondary),
                maxLines = 1,
                softWrap = false,
            )
            if (dot) StatusGlyph(StatusGlyphKind.Dot(palette.status.done), Modifier.size(7.dp))
        }
    }
}

/** iOS `companionsItem.accessibilityLabel`. */
const val CompanionsButtonLabel = "Agents, side chats and terminals"

/**
 * The session header's companions item (iOS `companionsItem`,
 * `SessionViewController.swift:56-60, 312-318`): always shown, a 44 dp
 * glass circle with `square.stack.3d.up`, and an accent badge with the
 * running-agent count in white while agents run (violet like the working
 * glyphs: a running agent isn't an alert).
 */
@Composable
fun CompanionsButton(runningAgents: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val palette = ZeronTheme.palette
    Box(modifier) {
        GlassCircleButton(
            icon = ZeronIcons.SquareStack3dUp,
            contentDescription = CompanionsButtonLabel,
            onClick = onClick,
            modifier = Modifier
                .testTag("session-companions")
                .semantics { if (runningAgents > 0) stateDescription = "$runningAgents running" },
            size = SessionHeaderDefaults.ButtonSize,
            iconPt = SessionHeaderDefaults.IconPt,
            weight = SymbolWeight.Medium,
        )
        if (runningAgents > 0) {
            // Decorative: the button's state carries the count.
            BasicText(
                "$runningAgents",
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 4.dp, y = (-2).dp)
                    .background(palette.accent, RoundedCornerShape(50))
                    .defaultMinSize(minWidth = 16.dp, minHeight = 16.dp)
                    .padding(horizontal = 4.5.dp, vertical = 1.dp)
                    .testTag("session-companions-badge")
                    .clearAndSetSemantics { },
                style = uiStyle(Face.SansSemibold, LocalTypeScale.current.fixed(11f), Color.White).copy(textAlign = TextAlign.Center),
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}
