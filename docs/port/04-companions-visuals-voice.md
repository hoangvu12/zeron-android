# 04 · Companions (subagents, side chats, terminals), agent visuals, voice

Port-gap audit, iOS (zeron v0.2.129, `zeron/` submodule HEAD `8b7afb30`) → Roboco Android.
Scope: everything iOS gained in `Companions/`, `Transcript/VisualView.swift`, `Voice/`,
`ZeronLiveActivity/`, `ZeronShared/` plus the session/shell entry points that open them.

Relevant upstream commits since the Roboco base `edb23db4` (2026-10-11):

| commit | what |
|---|---|
| `8a0fded8` #900 | Native visuals: `show_visual` Luau rendered by the core (CPU paint + wgpu offscreen) |
| `c526d6f7` #942 / `6f2721fa` #981 | iPhone Duo fold/unfold (inspector carried over as a sheet), voice-stage safe areas |
| `fd7d268c` #988 | Terminals, side chats and subagents beside every session (strip, panel, inspector, `zeron-term`) |
| `dfbb6876` #1007 | Terminals: direct WebRTC link to the host (Rust `webrtc-rs` in `zeron-preview`/`zeron-term-link`, app only calls `warmDirect`) |
| `3c97a05c` #1010 | Terminal box/block glyph painting, subagents chip icon fix |

Voice (`Voice/*`, Live Activity) predates `edb23db4` on iOS, but Roboco dropped it on purpose
(ADR 0009, see the note in `R/shell/SessionsBottomBar.kt`), so it is entirely missing.

Path prefixes used below:

