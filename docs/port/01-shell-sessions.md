# 01 — App shell, navigation and the Sessions list: port-gap audit

Scope: iOS `Shell/` (MainTabController, SplitRootController + SidebarViewController), `Threads/`
(SessionListController, SessionsViewController, FolderViewController, SearchViewController,
SessionCell / FolderCell / SectionHeaderCell, NewProjectViewController), `App/SceneDelegate.swift`
and the parts of `App/AppModel.swift`, `Design/Toast.swift` and
`Session/SessionViewController.swift` (`confirmDelete`) these screens call, at zeron v0.2.129, against
the Roboco Android app in this repo.

Path conventions used below:

- **iOS** paths are relative to `zeron/apps/ios/Zeron/` (e.g. `Threads/SessionListController.swift:241`).
- **Roboco** paths are relative to `app/src/main/java/sh/roboco/android/`.
- **zeron android ref** paths are relative to `zeron/apps/android/app/src/main/java/sh/zeron/android/`.
- **Core API** names are the Kotlin names UniFFI generates for `uniffi.zeron_core`
  (`zeron/crates/mobile/src/client_ffi/*.rs`; Rust `pub fn` → plain Kotlin call, `pub async fn` →
  `suspend`).

What changed on iOS in this area since roboco's port point (`git -C zeron log edb23db4..HEAD`):
#909 (drag sessions between sections), #942 + #981 (iPhone Duo: split everywhere, carry the open
page across fold/unfold, 280 pt sidebar minimum), #966 (Cloud; root/onboarding routing), #967 + #971
(one-row bottom bar instead of tab bar + "New session" accessory; Settings pushed; Delete session),
#988 (companions inspector column), #1014 (composer rests level with the sidebar toolbar).
Roboco already ported #967/#971 (one-row bar), #967 Delete, #1014 and the pure logic of #909
("wave-10" tickets) — but it still carries the whole pre-#967 tab chrome and every engine/pairing
concept.

Status legend: ✅ same · ⚠️ differs (how) · ❌ missing.

---

## 1. Gap table

### A. Scene, root and app lifecycle (`App/SceneDelegate.swift`, `App/AppModel.swift`)

| # | iOS (file:line) | Behaviour | Roboco status + Kotlin file | zeron core API | zeron android ref | Native-Android option |
|---|---|---|---|---|---|---|
| A1 | `App/SceneDelegate.swift:162-168` | Root is `SignInViewController` when signed out, otherwise `SplitRootController` everywhere (iPhone too; it collapses itself). | ⚠️ Root is chosen by **paired engines** + size: `RootInput.Ready(hasEngines)` → `RootKind.Pairing / Phone / Split` (`shell/RootKind.kt:29-70`, `shell/AppRoot.kt`, `shell/RobocoAppContent.kt:176-186` mounts `PairingRoot`). No sign-in root. | `CoreClient(config, credentials, listener)` (signed in = client exists); `Credentials.WorkOs/Dev/Demo` | `ui/ZeronRoot.kt:79-83` (`AnimatedContent(client != null)`) | Plain Compose `AnimatedContent`/`Crossfade`; no widget needed. |
| A2 | `App/SceneDelegate.swift:169-180` | Root swap: dismiss the old root's presented sheets, then 0.35 s cross-dissolve (none at launch). | ✅ `shell/RootCrossfade.kt` (350 ms easeInOut), `shell/RootPresentations.kt` dismissal — only the trigger differs (A1). | — | `ui/ZeronRoot.kt:81` | — |
| A3 | `App/SceneDelegate.swift:24-35`, `App/AppModel.swift:224-276` | `onSignedIn` → new root + push registration + onboarding (no sheet slide); `onSignOut` / `authExpired` → push unregister, `signOutLocally` (shutdown client, wipe store when user-initiated), back to sign-in. | ❌ No accounts; roboco "forget engine" instead (`pairing/`). | `authExchangeCode`, `authListOrgs`, `authRefresh` (all `suspend`), `CoreClient.shutdown()`, `ClientEvent.AuthRefreshed / AuthExpired`, `unregisterPushTarget()` (`suspend`) | `core/AppModel.kt:326-430`, `ui/SignInScreen.kt` | Custom Tabs (`androidx.browser`) for the WorkOS page + `zeron://callback` intent filter (as the zeron app does). |
| A4 | `App/SceneDelegate.swift:85-104` | First run after sign-in (or `-onboarding <step>`): onboarding presented over the shell; "start a session" ends in `presentNewSession`. | ❌ | `cloudStatus()`, `githubConnectStart()` … (Cloud area) | `ui/OnboardingScreen.kt`, `ui/ZeronRoot.kt:170-174` | Full-screen route. |
| A5 | `App/SceneDelegate.swift:36-49` | `-wallpaper <path\|none>`, `-wallpaper-effect <key>` launch args. | ✅ `shell/LaunchOptions.kt` (debug extras `wallpaper`, `wallpaper-effect`), `MainActivity.kt:103-109`. | — | `MainActivity.kt:40-41` | — |
| A6 | `App/SceneDelegate.swift:50-56` | `-lab`: Transcript Lab as root. | ⚠️ Roboco has a debug gallery (`route gallery`, `MainActivity.kt:149`) instead; no lab. | — | — | — |
| A7 | `App/SceneDelegate.swift:70-76` | `-route chat:<id>` / `new` / `more` / `search` after first layout. | ✅ `shell/LaunchOptions.kt:116` → `shell/DeepLinks.kt` `parseRoute` → `shell/DeepLinkHub.kt` (held until a router attaches). Roboco also accepts `roboco://chat|new|settings|search|pair` VIEW intents (roboco-only; scheme must become `zeron` or be dropped). | — | `ui/ZeronRoot.kt:145-169` | Manifest `intent-filter`. |
| A8 | `App/SceneDelegate.swift:77-80, 106-152` | DEBUG `-route cloud…/providers…/accounts…/billing/welcome` open Cloud screens. | ❌ | Cloud APIs | `ui/ZeronRoot.kt:155-167` | — |
| A9 | `App/SceneDelegate.swift:19-23`, `App/PushNotifications.swift:185-192` | Notification tap → `router.openSession(chatId)`; if the shell isn't up yet, kept and opened on `shellReady`. | ⚠️ Same hold-until-router logic via `shell/DeepLinkHub.kt`, but fed by roboco's **local** run notifications (`notify/Notifier.kt:342` posts `roboco://chat/<id>`); no remote push. | `registerPushTarget(...)` / `unregisterPushTarget()` (`suspend`) | none (zeron android has no FCM either) | `PendingIntent` + `NotificationCompat`; FCM only if remote push is wanted. |
| A10 | `App/SceneDelegate.swift:183-189` | `zeron://voice` (Live Activity) returns to the live call stage. | ❌ Voice dropped. | `CoreClient.startVoice(...)`, `voiceHostCapability()` | `MainActivity.kt:91-96`, `voice/VoiceCallService.kt` | Ongoing-call notification (`CallStyle`) tap intent. |
| A11 | `App/SceneDelegate.swift:191-197`, `App/AppModel.swift:293-312` | Background: if a send is in flight or delivery activity < 120 s, hold a 25 s background task; `onBackground()`. Foreground: `onForeground()` + immediate workspace re-read. | ⚠️ `core/AppLifecycleHooks.kt` calls onForeground/onBackground; the "hold for delivery" is replaced by roboco's RunWatch / "Stay connected" service (`RobocoApplication.kt:680-708`), not tied to send activity. | `onBackground()`, `onForeground()` (sync), `noteDeliveryActivity` is app-side | `core/AppModel.kt:456-482`, `core/DeliveryService.kt` | Short foreground service (`shortService`/`dataSync`) as zeron's `DeliveryService` does. |
| A12 | `App/AppModel.swift:104-122` | Network path: `setNetworkOnline(satisfied)`; `networkPathChanged()` only when the interface shape changes. | ⚠️ `core/AppLifecycleHooks.kt:26-43` has `setNetworkOnline` + `setNetworkChanged`; the "only on a real shape change" gate must be re-checked when rewired. | `setNetworkOnline(online)`, `networkPathChanged()` (sync) | `core/AppModel.kt:504-521` | `ConnectivityManager.NetworkCallback` (already used). |
| A13 | `App/AppModel.swift:197-199` | 30 s timer re-reads `workspace()` so relative times ("4m") and the 45 s staleness gate age without events. | ❌ `core/WorkspaceFeed.kt:108-117` re-reads only on a `WorkspaceChanged` revision change; no clock found. On zeron's core `timeLabel` is computed at derive time, so labels would freeze. | `workspace()` (sync), `SessionRow.timeLabel` | `core/AppModel.kt:245` (`delay(30_000)` loop) | Coroutine ticker in the feed (lifecycle-aware: `repeatOnLifecycle(STARTED)`). |
| A14 | `App/AppModel.swift:363-387` | Core events hop to main; `WorkspaceChanged` bursts coalesce into one rebuild per run-loop turn. | ✅ `core/WorkspaceFeed.kt:74-150` (conflated, revision-gated, off main). | `ClientListener.onEvent(ClientEvent)` | `core/AppModel.kt:429-450` | — |

