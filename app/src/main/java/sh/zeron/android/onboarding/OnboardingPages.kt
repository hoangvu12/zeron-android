package sh.zeron.android.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import sh.zeron.android.accounts.LocalProvidersSummary
import sh.zeron.android.accounts.ProvidersRoute
import sh.zeron.android.accounts.ProvidersSummary
import sh.zeron.android.cloud.GitHubConnectSheet
import sh.zeron.android.core.CloudStore
import sh.zeron.android.design.BottomChromeInsets
import sh.zeron.android.design.GroupedListTokens
import sh.zeron.android.design.LocalBottomChrome
import sh.zeron.android.shell.LocalLargeTitleHostInsets
import sh.zeron.android.shell.NavStack
import sh.zeron.android.shell.NavStackHost
import sh.zeron.android.shell.ShellRouteContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.zeron.android.ZeronApplication
import sh.zeron.android.auth.SignInState
import sh.zeron.android.core.Face
import sh.zeron.android.core.WallpaperEffects
import sh.zeron.android.core.WallpaperStore
import sh.zeron.android.design.ActionSheetAction
import sh.zeron.android.design.ActionSheetStyle
import sh.zeron.android.design.Appearance
import sh.zeron.android.design.Icon
import sh.zeron.android.design.LocalActionSheet
import sh.zeron.android.design.LocalIsDark
import sh.zeron.android.design.LocalTypeScale
import sh.zeron.android.design.SymbolWeight
import sh.zeron.android.design.ZeronIcons
import sh.zeron.android.design.ZeronTheme
import sh.zeron.android.design.uiStyle
import sh.zeron.android.notify.rememberNotificationPermissionRequester
import sh.zeron.android.settings.WallpaperRowIds
import sh.zeron.android.settings.rememberWallpaperSectionState
import sh.zeron.android.settings.wallpaperEffectSheet
import sh.zeron.android.shell.DeepLink

// ---------------------------------------------------------------------------
// Appearance (`appearancePage`, OnboardingViewController.swift:262-315)
// ---------------------------------------------------------------------------

@Composable
internal fun AppearancePage(app: ZeronApplication) {
    val appearance by app.appearance.collectAsState()
    PageStack {
        Heading("Make it yours", "Pick a look. You can change it any time in Settings → Appearance.")
        Group("Color scheme") {
            SchemePicker(appearance, onPick = app::setAppearance)
        }
        Group("New chat wallpaper") { WallpaperGroup() }
    }
}

/** `UISegmentedControl(["System", "Light", "Dark"])`: the stored look, switched instantly. */
@Composable
private fun SchemePicker(current: Appearance, onPick: (Appearance) -> Unit) {
    val palette = ZeronTheme.palette
    val typeScale = LocalTypeScale.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(32.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(palette.controlFill)
            .padding(2.dp)
            .testTag("onboarding-scheme"),
    ) {
        for (option in Appearance.entries) {
            val selected = option == current
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (selected) palette.elevated else palette.controlFill.copy(alpha = 0f))
                    .clickable(role = Role.Tab) { onPick(option) },
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    option.name,
                    style = uiStyle(if (selected) Face.SansSemibold else Face.SansMedium, typeScale.fixed(13f), palette.text),
                )
            }
        }
    }
}

/**
 * The wallpaper card: the image row ("Choose image" / "Change"), and once
 * one is set the effect row (a menu of effects plus Remove Wallpaper)
 * with the sharp preview above the card.
 */
@Composable
private fun WallpaperGroup() {
    val context = LocalContext.current
    val store = remember(context) { WallpaperStore.get(context) }
    val state by store.state.collectAsState()
    // Settings' wallpaper state owns the photo picker launcher.
    val section = rememberWallpaperSectionState()
    val sheets = LocalActionSheet.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (state.set) WallpaperPreview(store, state.generation)
        val image: @Composable () -> Unit = {
            CardRow(
                icon = ZeronIcons.Photo,
                title = if (state.set) state.name ?: "Wallpaper" else "No wallpaper",
                detail = "Shows behind every new chat.",
            ) {
                RowButton(
                    if (state.set) "Change" else "Choose image",
                    prominent = !state.set,
                    tag = "onboarding-wallpaper",
                ) { section.onRowClick(WallpaperRowIds.Choose) }
            }
        }
        val effect: @Composable () -> Unit = {
            CardRow(
                icon = ZeronIcons.WandAndStars,
                title = "Background effect",
                detail = WallpaperEffects.detail(state.effect),
            ) {
                Capsule(
                    title = WallpaperEffects.label(state.effect),
                    fill = ZeronTheme.palette.controlFill,
                    ink = ZeronTheme.palette.text,
                    face = Face.SansMedium,
                    size = 14f,
                    horizontal = 14.dp,
                    vertical = 8.dp,
                ) {
                    val effects = wallpaperEffectSheet(state.effect) { store.setEffect(it) }
                    sheets.show(
                        effects.copy(
                            actions = effects.actions + ActionSheetAction(
                                title = "Remove Wallpaper",
                                style = ActionSheetStyle.Destructive,
                            ) { store.remove() },
                        ),
                    )
                }
            }
        }
        Card(if (state.set) listOf(image, effect) else listOf(image))
    }
}