- `I/` = `zeron/apps/ios/Zeron/`  (`IL/` = `zeron/apps/ios/ZeronLiveActivity/`, `IS/` = `zeron/apps/ios/ZeronShared/`)
- `R/` = `app/src/main/java/sh/roboco/android/`  (Roboco, `uniffi.roboco_core`)
- `Z/` = `zeron/apps/android/app/src/main/java/sh/zeron/android/`  (Zeron's own Compose app: the logic reference)
- `C/` = `zeron/crates/mobile/src/`  (UniFFI core, `uniffi.zeron_core`)

Status: ✅ = matches current iOS · ⚠️ = present but an older/different shape · ❌ = missing.

All "zeron core API" names are the Kotlin binding names (camelCase of the Rust export).

---

## 1. Gap table

### A. Session entry points and the companion strip

| # | iOS (file:line) | Behaviour | Roboco | zeron core API | Z reference | Native-Android option |
|---|---|---|---|---|---|---|
| A1 | `I/Session/SessionViewController.swift:191-201, 773-782` | Strip sits at the trailing end of the status-pill row above the composer; the row hides when there is no banner and the strip is empty; structural show/hide animates with the 0.38 s spring. | ❌ `R/session/StatusPill.kt:252` `StatusPillRow` has only `Spacer` after the pill | — | `Z/ui/SessionScreen.kt:367` | Keep Roboco `StatusPillRow`, add the strip in place of the trailing `Spacer`; `AnimatedVisibility` + `animateContentSize`. |
| A2 | `I/Companions/CompanionStrip.swift:55-59` | Agents chip: while any run, spinner glyph + "`N` working" in `text` colour; otherwise `tool-bot` icon + total count. a11y "N subagents (working)". | ❌ | `SessionHandle.subagents(): List<SubagentRow>` (`C/client_ffi/companions.rs:59`) | `Z/ui/Companions.kt:126-134` | M3 `AssistChip`/`SuggestionChip` restyled (container = Roboco `GlassSurface`, label `Face.SansMedium` 13) is possible but the glass capsule is simpler as a custom `GlassSurface` capsule. Recommend custom on `GlassSurface`. |
| A3 | `CompanionStrip.swift:60-61`, `SessionViewController.swift:299-300` | Side-chats chip: `bubble.left.and.bubble.right` + count; never shown inside a side chat (its siblings live with the parent). | ❌ | `CoreClient.childSessions(parentId)` filtered `!archived` | `Z/ui/Companions.kt:135-137`, `Z/ui/SessionScreen.kt:211` | same as A2 |
| A4 | `CompanionStrip.swift:62-68` | Terminals chip: `apple.terminal` + count + 7 pt `done`-green dot when any terminal is live. | ❌ | `TerminalSession.phase()` via store | `Z/ui/Companions.kt:138-140` | same as A2 |
| A5 | `CompanionStrip.swift:37-45, 73-153` | Chip = 30 pt interactive glass capsule, 11 pt side insets, 6 pt gaps, Geist Medium 13 secondary, pressed alpha 0.7, selection haptic; tap opens the panel scrolled to that chip's section. | ❌ | — | `Z/ui/Companions.kt:144-165` | Custom (`GlassSurface` + `GlassPress`), `Haptic.Select`. |
| A6 | `SessionViewController.swift:282-294, 248-256` | Strip/badge re-sync throttled to ≤4/s (0.25 s), driven by session changes, workspace changes and the terminal store. | ❌ | `SessionEvent` hub / workspace revision / `TerminalStore.changes` | `Z/ui/Companions.kt:91-110` (`rememberCompanions`) | Conflated `Channel` + `delay(250)` (as `Z`), or Roboco `SessionEventHub` like `SideChatsModel`. |
| A7 | `SessionViewController.swift:56-60, 312-318` | Nav-bar item `square.stack.3d.up` **always shown**, a11y "Agents, side chats and terminals"; accent badge with the running-agent count (white text) only while agents run. Bar order: ⋯, companions, voice. | ⚠️ `R/sidechats/SideChatsButton.kt` — `TabChat` icon, hidden while nothing hangs off the chat, badge "● N" (accent) or total count (secondary) | — | `Z/ui/SessionScreen.kt:285-294` | Roboco `GlassCircleButton` + badge (already has `RobocoIcons.SquareStack3dUp`); M3 `BadgedBox`+`Badge` restyled is a fine native alternative for the badge. |
| A8 | `SessionViewController.swift:810-817, 835-846` | ⋯ menu inline group: **New Side Chat** (`bubble.left.and.text.bubble.right`; from a side chat → sibling under its parent), **Fork Into Side Chat** (toast "Forking…", success haptic, open), **Terminal** (`apple.terminal`, opens the first live tab or a new one). | ⚠️ `R/session/SessionMenu.kt:181-205` has New Side Chat + "Fork into Side Chat" (disabled while forking), no **Terminal** row; gated on engine version | `createSideChat`, `forkSideChat` (`C/client_ffi/companions.rs:71-83`) | `Z/ui/SessionScreen.kt:216-232, 300-306` | Roboco `GlassMenu` (`R/design/glass/GlassMenu.kt`); M3 `DropdownMenu` restyled is the native alternative. |
| A9 | `SessionViewController.swift:378-386` | Hardware keyboard: ⌘J Terminal, ⌘⌥0 companions panel. | ❌ | — | `Z/ui/ZeronRoot.kt:210-219` (Ctrl+J) | `Modifier.onPreviewKeyEvent` (Ctrl on Android), also register in `R/shell/ShellShortcuts.kt` / Keyboard Shortcuts Helper (`Activity.onProvideKeyboardShortcuts`). |

### B. Companions panel (sheet on phone, inspector column on large screens)

| # | iOS (file:line) | Behaviour | Roboco | zeron core API | Z reference | Native-Android option |
|---|---|---|---|---|---|---|
| B1 | `I/Session/SessionViewController.swift:347-371`, `I/Companions/CompanionsViewController.swift:63-75, 256-259` | Phone: nav controller in a sheet, detents medium+large, grabber, no scroll-expand; title = chat title, subtitle "Agents, side chats & terminals" or "N agent(s) working"; Close (X) trailing. | ⚠️ `R/sidechats/SideChatsSheet.kt:150-199` — `GlassSheet` [Medium, Large] + grabber, fixed title "Side Chats & Agents", leading + menu, trailing accent ✓; auto-closes when empty | — | `Z/ui/Companions.kt:172-200, 273-285` | Keep Roboco `GlassSheet` (`R/design/sheet/GlassSheet.kt`) — it already has the iOS detents; M3 `ModalBottomSheet` restyled is the native alternative (loses the medium detent nuance). |
| B2 | `CompanionsViewController.swift:8-18, 238-249` | Sections **Agents** (only when any), **Side chats**, **Terminals** (both always shown). Not foldable. | ⚠️ `SideChatsSheet.kt:75-80, 286-353` — Running / Side chats / Finished, foldable headers with counts, empty sections dropped, no Terminals | — | `Z/ui/Companions.kt:286-330` | `LazyColumn` with keyed items. |
| B3 | `CompanionsViewController.swift:179-202, 522-559` | Section header: Geist Semibold 13 secondary, 46 pt row; trailing 28 pt glass "+" — Side chats: menu (New Side Chat / Fork This Chat), Terminals: direct New terminal; Agents: none. | ⚠️ header row is Roboco's sessions `SectionHeaderRow` (fold chevron + count); the + lives in the sheet's nav bar | — | `Z/ui/Companions.kt:292-319, 340-351` | Custom header + `GlassCircleButton` small + `GlassMenu`. |
| B4 | `CompanionsViewController.swift:106-120, 244-249, 469-491` | Empty states: "Ask something on the side without derailing the main thread." + action rows "New side chat" / "Fork this chat" (→ "Forking…" + spinner while busy); Terminals: "A shell on this session's computer, in its folder." + "New terminal". Action rows: 48 pt, 26 pt `accentSoft` tile, accent title. | ❌ | — | `Z/ui/Companions.kt:306-323, 463-478` | Custom row (Roboco row-highlight pattern). |
| B5 | `CompanionsViewController.swift:219-225` | Agents ordered live first, then newest. | ⚠️ `R/sidechats/SideChatsModel.kt:103-107` splits running/finished into two sections, newest first | `SubagentRow` order = transcript order | `Z/ui/Companions.kt:254-257` | — |
| B6 | `CompanionsViewController.swift:410-429, 497-518`, `I/Companions/SubagentViewController.swift:160-178` | **Running** agent row (62 pt × TypeScale): 30 pt r8 `accentSoft` tile with spinner; title Medium 16; detail = live `tail` ?? "model · kind" ?? "Working"; trailing elapsed (`StatusPill.elapsed`) in `working` tone, ticking every 1 s while the panel is visible; chevron when it has a doc. | ⚠️ `SideChatsSheet.kt:400-494` `SubagentRowView` — glyph without tile, trailing = model (capitalized), no elapsed, no `kind` | `SubagentRow{id, docId?, title, model?, kind?, state, tail?, startedAtMs}` | `Z/ui/Companions.kt:370-413`, `Z/ui/SubagentScreen.kt` (`SubagentFormat`) | Custom row; elapsed via `produceState` 1 s ticker. |
| B7 | `CompanionsViewController.swift:430-431` | **Done** agent row: `chip` tile + green ✓; detail = "model · kind" or "Finished"; no trailing. | ⚠️ ✓ glyph, trailing relative time ("12m"), 46 pt height, title at 88 % alpha | same | same | same |
| B8 | `CompanionsViewController.swift:432-434` | **Failed** agent row: `dangerWash` tile + red ✗; detail "Failed · model" or "Didn't finish". | ⚠️ red dot glyph, trailing time in `failed` | same | same | same (Roboco palette lacks `dangerWash`: add token) |
| B9 | `CompanionsViewController.swift:263-269, 436` | **Unbound** agent (no `docId` yet): row not selectable, no chevron. | ❌ Roboco's `SideChatsAgent.docId` is non-null (its FFI always has one) | `SubagentRow.docId: String?` | `Z/ui/Companions.kt:376-378` | — |
| B10 | `CompanionsViewController.swift:94-98` | Side chat row = the Sessions list `SessionCell`. | ✅ `SideChatsSheet.kt:305-315` reuses `R/sessions` `SessionRow` | `SessionRow` | `Z/ui/Companions.kt:415-433` | — |
| B11 | `CompanionsViewController.swift:343-350` | Side chat trailing swipe → **Archive** (`archivebox`, secondary bg). | ❌ | `CoreClient.archiveSession` | `Z/ui/Companions.kt:311-314, 480-512` | Roboco `R/sessions/SwipeRow.kt` (already the iOS swipe clone); M3 `SwipeToDismissBox` restyled is the native alternative (no reveal-and-hold). Reuse `SwipeRow`. |
| B12 | `CompanionsViewController.swift:442-467` | Terminal row: `chip` tile + `apple.terminal`; title tab name; detail "folder · host"; trailing **Live** (green, also while connecting) / **Reconnecting** (secondary) / **Ended** (tertiary); chevron. | ❌ | `TerminalTab` (`TerminalSession.info()/phase()/title()`), `CoreClient.devices()` for host name | `Z/ui/Companions.kt:435-461` | Custom row. |
| B13 | `CompanionsViewController.swift:335-342` | Terminal trailing swipe → **Close** (destructive, `xmark`): ends the shell on the host. | ❌ | `TerminalSession.close()` (Kotlin: `end()`/`close()` per binding) | `Z/ui/Companions.kt:324-327` | `SwipeRow` destructive. |
| B14 | `CompanionsViewController.swift:158-163` | Opening from a chip scrolls to that section's header once loaded. | ❌ | — | `Z/ui/Companions.kt:258-271` | `LazyListState.animateScrollToItem`. |
| B15 | `CompanionsViewController.swift:131-136, 206-217` | Panel reloads ≤4/s from session, workspace and terminal-store changes. | ⚠️ `SideChatsModel.kt:158-214` conflated pulls off main (no terminal store) | as A6 | `Z/ui/Companions.kt:91-110` | — |
| B16 | `CompanionsViewController.swift:151-157` | On appear: `warmDirect(chatId)` (pair with the host so a terminal opens on the direct link); start the 1 s elapsed ticker; stop it on disappear. | ❌ | `CoreClient.warmDirect(chatId)` (`C/client_ffi/mod.rs:550`) | `Z/ui/TerminalScreen.kt:141` | `LaunchedEffect` / `LifecycleResumeEffect`. |
| B17 | `CompanionsViewController.swift:271-318`, `SessionViewController.swift:362-369` | Picking a row: in the sheet, dismiss then push on the session's stack (side chat via `openSideChat`); in the inspector, push inside the column. Haptic select. | ⚠️ `SideChatsSheet.kt:186-197` opens then dismisses; new/fork use `dismissThen` | — | `Z/ui/Companions.kt:189-195` | — |
| B18 | `I/Shell/SplitRootController.swift:22-27, 56-63, 196-280` | **Inspector column** on regular×regular (iPad, unfolded Duo): 400 pt preferred (340-480); toggled by the nav item; follows the current session; narrow windows (<1100 pt) hide the sidebar while it shows and restore it after. | ❌ `R/shell/SplitRoot.kt` is two-column only (sidebar + detail) | — | `Z/ui/TabletShell.kt:42-72` (`InspectorPage` stack) | `androidx.compose.material3.adaptive` `SupportingPaneScaffold` / `ListDetailPaneScaffold` + `WindowSizeClass` is the native option; restyle it flat (`Palette.background`, hairline) — or add a third slot to Roboco's custom `SplitRoot` `Layout` (more faithful, same code style). Recommend extending `SplitRoot`. |
| B19 | `SplitRootController.swift:293-322`, `CompanionsViewController.swift:140-149` | Folding (Duo) / window shrinking: inspector re-presents as a sheet with a **Close** button; unfolding restores the column. | ❌ | — | — | `WindowInfoTracker` (Jetpack WindowManager) folding features + size class; reuse the B1 sheet with a Close button. |
| B20 | `SessionViewController.swift:327-344` | `presentCompanion`: subagent / terminal / side chat open in the inspector when the split is expanded, else pushed; a side chat opened this way is `isCompanion` (never the split's current session). | ⚠️ always pushed on the session's `NavStack` | — | `Z/ui/TabletShell.kt` | — |

### C. Side chats (new / fork / close-unused-deletes)

| # | iOS (file:line) | Behaviour | Roboco | zeron core API | Z reference | Native-Android option |
|---|---|---|---|---|---|---|
| C1 | `I/App/AppModel.swift:615-625`, `CompanionsViewController.swift:293-297` | **New side chat** mints the chat immediately (`createSideChat(parent)` — host, folder, agent of the parent), records it as *fresh*, refreshes the workspace and opens the real session screen. Failure toast "Couldn't start a side chat". | ⚠️ `R/sidechats/SideChatDraft.kt` pushes a New-Session **draft** canvas; the chat is created at first send (`createChat(parentChatId)`) | `CoreClient.createSideChat(parentId): String` (sync) | `Z/core/AppModel.kt:629-631`, `Z/ui/Companions.kt:234-237` | — |
| C2 | `SessionViewController.swift:812` | From inside a side chat, "New Side Chat" makes a sibling under the root. | ✅ draft seeded from the parent row (`SideChatParent.of`) | pass `parentId ?: chatId` | `Z/ui/SessionScreen.kt:217` | — |
| C3 | `AppModel.swift:628-633`, `CompanionsViewController.swift:298-324` | **Fork**: async, success haptic, opens the fork; errors: HostUnavailable → "Its computer isn't reachable right now", Unsupported → "Its computer needs a newer Zeron to fork", else "Couldn't fork this chat". | ⚠️ `R/sidechats/SideChatFork.kt:14-32` — Roboco copy ("The host is unreachable (…)", "This engine doesn't support side chats…", host's own reason) | `CoreClient.forkSideChat(sourceId)` (suspend) | `Z/ui/Companions.kt:238-252, 334-338` | — |
| C4 | `CompanionsViewController.swift:299-306, 475` | One fork at a time; panel action row shows "Forking…" + spinner. | ⚠️ `SideChatFork.forking` disables the ⋯ row only | — | `Z/ui/Companions.kt:309` | — |
| C5 | `SessionViewController.swift:541-550` | **Close unused deletes**: tearing down a fresh side chat with no message sent, empty draft and no preview deletes it. | ⚠️ same outcome by construction (nothing exists until first send), different mechanism | `CoreClient.deleteSession(chatId)`; `SessionHandle` "unused" (`I/Session/CoreSessionSource.swift:60`) | `Z/ui/SessionScreen.kt:140-141` | — |
| C6 | `AppModel.swift:587-612, 436` | Fresh set persisted (`UserDefaults "freshSideChats"`); on workspace refresh, a fresh chat not open and empty for >60 s is deleted; one that got a message (any device) is forgotten. | ❌ (n/a with drafts; required once on `createSideChat`) | `sessionRow(chatId).preview / createdAtMs` | `Z/core/AppModel.kt:601-626` | `SharedPreferences`/DataStore string set. |
| C7 | `SessionViewController.swift:337-344` | A side chat opens beside (inspector) or above its parent. | ⚠️ pushed (phone-correct, no inspector) | — | `Z/ui/SessionScreen.kt:100` | — |
| C8 | `SessionViewController.swift:319-324` | A side chat's own screen titles itself "Side chat" with the parent's title as subtitle. | ❌ (only the draft has "Side chat · parent") | `SessionRow.parentChatId` | — | — |

### D. Subagent screen and transcript card

| # | iOS (file:line) | Behaviour | Roboco | zeron core API | Z reference | Native-Android option |
|---|---|---|---|---|---|---|
| D1 | `I/Companions/SubagentViewController.swift:26-35` | Pushed read-only screen; hides bottom bar; root chat = doc prefix before `--sub--`. | ✅ `R/sidechats/SubagentViewer.kt:124-155, 282-293` | — | `Z/ui/SubagentScreen.kt` | — |
| D2 | `SubagentViewController.swift:85-88` | Live transcript while running, frozen copy when done. | ✅ (Roboco engine's `WatchDocMessages`) | `TranscriptView.attachSubagent(client, chatId, docId, frozen)` | `Z/ui/SubagentScreen.kt` | — |
| D3 | `SubagentViewController.swift:57-66, 123-132` | 1.75× spinner until the first row; transcript fades in over 0.2 s. | ⚠️ `SubagentViewer.kt:501-523` reveal is an instant alpha switch | — | — | `animateFloatAsState`. |
| D4 | `SubagentViewController.swift:95-99, 134-138` | Nothing in 10 s → status bar "Waiting for its computer…" (idle dot). | ⚠️ 8 s → centred "Nothing from this subagent yet." | — | `Z/ui/SubagentScreen.kt` | — |
| D5 | `SubagentViewController.swift:42-44, 148` | Title = spawn title; subtitle "Subagent · <model · kind>". | ⚠️ `SubagentViewer.kt:95-102` "Running/Failed/Subagent · <parent title>" | `SubagentRow.model/kind` | — | — |
| D6 | `SubagentViewController.swift:45-51` | `doc.on.doc` bar button copies the transcript → toast "Transcript copied". | ⚠️ in the ⋯ menu (`subagentMenu`) | `TranscriptFrame.plainText()` | — | `GlassCircleButton` in the header. |
| D7 | `SubagentViewController.swift:67-83, 101-110, 140-152, 180-258` | 50 pt **glass status bar** at the bottom (≤560 pt wide): Working → trailer glyph + "Working · 1m 5s" ticking each second + detail model or "Runs on its own"; Finished → green ✓; Failed → red dot; transcript inset above it. State follows the spawn on the root chat. | ❌ | `SessionHandle.subagents()` on the root chat | `Z/ui/SubagentScreen.kt` (`SubagentStatusBar`) | Custom on `GlassSurface`. |
| D8 | `SubagentViewController.swift:56, 154-157` | Nested spawns push another subagent screen (parent = this doc). | ✅ `SubagentViewer.kt:476-487` | — | — | — |
| D9 | `SubagentViewController.swift:89-91` | Agent **visuals** inside a subagent transcript load from the root chat's host. | ❌ | `TranscriptView.setVisualSource(client, deviceId, pixelScale)` | `Z/ui/SubagentScreen.kt:110` | see F. |
| D10 | `SubagentViewController.swift:112-121` | Engine closes on pop / on off-screen removal. | ✅ `SubagentViewer.kt:228-244, 256-273` | `TranscriptView.close()` | — | — |
| D11 | `SubagentViewController.swift:160-178` | Model labels: `claude-haiku-5` → "Haiku 5", aliases capitalized, catalog `modelLabel`. | ⚠️ `SideChatsSheet.kt:382-384` capitalizes only | `modelLabel(harness, model)` | `Z/ui/SubagentScreen.kt` (`SubagentFormat`) | — |
| D12 | `I/Transcript/RowView.swift` (+ `.subagent` case), `I/Transcript/ToolViews.swift` `SubagentCardControl` | Spawn card = whole-card control: `controlFill` wash, spring in 0.12 s / out 0.28 s (damping 0.7), light impact, a11y "Subagent: <title>" value Working/Failed/Done, disabled without a doc. | ⚠️ `R/transcript/widgets/SubagentLink.kt` — instant wash, `Haptic.Select`, "Open subagent: <title>", no value | `WidgetKind.Subagent{docId?, title, running, failed}` (`C/layout/display.rs:275`) | `Z/transcript/Widgets.kt` | `Modifier.clickable` + `animateColorAsState(spring)`; semantics `stateDescription`. |

### E. Terminals

| # | iOS (file:line) | Behaviour | Roboco | zeron core API | Z reference | Native-Android option |
|---|---|---|---|---|---|---|
| E1 | `I/Companions/TerminalStore.swift:100-137` | Per-chat terminal store; open = shell on the chat's host in its folder; ordinal per chat. | ❌ | `CoreClient.openTerminal(chatId, cols, rows, TerminalListener)` | `Z/core/Terminals.kt:100-129` | — |
| E2 | `TerminalStore.swift:166-186` | Refs persisted per chat (id, device, cwd, shell, replies); first access after relaunch reattaches (80×24) and output replays. | ❌ | `CoreClient.attachTerminal(TerminalRef, cols, rows, listener)` | `Z/core/Terminals.kt:157-188` | `SharedPreferences` JSON (as Z) or DataStore. |
| E3 | `TerminalStore.swift:30-46` | Name: program title, else "zsh"/"zsh 2"…; folder home-abbreviated (`/Users/x`, `/home/x`, `/root` → `~`). | ❌ | `TerminalSession.title()/info()` | `Z/core/Terminals.kt:45-94` | — |
| E4 | `TerminalStore.swift:139-160` | Close ends the shell on the host; forget detaches; a `gone` terminal is forgotten automatically. | ❌ | `TerminalSession.close()` / `detach()` / `phase()` | `Z/core/Terminals.kt:131-155` | — |
| E5 | `TerminalStore.swift:193-198`, `AppModel.swift:253` | Sign-out detaches everything and clears the saved refs. | ❌ | `detach()` | `Z/core/Terminals.kt:190-195` | — |
| E6 | `TerminalStore.swift:161, 188-191`, `TerminalView.swift:70-82` | Palette follows light/dark. | ❌ | `TerminalSession.setDark(dark)` | `Z/core/Terminals.kt:107-111` | — |
| E7 | `TerminalStore.swift:86-88` | Bell → soft impact (0.6). | ❌ | `TerminalListener.bell()` | `Z/core/Terminals.kt:80-82` | `Haptic.Light` (`R/design/Feedback.kt`). |
| E8 | `TerminalStore.swift:17-19`, `TerminalViewController.swift:81-85`, commit `dfbb6876` | Direct WebRTC link: warm pairing as the screen slides in; route not shown (DEBUG probe only). Network-path change / foreground re-pair. | ❌ | `warmDirect(chatId)`, `networkPathChanged()`, `setNetworkOnline()`, `onForeground()/onBackground()`, `TerminalSession.isDirect()` | `Z/ui/TerminalScreen.kt:141` | `ConnectivityManager.NetworkCallback` → `networkPathChanged`; `ProcessLifecycleOwner` → foreground/background. |
| E9 | `SessionViewController.swift:373-376` | "Terminal" opens the first live tab, else a new one. | ❌ | — | `Z/ui/SessionScreen.kt:232` | — |
| E10 | `I/Companions/TerminalViewController.swift:21, 33-44, 181-197` | Screen: bg `#FAFAFB`/`#090909`; title = tab name, subtitle "folder · host"; **title menu** switches tabs (✓ current, "· ended") + New Terminal. | ❌ | — | `Z/ui/TerminalScreen.kt:209-241` | Roboco `SessionHeader`-style bar + `GlassMenu` anchored to the title. |
| E11 | `TerminalViewController.swift:38-43, 279-305` | Bar: **+** new terminal, **⋯**: Text Size (Smaller/Reset/Bigger, small inline), Add Output to Message, Copy All Output (toast "Copied"), Clear Screen (Ctrl-L), Close Terminal (destructive). | ❌ | `TerminalSession.text(all)`, `typeText("l", ctrl)` | `Z/ui/TerminalScreen.kt:242-269` | `GlassMenu` with an inline "small" row (Roboco `GlassButtonGroup`). |
| E12 | `TerminalViewController.swift:99-146, 199-242, 341-406` | Loader until the shell paints; glass status pill: Reconnecting… (spinner); exited "Process exited" / "Exited with code N" / "Ended by SIG" + **New Terminal**; gone "This terminal ended on its host"; open failure (red dot) "This computer needs a newer Zeron for terminals" / "The computer isn't reachable right now" / "Couldn't open a terminal" + **Try Again**. Exit resigns the keyboard. | ❌ | `TerminalPhase.{Connecting, Live, Reconnecting, Exited(code, signal), Gone}`; `CoreException.{Unsupported, HostUnavailable}` | `Z/ui/TerminalScreen.kt:301-327, 362-390` | `GlassSurface` capsule. |
| E13 | `TerminalViewController.swift:56-59, 87-95, 244-251` | Phone: keyboard comes up with the terminal; beside other columns it waits. 48 pt glass keyboard button bottom-trailing while the keyboard is down (spring scale 0.6↔1). | ❌ | — | `Z/ui/TerminalScreen.kt:328-341` | M3 `FloatingActionButton` restyled = `GlassCircleButton` 48 dp. Use `GlassCircleButton`. |
| E14 | `TerminalViewController.swift:307-323` | Close current → show next tab, else leave (pop or close inspector). | ❌ | — | `Z/ui/TerminalScreen.kt:194-199` | — |
| E15 | `TerminalViewController.swift:325-338`, `SessionViewController.swift:273-278` | Add to message: trimmed text as a fenced block, appended to the live composer or saved into the chat's draft; success haptic + toast "Added to message". | ❌ | — | `Z/ui/TerminalScreen.kt:186-192` | Roboco `Drafts` (`R/composer/Drafts.kt`) + live composer controller. |
| E16 | `TerminalViewController.swift:148-153`, `TerminalView.swift:163-189` | Grid: guessed before layout (55 % height), then from bounds; resize debounced 80 ms (first immediate); ≥20 cols, ≥4 rows. | ❌ | `TerminalSession.resize(cols, rows)` | `Z/terminal/TerminalView.kt:147-174` | `onSizeChanged` + `postDelayed`. |
| E17 | `TerminalView.swift:8-10, 86-132` | **Scrollback**: native scroll physics, content height = history; pixel-precise position incl. row fraction; follows the bottom unless dragged. | ❌ | `TerminalFrame{history, displayOffset, scrollFrac, above}`, `scrollToPosition(back)` | `Z/terminal/TerminalView.kt:176-201` | `OverScroller` fling (native) + `EdgeEffect` stretch instead of iOS rubber band, or Roboco `R/design/RubberBand.kt` for parity. |
| E18 | `TerminalView.swift:101-151, 267-273` | Alt screen / mouse-tracking programs own the wheel: one-finger drag → wheel rows (fractional); tap → click. | ❌ | `TerminalFrame.altScreen/mouse`, `wheel(rows, row, col)`, `click(row, col)` | `Z/terminal/TerminalView.kt:231-241, 222-226` | — |
| E19 | `TerminalView.swift:191-257` | Cursor: block/beam/underline/hollow (hollow when unfocused), accent tint, blink 1.1 s cycle, rests after 15 s idle, none under Reduce Motion or when scrolled back; typing restarts in the visible phase. | ❌ | `TerminalFrame.cursor{row, col, shape}`, `cursorBlinks` | `Z/terminal/TerminalView.kt:505-560` | — |
| E20 | `TerminalView.swift:261-340` | **Selection**: long-press 0.35 s selects a word, drag extends, release shows edit menu **Copy / Add to Message / Paste**; copy flashes the wash 0.18 s; tap clears. | ❌ | `select(row, col, word)`, `extendSelection`, `selection()`, `clearSelection()` | `Z/terminal/TerminalView.kt:250-258, 323-384` | **Native**: `ActionMode.TYPE_FLOATING` (system floating text toolbar) — the Android equivalent of `UIEditMenuInteraction`; recommended over a glass clone. |
| E21 | `TerminalView.swift:26-31, 342-366` | **Pinch** font 8–22 pt in 0.5 steps, selection haptic per step, rigid haptic at the bounds; persisted; default 12 (13.5 iPad). | ❌ | — | `Z/terminal/TerminalView.kt:260-321` | `ScaleGestureDetector`. |
| E22 | `TerminalView.swift:368-444` | **IME**: plain keys, no autocorrect/caps/smart punctuation/predictions, ASCII keyboard; `insertText`/`deleteBackward`; key bar is the `inputAccessoryView`; focus state to the core. | ❌ | `typeText(text, KeyModifiers)`, `press(TerminalKey, mods)`, `paste`, `setFocused` | `Z/terminal/TerminalView.kt:388-463` (`BaseInputConnection`, `VISIBLE_PASSWORD|NO_SUGGESTIONS`, `IME_FLAG_NO_EXTRACT_UI`) | Native `onCreateInputConnection` (keep Z's approach; avoid `TYPE_NULL`, it breaks Gboard/Samsung). |
| E23 | `TerminalView.swift:446-491` | Hardware keys: arrows, Esc, Home/End, PgUp/PgDn, forward delete, Tab; Ctrl/Alt(+Shift) combos; ⌘ passes through. | ❌ | `TerminalKey.*` | `Z/terminal/TerminalView.kt:465-503` (+ Insert, F1-F12) | `onKeyDown`. |
| E24 | `TerminalViewController.swift:265-277` | ⌘T new, ⌘K clear, ⌘+/⌘− size. | ❌ | — | — | `onPreviewKeyEvent` (Ctrl). |
| E25 | `I/Companions/TerminalKeyBar.swift:36-102` | **Key bar**: 52 pt above the keyboard, horizontally scrolling 38 pt glass capsules (≥44 wide, 6 pt gaps, 8 pt insets), soft fade mask at both edges, hide-keyboard key pinned trailing. | ❌ | — | `Z/ui/TerminalScreen.kt:343-355, 391-533` | Custom row riding `WindowInsets.ime` (Roboco already tracks IME animation in `R/transcript/ImeDragSession.kt`/`InteractiveImeDismiss.kt`); `FadingHorizontalScroll` (`R/design/FadingHorizontalScroll.kt`) for the edge fade. No native widget fits. |
| E26 | `TerminalKeyBar.swift:53-72` | Keys: esc · ctrl · tab · ↑ ↓ ← → · alt │ ~ / - \| * $ \` │ paste; trailing keyboard-down. Mono Medium 14 titles. | ❌ | `TerminalKey.{Escape, Tab, Up, Down, Left, Right}` | `Z/ui/TerminalScreen.kt:420-450` | — |
| E27 | `TerminalKeyBar.swift:17-27, 109-145` | **Ctrl/Alt latch**: off → once (prominent glass, accent 75 %) → locked (accent) → off; one-shot released after the next key; a11y value On/Locked; selection haptic. | ❌ | `KeyModifiers(ctrl, alt, shift)` | `Z/ui/TerminalScreen.kt:93, 135-136, 286-290, 410-414, 454-462` | `ProminentGlassButton` / `GlassTextCapsule` tints. |
| E28 | `TerminalKeyBar.swift:173-190` | Arrows fire on touch-down, repeat after 0.38 s every 60 ms until release/cancel/drag-exit. | ❌ | — | `Z/ui/TerminalScreen.kt:493-528` | `awaitEachGesture` + coroutine repeat. |
| E29 | `TerminalKeyBar.swift:104-107, 204-206` | Keyboard input clicks on key-bar taps. | ❌ | — | `HapticFeedbackConstants.CLOCK_TICK` | `View.playSoundEffect(SoundEffectConstants.CLICK)` + `Haptic.Select`. |
| E30 | `TerminalView.swift:494-646` | **Rendering**: Geist Mono (regular/semibold/italic), exact advance as cell width, line = natural × 1.22 pixel-rounded; run bg, selection wash (accent 28 %+30 % boost), fg/bold/italic/underline; **predicted echo** faint underline; wide/CJK/emoji centred per cell; VS15 for one-cell emoji-capable symbols. | ❌ | `TerminalFrame.lines[].runs[]{col, cells, text, fg, bg, bold, italic, underline, selected, predicted, wide}` | `Z/terminal/TerminalView.kt:42-62, 560-668` | Android `Canvas` in a custom `View` (via `AndroidView`); Roboco `Face.Mono*` + `R/transcript/MonoWide.kt`. |
| E31 | `I/Companions/TerminalGlyphs.swift:1-198` | Box drawing U+2500–257F, blocks U+2580–259F and `⏺` painted into cells, pixel-snapped. | ❌ | — | `Z/terminal/TerminalGlyphs.kt` | Port as-is. |
| E32 | `TerminalView.swift:65-67, 93` | a11y: direct-interaction element; value = visible text while a screen reader runs. | ❌ | `text(all=false)` | `Z/terminal/TerminalView.kt:107` | `AccessibilityNodeInfo` text / Compose `semantics { text }`. |

### F. Agent visuals (`show_visual`)

| # | iOS (file:line) | Behaviour | Roboco | zeron core API | Z reference | Native-Android option |
|---|---|---|---|---|---|---|
| F1 | `I/Transcript/RowView.swift` (`.visual(id)` case), `I/Transcript/TranscriptListView.swift:731-741` | `WidgetKind.Visual{id}` slot hosts a live `VisualView`. | ❌ (`R/transcript/RowWidget.kt` has no Visual case; Roboco core has none) | `TranscriptView.visual(id): VisualSession?` | `Z/transcript/Visual.kt:140-149` | Compose (`Box` + `Canvas`), as Z. |
| F2 | `I/Session/CoreSessionSource.swift:50-54` | Scripts load from the chat's host; `pixelScale = max(2, displayScale)`; re-set when the host changes. | ❌ | `TranscriptView.setVisualSource(client, deviceId, pixelScale)` | `Z/ui/SessionScreen.kt:128`, `Z/transcript/TranscriptState.kt:231` | — |
| F3 | `TranscriptListView.swift:93-95, 731-741`, `RowView.swift` `layoutWidgets` | One live view per visual id, moved between reused rows (never torn down by a later reuse). | ❌ | — | `Z/transcript/Visual.kt:145-149` (`remember(id)`) | Keyed `movableContentOf` per id if Roboco rows are recycled. |
| F4 | `I/Transcript/VisualView.swift:19-75`, `RowView.swift` `isHidden` | Frames flow only while in a window, the row is visible (pooled rows pause) and the app is foreground. | ❌ | `VisualSession.attach(listener, dark, reduceMotion)` / `detach()` | `Z/transcript/Visual.kt:97-113` (lifecycle STARTED only) | `LifecycleEventObserver` + Roboco row realization (`R/transcript/RealizedRows.kt`). Z misses the row-visible gate — add it. |
| F5 | `VisualView.swift:71, 77-82` | Appearance and Reduce Motion at attach; `setAppearance` on flips. | ❌ | `setAppearance(dark)` | `Z/transcript/Visual.kt:114-121` | Animator scale 0 = reduce motion (`R/design/ReduceMotion.kt`). |
| F6 | `VisualView.swift:85-131` | Sizing: the core lays out a `VisualDisplay` (points) inside the row rect; base paint top-left at contentsScale; surfaces and controls positioned from it; later controls above earlier. | ❌ | `VisualDisplay{width, height, paint, surfaces[], controls[], styles}` | `Z/transcript/Visual.kt:123-137` | — |
| F7 | `VisualView.swift:147-303` | **Rasterization** off the main thread: rounded rects, polylines (fill/stroke/dash), text runs with per-style fonts and ligatures → bitmap; only the latest display/frame per surface is posted to main. | ❌ | `VisualListener.display/frame`, `VisualPaint{shapes, runs, text}` | `Z/transcript/Visual.kt:337-433` (`VisualSink`, `VisualPainter`) | `android.graphics.Canvas` into `Bitmap`; Roboco `R/transcript/StyleFonts.kt` for runs. Consider `HardwareRenderer`/`RenderNode` later. |
| F8 | `VisualView.swift:174-188, 220-229, 305-343` | Surfaces (canvas, chart, scene, shader): worker BGRA image under a vector-paint layer, scaled to the surface frame. | ❌ | `SurfaceFrame{surface, image: FrameImage(bgra), paint}` | `Z/transcript/Visual.kt:151-235, 397-421` (R/B swap) | — |
| F9 | `VisualView.swift:345-407` | **Touch forwarding**: `none` / `tap` / `drag` (tracks from touch-down, never scrolls the transcript) / `horizontalDrag` (mostly-horizontal scrubs, vertical scrolls the transcript). | ❌ | `SurfaceGesture`, `VisualSession.pointer(surface, PointerPhase, x, y)` (points) | `Z/transcript/Visual.kt:155-235` | `pointerInput`; for `drag` consume from down; for `horizontalDrag` `detectHorizontalDragGestures` + Roboco scroller nested-scroll rules. |
| F10 | `VisualView.swift:385-396` | Tapping a chart shows its readout for 2.5 s (down, then up later). | ❌ | same | `Z/transcript/Visual.kt` (`readout` job) | — |
| F11 | `VisualView.swift:410-498` | Controls over painted art: button/tap/tab/toggle/slider/link; pressed wash (`controlFill`, r 6/8); ≥44 pt hit height; tab/toggle selection haptic; links via the transcript's link router. | ❌ | `VisualControl{id, kind: ControlKind, x,y,w,h, label}`, `activate(control)` | `Z/transcript/Visual.kt:238-326` | Transparent Compose targets with `Modifier.minimumInteractiveComponentSize()`; links through Roboco `R/transcript/LinkRouter.kt`. |
| F12 | `VisualView.swift:500-524` | Slider tracks the finger along its track; a11y increment/decrement by step (or 10 %). | ❌ | `slide(control, fraction)`, `ControlKind.Slider{min,max,step,fraction,trackX,trackW,shown}` | `Z/transcript/Visual.kt:268-300` | Compose `progressBarRangeInfo` + `setProgress` semantics (native a11y slider), art stays core-painted. |
| F13 | `VisualView.swift:31-32, 141-144, 433-451` | a11y: visual labelled by title; controls in reading order with traits/values. | ❌ | `VisualSession.title()` | `Z/transcript/Visual.kt:124, 255-277` | Compose semantics + `traversalIndex`. |
| F14 | `zeron/crates/visual-gpu/src/lib.rs:100-125`, `C/visual/mod.rs:1135-1295` | Shaders / 3D scenes rendered **offscreen** by wgpu (no window surface); scenes fall back to the software renderer without a GPU; a shader without a GPU shows its error. | ❌ | (internal) | — | Android needs a **Vulkan** adapter (`Backends::PRIMARY` excludes GLES); no `SurfaceView` needed since frames come back as BGRA. |

### G. Voice / orb / Live Activity

| # | iOS (file:line) | Behaviour | Roboco | zeron core API | Z reference | Native-Android option |
|---|---|---|---|---|---|---|
| G1 | `I/Voice/RemoteVoiceController.swift:10-14, 51-61` | Voice offered once any registered execution host advertises the capability (or a call is live); dev override flag. | ❌ (dropped, ADR 0009) | `voiceHostCapability()`, `DeviceView.capabilities/isExecutionHost/online`, `CoreClient.devices()` | `Z/voice/VoiceController.kt`, `Z/voice/VoiceControls.kt:88-98` | — |
| G2 | `I/Shell/MainTabController.swift:54-92` | Phone bottom bar: `waveform` item between Search and New session; tap → open live call / start / host picker; long-press → voice menu; live → `waveform.circle.fill` accent, no menu. | ❌ `R/shell/SessionsBottomBar.kt` (voice item explicitly dropped) | — | `Z/voice/VoiceControls.kt:100-142` | `GlassCircleButton` + long-press `GlassMenu`. |
| G3 | `I/Voice/VoiceControls.swift:149-205`, `SessionViewController.swift:61-81` | Session nav bar: waveform (idle) / live orb (avatar preset, 32 pt) after the companions item; tap opens/starts; long-press menu only when idle. | ❌ | `OrbRenderer` | `Z/voice/VoiceControls.kt:205-232` | `GlassCircleButton` hosting the orb canvas. |
| G4 | `I/Shell/SplitRootController.swift:429-441` | Tablet sidebar toolbar voice item beside Compose. | ❌ | — | `Z/ui/TabletShell.kt` | `R/shell/SidebarToolbar.kt`. |
| G5 | `I/Voice/VoiceStageViewController.swift:376-394` | Host picker action sheet "Talk to Codex on…" (copy for none / some hosts), Voice Settings, Cancel. | ❌ | `VoiceController.hosts` (app) | `Z/voice/VoiceControls.kt:264-309` | Roboco `ActionSheet` (`R/design/ActionSheet.kt`); M3 `ModalBottomSheet` restyled is the native alternative. |
| G6 | `VoiceStageViewController.swift:405-427` | Voice menu: "Codex runs on" (hosts, ✓ selected, disabled during a call), "Voice" submenu (Codex default + host styles), Voice Settings. | ❌ | `defaultVoiceStyles()`, `VoiceCallState.voices` | `Z/voice/VoiceControls.kt:234-262` | `GlassMenu` with submenu. |
| G7 | `RemoteVoiceController.swift:63-80` | Prefs per user+org: selected host (locked during a call) and style; plain tap calls the chosen host or the only one online. | ❌ | `CoreClient.userId()/orgId()` | `Z/voice/VoiceController.kt` | `SharedPreferences`. |
| G8 | `RemoteVoiceController.swift:91-118` | Start: new generation, `startVoice`, keep screen awake, medium haptic, **start sound** as the call is placed. | ❌ | `CoreClient.startVoice(hostDeviceId, voice, VoiceMediaListener, VoiceSessionListener): VoiceCall` | `Z/voice/VoiceController.kt` | `View.keepScreenOn` / `FLAG_KEEP_SCREEN_ON`; `SoundPool` (usage `VOICE_COMMUNICATION_SIGNALLING`). |
| G9 | `I/Voice/NativeVoiceMedia.swift:1-82`, `I/Voice/CodexVoicePeer.swift:28-147` | Media bridge: Prepare (mic permission, audio session), Offer (gathered SDP ≤64 KB, `oai-events` ordered channel), ApplyAnswer (wait connected + channel open ≤25 s), SetMuted (first one activates audio), Levels (stats `audioLevel` → u16; skipped in background), Close; ≤4 ops in flight; `permissionDenied`/`unavailable`. | ❌ | `VoiceMediaListener.onRequest(VoiceMediaRequest)`, `VoiceCall.completeMedia(requestId, failure, sdp, microphone, speaker)` | `Z/voice/VoiceMedia.kt`, `Z/voice/VoicePeer.kt` | `org.webrtc` (`io.github.webrtc-sdk:android`). |
| G10 | `CodexVoicePeer.swift:32-45, 61-72, 114-132` | Routing like a phone call: voice-chat mode, Bluetooth HFP, loudspeaker in hand, earpiece at the ear, any headset/BT/car wins; an interruption (phone call, Siri, alarm) **ends** the call. | ❌ | — | `Z/voice/VoicePeer.kt:198-277` | `AudioManager.MODE_IN_COMMUNICATION`, `setCommunicationDevice` (API 31+), `AudioDeviceCallback`, `AudioFocusRequest` loss → end. |
| G11 | `RemoteVoiceController.swift:204-216` | Proximity monitoring once active: screen off and earpiece at the ear. | ❌ | — | `Z/voice/VoiceController.kt:467-480, 653-680` | `PROXIMITY_SCREEN_OFF_WAKE_LOCK` + `Sensor.TYPE_PROXIMITY`. |
| G12 | `RemoteVoiceController.swift:120-127`, `NativeVoiceMedia.swift:63-67` | Mute: local track instantly, then the core; light haptic. | ❌ | `VoiceCall.setMuted(muted)` | `Z/voice/VoiceController.kt` | — |
| G13 | `RemoteVoiceController.swift:129-174` | End: rigid haptic; close media, stop call; **end sound** only if it had connected or the user hung up; error haptic on failure. | ❌ | `VoiceCall.stop()` | `Z/voice/VoiceController.kt` | — |
| G14 | `RemoteVoiceController.swift:242-257` | End-reason copy (8): mic denied, audio closed, sign in on host, usage unavailable, busy, unreachable, update host, dropped. | ❌ | `VoiceEndReason` | `Z/voice/VoiceController.kt` | — |
| G15 | `RemoteVoiceController.swift:159-171` | Failure while the stage isn't showing → toast with **Settings** (mic denied) or **Retry** (start + present stage). | ❌ | — | `Z/voice/VoiceHost.kt` (snackbar) | Roboco `Toast` with action (`R/design/Toast.kt:121`); M3 `Snackbar` is the native alternative. |
| G16 | `I/Voice/OrbView.swift:1-131` | Orb: core renderer frames (lines then disks), 30 fps while visible, paused hidden/background (clock stops), Reduce Motion static, monochrome ink mirrored in dark; presets inline/avatar/large/hero. | ❌ | `OrbRenderer(preset, orb)`, `setOrb`, `setAudioLevels`, `nextFrame(animating, reducedMotion): OrbFrame{size, lines, dots}` (`C/orb.rs`) | `Z/voice/VoiceOrbView.kt` | Compose `Canvas` + `withFrameNanos` throttled to 30 fps. |
| G17 | `C/orb.rs:39-60`, `RemoteVoiceController.swift:42, 228-240` | Orb states: idle, connecting, listening, speaking, working, awaitingInput, muted (with status words). | ❌ | `VoiceOrb` | `Z/voice/VoiceOrbView.kt` | — |
| G18 | `VoiceStageViewController.swift:61-196` | **Stage**: full screen; wallpaper over the top 72 % (≤760 pt); 340 pt hero orb centred −64; header dot (tertiary idle / warning awaiting / tertiary muted / success) + mono clock "–:––" + host. | ❌ | — | `Z/voice/VoiceStageScreen.kt:98-310` | Full-screen route; `R/design/WallpaperHero.kt` for the backdrop. |
| G19 | `RemoteVoiceController.swift:228-240` | Status line: "Calling host…", "Connecting to host…", "Ending…", Speaking, "Codex needs your answer", "Working on it…", Muted, Listening. | ❌ | `VoiceCallState.phase/orb` | same | — |
| G20 | `VoiceStageViewController.swift:18-26, 226-234, 264-293` | **Captions**: tail of the utterance (≤160 chars, 4 lines, truncate head), newest words veil in via core spans, previous turn fades out; user tertiary vs assistant secondary; repaint per frame only while animating. | ❌ | `CaptionFader(maxChars).frame(item, text, reducedMotion): CaptionFrame{text, spans, previous, previousAlpha, animating}` | `Z/voice/VoiceStageScreen.kt:313-360` | `AnnotatedString` + `withFrameNanos`. |
| G21 | `VoiceStageViewController.swift:36-50, 235-247, 297-348` | Glass control bar: **mute** 60 pt (inverted plate while muted), **audio-output** route picker well, **end** 88×60 red; ended: "Call again" (or "Open Settings") + "Close". | ❌ | — | `Z/voice/VoiceStageScreen.kt:240-300, 362-390` | No system route picker for call audio on Android: speaker/earpiece toggle (Z) or a `GlassMenu` of `AudioManager.availableCommunicationDevices`. |
| G22 | `VoiceStageViewController.swift:27-33, 102-115, 179-181, 250-254` | Minimize (chevron.down) or swipe down; call continues. Transcript button opens the orchestrator chat; warning badge while Codex awaits input. | ❌ | `VoiceCallState.chatId/work` | `Z/voice/VoiceStageScreen.kt:130, 156-200` | Predictive back (`PredictiveBackHandler`) + vertical drag dismiss. |
| G23 | `VoiceStageViewController.swift:198-204, 365-374` | Zoom transition out of the tapped bar control; stage dismisses itself when the call ends elsewhere. | ❌ | — | — | `SharedTransitionLayout`/`sharedBounds`, or Roboco's morph helpers (`R/shell/SearchMorph.kt`). |
| G24 | `I/Voice/VoiceViewController.swift:1-251` | **Voice settings**: hero (large orb, "Talk with Codex", one-liner); "Codex runs on" hosts (platform icon, Ready/Offline/"Update Zeron and enable remote voice", status dot, ✓ selected, disabled during a call) + footers; "Voice" value row with popup; "During a call" tips (ear, lock, bottom bar). | ❌ | `devices()`, `voiceHostCapability()` | `Z/voice/VoiceSettingsScreen.kt` | Roboco `GroupedList` (`R/design/GroupedList.kt`); M3 `ListItem` restyled is the native alternative. Use `GroupedList`. |
| G25 | `I/Shell/MoreViewController.swift:213-215, 256` | Settings gets a "Voice" row (subtitle = style) when voice is available. | ❌ | — | `Z/ui/SettingsScreen.kt` | Roboco `R/settings/SettingsScreen.kt` row. |
| G26 | `IL/VoiceLiveActivity.swift:11-110`, `IS/VoiceActivity.swift` | **Live Activity**: Lock Screen (orb glyph, "Codex · host", status, clock, mute+end) and Dynamic Island (compact orb + clock or mic-slash; expanded status, "Codex on host", labelled Mute/End); tap → stage. | ❌ | — | `Z/voice/VoiceCallService.kt` (CallStyle notification) | **Android equivalent**: microphone foreground service + `NotificationCompat.CallStyle.forOngoingCall` (host, chronometer, Mute/End actions); on Android 16 eligible for the status-bar chip / promoted ongoing (Roboco already uses `setRequestPromotedOngoing` in `R/notify/Notifier.kt`). |
| G27 | `I/Voice/VoiceActivityController.swift:17-90` | Updates ≤1/s, mute/end/going-live immediately; stale activities ended at launch. | ❌ | — | `Z/voice/VoiceCallService.kt` | Throttle `notify()` the same way; FGS dies with the process (nothing stale), cancel on start anyway. |
| G28 | `IS/VoiceActivity.swift:39-69` | Mute/End buttons run in-process (App Intents). | ❌ | — | `Z/voice/VoiceCallService.kt:164-170` | `PendingIntent.getService` (immutable) → service → controller. |
| G29 | `I/App/SceneDelegate.swift:183-188` | `zeron://voice` returns to the stage when a call is live. | ❌ (Roboco filter has no `voice` host) | — | `Z/MainActivity.kt:92-94` (`VoiceController.opensStage`) + manifest `<data android:host="voice"/>` | `roboco://voice` (or the scheme chosen) intent filter. |
| G30 | `CodexVoicePeer.swift:5-11` | Call survives screen lock (background audio). | ❌ | — | `Z/voice/VoiceCallService.kt` | FGS `microphone` (started while in foreground, after the mic grant). |
| G31 | `RemoteVoiceController.swift:259-292`, `SceneDelegate.swift:59-63` | DEBUG `-voice-preview` scripted call (screenshots). | ❌ | — | `Z/voice/VoiceController.kt` (`previewing`) | Debug launch extra (Roboco `R/shell/LaunchOptions.kt`). |

### Counts

| | ✅ | ⚠️ | ❌ | rows |
|---|---|---|---|---|
| A entry points / strip | 0 | 2 | 7 | 9 |
| B panel / inspector | 1 | 10 | 9 | 20 |
| C side chats | 1 | 5 | 2 | 8 |
| D subagents | 4 | 6 | 2 | 12 |
| E terminals | 0 | 0 | 32 | 32 |
| F visuals | 0 | 0 | 14 | 14 |
| G voice | 0 | 0 | 31 | 31 |
| **total** | **6** | **23** | **97** | **126** |

---

## 2. Porting recipes

General rule for every recipe: lift **logic** from `Z/` (it is wired to the real `uniffi.zeron_core`
API and mirrors iOS line by line), rebuild **UI** on Roboco's design system
(`R/design/`: `GlassSurface`, `GlassCircleButton`, `GlassTextCapsule`, `ProminentGlassButton`,
`GlassMenu`, `GlassSheet`, `StatusGlyph`, `FadingText`, `Toast`, `GroupedList`, `WallpaperHero`,
`Palette`/`TypeScale`/`Face`, `Haptic`), and drop Z's Material 3 Expressive look
(`SegmentedListItem`, `LoadingIndicator`, tonal buttons, `ModalBottomSheet`).
These recipes assume the core switch (`uniffi.roboco_core` → `uniffi.zeron_core`) covered by the
core-migration doc; until then none of E/F/G can be wired.

### 2.1 Companion strip + companions panel (A1-A9, B1-B17)

1. **Model.** Replace `R/sidechats/SideChatsModel.kt`'s `SideChatsUi(running, chats, finished)` with a
   `Companions(agents: List<SubagentRow>, sideChats: List<SessionRow>, terminals: List<TerminalTab>)`
   (lift `Z/ui/Companions.kt:79-110`). Keep Roboco's off-main pull + `SessionEventHub` conflation, add
   the terminal store's `changes` flow as a third trigger and the 250 ms settle. `sideChats` empty when
   the chat has `parentChatId`.
2. **Strip.** New `R/companions/CompanionStrip.kt`: three `AnimatedVisibility` chips in a `Row`
   (6 dp gap). Chip = `GlassSurface` capsule 30 dp, 11 dp padding, `StatusGlyph(Spinner)` 12 dp or a 15 dp
   template icon, `Face.SansMedium` 13 (`text` when spinning else `secondary`), optional 7 dp
   `palette.done` dot, pressed alpha 0.7, `Haptic.Select`. Mount it in `StatusPillRow`
   (`R/session/StatusPill.kt:252`) in place of the `Spacer`'s trailing side, and make the row's
   visibility `banner != None || !companions.isEmpty` (iOS `SessionViewController.swift:773-782`).
3. **Nav item.** Replace `SideChatsButton` with an always-visible `GlassCircleButton(RobocoIcons.SquareStack3dUp)`
   + accent count badge while `runningAgents > 0` (white ink). Order: ⋯, companions, voice.
4. **Panel.** Rewrite `SideChatsSheet.kt` as `CompanionsPanel(inSheet: Boolean)`:
   keep `GlassSheet([Medium, Large], grabber)`; header = chat title + subtitle
   ("Agents, side chats & terminals" / "N agent(s) working"), trailing Close (`Xmark`) instead of ✓,
   no leading +. Sections per B2/B3 (Agents only if any; Side chats/Terminals always, header + glass
   28 dp button; side chats + opens `GlassMenu` New Side Chat / Fork This Chat). Rows per B4/B6-B9/B12
   (`CompanionCell` geometry: 62 dp × TypeScale, 30 dp r8 tile, 20 dp leading, 12 dp gap, Medium 16 /
   Sans 13.5 / Medium 13 trailing, chevron 12 dp tertiary; action rows 48 dp with 26 dp `accentSoft`
   tile). Elapsed ticker: `produceState` 1 s while composed. Swipes: reuse `R/sessions/SwipeRow.kt`
   (Archive on side chats, destructive Close on terminals). `initialSection` →
   `LazyListState.animateScrollToItem` after first load. On appear call `client.warmDirect(chatId)`.
   Add palette token `dangerWash`.
5. **Menu.** `R/session/SessionMenu.kt`: add the **Terminal** row (icon to add: `apple.terminal`
   equivalent), rename to "Fork Into Side Chat", keep the inline section.
6. **Shortcuts.** Ctrl+J / Ctrl+Alt+0 in the session's `onPreviewKeyEvent` and `R/shell/ShellShortcuts.kt`.

### 2.2 Inspector column (B18-B20, C7)

1. Extend `R/shell/SplitRoot.kt`'s custom `Layout` with a trailing inspector slot (400 dp preferred,
   clamp 340-480, hairline on its leading edge, flat `Palette.background`), shown only when the window
   is expanded width (and tall enough). The inspector has its own `NavStack`
   (`Z/ui/TabletShell.kt:42-72` `InspectorPage` stack is the logic to lift: Companions(section) /
   Terminal / Subagent / SideChat).
2. When `width < 1100 dp` and the sidebar is shown, remember its mode, hide it while the inspector shows,
   restore on close (iOS `SplitRootController.swift:264-272`).
3. Follow the current session (`followInspector`): reset the inspector stack to the new chat's panel.
4. `presentCompanion` in the session route: expanded split → push into the inspector; else push on the
   session stack. Side chats opened there are flagged companions (they must not become the split's
   current session).
5. Fold/unfold: observe `WindowInfoTracker.windowLayoutInfo` + size class; on collapse re-present the
   inspector content as the B1 sheet with a Close button; on expand move it back.
   Native alternative: `SupportingPaneScaffold` (material3-adaptive) handles 3-pane + folds, but its
   pane motion and chrome are Material; restyling is heavier than adding one slot to `SplitRoot`.

### 2.3 Side chats on the zeron core (C1-C8)

1. Replace `SideChatDraftRoute` with `createSideChat(parentId ?: chatId)` (sync FFI, call off main),
   record the id in a persisted `freshSideChats` set, refresh the workspace, open the real session
   (`Z/core/AppModel.kt:601-631`). Toast "Couldn't start a side chat" on failure.
2. Session teardown (`R/session/SessionNavLifecycle.kt` / route release): if fresh ∧ unused ∧ empty
   draft ∧ no preview → `forgetFresh` + `deleteSession` (`Z/ui/SessionScreen.kt:140-141`).
3. Workspace refresh: sweep fresh chats not open and >60 s old (`AppModel.swift:599-612`).
4. Fork: keep `SideChatFork` (one at a time, app scope) but swap the error copy to iOS's three strings
   and add the "Forking…" toast for the menu path; panel action row shows "Forking…" + spinner.
5. Side chat screen header: title "Side chat", subtitle parent title.

### 2.4 Subagent screen deltas (D3-D7, D9, D11-D12)

- Port `SubagentStatusBar` (`Z/ui/SubagentScreen.kt`) onto `GlassSurface` (50 dp, ≤560 dp, 18 dp insets,
  12 dp `StatusGlyph` Trailer/Check/Dot, Medium 14 + Sans 13 trailing) and inset the transcript above it.
- Subtitle "Subagent · <SubagentFormat.model(model) · kind>"; lift `SubagentFormat` (incl. the
  `claude-haiku-5` → "Haiku 5" fallback using `modelLabel`).
- 10 s "Waiting for its computer…" in the bar (drop the centred text); 0.2 s reveal fade.
- Copy button in the header (toast "Transcript copied").
- `transcript.setVisualSource(client, rootChatHost, max(2f, density))`.
- `SubagentLink.kt`: spring wash (in 120 ms critically damped, out 280 ms damping 0.7), light haptic,
  label "Subagent: <title>", `stateDescription` Working/Failed/Done, disabled when `docId == null`.

### 2.5 Terminals (E1-E32)

Files to lift almost verbatim (logic and drawing; only chrome is restyled):

| Lift from `Z/` | Into Roboco | Restyle |
|---|---|---|
| `core/Terminals.kt` (`TerminalTab`, `TerminalStore`) | `R/terminal/TerminalStore.kt`, owned by the app (sign-out hook) | none (no UI). Add bell → `Haptic.Light`. |
| `terminal/TerminalView.kt` (custom `View`: metrics, painting, `OverScroller`, `GestureDetector`, `ScaleGestureDetector`, floating `ActionMode`, `BaseInputConnection`, `onKeyDown`, blink) | `R/terminal/TerminalView.kt` via `AndroidView` | Fonts → `Face.Mono/MonoSemibold/MonoItalic` assets; colours from `Palette` (`text`, `accent`); add Ctrl+T/K/±; Z already has the blink rest and the animator-scale check — route the latter through `R/design/ReduceMotion.kt` and confirm the rest is iOS's 15 s. |
| `terminal/TerminalGlyphs.kt` | `R/terminal/TerminalGlyphs.kt` | none. |
| `ui/TerminalScreen.kt` (`TerminalScreen`, `TerminalKeyBar`, `Latch`, repeating keys, status pill) | `R/terminal/TerminalRoute.kt` (a `NavRoute`, `hidesBottomChrome`) | Header → Roboco `SessionHeader`-style bar (back, title+subtitle with a tab-switch `GlassMenu`, + and ⋯ `GlassCircleButton`s); key bar → `GlassTextCapsule`/`ProminentGlassButton` keys 38 dp in a `FadingHorizontalScroll`, dismiss key pinned; status → `GlassSurface` pill 40 dp; keyboard button → `GlassCircleButton` 48 dp; loader → `StatusGlyph(Spinner, scale 1.75)`. Menus → `GlassMenu` (text-size row as a small inline `GlassButtonGroup`). |

Android-specific:
- **IME**: keep `VISIBLE_PASSWORD | NO_SUGGESTIONS` + `IME_FLAG_NO_EXTRACT_UI|NO_FULLSCREEN`; map
  `deleteSurroundingText` to Backspace presses; `commitText` newlines → Enter. Test Gboard, Samsung
  Keyboard, SwiftKey, and a hardware keyboard (DeX/ChromeOS). The key bar must ride the IME
  (`WindowInsets.ime` + Roboco's IME animation helpers); the activity is already `adjustResize`.
- Resize debounce matters more on Android (IME insets animate per frame).
- Selection menu: native floating `ActionMode` (Copy / Add to message / Paste) — preferred over a glass
  clone of `UIEditMenuInteraction`.
- Scrollback: `OverScroller`; decide stretch `EdgeEffect` (platform) vs `R/design/RubberBand.kt` (iOS parity).
- Persist terminal refs in `SharedPreferences` ("terminals" JSON) and font size ("terminalFontSize").
- Direct link: register a `ConnectivityManager.NetworkCallback` → `networkPathChanged()` /
  `setNetworkOnline()`, and `ProcessLifecycleOwner` → `onForeground()`/`onBackground()` (if the core
  migration doc hasn't already). The WebRTC data channel for terminals is **Rust `webrtc-rs`** inside the
  core — no Java WebRTC dependency is needed for terminals. ICE host candidates on the LAN: if Roboco
  ever raises `targetSdk` to 37, local-network access becomes a runtime permission (Roboco pins 36 for
  exactly this, `app/build.gradle.kts:417`); without it the link falls back to non-LAN paths / relay.

### 2.6 Agent visuals (F1-F14)

1. Lift `Z/transcript/Visual.kt` (`TranscriptVisual`, `VisualWidget`, `VisualSink`, `VisualPainter`,
   surface gestures, controls) into `R/transcript/widgets/VisualWidget.kt`; add a
   `WidgetKind.Visual` branch in `R/transcript/RowWidget.kt`.
2. Gate `attach/detach` on **lifecycle STARTED ∧ row realized/visible** (iOS pauses pooled rows;
   Z only checks lifecycle) — use Roboco's `RealizedRows`/`RowHost` visibility.
3. Keep one `VisualSession` per id across row versions (`remember(id)`; if rows are recycled across
   keys, hoist into `TranscriptState` keyed by id like iOS's `visuals: [String: VisualView]`).
4. Call `setVisualSource(client, hostDeviceId, max(2f, density))` from the session route (and subagent
   route with the root chat's host); re-call when the host changes.
5. Text runs: reuse Roboco `R/transcript/StyleFonts.kt` (it already resolves the core's style table);
   rasterize on the listener thread; post only the latest frame per surface.
6. Touch: `drag` consumes from down (must not start a transcript scroll); `horizontalDrag` only claims
   mostly-horizontal drags and lets vertical ones reach Roboco's custom scroller (`R/transcript/RowScroller.kt`
   / `TranscriptScroll.kt` nested-scroll); tap readout 2.5 s.
7. Controls: transparent targets with `minimumInteractiveComponentSize`, pressed `controlFill` wash
   (r 6 dp tap, 8 dp button/tab), `Haptic.Select` on tab/toggle, links through `LinkRouter`; slider with
   `progressBarRangeInfo` + `setProgress`.
8. **GPU / wgpu**: shaders and 3D scenes render offscreen through wgpu with `Backends::PRIMARY`
   (`zeron/crates/visual-gpu/src/lib.rs:102-113`) — on Android that is **Vulkan only** (no GLES), low-power
   adapter, no surface. Add `<uses-feature android:name="android.hardware.vulkan.version"
   android:version="0x401000" android:required="false"/>` (documentary; keep not-required) and verify on
   an emulator image with Vulkan (gfxstream/host GPU) and a low-end device. Without an adapter scenes use
   the software renderer and shaders show an error card — acceptable degradation.
9. Frame bytes arrive BGRA premultiplied; `Bitmap.Config.ARGB_8888` expects RGBA order (Z swaps R/B in
   place, `Visual.kt:405-421`). Consider a `copyPixelsFromBuffer` into a reused bitmap per surface to cut GC.

### 2.7 Voice, orb, call notification (G1-G31)

Files to lift (logic) from `Z/voice/`:

| Lift | Role | Restyle into Roboco |
|---|---|---|
| `VoiceController.kt` | app-wide call (generation, prefs, start/mute/stop, end reasons, sounds, proximity, notice) | none (owned by `RobocoApplication`); copy the end-reason strings from iOS `RemoteVoiceController.swift:246-257`. |
| `VoiceMedia.kt`, `VoicePeer.kt` | `VoiceMediaListener` bridge + WebRTC peer, audio focus/routing | none. |
| `VoiceCallService.kt` | microphone FGS + CallStyle notification (Live Activity stand-in) | Notification copy as iOS Live Activity ("Codex · host", status word, chronometer, Mute/Unmute, End). |
| `VoiceOrbView.kt` | orb canvas | none (monochrome ink already matches); size presets 20/32/112/340 dp. |
| `VoiceStageScreen.kt` | stage | Rebuild: `WallpaperHero` backdrop (top 72 %, ≤760 dp), header dot + `Face.MonoMedium` 13 clock + host, `GlassCircleButton` 44 dp minimize/transcript (+ warning badge), `VoiceCaption` with `Face.Sans` 16, glass control bar: mute 60 dp (inverted when muted), output well 60 dp, end 88×60 red; ended pill buttons 52 dp Semibold 16. |
| `VoiceControls.kt` | start button, header button, menu, host picker | `GlassCircleButton` + `GlassMenu`; host picker → `ActionSheet`. |
| `VoiceSettingsScreen.kt` | settings | `GroupedList` sections (hero cell with large orb, hosts, Voice value row with popup `GlassMenu`, tips, footers). |
| `VoiceHost.kt` | permission prompt, keep-screen-on, failure notice | Roboco `Toast` with action instead of a snackbar. |

Entry points: phone `SessionsBottomBar` (waveform item, long-press menu) — this reverses ADR 0009, so
update that ADR; session header (after companions); tablet `SidebarToolbar`; Settings "Voice" row;
deep link `roboco://voice` (or the scheme chosen for the zeron port) → stage when live.

Android specifics:
- **Manifest**: `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS`, `FOREGROUND_SERVICE` (present),
  `FOREGROUND_SERVICE_MICROPHONE`, `POST_NOTIFICATIONS` (present), `WAKE_LOCK` (proximity lock),
  `VIBRATE` (present); `BLUETOOTH_CONNECT` only if routing must enumerate BT devices by name on
  API 31+ (`setCommunicationDevice` itself does not need it). Service:
  `<service android:name=".voice.VoiceCallService" android:exported="false" android:foregroundServiceType="microphone"/>`.
- **FGS rules**: a `microphone` FGS must be started while the app is visible **and** after
  `RECORD_AUDIO` is granted (start it from the Prepare step, as Z does); call `startForeground` within
  5 s even when stopping immediately. Roboco's existing `RunWatchService` is `dataSync|specialUse` —
  keep voice in its own service.
- **Notification**: `CallStyle.forOngoingCall` requires the notification to be tied to a FGS (it is);
  channel with no sound; `setOngoing`, `FOREGROUND_SERVICE_IMMEDIATE`; content intent →
  stage deep link; actions → `PendingIntent.getService` (immutable). On Android 16 the call chip /
  promoted ongoing replaces the Dynamic Island; throttle updates to 1/s except mute/end/active.
- **WebRTC**: add `io.github.webrtc-sdk:android:150.7871.01` (same M150 milestone as the iOS pin,
  `zeron/apps/android/gradle/libs.versions.toml:14-15,35`). It ships `libjingle_peerconnection_so.so`
  per ABI — fine with Roboco's ABI splits. Roboco **minifies with R8** (`app/build.gradle.kts:458`):
  add `-keep class org.webrtc.** { *; }` (JNI reflection) to `app/proguard-rules.pro`, and make sure the
  UniFFI/JNA keep rules cover the new `uniffi.zeron_core` callback interfaces
  (`VoiceMediaListener`, `VoiceSessionListener`, `TerminalListener`, `VisualListener`).
- **Audio**: `MODE_IN_COMMUNICATION`, `AudioFocusRequest(GAIN)` with loss → end call (iOS interruption
  semantics), `AudioDeviceCallback` re-route, earpiece/speaker via `setCommunicationDevice` (minSdk 31
  in Roboco, so the legacy `isSpeakerphoneOn` path in Z can go), `PROXIMITY_SCREEN_OFF_WAKE_LOCK`.
  Sounds via `SoundPool` with `USAGE_VOICE_COMMUNICATION_SIGNALLING` (assets `voice-start.wav` /
  `voice-end.wav` from `I/Voice/Sounds/`).
- **Route picker**: Android has no system output picker for communication audio (the media Output
  Switcher doesn't apply); ship the speaker/earpiece toggle (Z) and, optionally, a `GlassMenu` listing
  `AudioManager.availableCommunicationDevices`.

### 2.8 Build-level needs shared by E/F/G

- **libc++_shared**: the zeron core links C++ (terminal emulator / GPU paths) and must ship the NDK's
  `libc++_shared.so` beside `libzeron_mobile.so` (`zeron/scripts/android/build-core.sh` uses
  `cargo ndk --link-libcxx-shared`). Roboco's `AndroidCoreBuild` (`cargo xtask android-core`) must do the
  same when it switches to the zeron core; NDK 29.0.14206865 is what zeron pins (Roboco: 28.2).
- **Bindings**: Kotlin from `uniffi-bindgen generate --library … --language kotlin`, JNA `@aar`
  (already in Roboco).
- **Icons** to add to `RobocoIcons`: terminal, keyboard, keyboard-down, arrow left/right, mic, mic.slash,
  phone.down, waveform, waveform.circle.fill, text.bubble, textformat.size (smaller/larger), two-bubbles,
  bubble+text, tool-bot, person.wave.2, ear, lock, server.rack, pc, laptop (exists), clear.
  The iOS asset SVGs can be rasterized the way Z does (`zeron/scripts/android/gen-icons.sh`).

---

## 3. Ordered task list

1. (prereq, other doc) Core switch to `uniffi.zeron_core` incl. `libc++_shared`, R8/JNA keep rules,
   network/foreground lifecycle hooks (`setNetworkOnline`, `networkPathChanged`, `onForeground/onBackground`).
2. Side chats on zeron core: `createSideChat` + fresh-set persistence, teardown delete, 60 s sweep,
   fork copy, side-chat header (C1-C8).
3. Companions model (agents + side chats + terminals stub) and the **strip** in `StatusPillRow`;
   always-visible companions nav item with running badge (A1-A7).
4. Companions **panel** rewrite on `GlassSheet` (sections, rows per state, empty/action rows, swipes,
   initial section, `warmDirect`) + ⋯ menu Terminal row + shortcuts (A8-A9, B1-B17).
5. Subagent screen deltas: glass status bar, subtitle/model labels, 10 s unavailable, copy button,
   spawn-card control polish (D3-D12).
6. **Terminal store + view + glyphs** (lift Z), then the terminal route with header/menu/status pill/
   keyboard button/key bar restyled; persistence and reattach; IME matrix testing (E1-E32).
7. **Visuals**: `WidgetKind.Visual` in `RowWidget`, sink/painter, gestures, controls, visibility gating,
   `setVisualSource` in session + subagent routes; Vulkan device matrix (F1-F14, D9).
8. **Inspector column** in `SplitRoot` + `presentCompanion` routing + fold/unfold handling (B18-B20, C7).
9. **Voice core**: controller, media bridge, WebRTC peer, audio routing, proximity, sounds,
   permissions, microphone FGS + CallStyle notification, deep link (G1, G7-G15, G26-G30).
10. **Voice UI**: orb, stage (captions, controls, minimize/transcript, transition), entry points
    (bottom bar, session header, sidebar), host picker + menu, Voice settings + Settings row; update
    ADR 0009 (G2-G6, G16-G25).
11. DEBUG extras: voice preview, terminal demo script, companion launch extras; parity goldens for the
    strip, panel, terminal and stage (`parity/manifest.json`).
