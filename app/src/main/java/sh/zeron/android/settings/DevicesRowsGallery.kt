package sh.zeron.android.settings

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import sh.zeron.android.core.Face
import sh.zeron.android.design.Appearance
import sh.zeron.android.design.GroupedHeader
import sh.zeron.android.design.GroupedPosition
import sh.zeron.android.design.LocalTypeScale
import sh.zeron.android.design.ZeronTheme
import sh.zeron.android.design.uiStyle

/**
 * The "Devices rows" page of the debug design gallery (android-ui-parity
 * #102): the ticket's step-8 fixture — the "Devices" header plus four
 * rows drawn non-lazily (this gallery page is not a list — [DevicesRow]
 * is the drawing unit, positions from the row count, no clicks):
 *
 *  1. "MacBook Pro", "Connected", a `success` dot (the side-by-side
 *     partner of `2-settings.png`'s "Choose Wallpaper…" row: same 68 dp
 *     two-line row, icon at x 32…56, title at x 72, then the dot);
 *  2. "Studio", "Offline", a `tertiary` dot.
 *
 * The fixture is built from [DevicesRowSpec]s directly — no `DeviceView`
 * (no core, no FFI), so the page renders in any host. Registered in
 * ticket 22's `design/Gallery.kt` (debug source set) as
 * `GallerySection("Devices rows", screenshotHeightDp = 900)
 * { DevicesRowsGallerySection() }` — the default `Scroll` layout, like
 * ticket 100's "Settings · Sessions": the section needs no in-window
 * host (no sheet host, no menu host).
 * The column is capped at the reference phone's 402 dp width on wider
 * bodies, so the cards sit at the reference size.
 *
 * The Roborazzi goldens of `DevicesRowsScreenshotTest` host this section
 * on the ticket's `w402dp-h900dp` phone; nothing animates, so the
 * goldens are byte-stable without freezing the clock.
 */
@Composable
fun DevicesRowsGallerySection() {
    Column(Modifier.widthIn(max = 402.dp)) {
        DevicesRowsCaption("fixture \u00b7 one row per device")
        GroupedHeader(DevicesCopy.Header)
        devicesGalleryFixture.forEachIndexed { index, spec ->
            DevicesRow(spec, GroupedPosition.of(index, devicesGalleryFixture.size))
        }
    }
}

/**
 * The step-8 fixture, `DevicesRowSpec`s directly (no `DeviceView`): Zeron
 * reports presence only, so a row reads Connected or Offline.
 */
private val devicesGalleryFixture = listOf(
    DevicesRowSpec.Engine("e1", "MacBook Pro", DevicesCopy.Connected, connected = true),
    DevicesRowSpec.Engine("e2", "Studio", DevicesCopy.Offline, connected = false),
)

/** The block caption: Geist 12 fixed, secondary, at the header's inset. */
@Composable
private fun DevicesRowsCaption(text: String) {
    val typeScale = LocalTypeScale.current
    val palette = ZeronTheme.palette
    BasicText(
        text,
        style = uiStyle(Face.Sans, typeScale.fixed(12f), palette.secondary),
        modifier = Modifier.padding(start = 32.dp, top = 16.dp),
    )
}

@Preview(name = "light")
@Composable
private fun DevicesRowsGalleryLightPreview() {
    ZeronTheme(Appearance.Light) {
        Box(Modifier.background(ZeronTheme.palette.background)) {
            DevicesRowsGallerySection()
        }
    }
}

@Preview(name = "dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun DevicesRowsGalleryDarkPreview() {
    ZeronTheme(Appearance.Dark) {
        Box(Modifier.background(ZeronTheme.palette.background)) {
            DevicesRowsGallerySection()
        }
    }
}
