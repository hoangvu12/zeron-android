package sh.zeron.android.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import sh.zeron.android.ZeronApplication
import sh.zeron.android.auth.LocalSignIn
import sh.zeron.android.auth.SignInState
import sh.zeron.android.core.Face
import sh.zeron.android.design.CapsuleShape
import sh.zeron.android.design.Icon
import sh.zeron.android.design.LocalTypeScale
import sh.zeron.android.design.SymbolWeight
import sh.zeron.android.design.WallpaperHero
import sh.zeron.android.design.ZeronIcon
import sh.zeron.android.design.ZeronTheme
import sh.zeron.android.design.uiStyle

/**
 * The onboarding layer (#17, `OnboardingViewController`), drawn over the
 * app root while [OnboardingModel.screen] is set: the new-chat wallpaper
 * softened behind every step, the "N of M" progress, the page, and the
 * footer (Back on the left, Skip and the way forward on the right).
 *
 * It cross-dissolves in from Welcome's Get started and appears with no
 * transition after signing in (`showOnboardingIfNeeded(animated: false)`);
 * pages change with iOS's 0.3 s ease-out, sliding 20 dp in from the
 * trailing side. System back is the footer's Back.
 */
@Composable
fun OnboardingHost(app: ZeronApplication) {
    val screen by app.onboarding.screen.collectAsState()
    // The last screen shown, so the layer keeps drawing while it fades out.
    var last by remember { mutableStateOf(screen) }
    screen?.let { last = it }
    val enter = if (screen?.animated == false) EnterTransition.None else fadeIn(tween(PageMillis))
    AnimatedVisibility(
        visible = screen != null,
        enter = enter,
        exit = fadeOut(tween(PageMillis)),
    ) {
        last?.let { Onboarding(app, it) }
    }
}

@Composable
private fun Onboarding(app: ZeronApplication, screen: OnboardingScreen) {
    val palette = ZeronTheme.palette
    val signedIn = app.sessionHolder.client.collectAsState().value != null
    BackHandler { app.onboarding.back(signedIn) }
    val slidePx = with(LocalDensity.current) { 20.dp.roundToPx() }
    Box(
        Modifier
            .fillMaxSize()
            .background(palette.background)
            .testTag("onboarding"),
    ) {
        // `wallpaper.softened`: blurred to its colours behind every step, so
        // text over it stays legible; Appearance previews it sharp.
        WallpaperHero(Modifier.fillMaxSize().blur(SoftenRadius))
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
            contentAlignment = Alignment.TopCenter,
        ) {
            // The page: 560 wide at most, at least 24 from the sides.
            Column(
                Modifier
                    .widthIn(max = PageMaxWidth)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .fillMaxSize(),
            ) {
                Spacer(Modifier.height(14.dp))
                Progress(screen.step)
                Spacer(Modifier.height(18.dp))
                AnimatedContent(
                    targetState = screen.step,
                    transitionSpec = {
                        if (initialState == targetState) {
                            EnterTransition.None togetherWith ExitTransition.None
                        } else {
                            (fadeIn(tween(PageMillis, easing = EaseOut)) +
                                slideInHorizontally(tween(PageMillis, easing = EaseOut)) { slidePx })
                                .togetherWith(fadeOut(tween(PageMillis, easing = EaseOut)))
                        }
                    },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    label = "onboarding-page",
                ) { step ->
                    Page(app, step, signedIn)
                }
                Spacer(Modifier.height(12.dp))
                Footer(app, screen.step, signedIn)
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun Page(app: ZeronApplication, step: OnboardingStep, signedIn: Boolean) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 6.dp, bottom = 16.dp),
    ) {
        when (step) {
            OnboardingStep.Appearance -> AppearancePage(app)
            OnboardingStep.Sync -> SyncPage(app, signedIn)
            OnboardingStep.Notifications -> NotificationsPage()
            OnboardingStep.Done -> DonePage(app)
            // TODO(#54): the GitHub and Agents pages.
            OnboardingStep.GitHub, OnboardingStep.Agents -> Unit
        }
    }
}

