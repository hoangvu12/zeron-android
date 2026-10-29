package sh.zeron.android.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.dialog
import androidx.compose.ui.semantics.semantics
import com.composeunstyled.Modal
import com.composeunstyled.ModalHost
import com.composeunstyled.ModalState

/**
 * The in-window modal layer (#62, decision D7): every overlay — the alert,
 * the action sheet, the glass and context menus, the glass sheet — is
 * presented through Compose Unstyled's `Modal`, the headless primitive its
 * `UnstyledDialog`, `UnstyledModalBottomSheet` and `UnstyledDropdownMenu`
 * are built on. Under a `ModalHost` a `Modal` renders **in-window**,
 * through a Portal into the host's layer, so glass keeps sampling the app
 * behind it (Haze samples its own window only). Without a host the library
 * opens a separate `ComponentDialog` window and the glass falls back to the
 * flat fill — the #12 lab's "separate window" stage — so nothing here ever
 * lets that happen: hosts wrap themselves in [EnsureModalHost], and
 * [InWindowModal] draws inline when no host is mounted.
 *
 * A `Modal` portals into the nearest host, so `RootHosts` mounts three
 * layers to keep ticket 35's z-order: the root one (alerts, context
 * menus: above everything), one inside `ToastHost` (action sheets, glass
 * menus: below the toasts) and one around `SheetHost` (glass sheets: below
 * the image viewer, the handoff overlays, menus and toasts).
 */
private val LocalInModalHost = staticCompositionLocalOf { false }

/** True under a [ZeronModalHost]: a `Modal` composed here renders in-window. */
val hasModalHost: Boolean
    @Composable get() = LocalInModalHost.current

/**
 * Compose Unstyled's `ModalHost` filling its parent: [content] first, then
 * the portal layer the `Modal`s beneath it render into, above the content.
 */
@Composable
fun ZeronModalHost(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    ModalHost(modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalInModalHost provides true, content = content)
    }
}

/**
 * [content] under a [ZeronModalHost]: the nearest one when mounted (the app
 * root), else a local one — the gallery and the tests mount the overlay
 * hosts on their own, and a `Modal` must never fall back to a window.
 */
@Composable
fun EnsureModalHost(content: @Composable () -> Unit) {
    if (hasModalHost) content() else ZeronModalHost(content = content)
}

/**
 * One overlay presentation through Compose Unstyled's `Modal`, for as long
 * as this is composed: the caller owns the enter/exit animations and
 * leaves composition when its exit ends (the hosts' existing lifecycles).
 * The `Modal` root carries `dialog()` semantics and provides the modal
 * state; the content keeps the call site's composition locals (palette,
 * haze state, the hosts' presenters) through the Portal.
 *
 * A Portal composes its content a frame or two after the call (the entry
 * is added in a `SideEffect`, and `Modal` waits for the window attach), so
 * overlays that hide something of their own when they open do it from
 * inside the content (the context menu's lifted row).
 *
 * No [ZeronModalHost] above (a `GlassSheet` in a gallery specimen or a
 * test): [content] draws inline, in its parent, with the same semantics.
 */
@Composable
fun InWindowModal(content: @Composable () -> Unit) {
    if (hasModalHost) {
        val state = remember { ModalState(initiallyVisible = true) }
        Modal(state) { content() }
    } else {
        Box(Modifier.fillMaxSize().semantics { dialog() }) { content() }
    }
}
