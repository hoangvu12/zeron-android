package sh.zeron.android.design

import android.util.Log
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Immutable
import sh.zeron.android.BuildConfig
import sh.zeron.android.R
import java.util.concurrent.ConcurrentHashMap

/**
 * The SF Symbol weight → stroke scale table (android-ui-parity #06).
 *
 * iOS call sites pass `UIImage.SymbolConfiguration(pointSize:weight:)`;
 * Android cannot ship SF Symbols, so every weight is carried as a stroke
 * multiplier over the Zeron/Solar line glyphs instead:
 *
 * - `Semibold` 1.27 and `Regular` 1.0 are **measured** — the accessory
 *   `plus` at 15 semibold has a ≈ 1.8 pt stroke (`plus.svg`: stroke 1.25 on
 *   a 16 grid → 1.41 dp at Regular in an 18 dp box; 1.8 / 1.41 = 1.27) and
 *   the Settings row symbols have ≈ 1.4 pt against Solar's 1.5 dp;
 * - `Light` 0.85, `Medium` 1.12 and `Bold` 1.42 are interpolated (roughly
 *   linear, 0.12–0.15 per SF weight). Tune them only against a real iOS
 *   capture.
 */
enum class SymbolWeight(val strokeScale: Float) {
    Light(0.85f),
    Regular(1.00f),
    Medium(1.12f),
    Semibold(1.27f),
    Bold(1.42f),
}

/**
 * One glyph of the icon registry: either a `zi_*` VectorDrawable from the
 * #13 pipeline (`zeron-android-assets`), or SF `N.circle`, which
 * [NumberCircle] draws in code.
 */
@Immutable
sealed interface ZeronIcon {

    /**
     * A `zi_<snake>.xml` VectorDrawable, black ink tinted at use.
     *
     * @param res the drawable resource
     * @param opticalScale per-glyph correction so a `p`-pt call site draws
     *   the same ink as SF Symbols at `p` pt (Solar glyphs fill about 20/24
     *   of their viewBox). Values in the ticket's mapping table; call sites
     *   must never fudge their point size instead.
     * @param autoMirror draw mirrored in RTL (SF `chevron.backward` and
     *   friends). [IconInBox] is the only mirror — callers must not add
     *   their own `graphicsLayer { scaleX = -1f }` or the glyph mirrors back
     *   to LTR.
     */
    @Immutable
    data class Vector(
        @DrawableRes val res: Int,
        val opticalScale: Float = 1f,
        val autoMirror: Boolean = false,
    ) : ZeronIcon

    /** SF `N.circle` (`0.circle` … `50.circle`), drawn by [NumberCircle]. */
    @Immutable
    data class Number(val n: Int) : ZeronIcon
}

/**
 * One row of the registry: the [ZeronIcons] constant name, the SF Symbol
 * strings that resolve to it (empty for the Zeron-only glyphs), and the
 * icon itself. [ZeronIcons.entries] is the single source the
 * [bySfName] map, the gallery grid and the tests all read.
 */
@Immutable
class RegistryEntry(
    /** The `ZeronIcons` constant name, e.g. `"Plus"`. */
    val name: String,
    /** SF Symbol names that resolve to [icon]; empty for Zeron extras. */
    val sfNames: List<String>,
    val icon: ZeronIcon,
) {
    /** The gallery cell caption: the first SF name, or the constant name. */
    val label: String get() = sfNames.firstOrNull() ?: name
}

/**
 * The SF Symbol → `zi_*` drawable registry (android-ui-parity #06): one
 * constant per row of the ticket's mapping table, the aliases the kept iOS
 * screens and the Rust core emit, and the Zeron/Android extras that have
 * no SF name.
 *
 * Consumers pass the iOS point size and weight verbatim to [Icon] — the
 * per-glyph [ZeronIcon.Vector.opticalScale] absorbs the Solar-vs-SF
 * proportion differences, so call sites stay 1:1 with Swift.
 */
object ZeronIcons {

    // -----------------------------------------------------------------
    // SF Symbol rows, in the mapping table's order.
    // -----------------------------------------------------------------

    /** SF `plus` — accessory (15 semibold), composer attach, iPad compose. */
    val Plus = ZeronIcon.Vector(R.drawable.zi_plus)

