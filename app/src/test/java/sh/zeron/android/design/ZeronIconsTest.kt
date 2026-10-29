package sh.zeron.android.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.zeron.android.R

/**
 * The registry tests (android-ui-parity #06), plain JVM — no Robolectric:
 * `forSfName` is pure lookup over `bySfName` plus the `"<n>.circle"`
 * pattern, and `R.drawable` fields are compile-time ints. The resource
 * *resolution* (every `res` really exists and is a `zi_*` drawable) is a
 * Robolectric test in [IconSizingTest].
 */
class ZeronIconsTest {

    /** Every SF Symbol string on the kept screens and emitted by the core. */
    private val sfNames = listOf(
        "plus",
        "ellipsis",
        "arrow.up",
        "arrow.down",
        "arrow.up.right",
        "arrow.up.arrow.down",
        "arrow.turn.down.right",
        "arrow.turn.left.up",
        "arrow.up.circle.fill",
        "xmark",
        "xmark.circle.fill",
        "chevron.down",
        "chevron.right",
        "chevron.backward",
        "stop.fill",
        "stop.circle",
        "text.line.last.and.arrowtriangle.forward",
        "pin",
        "pin.fill",
        "pin.slash",
        "pin.slash.fill",
        "archivebox",
        "archivebox.fill",
        "tray",
        "tray.and.arrow.up",
        "tray.and.arrow.up.fill",
        "folder",
        "folder.fill",
        "folder.badge.plus",
        "folder.badge.gearshape",
        "pencil",
        "trash",
        "doc",
        "doc.text",
        "doc.on.doc",
        "square.on.square",
        "doc.on.clipboard",
        "checkmark",
        "checkmark.circle",
        "checkmark.circle.fill",
        "checkmark.square.fill",
        "square",
        "desktopcomputer",
        "gauge.with.dots.needle.67percent",
        "clock",
        "photo",
        "photo.on.rectangle",
        "camera",
        "character.cursor.ibeam",
        "link",
        "safari",
        "wand.and.stars",
        "sun.max",
        "moon",
        "circle.lefthalf.filled",
        "square.stack.3d.up",
        "square.and.arrow.up",
        "gearshape",
        "rectangle.compress.vertical",
        "line.3.horizontal",
        "magnifyingglass",
        "bell",
        "questionmark.bubble",
        "exclamationmark.triangle",
        "exclamationmark.triangle.fill",
        "bubble.left",
        "arrow.left.arrow.right",
        "exclamationmark.circle",
        "info.circle",
        "arrow.triangle.branch",
        "command",
    )

    @Test
    fun forSfNameResolvesEverySfSymbolOnTheKeptScreens() {
        for (name in sfNames) {
            val icon = ZeronIcons.forSfName(name)
            assertNotNull("no drawable for SF Symbol \"$name\"", icon)
            assertTrue("bySfName must contain \"$name\"", ZeronIcons.bySfName.containsKey(name))
            assertTrue(
                "SF rows resolve to a drawable, never a Number",
                ZeronIcons.bySfName[name] is ZeronIcon.Vector,
            )
        }
    }

    @Test
    fun forSfNameResolvesEveryNameTheCoreEmits() {
        // mob:layout/markdown.rs:573 (task boxes), rows.rs:414-423 (chip
        // rows), rows.rs:585-590 (slash command / skill chip, image chips).
        for (name in listOf(
            "checkmark.square.fill",
            "square",
            "questionmark.bubble",
            "exclamationmark.triangle",
            // #30: the error card, the sender caption without a harness,
            // the provider-switch seam (rows.rs:922, 989, 1070).
            "exclamationmark.triangle.fill",
            "bubble.left",
            "arrow.left.arrow.right",
            "arrow.triangle.branch",
            "command",
            "wand.and.stars",
            "photo",
        )) {
            assertNotNull("no drawable for core SF name \"$name\"", ZeronIcons.forSfName(name))
        }
    }