/** The desktop's progress: a short bar per step, then "2 of 3". Kept (empty) on Done. */
@Composable
private fun Progress(step: OnboardingStep) {
    val palette = ZeronTheme.palette
    val typeScale = LocalTypeScale.current
    val number = step.number()
    Row(Modifier.height(20.dp), verticalAlignment = Alignment.CenterVertically) {
        if (number == null) return@Row
        NumberedSteps.indices.forEach { ix ->
            Box(
                Modifier
                    .padding(end = if (ix == NumberedSteps.lastIndex) 10.dp else 4.dp)
                    .size(width = 20.dp, height = 3.dp)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(if (ix + 1 <= number) palette.accent else palette.controlFill),
            )
        }
        BasicText(
            "$number of ${NumberedSteps.size}",
            style = uiStyle(Face.Sans, typeScale.fixed(12f), palette.tertiary),
        )
    }
}

@Composable
private fun Footer(app: ZeronApplication, step: OnboardingStep, signedIn: Boolean) {
    if (step == OnboardingStep.Done) return
    val model = app.onboarding
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (step.previous != null || !signedIn) {
            GhostButton("Back", tag = "onboarding-back") { model.back(signedIn) }
        }
        Spacer(Modifier.weight(1f))
        when (step) {
            OnboardingStep.Appearance -> PrimaryButton("Continue") { model.go(step.next) }
            OnboardingStep.Sync -> {
                if (signedIn) {
                    PrimaryButton("Continue") { model.go(step.next) }
                } else {
                    val signIn = LocalSignIn.current
                    val state by app.authSession.state.collectAsState()
                    val loggingIn = state == SignInState.LoggingIn
                    PrimaryButton(
                        if (loggingIn) "Logging in…" else "Log in",
                        enabled = !loggingIn,
                        tag = "onboarding-log-in",
                    ) { signIn.start() }
                }
            }
            OnboardingStep.Notifications -> NotificationsFooter(app) { model.go(step.next) }
            // TODO(#54): GitHub's Continue / "Skip for now"; Agents' Continue.
            OnboardingStep.GitHub, OnboardingStep.Agents -> PrimaryButton("Continue") { model.go(step.next) }
            OnboardingStep.Done -> Unit
        }
    }
}

// ---------------------------------------------------------------------------
// Pieces (`OnboardingViewController.swift:505-603`, `CloudUI.swift`)
// ---------------------------------------------------------------------------

/** Title + detail, the way every step opens (`heading`): Sans SemiBold 26 over Sans 15. */
@Composable
internal fun Heading(title: String, detail: String) {
    val palette = ZeronTheme.palette
    val typeScale = LocalTypeScale.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicText(title, style = uiStyle(Face.SansSemibold, typeScale.fixed(26f), palette.text))
        BasicText(detail, style = uiStyle(Face.Sans, typeScale.fixed(15f), palette.secondary))
    }
}

/** A small muted label over its control (`group`). */
@Composable
internal fun Group(title: String, content: @Composable () -> Unit) {
    val palette = ZeronTheme.palette
    val typeScale = LocalTypeScale.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicText(
            title,
            style = uiStyle(Face.SansMedium, typeScale.fixed(13f), palette.secondary),
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        content()
    }
}

/** The page's column: 28 between blocks (`stack`). */
@Composable
internal fun PageStack(content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(28.dp)) { content() }
}

/** One row of a card (`CloudRow`): a tile, a title over a muted line, a trailing accessory. */
@Composable
internal fun CardRow(
    icon: ZeronIcon,
    title: String,
    detail: String?,
    accessory: (@Composable () -> Unit)? = null,
) {
    val palette = ZeronTheme.palette
    val typeScale = LocalTypeScale.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Tile(icon)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(title, style = uiStyle(Face.SansMedium, typeScale.fixed(16f), palette.text))
            if (detail != null) {
                BasicText(
                    detail,
                    style = uiStyle(Face.Sans, typeScale.fixed(13f), palette.secondary),
                    maxLines = 2,
                )
            }
        }
        accessory?.invoke()
    }
}