    /** SF `ellipsis` — nav-bar ⋯, list ⋯, queue-row ⋯ (14 semibold). */
    val Ellipsis = ZeronIcon.Vector(R.drawable.zi_more_horizontal, opticalScale = 1.15f)

    /** SF `arrow.up` — send (15 bold). */
    val ArrowUp = ZeronIcon.Vector(R.drawable.zi_arrow_up)

    /** SF `arrow.down` — jump-to-latest (15 medium). */
    val ArrowDown = ZeronIcon.Vector(R.drawable.zi_arrow_down)

    /** SF `arrow.up.right` — header menu "Open". */
    val ArrowUpRight = ZeronIcon.Vector(R.drawable.zi_arrow_up_right)

    /** SF `arrow.up.arrow.down` — header menu "Reorder…". */
    val ArrowUpArrowDown = ZeronIcon.Vector(R.drawable.zi_sort_vertical)

    /** SF `arrow.turn.down.right` — "Steer now". */
    val ArrowTurnDownRight =
        ZeronIcon.Vector(R.drawable.zi_arrow_turn_down_right, autoMirror = true)

    /** SF `arrow.turn.left.up` — folder-browser "Parent Folder". */
    val ArrowTurnLeftUp =
        ZeronIcon.Vector(R.drawable.zi_arrow_turn_left_up, autoMirror = true)

    /** SF `arrow.up.circle.fill` — queue send-now (20 regular). */
    val ArrowUpCircleFill = ZeronIcon.Vector(R.drawable.zi_arrow_up_circle_fill)

    /** SF `xmark` — viewer close, staged-image remove (8 bold), failed tool. */
    val Xmark = ZeronIcon.Vector(R.drawable.zi_close, opticalScale = 1.35f)

    /** SF `xmark.circle.fill` — search-field clear (system). */
    val XmarkCircleFill = ZeronIcon.Vector(R.drawable.zi_xmark_circle_fill)

    /** SF `chevron.down` — section header (11 semibold). */
    val ChevronDown = ZeronIcon.Vector(R.drawable.zi_alt_arrow_down, opticalScale = 1.1f)

    /** SF `chevron.right` — disclosure indicator (13 semibold), submenus. */
    val ChevronRight =
        ZeronIcon.Vector(R.drawable.zi_alt_arrow_right, opticalScale = 1.1f, autoMirror = true)

    /** SF `chevron.backward` — the back button (17 semibold). */
    val ChevronBackward =
        ZeronIcon.Vector(R.drawable.zi_alt_arrow_left, opticalScale = 1.35f, autoMirror = true)

    /** SF `stop.fill` — composer stop (11 bold). */
    val StopFill = ZeronIcon.Vector(R.drawable.zi_stop, opticalScale = 0.85f)

    /** SF `stop.circle` — "Stop and send". */
    val StopCircle = ZeronIcon.Vector(R.drawable.zi_stop_circle)

    /** SF `text.line.last.and.arrowtriangle.forward` — "Queue for next turn". */
    val QueueForNextTurn = ZeronIcon.Vector(R.drawable.zi_queue_send, autoMirror = true)

    /** SF `pin` — Pin menu, Pinned folder row. */
    val Pin = ZeronIcon.Vector(R.drawable.zi_pin)

    /** SF `pin.fill` — leading swipe action. */
    val PinFill = ZeronIcon.Vector(R.drawable.zi_pin_fill)

    /**
     * SF `pin.slash` / `pin.slash.fill` — Unpin menu and trailing swipe.
     * The filled pin with a slash is adapted to the line glyph: a knock-out
     * gap needs a second ink a single-tint drawable cannot draw cleanly.
     */
    val PinSlash = ZeronIcon.Vector(R.drawable.zi_pin_slash)

    /** SF `archivebox` — Archive menus, options "Archived". */
    val Archivebox = ZeronIcon.Vector(R.drawable.zi_archive_minimalistic)

    /** SF `archivebox.fill` — Archive swipe action. */
    val ArchiveboxFill = ZeronIcon.Vector(R.drawable.zi_archivebox_fill)