    @Test
    fun theAliasesShareTheirGlyph() {
        assertSame(ZeronIcons.PinSlash, ZeronIcons.forSfName("pin.slash"))
        assertSame(ZeronIcons.PinSlash, ZeronIcons.forSfName("pin.slash.fill"))
        assertSame(ZeronIcons.Doc, ZeronIcons.forSfName("doc"))
        assertSame(ZeronIcons.DocText, ZeronIcons.forSfName("doc.text"))
        assertSame(ZeronIcons.DocOnDoc, ZeronIcons.forSfName("doc.on.doc"))
        assertSame(ZeronIcons.SquareOnSquare, ZeronIcons.forSfName("square.on.square"))
    }

    @Test
    fun numberCircleNamesResolveToNumberIcons() {
        assertEquals(ZeronIcon.Number(0), ZeronIcons.forSfName("0.circle"))
        assertEquals(ZeronIcon.Number(1), ZeronIcons.forSfName("1.circle"))
        assertEquals(ZeronIcon.Number(3), ZeronIcons.forSfName("3.circle"))
        assertEquals(ZeronIcon.Number(50), ZeronIcons.forSfName("50.circle"))
    }

    @Test
    fun droppedAndUnknownAndOutOfRangeNamesReturnNullWithoutThrowing() {
        for (name in listOf(
            "51.circle", // past SF's 0.circle…50.circle
            "100.circle", // three digits: not the N.circle pattern
            "3.circle.", // trailing dot
            "3.circle.fill",
            "square.circle", // not numeric
            "-1.circle",
            "waveform", // dropped: voice
            "mic.fill",
            "person.crop.circle", // dropped: account
            "rectangle.portrait.and.arrow.right", // dropped: sign out
            "sparkle", // dropped: sign-in mark
            "tab-chat", // Zeron extras have no SF name
            "WifiOff",
            "",
        )) {
            assertNull("forSfName(\"$name\") must be null", ZeronIcons.forSfName(name))
            // Unknown names repeat (every row): still null, never a throw.
            assertNull(ZeronIcons.forSfName(name))
        }
    }

    @Test
    fun entriesAreTheSingleSourceOfTheSfNameMap() {
        val allSfNames = ZeronIcons.entries.flatMap { it.sfNames }
        assertEquals("no SF name may be mapped twice", allSfNames.size, allSfNames.toSet().size)
        assertEquals("bySfName covers exactly the entries' sfNames", allSfNames.toSet(), ZeronIcons.bySfName.keys)
        for (entry in ZeronIcons.entries) {
            for (name in entry.sfNames) {
                assertSame("bySfName[\"$name\"] must be ${entry.name}", entry.icon, ZeronIcons.bySfName[name])
            }
        }
        // 68 SF constants + 1 alias (pin.slash.fill) + 7 Zeron extras.
        // android-next#04 added Refresh (`arrow.clockwise`, the About
        // "Check for Updates" row; the approved design allows one new glyph).
        // #30 added the core's three new names.
        assertEquals(72, ZeronIcons.bySfName.size)
        assertEquals(78, ZeronIcons.entries.size)
        assertEquals(
            "constant names are unique",
            ZeronIcons.entries.size,
            ZeronIcons.entries.map { it.name }.toSet().size,
        )
    }

    @Test
    fun opticalScalesMatchTheMappingTable() {
        val expected = mapOf(
            ZeronIcons.Ellipsis to 1.15f,
            ZeronIcons.Xmark to 1.35f,
            ZeronIcons.ChevronDown to 1.1f,
            ZeronIcons.ChevronRight to 1.1f,
            ZeronIcons.ChevronBackward to 1.35f,
            ZeronIcons.StopFill to 0.85f,
            ZeronIcons.Checkmark to 1.05f,
        )
        for (entry in ZeronIcons.entries) {
            val icon = entry.icon as ZeronIcon.Vector
            val scale = expected[icon] ?: 1f
            assertEquals("opticalScale of ${entry.name}", scale, icon.opticalScale)
        }
        assertEquals("only the table's glyphs carry a correction", 7, expected.size)
    }

    @Test
    fun autoMirrorIsExactlyTheDirectionalGlyphs() {
        val mirrored = ZeronIcons.entries
            .filter { (it.icon as ZeronIcon.Vector).autoMirror }
            .map { it.name }
        assertEquals(
            listOf("ArrowTurnDownRight", "ArrowTurnLeftUp", "ChevronRight", "ChevronBackward", "QueueForNextTurn"),
            mirrored,
        )
    }

