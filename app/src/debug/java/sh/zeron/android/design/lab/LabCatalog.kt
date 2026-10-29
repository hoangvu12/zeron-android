package sh.zeron.android.design.lab

/**
 * The component lab's data (ticket #12, input for decision D7 / #1): the ten
 * components, the three ways each is built, and the code-level check of
 * every variant against the accessibility contract. The screen renders this
 * under each stage; the PR description and the #1 comment are written from
 * the same rows, so they cannot drift apart.
 *
 * "Code-level" means read from source (Roboco's, Compose Unstyled 2.10.1's,
 * Material 3 1.4.0's), not measured with TalkBack or a keyboard on a device.
 * Rows marked "verify on device" are the ones the owner should try.
 */
enum class LabVariant(val label: String, val short: String) {
    Custom("Roboco custom", "Custom"),
    Headless("Our look on headless primitives", "Headless"),
    Material("Stock Material 3 (behaviour reference)", "M3"),
}

/** The D7 accessibility contract, plus the window/blur column the owner asked for. */
enum class Contract(val label: String) {
    Role("Role"),
    Label("Label"),
    State("State"),
    Focus("Focus"),
    Keyboard("Keyboard"),
    Back("Predictive back"),
    Ime("IME"),
    Window("Window / blur"),
}

enum class Mark(val glyph: String) {
    Pass("✅"),
    Partial("⚠️"),
    Fail("❌"),
    NotApplicable("—"),
}

class Finding(val item: Contract, val mark: Mark, val note: String)

enum class LabComponent(val title: String, val basis: Map<LabVariant, String>) {
    Alert(
        "Alert",
        mapOf(
            LabVariant.Custom to "design/Alert.kt + AlertCard.kt (AlertHost, in-window glass card, FIFO queue)",
            LabVariant.Headless to "Compose Unstyled UnstyledDialog + DialogPanel, ZeronAlertCard inside; our PredictiveBackHandler",
            LabVariant.Material to "AlertDialog (+ OutlinedTextField)",
        ),
    ),
    ActionSheet(
        "Action sheet",
        mapOf(
            LabVariant.Custom to "design/ActionSheet.kt (ActionSheetHost, two glass cards)",
            LabVariant.Headless to "Compose Unstyled UnstyledModalBottomSheet, ActionSheetPanel inside; our PredictiveBackHandler",
            LabVariant.Material to "ModalBottomSheet + ListItem rows",
        ),
    ),
    Menu(
        "Menu",
        mapOf(
            LabVariant.Custom to "design/glass/GlassMenu.kt (Modifier.glassMenu over GlassMenuHost)",
            LabVariant.Headless to "Compose Unstyled UnstyledDropdownMenu + DropdownMenuPanel, wrapped in GlassSurface",
            LabVariant.Material to "DropdownMenu + DropdownMenuItem",
        ),
    ),
    BottomSheet(
        "Bottom sheet",
        mapOf(
            LabVariant.Custom to "design/sheet/GlassSheet.kt (medium/large detents, grabber, SheetHeader)",
            LabVariant.Headless to "Compose Unstyled UnstyledModalBottomSheet (half/full detents) + DragIndication",
            LabVariant.Material to "ModalBottomSheet (partial/expanded)",
        ),
    ),
    Switch(
        "Switch",
        mapOf(
            LabVariant.Custom to "design/ZeronSwitch.kt (63×28 capsule, lift, drag)",
            LabVariant.Headless to "Compose Unstyled UnstyledSwitch + Track/Thumb in our capsule geometry",
            LabVariant.Material to "Switch in a toggleable row",
        ),
    ),
    Toast(
        "Toast / snackbar",
        mapOf(
            LabVariant.Custom to "design/Toast.kt (ToastHost capsule, announceForAccessibility)",
            LabVariant.Headless to "Foundation only: our glass capsule + liveRegion + recommended timeout",
            LabVariant.Material to "SnackbarHost + Snackbar with action",
        ),
    ),
    Segmented(
        "Segmented control",
        mapOf(
            LabVariant.Custom to "No app component: the Roboco idiom (the gallery's capsule segments)",
            LabVariant.Headless to "Compose Unstyled UnstyledRadioGroup + RadioButton, our sliding thumb",
            LabVariant.Material to "SingleChoiceSegmentedButtonRow + SegmentedButton",
        ),
    ),
    ProviderTabs(
        "Provider tab switcher",
        mapOf(
            LabVariant.Custom to "Replica of the private ProviderSwitcher in composer/ModelPicker.kt + HorizontalPager",
            LabVariant.Headless to "Compose Unstyled UnstyledTabGroup / TabList / Tab, our capsule thumb following the pager",
            LabVariant.Material to "SecondaryScrollableTabRow + Tab + HorizontalPager",
        ),
    ),
    PullToRefresh(
        "Pull to refresh",
        mapOf(
            LabVariant.Custom to "design/PullToRefresh.kt through LargeTitleScaffold",
            LabVariant.Headless to "Material's behaviour-only Modifier.pullToRefresh (no headless one exists) + our spinner and semantics",
            LabVariant.Material to "PullToRefreshBox",
        ),
    ),
    Search(
        "Search field",
        mapOf(
            LabVariant.Custom to "shell/tablet/SidebarSearchField.kt over a SidebarState",
            LabVariant.Headless to "Compose Unstyled UnstyledTextField (BasicTextField + TextFieldState) in our capsule",
            LabVariant.Material to "SearchBar + SearchBarDefaults.InputField",
        ),
    ),
    ;

