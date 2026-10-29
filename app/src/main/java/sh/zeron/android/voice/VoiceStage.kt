package sh.zeron.android.voice

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.zeron.android.core.Face
import sh.zeron.android.design.CapsuleShape
import sh.zeron.android.design.Icon
import sh.zeron.android.design.IosSpring
import sh.zeron.android.design.LocalReduceMotion
import sh.zeron.android.design.LocalTypeScale
import sh.zeron.android.design.SymbolWeight
import sh.zeron.android.design.WallpaperHero
import sh.zeron.android.design.ZeronIcon
import sh.zeron.android.design.ZeronIcons
import sh.zeron.android.design.ZeronTheme
import sh.zeron.android.design.glass.GlassCircleButton
import sh.zeron.android.design.glass.GlassSurface
import sh.zeron.android.design.glass.glassPress
import sh.zeron.android.design.uiStyle
import uniffi.zeron_core.CaptionFader
import uniffi.zeron_core.CaptionFrame
import uniffi.zeron_core.OrbPreset
import uniffi.zeron_core.VoiceEndReason
import uniffi.zeron_core.VoiceOrb
import uniffi.zeron_core.VoiceSpeaker

/** The stage's copy, verbatim from iOS `VoiceStageViewController`. */
object VoiceStageCopy {
    const val Minimize = "Back to Zeron"
    const val Transcript = "Open the voice transcript"
    const val TranscriptAwaiting = "Answer Codex in the transcript"
    const val AudioOutput = "Audio output"
    const val EndCall = "End call"
    const val Mute = "Mute microphone"
    const val Unmute = "Unmute microphone"
    const val Ended = "Call ended"
    const val CallAgain = "Call again"
    const val OpenSettings = "Open Settings"
    const val Close = "Close"
    const val NoClock = "–:––"
}

/** The stage's open/close and swipe springs. */
private val StageSpring = IosSpring("voiceStage", 0.45, 0.9f)

/** How far a swipe down must travel before the stage minimizes. */
private val SwipeDismiss = 96.dp

/**
 * The stage at the app root (#61): draws [VoiceStage] over everything while
 * [presenter] shows it, growing out of the tapped bar control (iOS's zoom
 * transition, `VoiceStageViewController.swift:365-374`) and shrinking back
 * into it — a plain fade under Reduce Motion or without a source. Also
 * answers the controller's stage requests (the call notification's tap,
 * `zeron://voice`, a toast's Retry).
 */
@Composable
fun VoiceStageHost(controller: VoiceController, presenter: VoiceStagePresenter, onOpenChat: (String) -> Unit) {
    LaunchedEffect(controller, presenter) {
        controller.stageRequests.collect { presenter.show(null) }
    }
    val reduce = LocalReduceMotion.current
    val progress = remember { Animatable(0f) }
    var composed by remember { mutableStateOf(false) }
    val drag = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(presenter.showing) {
        if (presenter.showing) {
            composed = true
            drag.floatValue = 0f
            if (reduce) progress.animateTo(1f, tween(200)) else progress.animateTo(1f, StageSpring.spec())
        } else if (composed) {
            progress.animateTo(0f, tween(if (reduce) 200 else 280))
            composed = false
        }
    }
    if (!composed) return
    val source = presenter.source.takeIf { !reduce }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val p = progress.value
                alpha = p.coerceIn(0f, 1f)
                translationY = drag.floatValue
                if (source != null && size.width > 0f) {
                    val q = 1f - p
                    val from = max(0.08f, source.width / size.width)
                    scaleX = from + (1f - from) * p
                    scaleY = scaleX
                    transformOrigin = TransformOrigin.Center
                    translationX = (source.center.x - size.width / 2f) * q
                    translationY += (source.center.y - size.height / 2f) * q
                }
            },
    ) {
        VoiceStage(
            controller = controller,
            onClose = presenter::hide,
            onOpenChat = { chatId ->
                presenter.hide()
                onOpenChat(chatId)
            },
            drag = drag,
        )
    }
}