### B. iPhone shell and the one-row bottom bar (`Shell/MainTabController.swift`)

| # | iOS (file:line) | Behaviour | Roboco status + Kotlin file | zeron core API | zeron android ref | Native-Android option |
|---|---|---|---|---|---|---|
| B1 | `Shell/MainTabController.swift:3-34` | A plain container around **one** Sessions navigation stack — no tab bar at all (a hidden tab bar came back on pop). | ⚠️ `shell/MainTabShell.kt` still runs on `MainTabState` with three `TabId`s (`shell/TabId.kt`) and the whole tab chrome; `oneRowBar = true` (`shell/RobocoAppContent.kt:221`) just never selects Search/Settings. | — | `ui/ZeronRoot.kt:228-241` (single `NavHost`) | One back stack (androidx Navigation 3 / NavHost) — predictive back built in. |
| B2 | `Shell/MainTabController.swift:38-45`, `Threads/SessionListController.swift:609-611` | The bottom bar is shown on the list root only; every pushed page (`hidesBottomBarWhenPushed`) hides it. | ✅ `NavStack.chromeOnRootOnly` (`shell/MainTabShell.kt:246-250`), `shell/ChromeVisibility.kt`. | — | — | — |
| B3 | `Shell/MainTabController.swift:104-113` | Nav bars: large titles, large 30 pt semibold, inline 17 pt semibold, text tint. | ✅ `shell/LargeTitleScaffold.kt`, `shell/LargeTitleMetrics.kt`. | — | — | M3 `LargeTopAppBar` could be themed, but iOS collapse/rubber-band physics differ — keep custom. |
| B4 | `Shell/MainTabController.swift:47-52` | Settings item: `gearshape`, a11y "Settings", id `bottom-settings`. | ✅ `shell/SessionsBottomBar.kt:79-86` (48 dp glass circle). | — | `ui/SessionsScreen.kt:133-140` (avatar → Settings) | `IconButton` in a custom glass container (gains ripple/a11y defaults). |
| B5 | `Shell/MainTabController.swift:89`, `Threads/SessionListController.swift:487-507` | Search = the system toolbar-integrated `UISearchController`: placeholder **"Search"**, filters the list in place, never hides on scroll; on activation iOS 26 expands the field over the bar (other items step aside, a close control appears). | ⚠️ `shell/SessionsBottomBar.kt:87-105` always shows a static `SearchTabField` between the two circles; placeholder is the old Search-tab copy **"Sessions, projects, branches"** (`shell/SearchMorph.kt:95`); no expand/close morph (verify against device). Android-only extra: system Back clears a non-empty query (`shell/MainTabShell.kt:251-258`) — keep. | `search(query, limit = 60u)` (sync → `List<SearchHit>`) | `ui/SessionsScreen.kt:97-150` (M3 `SearchBar`) | M3 `SearchBar`/`DockedSearchBar` with `SearchBarDefaults.InputField` restyled as the glass capsule — expand/collapse, predictive-back collapse and IME handling come for free. |
| B6 | `Shell/MainTabController.swift:54-62, 77-90` | Voice item (only when a host can run voice or a call is live): `waveform`; live → `waveform.circle.fill` in accent, a11y "Voice call" / "Talk to Codex"; tap starts/opens the call, long-press opens the voice menu (none while live). | ❌ Dropped with voice (`shell/SessionsBottomBar.kt:30`). | `voiceHostCapability()`, `defaultVoiceStyles()`, `CoreClient.startVoice(...)` | `voice/VoiceControls.kt` (`VoiceHeaderButton`), `ui/ZeronRoot.kt:523-534` | `IconButton` + `combinedClickable(onLongClick)` + `DropdownMenu`. |
| B7 | `Voice/VoiceStageViewController.swift:406-427` | Voice long-press menu: inline "Codex runs on" (hosts, `desktopcomputer`, checkmark), "Voice" submenu (styles + "Codex default"), "Voice Settings". | ❌ | as B6 | `voice/VoiceControls.kt` | `DropdownMenu` with nested menu. |
| B8 | `Shell/MainTabController.swift:64-71` | New-session item: **`square.and.pencil`**, `.prominent` style tinted text colour, a11y "New session", id `new-session`. | ⚠️ `shell/SessionsBottomBar.kt:107-117` draws a **plus** in a text-filled circle; `design/RobocoIcons.kt` has no square-and-pencil glyph. | — | `ui/ZeronRoot.kt:537-546` (ExtendedFAB) | `FilledIconButton`/`FloatingActionButton` themed (container = text, content = background). |
| B9 | `Shell/MainTabController.swift:88-90` | Fixed spaces split the glass: every control is its own glass shape. | ✅ 8 dp gaps, separate glass (`shell/SessionsBottomBar.kt:35-44`). | — | — | — |
| B10 | `Shell/MainTabController.swift:97-102`, `Design/Toast.swift:35-48` | `bottomBarFrame` feeds toast placement (toasts float 12 pt above the bar). | ✅ `chrome.updateChromeTop` → `LocalBottomChromeTop` (`shell/MainTabShell.kt:226-237, 447-452`), `design/ToastPlacement.kt`. | — | `ui/ZeronRoot.kt:474-482` | — |
| B11 | `Shell/MainTabController.swift:115-127` | New Session: page sheet, `.large()` detent only, grabber visible. | ✅ `shell/PhoneShellAdapter.kt` `presentNewSession` → `SheetStyle.Page(grabber = true)` on the custom `shell/PageSheet.kt` / `shell/SheetHost.kt`. | (new-session area) | `ui/ZeronRoot.kt:334-344` (full-screen route) | M3 `ModalBottomSheet(skipPartiallyExpanded = true)` — but it is a separate window: the draft handoff (hide sheet without its slide, keep it under the chat) and Haze glass behind it are not possible. Keep custom. |
| B12 | `Shell/MainTabController.swift:129-137` | `showSettings`: dismiss anything presented, no-op if Settings is on top, pop to root, push Settings (bar steps aside). | ✅ `shell/PhoneShellAdapter.kt` `selectOnTheFrontPage(Settings)` + `SettingsRoute` (`shell/ShellRoutes.kt:41-44`). | — | `ui/ZeronRoot.kt:234` | — |
| B13 | `Shell/MainTabController.swift:139-142`, `Threads/SessionListController.swift:502-507` | `showSearch`: pop to the list, activate the field, keyboard up next run-loop turn. | ✅ `shell/PhoneShellAdapter.kt` `selectOnTheFrontPage(Search)` → `SearchTabState.activate()` (`shell/SearchTabState.kt:101`). | — | — | `FocusRequester` (already). |
| B14 | `Shell/MainTabController.swift:144-172` | From the new-session sheet: push the chat under the sheet without animation, hide the sheet (alpha 0, undimmed), run `DraftHandoffAnimator`, then dismiss. | ✅ `shell/PhoneShellAdapter.kt` `hideSheetForHandoff`, `newsession/DraftHandoffAnimator.kt`. | — | — | — |
| B15 | `Shell/MainTabController.swift:180-198` | `adoptSession` / `releaseSession` (#981): the **same** session page moves between the split column and the phone stack (scroll, draft kept); the side chat pushed above it is skipped. | ⚠️ `shell/CarryOver.kt:1-80` re-opens the chat on the other side (a new entry; state only survives through caches) — the pre-#981 behaviour. | — | `ui/ZeronRoot.kt:131-144` (re-navigates too) | Hoist the session entry/ViewModel above the shell switch (`movableContentOf`, already used in `shell/RobocoAppContent.kt:104`). |
| B16 | `Shell/MainTabController.swift:200-206` | Open error: dismiss, end editing, pop to front page, toast "Couldn't open session. Try again." | ✅ `shell/AppRouter.kt:6` `OPEN_ERROR_TOAST`, `shell/PhoneAppRouter.kt`. | `openSession(chatId)` (sync, throws) | — | — |
| B17 | `Shell/MainTabController.swift:208-216` | `openSession`: dismiss presented (animated), pop to root, push the session animated. | ✅ `shell/PhoneAppRouter.kt`, `shell/PhoneShellAdapter.kt` `pushSession`. | `openSession(chatId)` | `ui/ZeronRoot.kt:129` | — |
| B18 | `Design/Toast.swift:5-121` | Glass toast: 12 pt above bottom chrome, ≤ window−48 wide, label 15 medium (2 lines), optional filled-capsule action 14 semibold, spring in from +16 (0.4/0.85), auto-dismiss 4 s, fall +12 in 0.25 s, VoiceOver announcement, one at a time. | ✅ `design/Toast.kt` (4000 ms, announce), `design/ToastPlacement.kt`. | — | `ui/ZeronRoot.kt:460-483` (M3 Snackbar) | M3 `SnackbarHost` with a custom glass `snackbar` slot (same window, so glass still works) — adds the accessibility recommended timeout (`AccessibilityManager.getRecommendedTimeoutMillis`) and swipe-dismiss. Optional. |
| B19 | `App/AppModel.swift:535-539` | Archive anywhere → toast "Archived" with "Undo" (→ unarchive). | ✅ `sessions/CoreRowActions.kt:83-84` (roboco shows it only when the write succeeded). | `archiveSession`, `unarchiveSession` (sync) | `core/AppModel.kt:536-540` | — |

### C. Sessions front page (`Threads/SessionListController.swift`, `SessionsViewController`)

| # | iOS (file:line) | Behaviour | Roboco status + Kotlin file | zeron core API | zeron android ref | Native-Android option |
|---|---|---|---|---|---|---|
| C1 | `Threads/SessionListController.swift:475-485` | Title "Sessions", large title always, trailing `ellipsis` bar item opening the options menu. | ✅ `sessions/SessionsScreen.kt:303-311, 392-404` (a11y "More"). | — | `ui/SessionsScreen.kt:119` (leading menu) | `IconButton` + `DropdownMenu`. |
| C2 | `Threads/SessionListController.swift:578-580, 330-339` | ⋯ **"New Section…"** (`folder.badge.plus`) → alert "New Section", field "Name" (words caps), Cancel / Create → `createSection(name)` if the trim is non-empty. | ⚠️ `sessions/SessionsMenus.kt:92-96` → `SessionPrompts.newSection(engineId = null)` (`sessions/SessionPrompts.kt:161-175`) routes by connected engines: toast "Engine not connected" / "Create on" engine sheet / alert. | `createSection(name)` (sync → id) | `ui/SessionsScreen.kt:374, 394` (`TextPromptDialog`) | M3 `AlertDialog` + `OutlinedTextField` (IME, autofocus). Roboco's custom `design/Alert.kt` already handles back + IME. |
| C3 | `Threads/SessionListController.swift:581-586` | ⋯ **"Collapse All"** (`rectangle.compress.vertical`): fold every user section (+ Pinned) and, when grouping, every project group; Recent never; each fold via `toggleSection` (haptic). | ✅ `sessions/SessionsMenus.kt:97-101`, `FrontPageSections.collapsibleIds` (`sessions/FrontPageSections.kt:118-122`). | — | `ui/SessionsScreen.kt:375-381` | — |
| C4 | `Threads/SessionListController.swift:587-593` | ⋯ **"Group by Project"** (`square.stack.3d.up`) with a checkmark, rebuilt on every open (Settings flips it too). | ✅ `sessions/SessionsMenus.kt:106-114`. | — (local pref) | `ui/SessionsScreen.kt:382` | `DropdownMenuItem` with trailing check. |
| C5 | `Threads/SessionListController.swift:594-597` | ⋯ **"Archived"** (`archivebox`) → push the Archived folder page. | ✅ `sessions/SessionsMenus.kt:115-119` → `FolderRoute.archived()`. | `workspace().archived` | `ui/ArchivedScreen.kt` | — |
| C6 | `Threads/SessionListController.swift:509-525` | Sections: Pinned (only if any pins), every user section (empty ones too), then Recent — header "Recent" only when something came before it. | ✅ `sessions/FrontPageSections.kt:44-82`. | `workspace().front.pinned / sections / recent` | `ui/SessionsScreen.kt:316-345` | `LazyColumn`. |
| C7 | `Threads/SessionListController.swift:517-521`, `App/AppModel.swift:442-468` | Group by Project: user sections stay, Recent is replaced by project groups computed **in the app**: id `project:<groupKey>` named `groupName`; project-less rows grouped per device `home:<deviceId>` "Home · <deviceName>"; recency order kept. | ⚠️ `sessions/FrontPageSections.kt:58-69` reads a core-provided `front.groups` (not in zeron's `FrontPage`) and adds a roboco "· <engine name>" collision suffix (`:150-176`). | compute from `front.recent` + `workspace().projects[].groupKey/groupName` + `SessionRow.project/deviceId/deviceName` | `core/AppModel.kt:715` `projectGroups()` | — |
| C8 | `App/AppModel.swift:24-33` | `groupByProject` is a phone-local pref; flipping it notifies every list. | ✅ `core/Prefs.kt` via `sessions/SessionsMenus.kt:209-212`. | — | `core/AppModel.kt:143-149` | DataStore (already). |
| C9 | `Threads/SessionListController.swift:473-481, 527-536` | Wallpaper hero behind the list: `min(0.72·height, 760)`, fixed, fading `1 − travel / (0.6·heroH)` as the list scrolls. | ✅ `sessions/SessionsScreen.kt:313-333`, `sessions/SessionsHero.kt`, `design/WallpaperHero.kt`. | — | `design/WallpaperHero.kt` | — |
| C10 | `Threads/SessionListController.swift:112-121`, `App/AppModel.swift:314-340` | Pull to refresh (`UIRefreshControl`, id `pull-to-refresh`) on every list: `onForeground()` + workspace re-read; spinner ends on the next workspace change (ignoring reconnecting/offline blips), ≥ 0.6 s, ≤ 3 s. | ❌ Not wired on the Sessions page: `LargeTitleScaffold`'s `pullToRefresh` is never passed by `sessions/SessionsScreen.kt` (only the dead `sessions/SearchScreen.kt:231` uses it). `sessions/Refresher.kt` calls roboco's `CoreClient.refresh()`. | No `refresh()`: `onForeground()` (sync) + `workspace()` + wait for `WorkspaceChanged`/`ConnectivityChanged` | `core/AppModel.kt:490` `refresh(min, max)`; `ui/SessionsScreen.kt:245` (`PullToRefreshBox`) | M3 `PullToRefreshBox` with a custom `indicator` (the 21 dp cell spinner) — correct nested scroll + a11y. |
| C11 | `Threads/SessionListController.swift:105-111` | Text-size change → reconfigure every cell, invalidate layout (fixed heights scale). | ✅ `design/TypeScale.kt` (`LocalTypeScale`). | — | — | System font scale. |
| C12 | `Threads/SessionListController.swift:127-172` | Diffable reload keyed by id; changed rows reconfigured in place; differences animate only while on screen. | ✅ `sessions/SessionList.kt:648-670` (`itemAnimation`, keyed items). | — | — | `Modifier.animateItem()`. |
| C13 | (no code) | Empty front page: blank list, no copy. | ⚠️ Roboco addition: "No sessions yet" / "Tap New session to start one." (`sessions/SessionListStatusOverlay.kt:99-117`). | — | `ui/SessionsScreen.kt:695-704` (has empty copy) | — |
| C14 | (no code) | Before the first workspace: nothing (no spinner). | ⚠️ Roboco spinner while `sessionsLoading(engines)` (`sessions/SessionListItems.kt:196-209`) — needs `EngineView.fromCache`. | `WorkspaceSnapshot.synced` could drive an equivalent | — | — |

### D. Section headers (`SectionHeaderCell`, `Threads/SessionCell.swift:232-307`)

| # | iOS (file:line) | Behaviour | Roboco status + Kotlin file | zeron core API | zeron android ref | Native-Android option |
|---|---|---|---|---|---|---|
| D1 | `Threads/SessionCell.swift:243, 275-306` | 40·k tall; title 13.5 semibold `secondary`, count 13.5 medium `tertiary` 7 pt after; live glyph 12 pt 7 pt after the count; `chevron.down` 11 pt semibold `tertiary`, 16×20 at right−36; baseline band `h−26`. | ✅ `sessions/SectionHeaderRow.kt:85-132, 195-196`. | — | `ui/SessionsScreen.kt:420-470` | — |
| D2 | `Threads/SessionCell.swift:282-288` | Chevron rotates −90° when folded, spring 0.3 s / 0.85. | ✅ `sessions/SectionHeaderRow.kt:168-175` (`Motion.SectionChevron`, reduce-motion aware). | — | — | — |
| D3 | `Threads/SessionListController.swift:145` | Live glyph: spinner if any row is Working, else an `input` dot if any Awaiting. | ⚠️ `sessions/SessionListItems.kt:26` same rule **plus** a roboco-only `SectionLive.Offline` wifi-off glyph for offline engines' Home groups (`sessions/SectionHeaderRow.kt:324`, `FrontPageSections.offlineOverrides`). | `SessionRow.indicator` | — | — |
| D4 | `Threads/SessionCell.swift:257, 289-291` | a11y: button trait, label "<title>, <count>", value "Collapsed"/"Expanded", id `section-<id>`. | ✅ `sessions/SectionHeaderRow.kt:230-238` (roboco appends ", Offline"). | — | — | `Role.Button` + `stateDescription` (already). |
| D5 | `Threads/SessionCell.swift:267-273` | Pressed fill `controlFill`, radius 12, insets 2/8. | ✅ `sessions/SectionHeaderRow.kt:136-142, 255`. | — | — | — |
| D6 | `Threads/SessionListController.swift:39, 202-207` | Tap folds/unfolds; **all** folds (Pinned, Recent, user sections, groups) persist phone-locally under `collapsedSections`; selection haptic. | ⚠️ `sessions/FoldState.kt`: Pinned/Recent/groups local under the same key, but **user sections sync to the engine** via `setSectionCollapsed` with per-engine offline overrides (`sectionFoldOverrides`). | zeron also has `setSectionCollapsed(sectionId, collapsed)` + `SectionView.collapsed`, but iOS ignores them → keep it local | `core/AppModel.kt:152-157` (`collapsedGroups`, local) | Haptic: `HapticFeedbackType.SegmentTick` / `CLOCK_TICK` (roboco `design/Feedback.kt`). |
| D7 | `Threads/SessionListController.swift:278-281, 548-563` | Header long-press menus: Recent and project groups none; Pinned → "Open" (`arrow.up.right`), "Reorder…" (`arrow.up.arrow.down`) — both push Pinned; user section → "Open", "Rename Section…" (`pencil`), "Delete Section" (`trash`, destructive, **no confirm**). | ✅ `sessions/SessionsMenus.kt:139-178`, wired through `design/ContextMenu.kt` (lift + "Show menu" a11y action). | `renameSection(sectionId, name)`, `deleteSection(sectionId)` (sync) | `ui/SessionsScreen.kt:316-330` | `DropdownMenu` anchored to the header (loses the iOS lift preview); keep custom `ContextMenu`. |
| D8 | `Threads/SessionListController.swift:565-574` | "Rename Section" alert prefilled with the name; Cancel / Rename; empty trim ignored. | ✅ `sessions/SessionPrompts.kt:177-185`, `sessions/SessionPromptModels.kt:39`. | `renameSection` | `ui/SessionsScreen.kt:400` | `AlertDialog` + `TextField`. |
| D9 | `Threads/SessionListController.swift:80-90` | Plain (non-folding) header: 13 semibold `secondary`, margins 18/20/6/20. | ✅ `sessions/SessionList.kt:636-690` (`PlainSectionHeader`, 41 dp). | — | — | — |

### E. Session row (`Threads/SessionCell.swift:3-195`)

| # | iOS (file:line) | Behaviour | Roboco status + Kotlin file | zeron core API | zeron android ref | Native-Android option |
|---|---|---|---|---|---|---|
| E1 | `Threads/SessionCell.swift:49, 157-194` | Fixed 62·k height; mark 20 at x 20 centred on the title x-height; text x = 54; title y 10 h 22; meta y 35 h 18; 20 pt right margin. | ✅ `sessions/SessionRowGeometry.kt:59-92`, `sessions/SessionRow.kt:128`. | — | `ui/SessionsScreen.kt:522-581` | — |
| E2 | `Threads/SessionCell.swift:109-111` | Title 16.5: semibold when unseen else medium; `text`, or `text·0.88` when seen and idle. | ✅ `sessions/SessionRow.kt:88-91`. | `SessionRow.unseen`, `indicator`, `title` | — | — |
| E3 | `Threads/SessionCell.swift:108` | Harness brand mark (default `claude-code`). | ✅ `design/BrandMarks.kt`. | `SessionRow.harness` | `design/BrandMarks.kt` | — |
| E4 | `Threads/SessionCell.swift:112-120`, `App/AppModel.swift:484-487` | Meta: project tile 14·k (Home "H" tile when project-less, home colour index) + "  " + project name 13.5 `secondary` (fallback device name, then "No project"). | ✅ `sessions/SessionRowMeta.kt`, `sessions/SessionRowVM.kt:64-84`. | `SessionRow.project`, `deviceName`, `projectColorIndex("home")` | `ui/SessionsScreen.kt:593-615` | — |
| E5 | `Threads/SessionCell.swift:121-130` | Branch: 3 spaces, git-branch icon 12·k + branch 12.5 `subline`. | ✅ `sessions/SessionRowMeta.kt:131-132`, `sessions/SessionRowMetaLine.kt`. | `SessionRow.branch` | — | — |
| E6 | `Threads/SessionCell.swift:132-137` | PR badge at the meta line's right (state + number, 11 pt). | ✅ `design/PrBadge.kt`. | `SessionRow.pullRequest` (`PullRequestState.OPEN/MERGED/CLOSED`) | `ui/SessionsScreen.kt:619` | — |
| E7 | `Threads/SessionCell.swift:93-104, 138-151` | Corner precedence: failed send "Failed" (danger) › Working (spinner) › "Input" (dot) › "Failed" (dot) › "Done" (check, unseen completed) › time label; 13 medium; glyph 12 pt 5 pt before the word. | ✅ `sessions/SessionRowCorner.kt`. | `SessionRow.sendState`, `indicator`, `timeLabel` | — | — |
| E8 | `Threads/SessionCell.swift:152-153` | a11y label: title, project, corner word, PR label; id `session-<id>`. | ⚠️ `sessions/SessionRowVM.kt:115-125` also speaks roboco's "N subagents running". | — | — | — |
| E9 | `Threads/SessionCell.swift:79-85` | Background: pressed/selected `controlFill`; current (iPad) `rowActive`; radius 16, insets 1/8. | ✅ `sessions/SessionRow.kt:146-152`, `sessions/SessionRowGeometry.kt:159-165`. | — | — | — |
| E10 | `Threads/SessionCell.swift:42-47` | No running-agents pill on the row. | ⚠️ Roboco-only "● N" pill (`SessionRowVM.runningSubagents`, `sessions/SessionRowGeometry.kt:83-108`, `sessions/SessionRow.kt:219-226`). | none (`SessionRow` has no `runningSubagents`) | — | — |
| E11 | `Threads/SessionCell.swift:71-74` | Light/dark change re-renders the tile. | ✅ Compose recomposition. | — | — | — |

### F. Swipe actions (`Threads/SessionListController.swift:241-328`)

| # | iOS (file:line) | Behaviour | Roboco status + Kotlin file | zeron core API | zeron android ref | Native-Android option |
|---|---|---|---|---|---|---|
| F1 | `Threads/SessionListController.swift:241-250` | Leading: Pin / Unpin (`pin.fill` / `pin.slash.fill`), accent; none on Archived. | ✅ `sessions/SessionRowActions.kt:193-200`, `sessions/SwipeRow.kt`. | `pinSession` / `unpinSession` (sync) | `ui/SessionsScreen.kt:476-516` (`SwipeToDismissBox`) | M3 `SwipeToDismissBox` = one dismiss action per side; iOS reveals **two** buttons + full swipe. Keep the custom `SwipeRow` (foundation drag, already exposes a11y `customActions`). |
| F2 | `Threads/SessionListController.swift:263-268` | Trailing outer: Archive, destructive (row slides out), `archivebox.fill`, `secondary`. | ✅ `sessions/SessionRowActions.kt:201-208` (roboco springs the row back when the write is refused). | `archiveSession` | as F1 | as F1 |
| F3 | `Threads/SessionListController.swift:269-275` | Trailing inner: Move, `folder.fill`, `#5E6AD2` → Move sheet (F6). | ✅ `sessions/SessionRowActions.kt:209-215` (`SwipeTokens.MoveTint`). | — | — | as F1 |
| F4 | `Threads/SessionListController.swift:254-261` | Archived list: trailing Unarchive only, `tray.and.arrow.up.fill`, accent. | ✅ `sessions/SessionRowActions.kt:182-191`. | `unarchiveSession` | `ui/ArchivedScreen.kt` | as F1 |
| F5 | (UIKit default) | Full swipe runs the side's first action. | ✅ `sessions/SwipeRow.kt:197, 232-252` (`FullSwipeFraction`). | — | — | — |
| F6 | `Threads/SessionListController.swift:319-328` | Move action sheet "Move to Section": **every** user section, "No Section", Cancel (popover on iPad). | ⚠️ `sessions/SessionPrompts.kt:187-196` lists only `sectionsFor(row.engineId)` (sections of the chat's engine). | `assignSection(chatId, sectionId?)` (sync) | `ui/SessionsScreen.kt:569-573` | M3 `ModalBottomSheet` list (window ⇒ no Haze blur behind) or keep `design/ActionSheet.kt`. |

### G. Row long-press menu and prompts

| # | iOS (file:line) | Behaviour | Roboco status + Kotlin file | zeron core API | zeron android ref | Native-Android option |
|---|---|---|---|---|---|---|
| G1 | `Threads/SessionListController.swift:292-298` | Menu: Pin/Unpin, "Move to Section ▸", "Rename…", "Archive" (not red), "Delete" (red). | ✅ `sessions/SessionRowActions.kt:260-309` via `design/ContextMenu.kt`. | — | `ui/SessionsScreen.kt:559-575` (no Delete) | `DropdownMenu` anchored at the touch point (`DropdownMenu(offset=)`); loses the lift preview — keep custom. |
| G2 | `Threads/SessionListController.swift:308-317` | Move submenu: every user section (`folder`), "No Section" (`tray`), "New Section…" (`folder.badge.plus`, creates only). | ⚠️ `sessions/SessionRowActions.kt:266-290` lists the chat engine's sections; "New Section…" scoped to that engine (`cmd.newSection(row.engineId)`). | `assignSection`, `createSection(name)` | `ui/SessionsScreen.kt:569-574` | Nested `DropdownMenu`. |
| G3 | `Threads/SessionListController.swift:285-290` | Archived menu: "Unarchive", "Rename…", "Delete". | ✅ `sessions/SessionRowActions.kt:242-258`. | `unarchiveSession`, `renameSession`, `deleteSession` | — | — |
| G4 | `Threads/SessionListController.swift:341-350` | "Rename Session" alert prefilled with the title; Cancel / Rename; empty trim ignored. | ✅ `sessions/SessionPrompts.kt:155-159`, `sessions/SessionPromptModels.kt:30`. | `renameSession(chatId, title)` | `ui/SessionsScreen.kt:635` | `AlertDialog` + `TextField`. |
| G5 | `Session/SessionViewController.swift:870-884` | "Delete session?" — "“<title>” will be permanently deleted. This can’t be undone." or, for Cloud chats, "“<title>” and its Cloud machine will be permanently deleted, with anything not pushed from it. This can’t be undone."; untitled → "New session"; Cancel / Delete (destructive). | ⚠️ `sessions/SessionPromptModels.kt:72-82` has only the plain message (no Cloud variant). | `deleteSession(chatId)`, `SessionRow.isCloud`, `hasTitle` | `ui/SessionScreen.kt:402-422` | `AlertDialog`. |
| G6 | `Threads/SessionListController.swift:330-339` | "New Section" alert from the Move submenu (same as C2). | ✅ copy/caps (`sessions/SessionPromptModels.kt:33-36, 117`); ⚠️ routing as C2. | `createSection(name)` | — | — |

### H. Drag a session between sections (#909, `Threads/SessionListController.swift:353-462, 538-546`)

| # | iOS (file:line) | Behaviour | Roboco status + Kotlin file | zeron core API | zeron android ref | Native-Android option |
|---|---|---|---|---|---|---|
| H1 | `Threads/SessionListController.swift:62-67, 364-373` | Long-press drags a session row on the front page (drag interaction forced on for iPhone). | ❌ No gesture. `sessions/SessionDrop.kt` is pure logic used only by tests (`app/src/test/.../SessionDropTest.kt`). | — | none | `sh.calvin.reorderable` (already a dependency for pins: `sessions/ReorderHandle.kt`) with `longPressDraggableHandle` across the whole `LazyColumn`; or Compose `dragAndDropSource/Target`. |
| H2 | `Threads/SessionListController.swift:375-385, 432-461, 538-546` | Drop targets: Pinned, user sections, Recent; none for search results or project groups; within-Recent → cancel; above a header → no slot. | ⚠️ Logic present (`sessions/SessionDrop.kt:27-57`) but unused; the "above the header" rule isn't modelled. | — | — | — |
| H3 | `Threads/SessionListController.swift:393-412` | Optimistic placement at once; a folded destination unfolds (and persists); selection haptic. | ❌ | — | — | Haptic `SegmentTick`. |
| H4 | `Threads/SessionListController.swift:414-429` | Writes: within Pinned `movePin`, into Pinned `pinSessionAt`, into a section `placeSession`, Pinned → Recent `unpinSession`, section → Recent `assignSection(nil)`. | ⚠️ Logic present (`sessions/SessionDrop.kt:81-106`, `RowActions.applyDrop`) but unused. | `movePin`, `pinSessionAt`, `placeSession(chatId, sectionId?, after?, before?)`, `unpinSession`, `assignSection` (all sync) | — | — |

### I. Folder pages (`FolderViewController`, `Threads/SessionListController.swift:602-681`)

| # | iOS (file:line) | Behaviour | Roboco status + Kotlin file | zeron core API | zeron android ref | Native-Android option |
|---|---|---|---|---|---|---|
| I1 | `Threads/SessionListController.swift:603-611, 640-642` | One folder's sessions (Pinned, a section, Archived); hides the bottom bar. | ✅ `sessions/FolderScreen.kt`, `sessions/FolderRoute.kt`, `chromeOnRootOnly`. | `workspace().front.*`, `archived` | `ui/ArchivedScreen.kt` | — |
| I2 | `Threads/SessionListController.swift:618-619` | Title = folder name, large title always. | ✅ `sessions/FolderScreen.kt:120`. | — | — | — |
| I3 | `Threads/SessionListController.swift:620-626, 635-655` | Pinned: system Edit/Done button, reorder handles while editing, each move → `movePin(after, before)` (synced). | ⚠️ `sessions/FolderScreen.kt` (`pinned-edit` capsule), `sessions/ReorderHandle.kt`, `sessions/PinReorder.kt` — same, plus a roboco clamp that keeps a drag inside one engine's block (`sessions/SessionList.kt:505-507`). | `movePin(chatId, after?, before?)` | `ui/SessionsScreen.kt:666-692` (up/down sheet) | `sh.calvin.reorderable` (already). |
| I4 | `Threads/SessionListController.swift:627-632` | User section ⋯: "Rename Section…", "Delete Section" (destructive). | ✅ `sessions/FolderScreen.kt` (glass ⋯ menu). | — | — | `DropdownMenu`. |
| I5 | `Threads/SessionListController.swift:657-667` | Rename updates the page title. | ✅ `sessions/FolderScreen.kt:181`. | `renameSection` | — | — |
| I6 | `Threads/SessionListController.swift:669-680` | Delete confirm action sheet: "Delete “<name>”?" / "Its sessions move back to the main list." / "Delete Section" / Cancel; then pop. | ✅ `sessions/SessionPrompts.kt:198-206`, `sessions/SessionPromptModels.kt:48-69`. | `deleteSection` | — | `ModalBottomSheet` or `AlertDialog`; custom `design/ActionSheet.kt` today. |
| I7 | `Threads/SessionListController.swift:616` | Archived: no trailing item, archived row actions, blank when empty. | ⚠️ Empty copy "No archived sessions" (roboco addition, `sessions/SessionListStatusOverlay.kt:118-124`). | `workspace().archived` | `ui/ArchivedScreen.kt` | — |
| I8 | `Threads/SessionListController.swift:112-121` | Pull to refresh on folder pages too. | ❌ (as C10). | as C10 | — | `PullToRefreshBox`. |
| I9 | `Threads/SessionListController.swift:183-195, 229-232` | Row tap opens the session (beside the sidebar on iPad); folder rows push folder pages. | ✅ `sessions/SessionListNavigation.kt`, `LocalSessionListOpener`. | `openSession` | — | — |

### J. Search

| # | iOS (file:line) | Behaviour | Roboco status + Kotlin file | zeron core API | zeron android ref | Native-Android option |
|---|---|---|---|---|---|---|
| J1 | `Threads/SessionListController.swift:469, 509-512`, `App/AppModel.swift:518-522` | A non-empty trimmed query swaps the sections for one header-less "results" section = core search (limit 60). | ✅ `sessions/SessionsScreen.kt:145-167, 350-363`, `sessions/SearchModel.kt` (60, off main), `sessions/SidebarQuery.kt:47`. | `search(query, 60u)` (sync) | `ui/SearchScreen.kt` | — |
| J2 | `Threads/SessionListController.swift:22-23, 252-262` | Result rows use the list's normal actions (no per-row archived variant). | ⚠️ Roboco gives archived hits the archived actions (`sessions/SessionsScreen.kt:360`). | `SearchHit.session.archived` | — | — |
| J3 | (no code) | No results → blank list. | ⚠️ Roboco shows "No Results" / "Check the spelling or try a new search." (`sessions/SessionsScreen.kt:345-347`, `sessions/SearchScreen.kt:329-372`). | — | — | — |
| J4 | `Threads/SessionListController.swift:689-713` | `SearchViewController` (old Search tab, placeholder "Sessions, projects, branches") is no longer instantiated anywhere. | ⚠️ Roboco still ships the Search tab screen (`sessions/SearchScreen.kt`, `sessions/SearchFixtures.kt`, `TabId.Search`) — dead in production; delete. | — | — | — |
| J5 | `Shell/SplitRootController.swift:399-406, 463-477` | Sidebar search: stacked, never hides, placeholder "Search", id `sidebar-search`; typing sets the list query; `showSessions` clears and pops. | ✅ `shell/tablet/SidebarSearchField.kt`, `shell/tablet/SidebarState.kt`. | `search` | `ui/ZeronRoot.kt:249-253` | M3 `SearchBar` inputField restyled (optional). |

### K. iPad / unfolded split (`Shell/SplitRootController.swift`)

| # | iOS (file:line) | Behaviour | Roboco status + Kotlin file | zeron core API | zeron android ref | Native-Android option |
|---|---|---|---|---|---|---|
| K1 | `Shell/SplitRootController.swift:40-55` | Double column, sidebar beside the detail (tile), primary 360 pt (320–400), no display-mode button, no primary background. | ✅ `shell/SplitRoot.kt`, `shell/TabletShellState.kt:36-42`. | — | `ui/TabletShell.kt` (320/380) | `material3-adaptive` `ListDetailPaneScaffold` could host it, but custom `SplitRoot` already matches. |
| K2 | `Shell/SplitRootController.swift:89-97` | Window < 700 pt (unfolded Duo, 669 pt portrait) drops the sidebar minimum to **280**. | ❌ No 280 rule; `SidebarMinWidth = 320.dp`. | — | `ui/ZeronRoot.kt:127` | — |
| K3 | `Shell/SplitRootController.swift:12-18`, `App/SceneDelegate.swift:163-168` | Split whenever the window is regular × regular (iPad, unfolded Duo); live as the window changes. | ⚠️ `shell/RootKind.kt:46` `SplitBreakpoint = 744.dp` — a book foldable's ~670–700 dp inner screen stays on the phone layout; never splits without engines. | — | `ui/ZeronRoot.kt:125` (≥ 700 dp × ≥ 480 dp) | `currentWindowAdaptiveInfo()` / `WindowSizeClass` + `WindowInfoTracker` `FoldingFeature` (hinge, uneven panes — the Android form of #942's "uneven safe areas"). |
| K4 | `Shell/SplitRootController.swift:65-80` | Compact height (phone landscape) forces the collapsed shell. | ✅ `shell/RootKind.kt:52-66` (`CompactHeight = 480.dp`). | — | `ui/ZeronRoot.kt:125` | — |
| K5 | `Shell/SplitRootController.swift:82-87, 144-170` | Detail starts on the embedded new-session draft without the keyboard; ⌘N / New focuses it; a created chat replaces the draft with the handoff. | ✅ `shell/TabletAppRouter.kt:293-335` (`showDraft`, `onDraftCreated`), `shell/TabletDetail.kt`. | (new-session area) | `ui/ZeronRoot.kt:269-277` | — |
| K6 | `Shell/SplitRootController.swift:99-113` | Key commands ⌘N "New Session", ⇧⌘F "Search", ⌘B "Toggle Sidebar" (0.3 s). | ✅ `shell/ShellShortcuts.kt` (Ctrl), `MainActivity.kt:282` `onProvideKeyboardShortcuts`, `Motion.sidebarToggle()`. | — | `ui/ZeronRoot.kt:209-226` | Already native (Keyboard Shortcuts Helper). |
| K7 | `Shell/SplitRootController.swift:117-128` | `openSession`: dismiss presented; collapsed → phone shell; same chat no-op; replace detail without animation; highlight it in the sidebar. | ✅ `shell/TabletAppRouter.kt:275-291` (minus inspector-follow, see L6). | `openSession` | `ui/ZeronRoot.kt:129` | — |
| K8 | `Shell/SplitRootController.swift:130-137` | Open error: draft in the detail, sidebar back to Sessions, sidebar shown, toast. | ✅ `shell/TabletAppRouter.kt:353-360`. | — | — | — |
| K9 | `Shell/SplitRootController.swift:172-180` | Settings as a form sheet with a Done button. | ✅ `shell/SettingsDialog.kt` (540×620 card, Done on root, predictive back). | — | `ui/ZeronRoot.kt:440-458` (`Dialog`) | `Dialog(usePlatformDefaultWidth = false)` — separate window, so no Haze glass behind; keep custom. |
| K10 | `Shell/SplitRootController.swift:182-186` | `showSearch`: reveal the sidebar if hidden, pop it, focus its field. | ✅ `shell/TabletAppRouter.kt` (`showSidebar`, `focusSidebarSearch`). | — | `ui/ZeronRoot.kt:150` | — |
| K11 | `Shell/SplitRootController.swift:188-194, 280-286` | `sessionDidAppear` / `sessionDidClose` keep `currentChatId` + sidebar highlight across width changes. | ✅ `shell/CarryOver.kt` (`CarryOverPresence`). | — | — | — |
| K12 | `Shell/SplitRootController.swift:290-317` | Narrowing: the detail's session page **itself** moves into the phone stack (no rebuild, no loading flash under the fold animation). | ⚠️ `shell/CarryOver.kt` clears the column and re-opens the chat on the phone stack next frame (pre-#981). | — | `ui/ZeronRoot.kt:131-136` | see B15. |
| K13 | `Shell/SplitRootController.swift:319-348` | Widening: the phone's session page moves into the detail; else re-open the current chat or show the draft. | ⚠️ `shell/CarryOver.kt` re-opens (pre-#981). | — | `ui/ZeronRoot.kt:137-143` | see B15. |
| K14 | `Shell/SplitRootController.swift:351-461` | Sidebar = the phone list in its own nav stack (folders push inside), large title, no nav ⋯, 1/scale hairline on its trailing edge, the split's outer nav bar hidden. | ✅ `shell/tablet/Sidebar.kt`, `shell/SplitRoot.kt:132-157`. | — | `ui/ZeronRoot.kt:248-266` | — |
| K15 | `Shell/SplitRootController.swift:407-441` | Sidebar toolbar: gear (`sidebar-settings`), ⋯ "Options" (same options menu), flexible space, toolbar probe, **voice (when available)**, prominent accent `plus` "New session". | ⚠️ `shell/SidebarToolbar.kt` has gear, ⋯, accent + — no voice item. | voice APIs (B6) | — | — |
| K16 | `Shell/SplitRootController.swift:361-378, 214-219` | Invisible toolbar probe → a session's composer rests level with the sidebar toolbar (#1014). | ✅ `session/SessionBottomStack.kt:210-240` (`LocalSidebarToolbarMid`), `session/BottomStackMetrics.kt:72-84`. | — | — | — |
| K17 | `Threads/SessionListController.swift:24-33` | The session open beside the sidebar is drawn current (`rowActive`). | ✅ `sessions/SessionRow.kt:66-78` (`isCurrent`). | — | `ui/SessionsScreen.kt` (`selected`) | — |

### L. Companions inspector column (#988, `Shell/SplitRootController.swift:22-27, 196-278, 294-301`)

| # | iOS (file:line) | Behaviour | Roboco status + Kotlin file | zeron core API | zeron android ref | Native-Android option |
|---|---|---|---|---|---|---|
| L1 | `Shell/SplitRootController.swift:56-63` | Third (inspector) column, 400 pt (340–480), hidden at launch, own nav stack. | ❌ Roboco shows side chats/agents in a sheet (`sidechats/SideChatsSheet.kt`). | `childSessions(parentId)`, `SessionHandle.subagents()` | `ui/TabletShell.kt:43-126` (`InspectorPage`) | `material3-adaptive` `SupportingPaneScaffold` / `ListDetailPaneScaffold` extra pane, or extend custom `SplitRoot`. |
| L2 | `Shell/SplitRootController.swift:200-210` | `toggleCompanions(chat, section)`: show the companions panel for the chat; hide if already showing it. | ❌ | as L1 | `ui/TabletShell.kt:91-95` | — |
| L3 | `Shell/SplitRootController.swift:221-229, 249-262` | `showCompanion`: terminal / subagent / side chat pushed over the panel (Back returns to it); side chats open as `isCompanion` sessions. | ❌ | `createSideChat(parentId)` (sync), `forkSideChat(sourceId)` (`suspend`), terminals | `ui/TabletShell.kt:97-104, 187-241` | — |
| L4 | `Shell/SplitRootController.swift:231-247` | `closeCompanion` pops or hides; `hideCompanions` restores the sidebar mode it displaced. | ❌ | — | `ui/TabletShell.kt:102-110` | — |
| L5 | `Shell/SplitRootController.swift:264-272` | Below 1100 pt the sidebar steps aside (0.3 s) while the inspector shows. | ❌ | — | `ui/ZeronRoot.kt:126` (`roomy`) | — |
| L6 | `Shell/SplitRootController.swift:274-278` | Opening another session re-roots the open panel on it. | ❌ | — | `ui/TabletShell.kt:77-83` | — |
| L7 | `Shell/SplitRootController.swift:294-301, 321-322` | Folded/collapsed: the inspector becomes a sheet over the phone shell with a Close button. | ❌ | — | — | `ModalBottomSheet` (or the custom page sheet). |

### M. New Project (`Threads/NewProjectViewController.swift`)

| # | iOS (file:line) | Behaviour | Roboco status + Kotlin file | zeron core API | zeron android ref | Native-Android option |
|---|---|---|---|---|---|---|
| M1 | `Threads/NewProjectViewController.swift:22, 30` | Title "New Project"; ✕ Close on the left. | ✅ `newsession/NewProjectScreen.kt:202`, `newsession/FolderBrowserController.kt:123`. | — | `ui/NewProjectScreen.kt` | — |
| M2 | `Threads/NewProjectViewController.swift:85-94` | Host picker in the nav bar: title = device name ("No hosts"); menu "Device": each host with `desktopcomputer` and subtitle "Online"/"Offline"; defaults to the first online host. | ⚠️ Engine capsule + engine menu (`newsession/NewProjectScreen.kt`, `newsession/FolderBrowserController.kt:131-135` "Engines"/"Devices"). | `executionDevices()` → `DeviceView(id, name, online)` | `ui/NewProjectScreen.kt` | `DropdownMenu` with supporting text. |
| M3 | `Threads/NewProjectViewController.swift:31-56, 128-130` | Inset-grouped list: "Parent Folder" (`arrow.turn.left.up`) when not at root, then directories only; repo → `folder.badge.gearshape` accent, folder → `folder` secondary; chevrons. | ⚠️ Same rows (`design/GroupedList.kt` rows), **plus** roboco-only rail / path field / "Search folders…" / "New Folder…" (`newsession/FolderLocationsHeader.kt`, `newsession/FolderSearch.kt`, `newsession/FolderBrowserActions.kt`). | `listFolders(deviceId, path?)` (`suspend` → `FolderListing{path, entries[name,isDir,isRepo], truncated}`) | `ui/NewProjectScreen.kt` | — |
| M4 | `Threads/NewProjectViewController.swift:61-83, 135-141` | Bottom: status (13, secondary, centred, 2 lines) + capsule "Use “<folder>”" (text-filled), 20/12 margins; title follows the folder. | ⚠️ `newsession/FolderBrowserBottomBar.kt` same + roboco "Create and add". | — | — | — |
| M5 | `Threads/NewProjectViewController.swift:98-142, 145-153` | Load with a generation guard: "Loading…", "Couldn't read that folder.", "<device> is offline."; keep the last good folder only on the same device; taps ignored while loading. | ✅ `newsession/FolderBrowserController.kt:126-130`, `newsession/FolderNavState.kt`. | `listFolders` | — | — |
| M6 | `Threads/NewProjectViewController.swift:103-106` | No hosts: "No desktop devices yet — open Zeron on a computer to add one." | ⚠️ "No paired engines yet." (`newsession/FolderBrowserController.kt:131`). | `executionDevices()` | — | — |
| M7 | `Threads/NewProjectViewController.swift:162-174` | Create: git = a `.git` entry; spinner on the button; `onCreated(id)`; dismiss; failure "Couldn't create the project on <device>." | ⚠️ Same copy (`newsession/FolderBrowserActions.kt:51`), but goes through roboco `prepareSpacePath` first (`newsession/ProjectWriteApi.kt:32`). | `createProject(deviceId, path, gitDetected)` (`suspend` → id); no `prepareSpacePath` | `ui/NewProjectScreen.kt` | — |

### Counts

| Group | ✅ | ⚠️ | ❌ |
|---|---|---|---|
| A Scene/root/lifecycle | 4 | 5 | 5 |
| B iPhone shell + bar | 13 | 4 | 2 |
| C Front page | 9 | 4 | 1 |
| D Section headers | 7 | 2 | 0 |
| E Session row | 9 | 2 | 0 |
| F Swipe actions | 5 | 1 | 0 |
| G Row menu + prompts | 4 | 2 | 0 |
| H Drag between sections | 0 | 2 | 2 |
| I Folder pages | 6 | 2 | 1 |
| J Search | 2 | 3 | 0 |
| K Split | 12 | 4 | 1 |
| L Inspector | 0 | 0 | 7 |
| M New Project | 2 | 5 | 0 |
| **Total (128 rows)** | **73** | **36** | **19** |

(G6 counted ✅ for copy; its routing gap is C2.)

---

## 2. Roboco-only things in this area to delete or rework

Everything here is either an engine/pairing concept zeron's core doesn't have, or chrome iOS removed.

**Pairing root and engine plumbing (replace with sign-in + `CoreClient` lifecycle)**

- `pairing/` (whole package: `PairingRoot`, `CoreClientPairingBackend`, `StorePairedEngineSink`, `InboundPairingLinks`, `ProvidePairPage`, QR scan) and its wiring in `shell/RobocoAppContent.kt:93-143, 181-186`.
- `shell/RootKind.kt`: `RootInput.Ready(hasEngines)`, `RootKind.Pairing`, the "no engines ⇒ never split" rule → signed-in/signed-out root.
- `RobocoApplication.kt`: `engineDataStore`, `engineStore`, `launchPairRedeemer`/`redeemLaunchPairs`, `coreStartup.engines` consumers (`:284-318, 697-701`), `engineHealth` (`:371`), `runWatch` engine count; `MainActivity.kt:136-147` (`pair` extra) and the `ACTION_SEND` pairing share branch (`:217-225`).
- `AndroidManifest.xml:61-88`: `roboco://` hosts (`pair`, and the scheme itself), the pairing share-target activity, the QR activity.
- `shell/DeepLinks.kt`: `DeepLink.Pair`, `SCHEME = "roboco"` → `zeron` (and only what iOS supports: launch `route`; `zeron://voice`; `zeron://callback` for auth).
- `notify/Notifier.kt:342` (`roboco://chat/<id>`), `onForgotten = app.notifier::cancelEngine`.
- `core/WorkspaceFeed.kt:93-131`: `engines()` / `EnginesChanged` lane → `devices()` + `connectivity()`; `core/CoreStartup.kt:131-172`.

**Sessions list engine adaptations**

- `sessions/ConnectionLine.kt`, `sessions/EngineHealth.kt` (multi-engine connection line under the large title; `SessionsScreen.kt:129-131, 179-180, 365-369`). iOS shows no connectivity line on the list.
- `SectionLive.Offline`, `FrontPageSections.offlineOverrides` (`sessions/FrontPageSections.kt:96-110`), the wifi-off header glyph (`sessions/SectionHeaderRow.kt:236, 324`).
- `FrontPageSections.collisionSuffixes` / `engineNames` (" · <engine>" header suffix) and `FrontSectionMeta.engineId`.
- `sessions/FoldState.kt`: engine-synced user-section folds, `SectionFoldOverride`, `sectionFoldOverrides` → a single local `collapsedSections` set (iOS).
- `sessions/SessionPrompts.kt` / `sessions/SessionPromptModels.kt`: `SessionPromptBackend`, `CoreSessionPromptBackend`, `EngineChoice`, `newSectionTarget`, the "Create on" sheet, the "Engine not connected" toast; `RowActions.sectionsFor(engineId)` → all `front.sections`.
- `sessions/CoreRowActions.kt`: `createSection(name, engineId)`, `PINS_NOT_SYNCED_TOAST`/`ENGINE_OFFLINE_TOAST` copy, `ClientEvent.Notice` → `notices()` / `startNoticeToasts` (zeron emits no notices; failures are logged like iOS `attempt`).
- `sessions/PinReorder.kt` + `sessions/SessionList.kt:505-507`: the one-engine clamp on pin drags.
- `sessions/SessionListItems.kt:196-209` `sessionsLoading(engines)`; `sessions/Refresher.kt` (`CoreClient.refresh()`).
- `SessionRowVM.runningSubagents` + the "● N" pill (`sessions/SessionRowGeometry.kt:83-108`, `sessions/SessionRow.kt:219-226`, `runningAgentsLabel`).
- Roboco-added empty/no-result copy (`sessions/SessionListStatusOverlay.kt`, `SearchNoResults`) — delete for parity (or keep as a deliberate Android deviation; iOS is blank).

**Pre-#967 tab chrome (dead in production since `oneRowBar = true`)**

- `shell/GlassTabBar.kt`, `shell/TabBarMinimize.kt`, `shell/TabChromeLayout.kt`, `shell/TabChromeGeometry.kt`, `shell/SearchMorphGeometry.kt`, `shell/NewSessionAccessory.kt` (the "New session" + live-summary capsule), `shell/LiveCounts.kt` (+ `rememberLiveCounts` in `shell/RobocoAppContent.kt:208-218`), `shell/TabReselect.kt`, `shell/PlaceholderTabRoot.kt`, `shell/NewSessionSheetPlaceholder.kt`, and the tab branches of `shell/MainTabShell.kt:455-563`, `shell/MainTabState.kt`, `shell/TabId.kt` (Search/Settings tabs), `TabRootRoute(TabId.Search/Settings)` in `shell/ShellRoutes.kt:63-90`.
- `shell/SearchMorph.kt`: keep only `SearchTabField` (used by `shell/SessionsBottomBar.kt`) — move it out, delete the morph/return-circle; fix the placeholder to "Search".
- `sessions/SearchScreen.kt`, `sessions/SearchFixtures.kt` (Search tab screen).
- Galleries for the above: `shell/TabBarGallery.kt`, `shell/ChromeVisibilityGallery.kt`, `shell/NavGallery.kt`, `shell/LargeTitleGallery.kt` (review), `design/gallery/SearchTabMorphGallerySection.kt`, `design/gallery/TabBarMinimizeGallerySection.kt`.

**New Project extras not in iOS**

- `newsession/FolderLocationsHeader.kt` (rail + path field), `newsession/FolderSearch.kt` ("Search folders…", "Create and add"), "New Folder…" in `newsession/FolderBrowserActions.kt`, `ProjectWriteApi.prepareSpacePath` (`newsession/ProjectWriteApi.kt:32-63`) — no zeron core equivalent.

**Routes to review (other areas own them, but they hang off this shell's route table, `shell/ShellRoutes.kt:111-127`)**

- `EngineDetailRoute` (Settings → engine page) — roboco-only.
- `PreviewPanelRoute` / `BrowserRoute` (agent previews, in-app browser on `:7332`) — not in iOS.

---

## 3. Core API mismatches touching this area

Roboco names are from call sites (the `uniffi.roboco_core` binding is generated at build time and not in the repo). zeron: `pub fn` ⇒ sync Kotlin, `pub async fn` ⇒ `suspend`.

| Roboco call / type | zeron equivalent (`uniffi.zeron_core`) | Sync vs suspend / notes |
|---|---|---|
| `CoreClient.engines(): List<EngineView>` (`EngineState.Connected/Connecting/Reconnecting/Offline/Parked`, `fromCache`, `ParkReason`) | none. Nearest: `devices()` / `executionDevices()` → `DeviceView{online, isExecutionHost, isSelf, …}`; `connectivity()` → `Connectivity{state: Disabled/Offline/Reconnecting/Connected, retryAtMs, lastFailure, degradedChats}`; `WorkspaceSnapshot.synced` | all sync |
| `CoreClient.refresh()` (pull-to-refresh, called on IO) | none. Use `onForeground()` + `workspace()` and finish on the next `WorkspaceChanged`/`ConnectivityChanged` (iOS `AppModel.refresh`) | `onForeground()` sync |
| `CoreClient.trimMemory(level)` (`RobocoApplication.kt:512-517`) | none | — |
| `createSection(name, engineId)` | `createSection(name)` → id | sync, throws `CoreException` |
| `FrontPage.groups` (core-side project grouping) | none; build from `front.recent` + `WorkspaceSnapshot.projects[].groupKey/groupName` (+ `home:<deviceId>`) | client-side |
| `SessionRow.engineId` / `engineOnline` | `SessionRow.deviceId` / `deviceOnline` (+ `deviceName`, `isCloud`, `parentChatId`, `roomGen`) | — |
| `SectionView.engineId` | none (sections are workspace-wide): `SectionView{id, name, collapsed, sessions}` | — |
| `SessionRow.runningSubagents` | none (`childSessions(parentId)` sync; `SessionHandle.subagents()` sync) | — |
| `ClientEvent.EnginesChanged`, `RoomEngineJoined`, `EngineIdentityPinned`, `Notice{message}` | none. zeron events: `WorkspaceChanged`, `SessionChanged`, `ComposerChanged`, `ConnectivityChanged`, `AuthRefreshed`, `AuthExpired` | — |
| `setSectionCollapsed(id, collapsed)` (engine-synced folds) | exists (`setSectionCollapsed`, `SectionView.collapsed`) but iOS doesn't use it — folds stay local | sync |
| `pinSession`, `unpinSession`, `pinSessionAt`, `movePin`, `archiveSession`, `unarchiveSession`, `deleteSession`, `assignSection`, `placeSession`, `renameSession`, `renameSection`, `deleteSection`, `markSeen` | same names and shapes | all sync; keep the one serial IO lane in `sessions/CoreRowActions.kt` |
| `search(q, limit)` → `SearchHit` | `search(query, limit: UInt)` → `SearchHit{session, score, field}` | sync |
| `workspace()` → `WorkspaceSnapshot` | `workspace()`; also `revision`, `pinsReady`, `synced`, `front`, `projects`, `projectless`, `pullRequests`, `archived`, `devices` | sync |
| `openSession(chatId)` | `openSession(chatId)` → `SessionHandle` (also `session(chatId)`, `closeSession`, `preloadSessions()`) | sync |
| `CoreException.*` with `reason` | same variants (`NotFound`, `InvalidArgument`, `HostUnavailable`, `Unsupported`, `HostError`→`HostException`, `Network`, `Auth`, `Storage`, `NotImplemented`, `Closed`, `Internal`); `message`→`reason` rename is in `zeron/crates/mobile/uniffi.toml` too | `classify` in `sessions/CoreRowActions.kt:107-118` keeps working; only the toast copy changes |
| `projectColorIndex("home")` | `projectColorIndex(spacePath)` | sync |
| `listFolders(deviceId, path)` | `listFolders(deviceId, path?)` → `FolderListing` | `suspend` |
| `prepareSpacePath(deviceId, path, createIfMissing)` | none | — |
| `createProject(deviceId, path, gitDetected)` | `createProject(deviceId, path, gitDetected)` → id | `suspend` |
| `setNetworkOnline`, `setNetworkChanged` | `setNetworkOnline(online)`, `networkPathChanged()` | sync |
| `onForeground()`, `onBackground()` | same | sync |
| (none: no accounts) | `authExchangeCode`, `authListOrgs`, `authRefresh` (top-level `suspend`), `workosAuthorizeUrl`, `parseAuthCallback`, `CoreClient(config, credentials, listener)` | constructor sync/throws |
| (none: no push) | `registerPushTarget(...)`, `unregisterPushTarget()` | `suspend` |

---

## 4. Ordered task list to reach parity

1. **Swap the root to zeron's model.** Replace `RootInput`/`RootKind.Pairing` with signed-out → sign-in, signed-in → shell (`CoreClient` exists); keep `RootCrossfade` (A1–A3). Drop `pairing/` and the engine plumbing listed in §2. Retarget `WorkspaceFeed` to `workspace()` + `connectivity()` + `devices()` and add the 30 s re-read ticker (A13).
2. **Rebind row writes** (`sessions/CoreRowActions.kt`) to `uniffi.zeron_core` (same method names; `createSection(name)`), drop `Notice` toasts and engine-scoped `sectionsFor` → all sections (C2, F6, G2).
3. **Front-page data.** Port iOS `projectGroups` (C7), make every fold local under `collapsedSections` (D6), remove the collision suffix and Offline header glyph (D3).
4. **Collapse the phone shell to one stack.** Delete the tab chrome (§2), keep `SessionsBottomBar`; fix the search placeholder to "Search" and the compose glyph to square-and-pencil (B5, B8). Decide on the search field: re-skinned M3 `SearchBar` (gains the iOS 26 expand/close behaviour + predictive-back) vs. the current static field.
5. **Pull to refresh** on the front page and folder pages with zeron's `onForeground()`-based refresh (min 0.6 s / max 3 s) (C10, I8) — M3 `PullToRefreshBox` with the custom indicator.
6. **Drag between sections** (#909): wire `SessionDrop.kt` to a long-press drag on the front page (reorderable lib), optimistic placement, unfold destination, haptic (H1–H4). Remove the pin drag engine clamp (I3).
7. **Parity copy/states:** Delete-session Cloud message (G5), remove roboco-only empty/no-results/loading copy and the "● N" pill unless kept deliberately (C13, C14, J2, J3, E8, E10).
8. **Split/Duo:** lower the split breakpoint to iOS's regular width (≈ 700 dp with ≥ 480 dp height, or WindowSizeClass + FoldingFeature), add the 280 dp sidebar minimum under 700 dp (K2, K3), and move the live session entry across fold/unfold instead of re-opening it (B15, K12, K13).
9. **Inspector column** (#988): a third pane on the split with `toggleCompanions` / `showCompanion` / `closeCompanion`, sidebar stepping aside < 1100 dp, follow-on-session-change, and a sheet when collapsed (L1–L7). Coordinate with the companions audit.
10. **Voice entry points** if voice is in scope: bottom-bar and sidebar voice item + long-press menu, `zeron://voice` (B6, B7, K15, A10).
11. **New Project** to iOS's simpler page: device menu ("Device", Online/Offline), iOS copy, drop rail/search/New Folder/`prepareSpacePath` (M2–M7).
12. **Sign-in follow-ups:** onboarding after sign-in, debug cloud `route`s, notification → `openSession` via the deep-link hub with the `zeron` scheme, delivery hold on background (A4, A8, A9, A11).

### Native-component notes (applies across the table)

- Roboco's alerts, action sheets, glass menus, context menu and page sheets are deliberately **in-window** (no `Dialog`/`Popup`) so Haze can blur what's behind them; they already handle system Back (`BackHandler`/`PredictiveBackHandler`) and TalkBack actions. M3 `AlertDialog`, `ModalBottomSheet`, `DropdownMenu` and `Dialog` are separate windows: switching gains IME/insets/a11y defaults for free but loses the glass blur. Window blur-behind (`WindowManager.LayoutParams.blurBehindRadius`, API 31+, which is roboco's minSdk) is device-dependent and off in battery saver.
- Good native swaps with little visual cost: `PullToRefreshBox` (custom indicator), `SnackbarHost` with a custom glass snackbar (same window), M3 `SearchBar` input field re-skinned, `sh.calvin.reorderable` for drag, `WindowSizeClass`/`FoldingFeature` for the split decision, `onProvideKeyboardShortcuts` (already).
- Keep custom: the swipe row (M3 `SwipeToDismissBox` has one dismiss action per side; iOS reveals two buttons), the page sheet (draft handoff needs it in-window), the large-title scaffold, the context-menu lift.