/** `CloudUI.tile`: a 40 pt rounded square in the control fill, the glyph at half its side. */
@Composable
private fun Tile(icon: ZeronIcon) {
    val palette = ZeronTheme.palette
    Box(
        Modifier
            .size(TileSide)
            .clip(RoundedCornerShape(TileSide * 0.28f))
            .background(palette.controlFill),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, tint = palette.text, sizePt = 17f, weight = SymbolWeight.Medium)
    }
}

/** A card of rows separated by hairlines that start past the tile (`CloudCard`). */
@Composable
internal fun Card(rows: List<@Composable () -> Unit>) {
    val palette = ZeronTheme.palette
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(palette.elevated)
            .padding(vertical = 4.dp),
    ) {
        rows.forEachIndexed { index, row ->
            if (index > 0) {
                Box(
                    Modifier
                        .padding(start = 70.dp)
                        .fillMaxWidth()
                        .height(HairlineWidth)
                        .background(palette.hairline),
                )
            }
            row()
        }
    }
}

/** The footer's filled capsule (`primary`): Sans SemiBold 16, 12 × 22 insets. */
@Composable
internal fun PrimaryButton(title: String, enabled: Boolean = true, tag: String = "onboarding-continue", onClick: () -> Unit) {
    Capsule(
        title = title,
        fill = ZeronTheme.palette.text,
        ink = ZeronTheme.palette.background,
        face = Face.SansSemibold,
        size = 16f,
        horizontal = 22.dp,
        vertical = 12.dp,
        enabled = enabled,
        tag = tag,
        onClick = onClick,
    )
}

/** The footer's quiet text button (`ghost`): secondary Sans Medium 16, 12 × 8 insets. */
@Composable
internal fun GhostButton(title: String, tag: String? = null, onClick: () -> Unit) {
    Capsule(
        title = title,
        fill = Color.Transparent,
        ink = ZeronTheme.palette.secondary,
        face = Face.SansMedium,
        size = 16f,
        horizontal = 8.dp,
        vertical = 12.dp,
        tag = tag,
        onClick = onClick,
    )
}

/** A compact capsule for a row (`CloudRow.rowButton`): filled when prominent, else the control fill. */
@Composable
internal fun RowButton(title: String, prominent: Boolean, tag: String? = null, onClick: () -> Unit) {
    val palette = ZeronTheme.palette
    Capsule(
        title = title,
        fill = if (prominent) palette.text else palette.controlFill,
        ink = if (prominent) palette.background else palette.text,
        face = Face.SansSemibold,
        size = 14f,
        horizontal = 14.dp,
        vertical = 8.dp,
        tag = tag,
        onClick = onClick,
    )
}

@Composable
internal fun Capsule(
    title: String,
    fill: Color,
    ink: Color,
    face: Face,
    size: Float,
    horizontal: Dp,
    vertical: Dp,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tag: String? = null,
    leading: ZeronIcon? = null,
    onClick: () -> Unit,
) {
    val typeScale = LocalTypeScale.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    // A highlighted / disabled UIButton dims toward the background.
    val dim = when {
        !enabled -> 0.45f
        pressed -> 0.25f
        else -> 0f
    }
    val background = ZeronTheme.palette.background
    val shownFill = if (fill == Color.Transparent) fill else lerp(fill, background, dim)
    val shownInk = if (fill == Color.Transparent) ink.copy(alpha = ink.alpha * (1f - dim)) else ink
    Row(
        modifier
            .clip(CapsuleShape)
            .background(shownFill)
            .clickable(
                interactionSource = source,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .padding(horizontal = horizontal, vertical = vertical),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (leading != null) Icon(leading, tint = shownInk, sizePt = 15f, weight = SymbolWeight.Semibold)
        BasicText(
            title,
            style = uiStyle(face, typeScale.fixed(size), shownInk).copy(textAlign = TextAlign.Center),
        )
    }
}

internal const val PageMillis = 300
private val PageMaxWidth = 560.dp
private val SoftenRadius = 40.dp
private val TileSide = 40.dp
private val HairlineWidth = 0.5.dp