    /** The eight contract rows for [variant], in [Contract] order. */
    fun findings(variant: LabVariant): List<Finding> = LabFindings.rows.getValue(this).getValue(variant)
}

private fun f(item: Contract, mark: Mark, note: String) = Finding(item, mark, note)

private const val IN_WINDOW_HOST =
    "Under ModalHost it renders in-window via Portal, so the glass blurs; without a host it opens a " +
        "ComponentDialog window and the glass falls back to the flat fill"

/**
 * The findings, read from source. Custom rows cite `app/src/main` (paths
 * relative to `sh/zeron/android/`); headless rows cite Compose Unstyled
 * 2.10.1; M3 rows describe Material 3 1.4.0 (the BOM 2026.09.00 pin).
 */
internal object LabFindings {

    val rows: Map<LabComponent, Map<LabVariant, List<Finding>>> = mapOf(
        LabComponent.Alert to mapOf(
            LabVariant.Custom to listOf(
                f(Contract.Role, Mark.Partial, "Pane + traversal group, no dialog role (AlertCard.kt:122); buttons Role.Button"),
                f(Contract.Label, Mark.Pass, "paneTitle = title; the field reads its placeholder"),
                f(Contract.State, Mark.Fail, "Progress bar exposes only a test key, nothing to TalkBack (AlertCard.kt:290)"),
                f(Contract.Focus, Mark.Partial, "Content behind hidden from TalkBack; focus moves only when there is a field; not restored on close"),
                f(Contract.Keyboard, Mark.Fail, "No key handling; IME Done triggers the primary button"),
                f(Contract.Back, Mark.Partial, "Plain BackHandler, no progress (Alert.kt:393); dismiss a11y action"),
                f(Contract.Ime, Mark.Pass, "safeDrawing padding re-centres the card above the IME"),
                f(Contract.Window, Mark.Pass, "In-window; the card blurs the host's haze source"),
            ),
            LabVariant.Headless to listOf(
                f(Contract.Role, Mark.Pass, "Modal root sets dialog(); DialogPanel sets paneTitle; card buttons Role.Button"),
                f(Contract.Label, Mark.Pass, "Same card: paneTitle = title"),
                f(Contract.State, Mark.Fail, "Same card, so progress is still not exposed (fix in the card, not the primitive)"),
                f(Contract.Focus, Mark.Partial, "DialogPanel requests focus on open; we hide content behind (portal has no window modality); no restore"),
                f(Contract.Keyboard, Mark.Partial, "Escape dismisses while focus is inside; buttons Tab-reachable"),
                f(
                    Contract.Back,
                    Mark.Partial,
                    "Library dismisses on Key.Back only, which the app's enableOnBackInvokedCallback stops arriving " +
                        "(verify on device); our PredictiveBackHandler adds back with a scale-down",
                ),
                f(Contract.Ime, Mark.Partial, "In-window: our safeDrawing padding; separate window sets ADJUST_NOTHING, so the caller must pad"),
                f(Contract.Window, Mark.Pass, IN_WINDOW_HOST),
            ),
            LabVariant.Material to listOf(
                f(Contract.Role, Mark.Pass, "Dialog window: TalkBack treats it as a modal window"),
                f(Contract.Label, Mark.Pass, "Title and text slots announced"),
                f(Contract.State, Mark.NotApplicable, "No built-in progress"),
                f(Contract.Focus, Mark.Pass, "Window focus moves in; app behind unreachable; window focus returns on close"),
                f(Contract.Keyboard, Mark.Pass, "Buttons focusable; Back/Escape dismiss"),
                f(Contract.Back, Mark.Partial, "Dismisses on back, no predictive progress animation"),
                f(Contract.Ime, Mark.Pass, "Dialog window resizes for the IME"),
                f(Contract.Window, Mark.Fail, "Separate window: no Haze blur of the app behind"),
            ),
        ),
        LabComponent.ActionSheet to mapOf(
            LabVariant.Custom to listOf(
                f(Contract.Role, Mark.Partial, "Pane + traversal group; paneTitle only when there is a title (ActionSheet.kt:334)"),
                f(Contract.Label, Mark.Pass, "Row text + onClickLabel"),
                f(Contract.State, Mark.Pass, "Checked rows selected = true"),
                f(Contract.Focus, Mark.Partial, "Content behind hidden; focus to overlay root; not restored"),
                f(Contract.Keyboard, Mark.Pass, "Escape cancels (ActionSheet.kt:340); rows Tab-reachable; no arrows"),
                f(Contract.Back, Mark.Partial, "Plain BackHandler (ActionSheet.kt:328); no dismiss a11y action"),
                f(Contract.Ime, Mark.Partial, "Avoids nav bar and cutout, not the IME (no field today)"),
                f(Contract.Window, Mark.Pass, "In-window glass"),
            ),
            LabVariant.Headless to listOf(
                f(Contract.Role, Mark.Pass, "Modal root dialog(); we set paneTitle on the sheet"),
                f(Contract.Label, Mark.Pass, "Same ActionSheetPanel rows"),
                f(Contract.State, Mark.Pass, "Same rows: checked = selected"),
                f(Contract.Focus, Mark.Partial, "Library moves no focus into a sheet; we hide content behind"),
                f(Contract.Keyboard, Mark.Partial, "Rows Tab-reachable; Android EscapeHandler is BackHandler, so Escape is ours to add"),
                f(Contract.Back, Mark.Pass, "Library: plain BackHandler; ours: PredictiveBackHandler slides the sheet with progress"),
                f(Contract.Ime, Mark.Pass, "offsetForIme = true lifts the sheet over the IME"),
                f(Contract.Window, Mark.Pass, IN_WINDOW_HOST),
            ),
            LabVariant.Material to listOf(
                f(Contract.Role, Mark.Pass, "Dialog window + sheet paneTitle"),
                f(Contract.Label, Mark.Pass, "ListItem headline text"),
                f(Contract.State, Mark.Partial, "No checked state unless we add it"),
                f(Contract.Focus, Mark.Pass, "Window modality"),
                f(Contract.Keyboard, Mark.Pass, "Rows focusable; Escape/Back dismiss"),
                f(Contract.Back, Mark.Pass, "Predictive back with progress animation"),
                f(Contract.Ime, Mark.Pass, "contentWindowInsets handle the IME"),
                f(Contract.Window, Mark.Fail, "Separate window: no Haze blur"),
            ),
        ),
        LabComponent.Menu to mapOf(
            LabVariant.Custom to listOf(
                f(Contract.Role, Mark.Partial, "Pane; rows Role.Button not menu items; the glassMenu trigger has no role"),
                f(Contract.Label, Mark.Pass, "paneTitle (falls back to \"Menu\"); \"Back to …\"; outside reads \"Dismiss menu\""),
                f(Contract.State, Mark.Pass, "selected, disabled, \"Submenu\"; loading row is a polite live region"),
                f(Contract.Focus, Mark.Partial, "First row focused only with TalkBack on; behind not hidden; no restore"),
                f(Contract.Keyboard, Mark.Fail, "Trigger is detectTapGestures (GlassMenu.kt:575): a keyboard can't open it; no arrows/Escape"),
                f(Contract.Back, Mark.Partial, "Plain BackHandler, pops a submenu level first"),
                f(Contract.Ime, Mark.Pass, "Placed above max(nav, ime); not a Popup, so the IME stays up"),
                f(Contract.Window, Mark.Pass, "In-window glass"),
            ),
            LabVariant.Headless to listOf(
                f(Contract.Role, Mark.Partial, "Modal root dialog(); items are clickable with no role (we add Role.Button)"),
                f(Contract.Label, Mark.Pass, "Item text merges"),
                f(Contract.State, Mark.Partial, "No checked state built in; we set selected"),
                f(Contract.Focus, Mark.Pass, "Panel takes focus on open; Tab/Shift+Tab leave the menu and return to the anchor"),
                f(Contract.Keyboard, Mark.Pass, "↓/↑/Enter/Space open from the anchor; arrows, Home/End move between items"),
                f(Contract.Back, Mark.Partial, "EscapeHandler = plain BackHandler on Android; no progress"),
                f(Contract.Ime, Mark.Partial, "Anchored placement ignores the IME (verify on device)"),
                f(Contract.Window, Mark.Pass, "$IN_WINDOW_HOST. Items must be direct panel children, so glass wraps the panel"),
            ),
            LabVariant.Material to listOf(
                f(Contract.Role, Mark.Partial, "Popup window; items clickable, menu-item role not set (verify with TalkBack)"),
                f(Contract.Label, Mark.Pass, "Item text"),
                f(Contract.State, Mark.Partial, "No checked state built in"),
                f(Contract.Focus, Mark.Pass, "Focusable popup takes focus"),
                f(Contract.Keyboard, Mark.Pass, "Arrow keys move between items; Escape/Back dismiss"),
                f(Contract.Back, Mark.Partial, "Dismisses, no predictive animation"),
                f(Contract.Ime, Mark.Partial, "Positioned in its own popup window; can sit over the IME"),
                f(Contract.Window, Mark.Fail, "Popup window: no Haze blur"),
            ),
        ),
        LabComponent.BottomSheet to mapOf(
            LabVariant.Custom to listOf(
                f(Contract.Role, Mark.Partial, "Pane + traversal group + dismiss action; title is a heading"),
                f(Contract.Label, Mark.Pass, "paneTitle (\"Sheet\" fallback); grabber, Close and Done labelled"),
                f(Contract.State, Mark.Pass, "Grabber stateDescription \"Half screen\" / \"Full screen\""),
                f(Contract.Focus, Mark.Fail, "No focus on open; content behind still reachable by TalkBack; no restore"),
                f(Contract.Keyboard, Mark.Fail, "No Escape or key handling"),
                f(Contract.Back, Mark.Pass, "PredictiveBackHandler: drags 25% with progress, springs back on cancel"),
                f(Contract.Ime, Mark.Pass, "imePadding; the IME grows Medium to Large"),
                f(Contract.Window, Mark.Pass, "In-window glass"),
            ),
            LabVariant.Headless to listOf(
                f(Contract.Role, Mark.Pass, "Modal root dialog(); we set paneTitle"),
                f(Contract.Label, Mark.Partial, "DragIndication has no label of its own (we add \"Sheet grabber\")"),
                f(Contract.State, Mark.Pass, "DragIndication exposes expand / collapse / dismiss actions per detent"),
                f(Contract.Focus, Mark.Partial, "No focus moved on open; we hide content behind"),
                f(Contract.Keyboard, Mark.Partial, "Escape not mapped on Android (EscapeHandler = BackHandler)"),
                f(Contract.Back, Mark.Pass, "Library: plain BackHandler; ours: PredictiveBackHandler with progress"),
                f(Contract.Ime, Mark.Pass, "offsetForIme lifts the sheet"),
                f(Contract.Window, Mark.Pass, IN_WINDOW_HOST),
            ),
            LabVariant.Material to listOf(
                f(Contract.Role, Mark.Pass, "Dialog window + sheet paneTitle"),
                f(Contract.Label, Mark.Pass, "Drag handle labelled"),
                f(Contract.State, Mark.Pass, "Drag handle expand / collapse / dismiss actions"),
                f(Contract.Focus, Mark.Pass, "Window modality"),
                f(Contract.Keyboard, Mark.Pass, "Escape/Back dismiss"),
                f(Contract.Back, Mark.Pass, "Predictive back with progress animation"),
                f(Contract.Ime, Mark.Pass, "Handles IME insets"),
                f(Contract.Window, Mark.Fail, "Separate window: no Haze blur"),
            ),
        ),
        LabComponent.Switch to mapOf(
            LabVariant.Custom to listOf(
                f(Contract.Role, Mark.Pass, "Role.Switch + toggleableState + onClick action"),
                f(Contract.Label, Mark.Partial, "No label of its own; relies on the row merging it"),
                f(Contract.State, Mark.Fail, "enabled = false sets no disabled() semantics"),
                f(Contract.Focus, Mark.Fail, "Not focusable: raw pointerInput"),
                f(Contract.Keyboard, Mark.Fail, "No key handling; unreachable from a hardware keyboard"),
                f(Contract.Back, Mark.NotApplicable, "—"),
                f(Contract.Ime, Mark.NotApplicable, "—"),
                f(Contract.Window, Mark.Pass, "Inline; iOS 63×28 capsule, lift and thumb drag"),
            ),
            LabVariant.Headless to listOf(
                f(Contract.Role, Mark.Pass, "toggleable(role = Switch)"),
                f(Contract.Label, Mark.Pass, "accessibilityLabel parameter (or the row merges it)"),
                f(Contract.State, Mark.Pass, "On/off and disabled from toggleable"),
                f(Contract.Focus, Mark.Pass, "Focusable through toggleable"),
                f(Contract.Keyboard, Mark.Pass, "Enter/Space toggle"),
                f(Contract.Back, Mark.NotApplicable, "—"),
                f(Contract.Ime, Mark.NotApplicable, "—"),
                f(Contract.Window, Mark.Partial, "Inline; no thumb drag or press lift: we'd port those from ZeronSwitch"),
            ),
            LabVariant.Material to listOf(
                f(Contract.Role, Mark.Pass, "Role.Switch"),
                f(Contract.Label, Mark.Pass, "Row is toggleable and merges the label (the recommended pattern)"),
                f(Contract.State, Mark.Pass, "On/off and disabled"),
                f(Contract.Focus, Mark.Pass, "Focusable"),
                f(Contract.Keyboard, Mark.Pass, "Enter/Space toggle"),
                f(Contract.Back, Mark.NotApplicable, "—"),
                f(Contract.Ime, Mark.NotApplicable, "—"),
                f(Contract.Window, Mark.Partial, "Inline; fixed 52×32 track, round thumb: not the iOS capsule"),
            ),
        ),
        LabComponent.Toast to mapOf(
            LabVariant.Custom to listOf(
                f(Contract.Role, Mark.Partial, "Capsule has no role; action Role.Button + onClickLabel"),
                f(Contract.Label, Mark.Pass, "Text announced once via announceForAccessibility (deprecated in API 36)"),
                f(Contract.State, Mark.Pass, "Hold uses the recommended a11y timeout"),
                f(Contract.Focus, Mark.Pass, "Doesn't steal focus"),
                f(Contract.Keyboard, Mark.Partial, "Action Tab-reachable; no shortcut"),
                f(Contract.Back, Mark.NotApplicable, "—"),
                f(Contract.Ime, Mark.Pass, "Sits above max(systemBars, ime)"),
                f(Contract.Window, Mark.Pass, "In-window glass"),
            ),
            LabVariant.Headless to listOf(
                f(Contract.Role, Mark.Partial, "No toast role on Android; action Role.Button"),
                f(Contract.Label, Mark.Pass, "liveRegion = Polite announces the text (the non-deprecated path)"),
                f(Contract.State, Mark.Pass, "calculateRecommendedTimeoutMillis for the hold"),
                f(Contract.Focus, Mark.Pass, "Doesn't steal focus"),
                f(Contract.Keyboard, Mark.Partial, "Action Tab-reachable"),
                f(Contract.Back, Mark.NotApplicable, "—"),
                f(Contract.Ime, Mark.Pass, "imePadding keeps it above the IME"),
                f(Contract.Window, Mark.Pass, "In-window glass (no primitive needed)"),
            ),
            LabVariant.Material to listOf(
                f(Contract.Role, Mark.Partial, "No role on the container; action is a button"),
                f(Contract.Label, Mark.Pass, "SnackbarHost live region announces"),
                f(Contract.State, Mark.Pass, "Recommended a11y timeout"),
                f(Contract.Focus, Mark.Pass, "Doesn't steal focus"),
                f(Contract.Keyboard, Mark.Partial, "Action Tab-reachable"),
                f(Contract.Back, Mark.NotApplicable, "—"),
                f(Contract.Ime, Mark.Partial, "Placement is the caller's"),
                f(Contract.Window, Mark.Pass, "In-window: a custom glass snackbar is possible"),
            ),
        ),
        LabComponent.Segmented to mapOf(
            LabVariant.Custom to listOf(
                f(Contract.Role, Mark.Pass, "selectable(role = RadioButton) in a selectableGroup"),
                f(Contract.Label, Mark.Pass, "Segment text"),
                f(Contract.State, Mark.Pass, "selected"),
                f(Contract.Focus, Mark.Pass, "Segments focusable"),
                f(Contract.Keyboard, Mark.Partial, "Tab + Enter; no arrow keys"),
                f(Contract.Back, Mark.NotApplicable, "—"),
                f(Contract.Ime, Mark.NotApplicable, "—"),
                f(Contract.Window, Mark.Partial, "Inline; no sliding thumb (UISegmentedControl slides). No app component exists"),
            ),
            LabVariant.Headless to listOf(
                f(Contract.Role, Mark.Pass, "Role.RadioButton in a selectableGroup"),
                f(Contract.Label, Mark.Pass, "Group accessibilityLabel + segment text"),
                f(Contract.State, Mark.Pass, "selected"),
                f(Contract.Focus, Mark.Pass, "Segments focusable"),
                f(Contract.Keyboard, Mark.Pass, "Arrow keys move focus within the group; Enter/Space select"),
                f(Contract.Back, Mark.NotApplicable, "—"),
                f(Contract.Ime, Mark.NotApplicable, "—"),
                f(Contract.Window, Mark.Pass, "Inline; the sliding thumb is ours"),
            ),
            LabVariant.Material to listOf(
                f(Contract.Role, Mark.Pass, "Role.RadioButton in a selectableGroup"),
                f(Contract.Label, Mark.Pass, "Segment label"),
                f(Contract.State, Mark.Pass, "selected"),
                f(Contract.Focus, Mark.Pass, "Focusable"),
                f(Contract.Keyboard, Mark.Partial, "Tab + Enter; arrows through default focus search"),
                f(Contract.Back, Mark.NotApplicable, "—"),
                f(Contract.Ime, Mark.NotApplicable, "—"),
                f(Contract.Window, Mark.Partial, "Inline; outlined M3 look, no sliding thumb"),
            ),
        ),
        LabComponent.ProviderTabs to mapOf(
            LabVariant.Custom to listOf(
                f(Contract.Role, Mark.Fail, "Plain clickable: no Role.Tab, no selectableGroup (ModelPicker.kt:373)"),
                f(Contract.Label, Mark.Partial, "Name merges; brand mark decorative"),
                f(Contract.State, Mark.Pass, "selected; no \"n of m\""),
                f(Contract.Focus, Mark.Partial, "Clickable segments focusable"),
                f(Contract.Keyboard, Mark.Partial, "Tab only; no arrows"),
                f(Contract.Back, Mark.NotApplicable, "—"),
                f(Contract.Ime, Mark.NotApplicable, "—"),
                f(Contract.Window, Mark.Partial, "Inline; the thumb snaps instead of following the pager (audit 03 row 71)"),
            ),
            LabVariant.Headless to listOf(
                f(Contract.Role, Mark.Pass, "Role.Tab (we add selectableGroup for \"n of m\")"),
                f(Contract.Label, Mark.Pass, "Tab text"),
                f(Contract.State, Mark.Pass, "selected"),
                f(Contract.Focus, Mark.Pass, "focusGroup; Tab enters the selected tab, then the panel"),
                f(Contract.Keyboard, Mark.Pass, "←/→/Home/End move between tabs (activate on focus)"),
                f(Contract.Back, Mark.NotApplicable, "—"),
                f(Contract.Ime, Mark.NotApplicable, "—"),
                f(Contract.Window, Mark.Pass, "Inline; our capsule thumb follows the pager offset"),
            ),
            LabVariant.Material to listOf(
                f(Contract.Role, Mark.Pass, "Role.Tab in a selectableGroup"),
                f(Contract.Label, Mark.Pass, "Tab text"),
                f(Contract.State, Mark.Pass, "selected"),
                f(Contract.Focus, Mark.Pass, "Focusable"),
                f(Contract.Keyboard, Mark.Partial, "Tab/arrows via focus search; no Home/End"),
                f(Contract.Back, Mark.NotApplicable, "—"),
                f(Contract.Ime, Mark.NotApplicable, "—"),
                f(Contract.Window, Mark.Partial, "Inline; underline indicator (a custom indicator is possible)"),
            ),
        ),
        LabComponent.PullToRefresh to mapOf(
            LabVariant.Custom to listOf(
                f(Contract.Role, Mark.Fail, "Indicator clears its semantics (PullToRefresh.kt:511)"),
                f(Contract.Label, Mark.Fail, "No refresh action or label"),
                f(Contract.State, Mark.Fail, "No \"Refreshing\" state or announcement"),
                f(Contract.Focus, Mark.NotApplicable, "—"),
                f(Contract.Keyboard, Mark.Fail, "Gesture only (NestedScrollConnection)"),
                f(Contract.Back, Mark.NotApplicable, "—"),
                f(Contract.Ime, Mark.NotApplicable, "—"),
                f(Contract.Window, Mark.Pass, "In-window; iOS rubber band, 60 dp hold"),
            ),
            LabVariant.Headless to listOf(
                f(Contract.Role, Mark.Partial, "No headless pull-to-refresh in Foundation or Unstyled; uses material3's behaviour-only modifier"),
                f(Contract.Label, Mark.Pass, "We add a \"Refresh\" custom accessibility action on the list"),
                f(Contract.State, Mark.Pass, "We announce \"Refreshing\" through a polite live region"),
                f(Contract.Focus, Mark.NotApplicable, "—"),
                f(Contract.Keyboard, Mark.Partial, "The custom action is the non-gesture path; no key shortcut"),
                f(Contract.Back, Mark.NotApplicable, "—"),
                f(Contract.Ime, Mark.NotApplicable, "—"),
                f(Contract.Window, Mark.Partial, "In-window; needs the material3 artifact in main (breaks the no-Material rule) or our port"),
            ),
            LabVariant.Material to listOf(
                f(Contract.Role, Mark.Fail, "Indicator has no semantics (verify with TalkBack)"),
                f(Contract.Label, Mark.Fail, "No refresh action"),
                f(Contract.State, Mark.Fail, "No refreshing announcement"),
                f(Contract.Focus, Mark.NotApplicable, "—"),
                f(Contract.Keyboard, Mark.Fail, "Gesture only"),
                f(Contract.Back, Mark.NotApplicable, "—"),
                f(Contract.Ime, Mark.NotApplicable, "—"),
                f(Contract.Window, Mark.Partial, "In-window; Android stretch + M3 disc, not iOS"),
            ),
        ),
        LabComponent.Search to mapOf(
            LabVariant.Custom to listOf(
                f(Contract.Role, Mark.Pass, "Text field; clear button Role.Button \"Clear text\""),
                f(Contract.Label, Mark.Pass, "contentDescription \"Search\""),
                f(Contract.State, Mark.Partial, "No result-count announcement"),
                f(Contract.Focus, Mark.Pass, "Programmatic focus + IME on request"),
                f(Contract.Keyboard, Mark.Partial, "IME Search hides the IME; no Escape"),
                f(Contract.Back, Mark.Partial, "Back hides the IME; the caret drops with it"),
                f(Contract.Ime, Mark.Pass, "ImeAction.Search, autocorrect off"),
                f(Contract.Window, Mark.Pass, "In-window glass"),
            ),
            LabVariant.Headless to listOf(
                f(Contract.Role, Mark.Pass, "Editable text (BasicTextField); clear button Role.Button"),
                f(Contract.Label, Mark.Pass, "accessibilityLabel \"Search\""),
                f(Contract.State, Mark.Pass, "We announce the result count through a polite live region"),
                f(Contract.Focus, Mark.Pass, "Plain focus; clear refocuses the field"),
                f(Contract.Keyboard, Mark.Pass, "IME Search; Escape clears, then blurs (ours)"),
                f(Contract.Back, Mark.Partial, "Back hides the IME (system); no in-field back handling"),
                f(Contract.Ime, Mark.Pass, "ImeAction.Search, autocorrect off"),
                f(Contract.Window, Mark.Pass, "In-window glass"),
            ),
            LabVariant.Material to listOf(
                f(Contract.Role, Mark.Pass, "Text field; expanded bar is a search pane"),
                f(Contract.Label, Mark.Pass, "Placeholder read as the label"),
                f(Contract.State, Mark.Partial, "Expanded/collapsed announced; no result count"),
                f(Contract.Focus, Mark.Pass, "Expanding focuses the field"),
                f(Contract.Keyboard, Mark.Pass, "IME Search; Escape collapses"),
                f(Contract.Back, Mark.Pass, "Predictive back collapses the expanded bar"),
                f(Contract.Ime, Mark.Pass, "Insets handled"),
                f(Contract.Window, Mark.Partial, "In-window; the expanded view is a full-screen M3 surface"),
            ),
        ),
    )
}