    /** SF `tray` — "No Project…" / "No Section" placeholder rows. */
    val Tray = ZeronIcon.Vector(R.drawable.zi_tray)

    /** SF `tray.and.arrow.up` — Unarchive menu (Solar `inbox-out`, per iOS). */
    val TrayAndArrowUp = ZeronIcon.Vector(R.drawable.zi_tray_arrow_up)

    /** SF `tray.and.arrow.up.fill` — archived-row swipe. */
    val TrayAndArrowUpFill = ZeronIcon.Vector(R.drawable.zi_tray_arrow_up_fill)

    /** SF `folder` — mention rows, section folders, "Move to Section". */
    val Folder = ZeronIcon.Vector(R.drawable.zi_folder)

    /** SF `folder.fill` — "Move" swipe action. */
    val FolderFill = ZeronIcon.Vector(R.drawable.zi_folder_fill)

    /** SF `folder.badge.plus` — "New Project…" / "New Section…". */
    val FolderBadgePlus = ZeronIcon.Vector(R.drawable.zi_folder_badge_plus)

    /** SF `folder.badge.gearshape` — folder-browser engine row. */
    val FolderBadgeGearshape = ZeronIcon.Vector(R.drawable.zi_folder_badge_gear)

    /** SF `pencil` — queue-row and row menus. */
    val Pencil = ZeronIcon.Vector(R.drawable.zi_pen)

    /** SF `square.and.pencil` — the iPhone bottom bar's New session. */
    val SquareAndPencil = ZeronIcon.Vector(R.drawable.zi_pen_new_square)

    /** SF `trash` — row menus, Settings wallpaper. */
    val Trash = ZeronIcon.Vector(R.drawable.zi_trash_bin_minimalistic)

    /** SF `doc` — mention rows, folder-browser file row. */
    val Doc = ZeronIcon.Vector(R.drawable.zi_document)

    /** SF `doc.text` — mention rows. */
    val DocText = ZeronIcon.Vector(R.drawable.zi_document)

    /** SF `doc.on.doc` — "Copy Transcript" / transcript "Copy". */
    val DocOnDoc = ZeronIcon.Vector(R.drawable.zi_copy)

    /** SF `square.on.square` — code-block copy button (13 medium). */
    val SquareOnSquare = ZeronIcon.Vector(R.drawable.zi_copy)

    /** SF `doc.on.clipboard` — attach menu, transcript "Copy Message". */
    val DocOnClipboard = ZeronIcon.Vector(R.drawable.zi_clipboard)

    /** SF `checkmark` — menu `.on` state, `.checkmark()` accessory, tool done. */
    val Checkmark = ZeronIcon.Vector(R.drawable.zi_check, opticalScale = 1.05f)

    /** SF `checkmark.circle` — Settings notifications. */
    val CheckmarkCircle = ZeronIcon.Vector(R.drawable.zi_check_circle)

    /** SF `checkmark.circle.fill` — question options (chosen), core task boxes. */
    val CheckmarkCircleFill = ZeronIcon.Vector(R.drawable.zi_check_circle_fill)

    /** SF `checkmark.square.fill` — question options, core task boxes. */
    val CheckmarkSquareFill = ZeronIcon.Vector(R.drawable.zi_check_square_fill)

    /** SF `square` — the unchecked task-box glyph the core emits. */
    val Square = ZeronIcon.Vector(R.drawable.zi_square)

    /** SF `desktopcomputer` — host chip, engine menus, device rows. */
    val Desktopcomputer = ZeronIcon.Vector(R.drawable.zi_monitor)

    /** SF `gauge.with.dots.needle.67percent` — effort chip (11.5 semibold). */
    val Gauge = ZeronIcon.Vector(R.drawable.zi_speedometer)

    /** SF `clock` — queue row (12 medium). */
    val Clock = ZeronIcon.Vector(R.drawable.zi_clock_circle)

    /** SF `photo` — Settings wallpaper; image-attachment chips (core). */
    val Photo = ZeronIcon.Vector(R.drawable.zi_gallery)

    /** SF `photo.on.rectangle` — attach menu "Photos". */
    val PhotoOnRectangle = ZeronIcon.Vector(R.drawable.zi_gallery_stack)