    @Test
    fun drawableIdsMatchTheMappingTable() {
        val expected: List<Pair<ZeronIcon.Vector, Int>> = listOf(
            ZeronIcons.Plus to R.drawable.zi_plus,
            ZeronIcons.Ellipsis to R.drawable.zi_more_horizontal,
            ZeronIcons.ArrowUp to R.drawable.zi_arrow_up,
            ZeronIcons.ArrowDown to R.drawable.zi_arrow_down,
            ZeronIcons.ArrowUpRight to R.drawable.zi_arrow_up_right,
            ZeronIcons.ArrowUpArrowDown to R.drawable.zi_sort_vertical,
            ZeronIcons.ArrowTurnDownRight to R.drawable.zi_arrow_turn_down_right,
            ZeronIcons.ArrowTurnLeftUp to R.drawable.zi_arrow_turn_left_up,
            ZeronIcons.ArrowUpCircleFill to R.drawable.zi_arrow_up_circle_fill,
            ZeronIcons.Xmark to R.drawable.zi_close,
            ZeronIcons.XmarkCircleFill to R.drawable.zi_xmark_circle_fill,
            ZeronIcons.ChevronDown to R.drawable.zi_alt_arrow_down,
            ZeronIcons.ChevronRight to R.drawable.zi_alt_arrow_right,
            ZeronIcons.ChevronBackward to R.drawable.zi_alt_arrow_left,
            ZeronIcons.StopFill to R.drawable.zi_stop,
            ZeronIcons.StopCircle to R.drawable.zi_stop_circle,
            ZeronIcons.QueueForNextTurn to R.drawable.zi_queue_send,
            ZeronIcons.Pin to R.drawable.zi_pin,
            ZeronIcons.PinFill to R.drawable.zi_pin_fill,
            ZeronIcons.PinSlash to R.drawable.zi_pin_slash,
            ZeronIcons.Archivebox to R.drawable.zi_archive_minimalistic,
            ZeronIcons.ArchiveboxFill to R.drawable.zi_archivebox_fill,
            ZeronIcons.Tray to R.drawable.zi_tray,
            ZeronIcons.TrayAndArrowUp to R.drawable.zi_tray_arrow_up,
            ZeronIcons.TrayAndArrowUpFill to R.drawable.zi_tray_arrow_up_fill,
            ZeronIcons.Folder to R.drawable.zi_folder,
            ZeronIcons.FolderFill to R.drawable.zi_folder_fill,
            ZeronIcons.FolderBadgePlus to R.drawable.zi_folder_badge_plus,
            ZeronIcons.FolderBadgeGearshape to R.drawable.zi_folder_badge_gear,
            ZeronIcons.Pencil to R.drawable.zi_pen,
            ZeronIcons.Trash to R.drawable.zi_trash_bin_minimalistic,
            ZeronIcons.Doc to R.drawable.zi_document,
            ZeronIcons.DocText to R.drawable.zi_document,
            ZeronIcons.DocOnDoc to R.drawable.zi_copy,
            ZeronIcons.SquareOnSquare to R.drawable.zi_copy,
            ZeronIcons.DocOnClipboard to R.drawable.zi_clipboard,
            ZeronIcons.Checkmark to R.drawable.zi_check,
            ZeronIcons.CheckmarkCircle to R.drawable.zi_check_circle,
            ZeronIcons.CheckmarkCircleFill to R.drawable.zi_check_circle_fill,
            ZeronIcons.CheckmarkSquareFill to R.drawable.zi_check_square_fill,
            ZeronIcons.Square to R.drawable.zi_square,
            ZeronIcons.Desktopcomputer to R.drawable.zi_monitor,
            ZeronIcons.Gauge to R.drawable.zi_speedometer,
            ZeronIcons.Clock to R.drawable.zi_clock_circle,
            ZeronIcons.Photo to R.drawable.zi_gallery,
            ZeronIcons.PhotoOnRectangle to R.drawable.zi_gallery_stack,
            ZeronIcons.Camera to R.drawable.zi_camera,
            ZeronIcons.CharacterCursorIbeam to R.drawable.zi_text_cursor,
            ZeronIcons.Link to R.drawable.zi_link,
            ZeronIcons.Safari to R.drawable.zi_global,
            ZeronIcons.WandAndStars to R.drawable.zi_magic_stick_3,
            ZeronIcons.SunMax to R.drawable.zi_sun,
            ZeronIcons.Moon to R.drawable.zi_moon,
            ZeronIcons.CircleLeftHalfFilled to R.drawable.zi_circle_half,
            ZeronIcons.SquareStack3dUp to R.drawable.zi_layers,
            ZeronIcons.SquareAndArrowUp to R.drawable.zi_share,
            ZeronIcons.Gearshape to R.drawable.zi_settings,
            ZeronIcons.RectangleCompressVertical to R.drawable.zi_fold_vertical,
            ZeronIcons.Line3Horizontal to R.drawable.zi_line_3_horizontal,
            ZeronIcons.Magnifyingglass to R.drawable.zi_magnifer,
            ZeronIcons.Bell to R.drawable.zi_bell,
            ZeronIcons.QuestionmarkBubble to R.drawable.zi_chat_round_line,
            ZeronIcons.ExclamationmarkTriangle to R.drawable.zi_danger_triangle,
            ZeronIcons.ExclamationmarkTriangleFill to R.drawable.zi_danger_triangle_fill, // #30
            ZeronIcons.BubbleLeft to R.drawable.zi_bubble_left, // #30
            ZeronIcons.ArrowLeftArrowRight to R.drawable.zi_arrow_left_arrow_right, // #30
            ZeronIcons.ExclamationmarkCircle to R.drawable.zi_exclamation_circle,
            ZeronIcons.InfoCircle to R.drawable.zi_info_circle,
            ZeronIcons.ArrowTriangleBranch to R.drawable.zi_git_branch,
            ZeronIcons.Command to R.drawable.zi_command,
            ZeronIcons.Refresh to R.drawable.zi_refresh, // android-next#04
            ZeronIcons.TabChat to R.drawable.zi_tab_chat,
            ZeronIcons.TabSettings to R.drawable.zi_tab_settings,
            ZeronIcons.ZeronBow to R.drawable.zi_zeron_bow, // android-next #03: replaced ZeronGlyph
            ZeronIcons.WifiOff to R.drawable.zi_wifi_off,
            ZeronIcons.Home to R.drawable.zi_home,
            ZeronIcons.HardDrive to R.drawable.zi_hard_drive,
            ZeronIcons.Laptop to R.drawable.zi_laptop,
        )
        assertEquals("every registry constant is covered", ZeronIcons.entries.size, expected.size)
        assertEquals(
            "the mapping table's drawable set",
            expected.map { it.first }.toSet(),
            ZeronIcons.entries.map { it.icon as ZeronIcon.Vector }.toSet(),
        )
        for ((icon, res) in expected) {
            assertEquals(res, icon.res)
        }
    }

    @Test
    fun symbolWeightsMatchTheMeasuredTable() {
        assertEquals(0.85f, SymbolWeight.Light.strokeScale)
        assertEquals(1.00f, SymbolWeight.Regular.strokeScale)
        assertEquals(1.12f, SymbolWeight.Medium.strokeScale)
        assertEquals(1.27f, SymbolWeight.Semibold.strokeScale)
        assertEquals(1.42f, SymbolWeight.Bold.strokeScale)
    }

    @Test
    fun numberCircleRejectsOutOfRangeIndicesInDebug() {
        assertThrows(IllegalArgumentException::class.java) { clampNumberCircle(-1) }
        assertThrows(IllegalArgumentException::class.java) { clampNumberCircle(51) }
        for (n in 0..NUMBER_CIRCLE_MAX) {
            assertEquals(n, clampNumberCircle(n))
        }
    }

    @Test
    fun numberCircleDigitFractionsMatchTheSpec() {
        assertEquals(0.50f, numberCircleDigitFraction("7"))
        assertEquals(0.50f, numberCircleDigitFraction("0"))
        assertEquals(0.42f, numberCircleDigitFraction("12"))
        assertEquals(0.42f, numberCircleDigitFraction("50"))
    }
}