/** The wallpaper as a new chat shows it — effect and all — 132 tall (`WallpaperPreview`). */
@Composable
private fun WallpaperPreview(store: WallpaperStore, generation: Int) {
    val dark = LocalIsDark.current
    val image by produceState<ImageBitmap?>(null, generation, dark) {
        value = store.render(dark)?.image
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(132.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(ZeronTheme.palette.controlFill)
            .testTag("onboarding-wallpaper-preview"),
    ) {
        image?.let {
            Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

// ---------------------------------------------------------------------------
// Sync (`syncPage`, :317-337)
// ---------------------------------------------------------------------------

@Composable
internal fun SyncPage(app: ZeronApplication, signedIn: Boolean) {
    val palette = ZeronTheme.palette
    val typeScale = LocalTypeScale.current
    val signIn by app.authSession.state.collectAsState()
    val email = rememberSignedInEmail(app, signedIn)
    PageStack {
        Heading("Sync your work", "Log in to keep your chats, projects and settings in step everywhere you use Zeron.")
        Card(
            listOf(
                { CardRow(ZeronIcons.IphoneAndArrowForward, "On every device", "Pick up a chat on your phone or another computer, right where you left it.") },
                { CardRow(ZeronIcons.Laptop, "Run agents anywhere", "Start work on any of your machines from whichever one you're at.") },
                { CardRow(ZeronIcons.Cloud, "Zeron Cloud", "Hand chats to cloud machines that keep going while your computer sleeps.") },
            ),
        )
        val status: Pair<String, Color>? = when {
            signedIn -> (email?.let { "Signed in as $it" } ?: "You're signed in") to palette.secondary
            signIn is SignInState.Error -> (signIn as SignInState.Error).message to palette.danger
            else -> null
        }
        if (status != null) {
            BasicText(
                status.first,
                style = uiStyle(Face.Sans, typeScale.fixed(14f), status.second),
                modifier = Modifier.testTag("onboarding-sync-status"),
            )
        }
    }
}

/** The signed-in account's email from the profile (#15 saved it); read off main. */
@Composable
private fun rememberSignedInEmail(app: ZeronApplication, signedIn: Boolean): String? {
    val email by produceState<String?>(null, signedIn) {
        value = if (signedIn) withContext(Dispatchers.IO) { app.credentials.profile.email } else null
    }
    return email
}

// ---------------------------------------------------------------------------
// Notifications (`notificationsPage`, :412-422; footer :248-256)
// ---------------------------------------------------------------------------

@Composable
internal fun NotificationsPage() {
    PageStack {
        Heading("Stay in the loop", "Get a notification when an agent finishes, needs an answer, or runs into trouble.")
        Card(
            listOf(
                { CardRow(ZeronIcons.CheckmarkCircle, "Finished", "A session is done") },
                { CardRow(ZeronIcons.QuestionmarkBubble, "Needs you", "An agent is waiting on an answer") },
                { CardRow(ZeronIcons.ExclamationmarkTriangle, "Something went wrong", "A run failed") },
            ),
        )
    }
}

/**
 * "Not now" and "Turn on": the latter turns the app's notifications on
 * and asks for the system permission, then moves on whatever the answer.
 * Delivery itself is #56's.
 */
@Composable
internal fun NotificationsFooter(app: ZeronApplication, onNext: () -> Unit) {
    val requester = rememberNotificationPermissionRequester(app.notificationPrefs) {
        app.refreshNotificationsOn()
        onNext()
    }
    GhostButton("Not now", tag = "onboarding-not-now", onClick = onNext)
    PrimaryButton("Turn on", tag = "onboarding-turn-on") {
        app.notificationPrefs.enabled = true
        requester.request()
    }
}

// ---------------------------------------------------------------------------
// GitHub (`githubPage`, :339-358; `connectGitHub`, :630-632)
// ---------------------------------------------------------------------------

@Composable
internal fun GitHubPage(app: ZeronApplication) {
    val palette = ZeronTheme.palette
    val cloud = app.cloud
    var connecting by remember { mutableStateOf(false) }
    PageStack {
        Heading(
            "Connect GitHub",
            "Cloud agents work in your repositories on machines of their own. Connect GitHub so they can clone and push \u2014 or skip and do it later in Settings \u2192 Cloud.",
        )
        Card(
            listOf {
                val connected = cloud.githubConnected
                val repos = cloud.repos?.size ?: 0
                CardRow(
                    icon = ZeronIcons.GithubMark,
                    title = if (connected) cloud.githubAccount?.let { "@$it" } ?: "GitHub" else "GitHub",
                    detail = if (connected) {
                        if (repos == 1) "1 repository" else "$repos repositories"
                    } else {
                        "Clone and push from Cloud"
                    },
                ) {
                    if (connected) {
                        // `CloudRow.check()`: a 22 pt filled check in success green.
                        Icon(ZeronIcons.CheckmarkCircleFill, tint = palette.success, sizePt = 22f, weight = SymbolWeight.Semibold)
                    } else {
                        RowButton("Connect", prominent = true, tag = "onboarding-github") { connecting = true }
                    }
                }
            },
        )
    }
    if (connecting) GitHubConnectSheet(onDismissed = { connecting = false })
}

// ---------------------------------------------------------------------------
// Agents (`agentsPage`, :360-410)
// ---------------------------------------------------------------------------

/**
 * Settings → Providers itself under the heading, its device switcher,
 * switches and accounts included. The heading's line follows what the
 * list found (`onSummary`). The list keeps a navigation stack of its own,
 * so an agent's accounts open inside the step (iOS presents them as a
 * sheet where there is no stack), and it fades out above the footer.
 */
@Composable
internal fun AgentsPage(modifier: Modifier = Modifier) {
    var detail by remember { mutableStateOf("Looking for coding agents\u2026") }
    val summary = remember {
        ProvidersSummary { place, cloud, names ->
            detail = agentsDetail(place, cloud, names)
        }
    }
    Column(modifier) {
        Heading("Your agents", detail)
        Spacer(Modifier.height(4.dp))
        val stack = remember { NavStack(ProvidersRoute(embedded = true)) }
        DisposableEffect(stack) { onDispose { stack.closeAll() } }
        CompositionLocalProvider(
            LocalProvidersSummary provides summary,
            LocalLargeTitleHostInsets provides WindowInsets(0, 0, 0, 0),
            LocalBottomChrome provides BottomChromeInsets.None,
        ) {
            NavStackHost(
                stack = stack,
                active = true,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    // The list's inset-grouped margins line up with the page's.
                    .layout { measurable, constraints ->
                        val extra = (GroupedListTokens.CardInset * 2).roundToPx()
                        val wide = constraints.copy(
                            minWidth = constraints.minWidth + extra,
                            maxWidth = constraints.maxWidth + extra,
                        )
                        val placeable = measurable.measure(wide)
                        layout(constraints.maxWidth, placeable.height) {
                            placeable.place(-extra / 2, 0)
                        }
                    }
                    .fadingBottom(),
            ) { entry ->
                ShellRouteContent(entry.route)
            }
        }
    }
}

/** The heading's line for what Providers found (`agentsPage`'s `onSummary`). */
internal fun agentsDetail(place: String, cloud: Boolean, names: List<String>?): String = when {
    names == null -> "Looking for coding agents on $place\u2026"
    cloud -> "Cloud runs ${joinNames(names)} with your own subscriptions. Sign in to any you'd like to use."
    names.isEmpty() -> "Zeron runs the coding agents you already use, and none are on $place yet."
    else -> "Found ${if (names.size > 3) "${names.size} agents" else joinNames(names)} on $place. Turn on the ones you'd like to use."
}

/** "Claude Code", "Claude Code and Codex", "A, B and C" (`join`). */
internal fun joinNames(names: List<String>): String = when (names.size) {
    0 -> ""
    1 -> names[0]
    else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
}

/** `FadingBottomView`: the content fades out over its last 28 dp (at most half its height). */
private fun Modifier.fadingBottom(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val edge = if (size.height > 0f) minOf(0.5f, 28.dp.toPx() / size.height) else 0f
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Black,
                (1f - edge) to Color.Black,
                1f to Color.Transparent,
            ),
            blendMode = BlendMode.DstIn,
        )
    }

// ---------------------------------------------------------------------------
// Done (`donePage`, :424-501)
// ---------------------------------------------------------------------------

@Composable
internal fun DonePage(app: ZeronApplication) {
    val palette = ZeronTheme.palette
    val typeScale = LocalTypeScale.current
    val context = LocalContext.current
    val appearance by app.appearance.collectAsState()
    val wallpaper by remember(context) { WallpaperStore.get(context) }.state.collectAsState()
    val email = rememberSignedInEmail(app, signedIn = true)
    val cloud = app.cloud
    val withheld = cloud.info != null && !cloud.available
    Column(verticalArrangement = Arrangement.spacedBy(28.dp)) {
        // `spacer(24)` with no stack spacing after it: 24 above the badge.
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Box(
                Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(palette.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(ZeronIcons.Checkmark, tint = palette.accent, sizePt = 22f, weight = SymbolWeight.Semibold)
            }
            // `CloudUI.header(…, alignment: .center)`: SemiBold 28 over Sans 16, 10 apart.
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                BasicText(
                    "You're all set",
                    style = uiStyle(Face.SansSemibold, typeScale.fixed(28f), palette.text).copy(textAlign = TextAlign.Center),
                )
                BasicText(
                    if (withheld) {
                        "Pick a project and start a session on any of your computers."
                    } else {
                        "Pick a project and start a session \u2014 on any of your computers, or on Cloud."
                    },
                    style = uiStyle(Face.Sans, typeScale.fixed(16f), palette.secondary).copy(textAlign = TextAlign.Center),
                )
            }
        }
        val style = appearance.name
        val agents = CloudStore.agents.filter { cloud.activeAccount(it) != null }.map { it.name }
        Summary(
            buildList {
                add("Appearance" to if (wallpaper.set) "$style · Wallpaper" else style)
                add("Sync" to (email?.let { "Signed in as $it" } ?: "Signed in"))
                if (!withheld) {
                    add("GitHub" to if (cloud.githubConnected) "Connected" else "Not yet \u00b7 Settings \u2192 Cloud")
                    add("Cloud agents" to if (agents.isEmpty()) "Not yet \u00b7 Settings \u2192 Providers" else joinNames(agents))
                }
            },
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Capsule(
                title = "Start a session",
                fill = palette.text,
                ink = palette.background,
                face = Face.SansSemibold,
                size = 17f,
                horizontal = 20.dp,
                vertical = 15.dp,
                modifier = Modifier.fillMaxWidth(),
                tag = "onboarding-finish",
                leading = ZeronIcons.Plus,
            ) {
                app.onboarding.finish()
                app.deepLinks.submit(DeepLink.NewSession(null))
            }
            Capsule(
                title = "I'll do this later",
                fill = Color.Transparent,
                ink = palette.secondary,
                face = Face.SansMedium,
                size = 15f,
                horizontal = 12.dp,
                vertical = 10.dp,
                modifier = Modifier.fillMaxWidth(),
                tag = "onboarding-later",
            ) { app.onboarding.finish() }
        }
    }
}

/** The summary card: label on the left, value on the right, hairlines between (`summary`). */
@Composable
private fun Summary(lines: List<Pair<String, String>>) {
    val palette = ZeronTheme.palette
    val typeScale = LocalTypeScale.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(palette.elevated),
    ) {
        lines.forEachIndexed { ix, (label, value) ->
            if (ix > 0) {
                Box(
                    Modifier
                        .padding(horizontal = 16.dp)
                        .fillMaxWidth()
                        .height(0.5.dp)
                        .background(palette.hairline),
                )
            }
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BasicText(label, style = uiStyle(Face.Sans, typeScale.fixed(15f), palette.secondary))
                BasicText(
                    value,
                    style = uiStyle(Face.Sans, typeScale.fixed(15f), palette.text).copy(textAlign = TextAlign.End),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
