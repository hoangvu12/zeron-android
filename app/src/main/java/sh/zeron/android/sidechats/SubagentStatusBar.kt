package sh.zeron.android.sidechats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import sh.zeron.android.core.Face
import sh.zeron.android.design.CapsuleShape
import sh.zeron.android.design.LocalTypeScale
import sh.zeron.android.design.StatusGlyph
import sh.zeron.android.design.StatusGlyphKind
import sh.zeron.android.design.ZeronTheme
import sh.zeron.android.design.formatElapsed
import sh.zeron.android.design.glass.GlassSurface
import sh.zeron.android.design.uiStyle
import uniffi.zeron_core.modelLabel

/** Subagent labels (iOS `SubagentFormat`, `ios:Companions/SubagentViewController.swift:160-178`). */
object SubagentFormat {

    /**
     * `claude-haiku-5` → "Haiku 5"; Claude's bare aliases capitalized; any
     * other id through the core's catalog ([label], `modelLabel`).
     */
    fun model(id: String, label: (harness: String, model: String) -> String = ::modelLabel): String {
        val lower = id.lowercase()
        if (lower in listOf("haiku", "sonnet", "opus")) return lower.replaceFirstChar { it.uppercase() }
        val harness = when {
            lower.startsWith("claude") -> "claude-code"
            lower.startsWith("gpt") -> "codex"
            else -> ""
        }
        val known = runCatching { label(harness, id) }.getOrDefault(id)
        if (known != id || !lower.startsWith("claude-")) return known
        // An id the catalog doesn't know yet: `claude-haiku-5` → "Haiku 5".
        val words = lower.removePrefix("claude-").split('-')
        val family = words.firstOrNull()?.takeIf { it.isNotEmpty() }?.replaceFirstChar { it.uppercase() } ?: return known
        val version = words.drop(1).takeWhile { it.length <= 2 && it.all(Char::isDigit) }.joinToString(".")
        return if (version.isEmpty()) family else "$family $version"
    }

    /** "Haiku 5 · Explore" (either part may be missing; empty when both are). */
    fun detail(modelId: String?, kind: String?, label: (String, String) -> String = ::modelLabel): String =
        listOfNotNull(modelId?.let { model(it, label) }, kind).joinToString(" · ")

    /** The header subtitle: "Subagent", then the detail when there is one. */
    fun subtitle(detail: String): String = if (detail.isEmpty()) "Subagent" else "Subagent · $detail"

    /** iOS `elapsed(since:)`: the status pill's format, never negative. */
    fun elapsed(startedAtMs: Long, nowMs: Long): String = formatElapsed(maxOf(0L, (nowMs - startedAtMs) / 1000).toInt())
}

/** What the subagent screen's bar shows (iOS `SubagentStatusBar.State`). */
enum class SubagentBarState { Working, Done, Failed, Unavailable }

/** The bar's two texts: the status line and the trailing detail (`null`: none). */
data class SubagentBarText(val label: String, val detail: String?) {
    /** iOS `accessibilityLabel`: both, comma-joined. */
    val accessibilityLabel: String get() = listOfNotNull(label, detail).joinToString(", ")
}

/** iOS `configure` + `render`, pure: [startedAtMs] `null` = no spawn record yet. */
fun subagentBarText(state: SubagentBarState, detail: String, startedAtMs: Long?, nowMs: Long): SubagentBarText {
    val trailing = detail.ifEmpty { null }
    return when (state) {
        SubagentBarState.Working -> SubagentBarText(
            startedAtMs?.let { "Working · ${SubagentFormat.elapsed(it, nowMs)}" } ?: "Working",
            trailing ?: "Runs on its own",
        )
        SubagentBarState.Done -> SubagentBarText("Finished", trailing)
        SubagentBarState.Failed -> SubagentBarText("Failed", trailing)
        SubagentBarState.Unavailable -> SubagentBarText("Waiting for its computer…", null)
    }
}

/**
 * The subagent screen's bottom bar (iOS `SubagentStatusBar`,
 * `SubagentViewController.swift:180-258`): a 50 dp glass capsule, at most
 * 560 dp wide, with the status glyph (12 dp: the working trailer, a done
 * check, a failed or idle dot), the status line in Medium 14 and the
 * detail (model · kind, or "Runs on its own") in Sans 13 secondary on the
 * right. While working the elapsed time ticks each second. One TalkBack
 * element reading "<status>, <detail>".
 */
@Composable
fun SubagentStatusBar(
    state: SubagentBarState,
    detail: String,
    startedAtMs: Long?,
    modifier: Modifier = Modifier,
) {
    val palette = ZeronTheme.palette
    val type = LocalTypeScale.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    if (state == SubagentBarState.Working) {
        LaunchedEffect(Unit) {
            while (true) {
                now = System.currentTimeMillis()
                delay(1_000)
            }
        }
    }
    val text = subagentBarText(state, detail, startedAtMs, now)
    val glyph = when (state) {
        SubagentBarState.Working -> StatusGlyphKind.Trailer
        SubagentBarState.Done -> StatusGlyphKind.Check(palette.status.done)
        SubagentBarState.Failed -> StatusGlyphKind.Dot(palette.status.failed)
        SubagentBarState.Unavailable -> StatusGlyphKind.Dot(palette.status.idle)
    }
    GlassSurface(
        modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .height(50.dp)
            .testTag("subagent-status")
            .clearAndSetSemantics { contentDescription = text.accessibilityLabel },
        shape = CapsuleShape,
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusGlyph(glyph)
            BasicText(
                text.label,
                style = uiStyle(Face.SansMedium, type.fixed(14f), palette.text),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // The detail takes the rest, right-aligned, and gives way first
            // (iOS: low compression resistance).
            if (text.detail != null) {
                BasicText(
                    text.detail,
                    Modifier.weight(1f),
                    style = uiStyle(Face.Sans, type.fixed(13f), palette.secondary).copy(textAlign = TextAlign.End),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
