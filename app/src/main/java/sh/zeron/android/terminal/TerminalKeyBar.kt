package sh.zeron.android.terminal

import android.view.SoundEffectConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.zeron.android.core.Face
import sh.zeron.android.design.CapsuleShape
import sh.zeron.android.design.Haptic
import sh.zeron.android.design.Icon
import sh.zeron.android.design.LocalFeedback
import sh.zeron.android.design.LocalTypeScale
import sh.zeron.android.design.SymbolWeight
import sh.zeron.android.design.ZeronIcon
import sh.zeron.android.design.ZeronIcons
import sh.zeron.android.design.ZeronTheme
import sh.zeron.android.design.fadingHorizontalScroll
import sh.zeron.android.design.glass.GlassSurface
import sh.zeron.android.design.uiStyle
import uniffi.zeron_core.KeyModifiers
import uniffi.zeron_core.TerminalKey

/** A sticky modifier (iOS `TerminalKeyBar.Latch`): tap for the next key, again to lock, again off. */
enum class Latch { Off, Once, Locked }

/** The latch's next state on a tap. Pure. */
fun Latch.next(): Latch = when (this) {
    Latch.Off -> Latch.Once
    Latch.Once -> Latch.Locked
    Latch.Locked -> Latch.Off
}

/**
 * The key bar's sticky Ctrl/Alt (iOS `TerminalKeyBar.ctrl/alt`): what the
 * next key carries ([modifiers]), and the one-shot release after it goes
 * out ([consume]). Main thread.
 */
@Stable
class TerminalModifiers {
    var ctrl by mutableStateOf(Latch.Off)
    var alt by mutableStateOf(Latch.Off)

    val modifiers: KeyModifiers get() = KeyModifiers(ctrl = ctrl != Latch.Off, alt = alt != Latch.Off, shift = false)

    /** A key went out: one-shot modifiers let go; locked ones stay. */
    fun consume() {
        if (ctrl == Latch.Once) ctrl = Latch.Off
        if (alt == Latch.Once) alt = Latch.Off
    }
}

/** What a key-bar key does (iOS `TerminalKeyBar.Action`). */
sealed interface KeyBarAction {
    data class Key(val key: TerminalKey) : KeyBarAction
    data class Text(val text: String) : KeyBarAction
    data object Paste : KeyBarAction
    data object Dismiss : KeyBarAction
}

/** The key bar's geometry (`TerminalKeyBar.swift:36-102`), dp. */
internal object KeyBarTokens {
    const val Height = 52f
    const val KeyHeight = 38f
    const val KeyMinWidth = 44f
    const val KeyGap = 6f
    const val Inset = 8f
    const val KeyPadding = 12f
    const val TitlePt = 14f
    const val IconPt = 14f

    /** Arrows: repeat after 0.38 s, every 60 ms. */
    const val RepeatDelayMs = 380L
    const val RepeatEveryMs = 60L
}

/** The punctuation keys, in iOS order. */
internal val KeyBarPunctuation = listOf("~", "/", "-", "|", "*", "$", "`")

/**
 * The keys a phone keyboard lacks, on glass above it (iOS
 * `TerminalKeyBar`): esc · ctrl · tab · ↑ ↓ ← → · alt │ ~ / - | * $ ` │
 * paste, horizontally scrolling under a soft edge fade, with the
 * hide-keyboard key pinned trailing. Titles in Geist Mono Medium 14; every
 * key is its own 38 dp glass capsule (≥ 44 wide, 6 apart, 8 in). Ctrl and
 * Alt latch (prominent accent 75 % once, accent locked; a11y value On /
 * Locked; a selection haptic); arrows fire on touch-down and repeat while
 * held; the other keys click like the keyboard does.
 */