/**
 * The full-screen call (iOS `VoiceStageViewController`, desktop
 * `voice_stage.rs`; port 04 G18–G23): the orb over the wallpaper, the live
 * caption, and the call controls. Minimize (chevron), swipe down or Back
 * returns to the app; the call continues until End. When the call ends
 * elsewhere the stage closes itself; when it ends on its own, the stage
 * explains why and offers Call again (or Open Settings) and Close.
 */
@Composable
internal fun VoiceStage(
    controller: VoiceController,
    onClose: () -> Unit,
    onOpenChat: (String) -> Unit,
    drag: androidx.compose.runtime.MutableFloatState,
) {
    val s by controller.snapshot.collectAsState()
    val palette = ZeronTheme.palette
    val ts = LocalTypeScale.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val close by rememberUpdatedState(onClose)

    DisposableEffect(controller) {
        controller.stageShown(true)
        onDispose {
            controller.stageShown(false)
            if (!controller.live) controller.dismissEndReason()
        }
    }
    // Hung up (here or elsewhere): the stage has nothing left to show.
    LaunchedEffect(s.live, s.endReason) {
        if (!s.live && s.endReason == null) close()
    }
    BackHandler(onBack = { close() })

    val now by produceState(SystemClock.elapsedRealtime()) {
        while (true) {
            value = SystemClock.elapsedRealtime()
            delay(1000 - value % 1000)
        }
    }
    val state = s.state
    val reason = s.endReason
    val ended = !s.live
    val awaiting = s.awaitingInput

    Box(
        Modifier
            .fillMaxSize()
            .background(palette.background)
            .semantics { paneTitle = "Voice call" }
            .testTag("voice-stage")
            // iOS's swipe down: the stage follows the finger and minimizes
            // past the threshold; a short drag springs back.
            .pointerInput(Unit) {
                val threshold = SwipeDismiss.toPx()
                detectVerticalDragGestures(
                    onDragEnd = {
                        if (drag.floatValue > threshold) {
                            close()
                        } else {
                            scope.launch {
                                Animatable(drag.floatValue).animateTo(0f, StageSpring.spec()) { drag.floatValue = value }
                            }
                        }
                    },
                    onDragCancel = { drag.floatValue = 0f },
                ) { change, dy ->
                    change.consume()
                    drag.floatValue = max(0f, drag.floatValue + dy)
                }
            },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // The wallpaper over the top 72 % (≤ 760 pt), like iOS.
            WallpaperHero(Modifier.fillMaxWidth().height(min(maxHeight.value * 0.72f, 760f).dp))
        }
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            val width = maxWidth
            val height = maxHeight
            // Minimize, the call clock and host, the transcript.
            Box(Modifier.fillMaxWidth().padding(top = 8.dp, start = 16.dp, end = 16.dp).height(44.dp)) {
                GlassCircleButton(
                    icon = ZeronIcons.ChevronDown,
                    contentDescription = VoiceStageCopy.Minimize,
                    onClick = { close() },
                    modifier = Modifier.align(Alignment.CenterStart).testTag("voice-minimize"),
                    size = 44.dp,
                    iconPt = 16f,
                    weight = SymbolWeight.Semibold,
                )
                Row(
                    Modifier.align(Alignment.Center).padding(horizontal = 56.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    val dot = when {
                        !s.active -> palette.tertiary
                        awaiting -> palette.warning
                        s.muted -> palette.tertiary
                        else -> palette.success
                    }
                    Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
                    BasicText(
                        s.elapsedText(now) ?: VoiceStageCopy.NoClock,
                        style = uiStyle(Face.MonoMedium, ts.fixed(13f), palette.secondary),
                        maxLines = 1,
                    )
                    BasicText(
                        s.hostName,
                        style = uiStyle(Face.SansMedium, ts.fixed(13f), palette.tertiary),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val chatId = state?.chatId
                if (chatId != null) {
                    Box(Modifier.align(Alignment.CenterEnd)) {
                        GlassCircleButton(
                            icon = ZeronIcons.TextBubble,
                            contentDescription = if (awaiting) VoiceStageCopy.TranscriptAwaiting else VoiceStageCopy.Transcript,
                            onClick = { onOpenChat(chatId) },
                            modifier = Modifier.testTag("voice-transcript"),
                            size = 44.dp,
                            iconPt = 16f,
                            weight = SymbolWeight.Semibold,
                        )
                        if (awaiting) {
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(2.dp)
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(palette.warning)
                                    .border(2.dp, palette.background, CircleShape)
                                    .clearAndSetSemantics { },
                            )
                        }
                    }
                }
            }

            // The orb (340 pt, centred 64 above the middle), what the call
            // is doing, and the caption under it.
            val side = stageOrbSide(width.value, height.value).dp
            val orbTop = max(60f, height.value / 2f - 64f - side.value / 2f)
            Column(
                Modifier.fillMaxWidth().offset { IntOffset(0, with(density) { orbTop.dp.roundToPx() }) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                VoiceOrbView(
                    orb = if (s.live) s.orb else VoiceOrb.IDLE,
                    preset = OrbPreset.HERO,
                    modifier = Modifier.size(side),
                    microphone = state?.microphone ?: 0f,
                    speaker = state?.speaker ?: 0f,
                )
                Spacer(Modifier.height(4.dp))
                BasicText(
                    if (ended && reason != null) VoiceStageCopy.Ended else s.statusText,
                    style = uiStyle(Face.SansMedium, ts.fixed(17f), palette.text).copy(textAlign = TextAlign.Center),
                    modifier = Modifier
                        .padding(horizontal = 32.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
                Spacer(Modifier.height(10.dp))
                VoiceCaption(
                    item = state?.captionItem,
                    text = state?.caption.orEmpty(),
                    user = state?.captionSpeaker == VoiceSpeaker.USER,
                    message = if (ended && reason != null) s.message(reason) else null,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
                )
            }

            // Mute, audio output and End; after an end, Call again and Close.
            GlassSurface(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp),
                shape = CapsuleShape,
            ) {
                Row(
                    Modifier.padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!ended) {
                        CallButton(
                            icon = if (s.muted) ZeronIcons.MicSlashFill else ZeronIcons.MicFill,
                            label = if (s.muted) VoiceStageCopy.Unmute else VoiceStageCopy.Mute,
                            // Inverted plate while muted, like the desktop call bar.
                            fill = if (s.muted) palette.text else palette.controlFill,
                            ink = if (s.muted) palette.background else palette.text,
                            tag = "voice-mute",
                        ) { controller.toggleMute() }
                        CallButton(
                            icon = if (s.route == VoiceRoute.Earpiece) ZeronIcons.Iphone else ZeronIcons.SpeakerWave2Fill,
                            label = VoiceStageCopy.AudioOutput,
                            fill = palette.controlFill,
                            ink = if (s.route == VoiceRoute.Speaker) palette.accent else palette.text,
                            tag = "voice-route",
                            value = when (s.route) {
                                VoiceRoute.Speaker -> "Speaker"
                                VoiceRoute.Earpiece -> "Phone"
                                VoiceRoute.External -> "Headset"
                            },
                        ) { controller.toggleSpeaker() }
                        CallButton(
                            icon = ZeronIcons.PhoneDownFill,
                            label = VoiceStageCopy.EndCall,
                            fill = palette.danger,
                            ink = Color.White,
                            width = 88.dp,
                            tag = "voice-end",
                        ) { controller.stop() }
                    } else {
                        val denied = reason == VoiceEndReason.MICROPHONE_DENIED
                        PillButton(
                            title = if (denied) VoiceStageCopy.OpenSettings else VoiceStageCopy.CallAgain,
                            fill = palette.accent,
                            ink = Color.White,
                            tag = "voice-retry",
                        ) {
                            if (denied) {
                                openAppSettings(context)
                            } else if (!controller.start()) {
                                controller.dismissEndReason()
                            }
                        }
                        PillButton(VoiceStageCopy.Close, palette.controlFill, palette.text, "voice-close") {
                            controller.dismissEndReason()
                            close()
                        }
                    }
                }
            }
        }
    }
}

/** The hero orb's side: 340 pt, within the width less 32 and the room left for the text. Pure. */
internal fun stageOrbSide(width: Float, height: Float): Float =
    min(min(340f, width - 32f), height - 260f).coerceAtLeast(96f)

/**
 * The tail of the utterance (iOS `paintCaption`, port 04 G20): its newest
 * words veil in through the core's [CaptionFader] and the previous turn
 * fades out over it, repainting every frame until settled; the user's
 * words in `tertiary`, Codex's in `secondary`, at most four lines. A
 * [message] (why the call ended) replaces it.
 */
@Composable
internal fun VoiceCaption(item: String?, text: String, user: Boolean, message: String?, modifier: Modifier = Modifier) {
    val palette = ZeronTheme.palette
    val ts = LocalTypeScale.current
    val reducedMotion = LocalReduceMotion.current
    val fader = remember { CaptionFader(160u) }
    DisposableEffect(fader) { onDispose { fader.close() } }
    var frame by remember { mutableStateOf<CaptionFrame?>(null) }
    LaunchedEffect(item, text, message == null) {
        if (message != null) return@LaunchedEffect
        var next = fader.frame(item, text, reducedMotion)
        frame = next
        while (next.animating) {
            withFrameNanos { }
            next = fader.frame(item, text, reducedMotion)
            frame = next
        }
    }
    val style = uiStyle(Face.Sans, ts.fixed(16f), palette.secondary).copy(textAlign = TextAlign.Center)
    Box(modifier, contentAlignment = Alignment.TopCenter) {
        if (message != null) {
            BasicText(message, style = style, maxLines = 4, overflow = TextOverflow.Ellipsis)
            return@Box
        }
        val f = frame ?: return@Box
        f.previous?.let { previous ->
            BasicText(
                previous,
                style = style,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.alpha(f.previousAlpha.coerceIn(0f, 1f)).clearAndSetSemantics { },
            )
        }
        val base = if (user) palette.tertiary else palette.secondary
        val caption = buildAnnotatedString {
            append(f.text)
            for (span in f.spans) {
                if (span.alpha >= 1f) continue
                val start = span.start.toInt().coerceIn(0, f.text.length)
                val end = span.end.toInt().coerceIn(start, f.text.length)
                if (start < end) addStyle(SpanStyle(color = base.copy(alpha = base.alpha * span.alpha.coerceIn(0f, 1f))), start, end)
            }
        }
        // iOS truncates the head; the core already keeps only the tail.
        BasicText(caption, style = style.copy(color = base), maxLines = 4, overflow = TextOverflow.Ellipsis)
    }
}

/** A 60 pt call button (iOS `callButton`): a capsule plate, a 20 pt Semibold glyph. */
@Composable
private fun CallButton(
    icon: ZeronIcon,
    label: String,
    fill: Color,
    ink: Color,
    tag: String,
    width: Dp = 60.dp,
    value: String? = null,
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(width, 60.dp)
            .clip(CapsuleShape)
            .background(fill)
            .glassPress(interactionSource = source, shape = CapsuleShape, highlight = ink.copy(alpha = 0.12f))
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = label
                role = Role.Button
                if (value != null) stateDescription = value
            }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, ink, 20f, SymbolWeight.Semibold) }
}

/** A 52 pt pill (iOS `pillButton`): Semibold 16, 22 pt side insets. */
@Composable
private fun PillButton(title: String, fill: Color, ink: Color, tag: String, onClick: () -> Unit) {
    val ts = LocalTypeScale.current
    Box(
        Modifier
            .height(52.dp)
            .clip(CapsuleShape)
            .background(fill)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(PaddingValues(horizontal = 22.dp))
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) { BasicText(title, style = uiStyle(Face.SansSemibold, ts.fixed(16f), ink), maxLines = 1) }
}

/** This app's system settings page, where the microphone is granted (iOS `openSettingsURLString`). */
internal fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
    }
}