    /** SF `camera` — attach menu "Camera". */
    val Camera = ZeronIcon.Vector(R.drawable.zi_camera)

    /** SF `character.cursor.ibeam` — transcript "Select Text". */
    val CharacterCursorIbeam = ZeronIcon.Vector(R.drawable.zi_text_cursor)

    /** SF `link` — PR chip menu "Copy Link". */
    val Link = ZeronIcon.Vector(R.drawable.zi_link)

    /** SF `safari` — PR chip menu "Open Pull Request". */
    val Safari = ZeronIcon.Vector(R.drawable.zi_global)

    /** SF `wand.and.stars` — Settings wallpaper; core skill chip. */
    val WandAndStars = ZeronIcon.Vector(R.drawable.zi_magic_stick_3)

    /** SF `sun.max` — Settings appearance Light. */
    val SunMax = ZeronIcon.Vector(R.drawable.zi_sun)

    /** SF `moon` — Settings appearance Dark (fill-only path). */
    val Moon = ZeronIcon.Vector(R.drawable.zi_moon)

    /** SF `circle.lefthalf.filled` — appearance System, context-usage chip. */
    val CircleLeftHalfFilled = ZeronIcon.Vector(R.drawable.zi_circle_half)

    /** SF `square.stack.3d.up` — options/Settings "Group by Project". */
    val SquareStack3dUp = ZeronIcon.Vector(R.drawable.zi_layers)

    /** SF `square.and.arrow.up` — image viewer share (15 medium). */
    val SquareAndArrowUp = ZeronIcon.Vector(R.drawable.zi_share)

    /** SF `gearshape` — iPad toolbar Settings. */
    val Gearshape = ZeronIcon.Vector(R.drawable.zi_settings)

    /** SF `rectangle.compress.vertical` — options "Collapse All". */
    val RectangleCompressVertical = ZeronIcon.Vector(R.drawable.zi_fold_vertical)

    /** SF `line.3.horizontal` — the `.reorder` accessory. */
    val Line3Horizontal = ZeronIcon.Vector(R.drawable.zi_line_3_horizontal)

    /** SF `magnifyingglass` — the system search tab (22 regular, measured). */
    val Magnifyingglass = ZeronIcon.Vector(R.drawable.zi_magnifer)

    /** SF `bell` — Settings notifications. */
    val Bell = ZeronIcon.Vector(R.drawable.zi_bell)

    /** SF `questionmark.bubble` — Settings notifications; core chip rows. */
    val QuestionmarkBubble = ZeronIcon.Vector(R.drawable.zi_chat_round_line)

    /** SF `exclamationmark.triangle` — Settings notifications; core chip rows. */
    val ExclamationmarkTriangle = ZeronIcon.Vector(R.drawable.zi_danger_triangle)

    /** SF `exclamationmark.triangle.fill` — the core's error card (#30). */
    val ExclamationmarkTriangleFill = ZeronIcon.Vector(R.drawable.zi_danger_triangle_fill)

    /** SF `bubble.left` — the core's sender caption for a chat without a harness mark (#30). */
    val BubbleLeft = ZeronIcon.Vector(R.drawable.zi_bubble_left)

    /** SF `arrow.left.arrow.right` — the core's provider-switch seam (#30). */
    val ArrowLeftArrowRight = ZeronIcon.Vector(R.drawable.zi_arrow_left_arrow_right)

    /** SF `exclamationmark.circle` — context-usage chip. */
    val ExclamationmarkCircle = ZeronIcon.Vector(R.drawable.zi_exclamation_circle)

    /** SF `info.circle` — Settings About "Version" (101, android-app#26). */
    val InfoCircle = ZeronIcon.Vector(R.drawable.zi_info_circle)

    /** SF `arrow.triangle.branch` — core slash-command/branch chip. */
    val ArrowTriangleBranch = ZeronIcon.Vector(R.drawable.zi_git_branch)

    /** SF `bubble.left.and.text.bubble.right` — the session ⋯ "New Side Chat". */
    val BubbleLeftAndTextBubbleRight = ZeronIcon.Vector(R.drawable.zi_chat_round_line)

