package sh.zeron.android.design.lab

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import sh.zeron.android.core.Face
import sh.zeron.android.design.Appearance
import sh.zeron.android.design.CapsuleShape
import sh.zeron.android.design.GalleryProviders
import sh.zeron.android.design.LocalTypeScale
import sh.zeron.android.design.ZeronTheme
import sh.zeron.android.design.hairlineWidth
import sh.zeron.android.design.uiStyle

/**
 * The component lab (ticket #12, input for decision D7 / #1): each row is
 * one component the app hand-builds today, openable three ways — (1) the
 * current Roboco custom, (2) our look on headless primitives (Compose
 * Foundation / Compose Unstyled), (3) stock Material 3 as the behaviour
 * reference — with the code-level accessibility contract check under each
 * stage ([LabCatalog]).
 *
 * Debug only: this class and its launcher entry live in `src/debug`. The
 * debug manifest gives it its own launcher icon ("Component Lab") so the
 * owner can open it on a phone while signed out (the app lands on the
 * Welcome placeholder); `adb shell am start -n
 * sh.zeron.android/.design.lab.ComponentLabActivity` works too.
 */
class ComponentLabActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { ComponentLab() }
    }
}

@Composable
private fun ComponentLab() {
    var appearance by rememberSaveable { mutableIntStateOf(Appearance.System.storedValue) }
    var openComponent by rememberSaveable { mutableStateOf<String?>(null) }
    var openVariant by rememberSaveable { mutableStateOf(LabVariant.Custom.name) }

    GalleryProviders(Appearance.fromStored(appearance)) {
        val dark = ZeronTheme.isDark
        val view = LocalView.current
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
        val component = openComponent?.let(LabComponent::valueOf)
        val variant = LabVariant.valueOf(openVariant)
        BackHandler(enabled = component != null) { openComponent = null }

        Column(Modifier.fillMaxSize().background(ZeronTheme.palette.background)) {
            LabTopBar(
                title = component?.title ?: "Component Lab",
                showBack = component != null,
                onBack = { openComponent = null },
                appearance = Appearance.fromStored(appearance),
                onAppearance = { appearance = it.storedValue },
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (component == null) {
                    LabIndex { picked, pickedVariant ->
                        openComponent = picked.name
                        openVariant = pickedVariant.name
                    }
                } else {
                    LabStageScreen(component, variant, onVariant = { openVariant = it.name })
                }
            }
        }
    }
}

@Composable
private fun LabTopBar(
    title: String,
    showBack: Boolean,
    onBack: () -> Unit,
    appearance: Appearance,
    onAppearance: (Appearance) -> Unit,
) {
    val palette = ZeronTheme.palette
    val typeScale = LocalTypeScale.current
    Column(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        Box(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 16.dp)) {
            if (showBack) {
                BasicText(
                    "‹ Components",
                    style = uiStyle(Face.SansMedium, typeScale.fixed(17f), palette.accent),
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .clickable(role = Role.Button, onClick = onBack)
                        .semantics { contentDescription = "Components" },
                    maxLines = 1,
                )
            }
            BasicText(
                title,
                style = uiStyle(Face.SansSemibold, typeScale.fixed(17f), palette.text),
                modifier = Modifier.align(Alignment.Center).semantics { heading() },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        LabChoice(
            options = listOf(Appearance.System to "System", Appearance.Light to "Light", Appearance.Dark to "Dark"),
            selected = appearance,
            onSelect = onAppearance,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        )
        Box(Modifier.fillMaxWidth().height(hairlineWidth()).background(palette.hairline))
    }
}

/** The index: one row per component, each with its three variant buttons. */
@Composable
private fun LabIndex(onOpen: (LabComponent, LabVariant) -> Unit) {
    val palette = ZeronTheme.palette
    val typeScale = LocalTypeScale.current
    val bottom = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottom + 16.dp)) {
        item(key = "intro") {
            BasicText(
                "Input for D7 (#1). Each component three ways: 1 Roboco custom · 2 our look on headless " +
                    "primitives · 3 stock Material 3 (behaviour reference). Each stage lists its code-level " +
                    "accessibility contract check; try TalkBack, a keyboard and the back gesture to confirm.",
                style = uiStyle(Face.Sans, typeScale.fixed(13f), palette.secondary),
                modifier = Modifier.padding(16.dp),
            )
        }
        items(LabComponent.entries, key = { it.name }) { component ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                BasicText(
                    component.title,
                    style = uiStyle(Face.SansSemibold, typeScale.fixed(17f), palette.text),
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LabVariant.entries.forEachIndexed { index, variant ->
                        VariantButton(
                            text = "${index + 1} ${variant.short}",
                            label = "${component.title}: ${variant.label}",
                            modifier = Modifier.weight(1f),
                        ) { onOpen(component, variant) }
                    }
                }
            }
            Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(hairlineWidth()).background(palette.hairline))
        }
    }
}

@Composable
private fun VariantButton(text: String, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val palette = ZeronTheme.palette
    val typeScale = LocalTypeScale.current
    Box(
        modifier
            .height(36.dp)
            .clip(CapsuleShape)
            .background(palette.controlFill)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text, style = uiStyle(Face.SansMedium, typeScale.fixed(14f), palette.text), maxLines = 1)
    }
}

/**
 * One component in one variant: the variant switcher (flip between the
 * three without going back), the live stage, and the contract panel.
 */
@Composable
private fun LabStageScreen(component: LabComponent, variant: LabVariant, onVariant: (LabVariant) -> Unit) {
    val palette = ZeronTheme.palette
    Column(Modifier.fillMaxSize()) {
        LabChoice(
            options = LabVariant.entries.mapIndexed { index, v -> v to "${index + 1} ${v.short}" },
            selected = variant,
            onSelect = onVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // Keyed: switching variant or component starts each stage fresh.
            key(component, variant) {
                when (variant) {
                    LabVariant.Custom -> CustomVariant(component)
                    LabVariant.Headless -> HeadlessVariant(component)
                    LabVariant.Material -> MaterialVariant(component)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(hairlineWidth()).background(palette.hairline))
        ContractPanel(component, variant)
    }
}

/** The code-level contract rows for the open stage, scrollable, under it. */
@Composable
private fun ContractPanel(component: LabComponent, variant: LabVariant) {
    val palette = ZeronTheme.palette
    val typeScale = LocalTypeScale.current
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 230.dp)
            .background(palette.background)
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        BasicText(
            component.basis.getValue(variant),
            style = uiStyle(Face.Mono, typeScale.fixed(11f), palette.tertiary),
        )
        component.findings(variant).forEach { finding ->
            Row(Modifier.semantics(mergeDescendants = true) {}) {
                BasicText(
                    "${finding.mark.glyph} ${finding.item.label}",
                    style = uiStyle(Face.SansSemibold, typeScale.fixed(12f), palette.text),
                    modifier = Modifier.width(118.dp),
                )
                BasicText(finding.note, style = uiStyle(Face.Sans, typeScale.fixed(12f), palette.secondary))
            }
        }
    }
}