@Composable
fun TerminalKeyBar(
    modifiers: TerminalModifiers,
    onAction: (KeyBarAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val feedback = LocalFeedback.current
    val act by rememberUpdatedState(onAction)
    // iOS `playInputClick` on key-bar taps.
    fun send(action: KeyBarAction) {
        view.playSoundEffect(SoundEffectConstants.CLICK)
        act(action)
    }
    Row(
        modifier
            .fillMaxWidth()
            .height(KeyBarTokens.Height.dp)
            .testTag("terminal-key-bar"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .fadingHorizontalScroll(rememberScrollState())
                .padding(horizontal = KeyBarTokens.Inset.dp),
            horizontalArrangement = Arrangement.spacedBy(KeyBarTokens.KeyGap.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BarKey(title = "esc", label = "Escape") { send(KeyBarAction.Key(TerminalKey.Escape)) }
            BarKey(title = "ctrl", label = "Control", latch = modifiers.ctrl) {
                feedback.haptic(Haptic.Select)
                modifiers.ctrl = modifiers.ctrl.next()
            }
            BarKey(title = "tab", label = "Tab") { send(KeyBarAction.Key(TerminalKey.Tab)) }
            RepeatingKey(ZeronIcons.ArrowUp, "Up") { act(KeyBarAction.Key(TerminalKey.Up)) }
            RepeatingKey(ZeronIcons.ArrowDown, "Down") { act(KeyBarAction.Key(TerminalKey.Down)) }
            RepeatingKey(ZeronIcons.ArrowLeft, "Left") { act(KeyBarAction.Key(TerminalKey.Left)) }
            RepeatingKey(ZeronIcons.ArrowRight, "Right") { act(KeyBarAction.Key(TerminalKey.Right)) }
            BarKey(title = "alt", label = "Option", latch = modifiers.alt) {
                feedback.haptic(Haptic.Select)
                modifiers.alt = modifiers.alt.next()
            }
            KeyDivider()
            for (t in KeyBarPunctuation) BarKey(title = t, label = t) { send(KeyBarAction.Text(t)) }
            KeyDivider()
            BarKey(icon = ZeronIcons.DocOnClipboard, label = "Paste") { act(KeyBarAction.Paste) }
        }
        BarKey(
            icon = ZeronIcons.ChevronDown,
            label = TerminalCopy.HideKeyboard,
            modifier = Modifier.padding(start = 4.dp, end = KeyBarTokens.Inset.dp),
        ) { act(KeyBarAction.Dismiss) }
    }
}

/** One glass capsule key: a mono title or a glyph; the latch tints it. */
@Composable
private fun BarKey(
    title: String? = null,
    icon: ZeronIcon? = null,
    label: String,
    latch: Latch = Latch.Off,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val palette = ZeronTheme.palette
    val ts = LocalTypeScale.current
    val source = remember { MutableInteractionSource() }
    val (tint, ink) = when (latch) {
        Latch.Off -> null to palette.text
        Latch.Once -> palette.accent.copy(alpha = 0.75f) to Color.White
        Latch.Locked -> palette.accent to Color.White
    }
    GlassSurface(
        modifier = modifier
            .height(KeyBarTokens.KeyHeight.dp)
            .widthIn(min = KeyBarTokens.KeyMinWidth.dp)
            .clickable(source, indication = null, role = Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = label
                role = Role.Button
                if (latch != Latch.Off) stateDescription = if (latch == Latch.Locked) "Locked" else "On"
                onClick { onClick(); true }
            },
        shape = CapsuleShape,
        interactive = true,
        tint = tint,
        interactionSource = source,
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.padding(horizontal = KeyBarTokens.KeyPadding.dp), contentAlignment = Alignment.Center) {
            if (title != null) {
                BasicText(title, style = uiStyle(Face.MonoMedium, ts.fixed(KeyBarTokens.TitlePt), ink), maxLines = 1)
            }
            if (icon != null) {
                Icon(icon, tint = ink, sizePt = KeyBarTokens.IconPt, weight = SymbolWeight.Semibold)
            }
        }
    }
}

/**
 * An arrow (`repeating(symbol:label:_:)`): one step on touch-down (with
 * the input click), then after 0.38 s every 60 ms until the finger lifts,
 * the gesture is cancelled or it drags off the key.
 */
@Composable
private fun RepeatingKey(icon: ZeronIcon, label: String, onKey: () -> Unit) {
    val palette = ZeronTheme.palette
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val key by rememberUpdatedState(onKey)
    val source = remember { MutableInteractionSource() }
    GlassSurface(
        modifier = Modifier
            .height(KeyBarTokens.KeyHeight.dp)
            .widthIn(min = KeyBarTokens.KeyMinWidth.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    view.playSoundEffect(SoundEffectConstants.CLICK)
                    key()
                    val repeat = scope.launch {
                        delay(KeyBarTokens.RepeatDelayMs)
                        while (true) {
                            key()
                            delay(KeyBarTokens.RepeatEveryMs)
                        }
                    }
                    // Up, cancel or a drag off the key all end the repeat.
                    waitForUpOrCancellation()
                    repeat.cancel()
                }
            }
            .clearAndSetSemantics {
                contentDescription = label
                role = Role.Button
                onClick { key(); true }
            },
        shape = CapsuleShape,
        interactive = true,
        interactionSource = source,
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.padding(horizontal = KeyBarTokens.KeyPadding.dp), contentAlignment = Alignment.Center) {
            Icon(icon, tint = palette.text, sizePt = KeyBarTokens.IconPt, weight = SymbolWeight.Semibold)
        }
    }
}

/** The hairline between the key groups: 1 × 22 dp. */
@Composable
private fun KeyDivider() {
    Box(
        Modifier
            .width(1.dp)
            .height(22.dp)
            .background(ZeronTheme.palette.hairline),
    )
}