    /** SF `bubble.left.and.bubble.right` — the companion strip's side chats chip (#40). */
    val BubbleLeftAndBubbleRight = ZeronIcon.Vector(R.drawable.zi_bubble_left_and_bubble_right)

    /** SF `apple.terminal` — the session ⋯ "Terminal". */
    val Terminal = ZeronIcon.Vector(R.drawable.zi_terminal)

    /** SF `command` — core slash-command chip. */
    val Command = ZeronIcon.Vector(R.drawable.zi_command)

    /** SF `arrow.clockwise` — the update "Check for Updates" row (android-next#04). */
    val Refresh = ZeronIcon.Vector(R.drawable.zi_refresh)

    /** SF `person.crop.circle` — Settings' Account row (#18). */
    val PersonCropCircle = ZeronIcon.Vector(R.drawable.zi_user_circle)

    /** SF `rectangle.portrait.and.arrow.right` — Settings' Sign Out row (#18). */
    val RectanglePortraitAndArrowRight = ZeronIcon.Vector(R.drawable.zi_logout_2)

    // -----------------------------------------------------------------
    // Zeron / Android extras: no SF name, so [bySfName] omits them.
    // -----------------------------------------------------------------

    /** The chat tab glyph (24 dp asset from #13, `IconInBox(24.dp)`). */
    val TabChat = ZeronIcon.Vector(R.drawable.zi_tab_chat)

    /** The settings tab glyph (24 dp asset from #13, `IconInBox(24.dp)`). */
    val TabSettings = ZeronIcon.Vector(R.drawable.zi_tab_settings)

    /**
     * The Zeron ribbon bow (android-next #03) — the notification small
     * icon and the themed launcher icon's monochrome layer; listed here for
     * the icons gallery.
     */
    val ZeronBow = ZeronIcon.Vector(R.drawable.zi_zeron_bow)

    /** Offline engines (ticket 50). */
    val WifiOff = ZeronIcon.Vector(R.drawable.zi_wifi_off)

    /** Folder-browser locations rail (ticket 112). */
    val Home = ZeronIcon.Vector(R.drawable.zi_home)

    /** Folder-browser locations rail (ticket 112). */
    val HardDrive = ZeronIcon.Vector(R.drawable.zi_hard_drive)

    /** Engine-row icon (ticket 102, per android-app#26). */
    val Laptop = ZeronIcon.Vector(R.drawable.zi_laptop)

