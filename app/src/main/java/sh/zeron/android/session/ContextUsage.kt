package sh.zeron.android.session

import kotlin.math.roundToInt
import sh.zeron.android.composer.ChipTint
import sh.zeron.android.composer.ComposerChip

/**
 * The composer's `context` chip (#34, docs/port/02 C16) — the port of
 * `ios:Session/CoreSessionSource.swift:117-127`: `N% context`, with the
 * warning glyph and tint from 85 %. #133 (owner decision, deliberately
 * unlike iOS's `fraction >= 0.5`): shown whenever the usage is known. It has no menu (iOS `chipMenu`
 * answers `nil` for it) and no details card.
 *
 * Pure (plain-JVM tested): built on the controller's IO lane by
 * [sessionChips] from `ComposerState.contextUsage`.
 */

/** The context chip's calm glyph. */
const val CONTEXT_SYMBOL = "circle.lefthalf.filled"

/** The context chip's glyph from 85 % (the **unrounded** fraction). */
const val CONTEXT_WARNING_SYMBOL = "exclamationmark.circle"

/** The warning threshold (glyph + `warning` tint), iOS `fraction >= 0.85`. */
const val CONTEXT_WARNING_FRACTION = 0.85

/**
 * The `context` chip, or `null` for none: it needs a token count and a
 * window `> 0`; any fraction shows (#133). The warning reads the
 * **unrounded** fraction (`845 / 1000` shows `85% context` calm); the
 * percentage rounds half away from zero (Swift's `.rounded()`), and a
 * fraction above 1 is not clamped (`120% context`).
 */
fun contextChip(tokens: ULong?, window: ULong?): ComposerChip? {
    if (tokens == null || window == null || window == 0uL) return null
    val fraction = tokens.toDouble() / window.toDouble()
    val warn = fraction >= CONTEXT_WARNING_FRACTION
    return ComposerChip(
        id = SessionChipIds.Context,
        title = "${(fraction * 100).roundToInt()}% context",
        symbol = if (warn) CONTEXT_WARNING_SYMBOL else CONTEXT_SYMBOL,
        tint = if (warn) ChipTint.Warning else null,
    )
}