    /**
     * Every registry row, in mapping-table order — the single source
     * [bySfName], the design-gallery grid and the registry tests read.
     */
    val entries: List<RegistryEntry> = listOf(
        RegistryEntry("Plus", listOf("plus"), Plus),
        RegistryEntry("Ellipsis", listOf("ellipsis"), Ellipsis),
        RegistryEntry("ArrowUp", listOf("arrow.up"), ArrowUp),
        RegistryEntry("ArrowDown", listOf("arrow.down"), ArrowDown),
        RegistryEntry("ArrowUpRight", listOf("arrow.up.right"), ArrowUpRight),
        RegistryEntry("Refresh", listOf("arrow.clockwise"), Refresh),
        RegistryEntry("ArrowUpArrowDown", listOf("arrow.up.arrow.down"), ArrowUpArrowDown),
        RegistryEntry("ArrowTurnDownRight", listOf("arrow.turn.down.right"), ArrowTurnDownRight),
        RegistryEntry("ArrowTurnLeftUp", listOf("arrow.turn.left.up"), ArrowTurnLeftUp),
        RegistryEntry("ArrowUpCircleFill", listOf("arrow.up.circle.fill"), ArrowUpCircleFill),
        RegistryEntry("Xmark", listOf("xmark"), Xmark),
        RegistryEntry("XmarkCircleFill", listOf("xmark.circle.fill"), XmarkCircleFill),
        RegistryEntry("ChevronDown", listOf("chevron.down"), ChevronDown),
        RegistryEntry("ChevronRight", listOf("chevron.right"), ChevronRight),
        RegistryEntry("ChevronBackward", listOf("chevron.backward"), ChevronBackward),
        RegistryEntry("StopFill", listOf("stop.fill"), StopFill),
        RegistryEntry("StopCircle", listOf("stop.circle"), StopCircle),
        RegistryEntry(
            "QueueForNextTurn",
            listOf("text.line.last.and.arrowtriangle.forward"),
            QueueForNextTurn,
        ),
        RegistryEntry("Pin", listOf("pin"), Pin),
        RegistryEntry("PinFill", listOf("pin.fill"), PinFill),
        RegistryEntry("PinSlash", listOf("pin.slash", "pin.slash.fill"), PinSlash),
        RegistryEntry("Archivebox", listOf("archivebox"), Archivebox),
        RegistryEntry("ArchiveboxFill", listOf("archivebox.fill"), ArchiveboxFill),
        RegistryEntry("Tray", listOf("tray"), Tray),
        RegistryEntry("TrayAndArrowUp", listOf("tray.and.arrow.up"), TrayAndArrowUp),
        RegistryEntry("TrayAndArrowUpFill", listOf("tray.and.arrow.up.fill"), TrayAndArrowUpFill),
        RegistryEntry("Folder", listOf("folder"), Folder),
        RegistryEntry("FolderFill", listOf("folder.fill"), FolderFill),
        RegistryEntry("FolderBadgePlus", listOf("folder.badge.plus"), FolderBadgePlus),
        RegistryEntry(
            "FolderBadgeGearshape",
            listOf("folder.badge.gearshape"),
            FolderBadgeGearshape,
        ),
        RegistryEntry("Pencil", listOf("pencil"), Pencil),
        RegistryEntry("SquareAndPencil", listOf("square.and.pencil"), SquareAndPencil),
        RegistryEntry("Trash", listOf("trash"), Trash),
        RegistryEntry("Doc", listOf("doc"), Doc),
        RegistryEntry("DocText", listOf("doc.text"), DocText),
        RegistryEntry("DocOnDoc", listOf("doc.on.doc"), DocOnDoc),
        RegistryEntry("SquareOnSquare", listOf("square.on.square"), SquareOnSquare),
        RegistryEntry("DocOnClipboard", listOf("doc.on.clipboard"), DocOnClipboard),
        RegistryEntry("Checkmark", listOf("checkmark"), Checkmark),
        RegistryEntry("CheckmarkCircle", listOf("checkmark.circle"), CheckmarkCircle),
        RegistryEntry("CheckmarkCircleFill", listOf("checkmark.circle.fill"), CheckmarkCircleFill),
        RegistryEntry("CheckmarkSquareFill", listOf("checkmark.square.fill"), CheckmarkSquareFill),
        RegistryEntry("Square", listOf("square"), Square),
        RegistryEntry("Desktopcomputer", listOf("desktopcomputer"), Desktopcomputer),
        RegistryEntry(
            "Gauge",
            listOf("gauge.with.dots.needle.67percent"),
            Gauge,
        ),
        RegistryEntry("Clock", listOf("clock"), Clock),
        RegistryEntry("Photo", listOf("photo"), Photo),
        RegistryEntry("PhotoOnRectangle", listOf("photo.on.rectangle"), PhotoOnRectangle),
        RegistryEntry("Camera", listOf("camera"), Camera),
        RegistryEntry("CharacterCursorIbeam", listOf("character.cursor.ibeam"), CharacterCursorIbeam),
        RegistryEntry("Link", listOf("link"), Link),
        RegistryEntry("Safari", listOf("safari"), Safari),
        RegistryEntry("WandAndStars", listOf("wand.and.stars"), WandAndStars),
        RegistryEntry("SunMax", listOf("sun.max"), SunMax),
        RegistryEntry("Moon", listOf("moon"), Moon),
        RegistryEntry("CircleLeftHalfFilled", listOf("circle.lefthalf.filled"), CircleLeftHalfFilled),
        RegistryEntry("SquareStack3dUp", listOf("square.stack.3d.up"), SquareStack3dUp),
        RegistryEntry("SquareAndArrowUp", listOf("square.and.arrow.up"), SquareAndArrowUp),
        RegistryEntry("Gearshape", listOf("gearshape"), Gearshape),
        RegistryEntry(
            "RectangleCompressVertical",
            listOf("rectangle.compress.vertical"),
            RectangleCompressVertical,
        ),
        RegistryEntry("Line3Horizontal", listOf("line.3.horizontal"), Line3Horizontal),
        RegistryEntry("Magnifyingglass", listOf("magnifyingglass"), Magnifyingglass),
        RegistryEntry("Bell", listOf("bell"), Bell),
        RegistryEntry("QuestionmarkBubble", listOf("questionmark.bubble"), QuestionmarkBubble),
        RegistryEntry(
            "ExclamationmarkTriangle",
            listOf("exclamationmark.triangle"),
            ExclamationmarkTriangle,
        ),
        RegistryEntry(
            "ExclamationmarkTriangleFill",
            listOf("exclamationmark.triangle.fill"),
            ExclamationmarkTriangleFill,
        ),
        RegistryEntry("BubbleLeft", listOf("bubble.left"), BubbleLeft),
        RegistryEntry("ArrowLeftArrowRight", listOf("arrow.left.arrow.right"), ArrowLeftArrowRight),
        RegistryEntry("ExclamationmarkCircle", listOf("exclamationmark.circle"), ExclamationmarkCircle),
        RegistryEntry("InfoCircle", listOf("info.circle"), InfoCircle),
        RegistryEntry("ArrowTriangleBranch", listOf("arrow.triangle.branch"), ArrowTriangleBranch),
        RegistryEntry("Command", listOf("command"), Command),
        RegistryEntry(
            "BubbleLeftAndTextBubbleRight",
            listOf("bubble.left.and.text.bubble.right"),
            BubbleLeftAndTextBubbleRight,
        ),
        RegistryEntry("Terminal", listOf("apple.terminal"), Terminal),
        RegistryEntry("BubbleLeftAndBubbleRight", listOf("bubble.left.and.bubble.right"), BubbleLeftAndBubbleRight),
        RegistryEntry("PersonCropCircle", listOf("person.crop.circle"), PersonCropCircle),
        RegistryEntry(
            "RectanglePortraitAndArrowRight",
            listOf("rectangle.portrait.and.arrow.right"),
            RectanglePortraitAndArrowRight,
        ),
        // Zeron extras.
        RegistryEntry("TabChat", emptyList(), TabChat),
        RegistryEntry("TabSettings", emptyList(), TabSettings),
        RegistryEntry("ZeronBow", emptyList(), ZeronBow),
        RegistryEntry("WifiOff", emptyList(), WifiOff),
        RegistryEntry("Home", emptyList(), Home),
        RegistryEntry("HardDrive", emptyList(), HardDrive),
        RegistryEntry("Laptop", emptyList(), Laptop),
    )

    /** SF name → icon, for strings that arrive at runtime (core widgets, row VMs). */
    val bySfName: Map<String, ZeronIcon> =
        entries.flatMap { entry -> entry.sfNames.map { it to entry.icon } }.toMap()

    /** The `"<n>.circle"` family, `0.circle` … `50.circle`. */
    private val NUMBER_CIRCLE = Regex("""^(\d{1,2})\.circle$""")

    /**
     * Resolves an SF Symbol name: [bySfName] first, then the `"<n>.circle"`
     * pattern (0 ≤ n ≤ 50 → [ZeronIcon.Number]).
     *
     * Never throws on an unknown or dropped name — the core may emit new
     * names at any time; returns `null` and logs once per name in debug
     * builds (the core can repeat a name on every row).
     */
    fun forSfName(name: String): ZeronIcon? {
        bySfName[name]?.let { return it }
        NUMBER_CIRCLE.matchEntire(name)?.let { match ->
            val n = match.groupValues[1].toInt()
            if (n in 0..50) return ZeronIcon.Number(n)
        }
        if (BuildConfig.DEBUG) logUnknownSfName(name)
        return null
    }
}

private const val TAG = "ZeronIcons"

private val loggedUnknownNames: MutableSet<String> = ConcurrentHashMap.newKeySet()

private fun logUnknownSfName(name: String) {
    if (!loggedUnknownNames.add(name)) return
    // Plain-JVM unit tests run against the mockable android.jar, where
    // android.util.Log throws "not mocked"; on a device Log.w never throws.
    // forSfName must stay total either way (see the pitfalls).
    runCatching { Log.w(TAG, "no zi_* drawable for SF Symbol \"$name\"") }
}
