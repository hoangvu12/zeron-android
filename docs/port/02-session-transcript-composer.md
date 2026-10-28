# 02 — Session screen, transcript, composer: port-gap audit

Scope: the session screen, its transcript and its composer stack. The source of truth is iOS at `zeron/` v0.2.129. The companions hub, the subagent and terminal screens, the model picker sheet, new session, and settings belong to other auditors. They appear here only where the session screen hooks into them.

Path prefixes used below:

| Prefix | Path |
|---|---|
| `ios:` | `zeron/apps/ios/Zeron/` |
| `rb:` | `app/src/main/java/sh/roboco/android/` (current Roboco app) |
| `za:` | `zeron/apps/android/app/src/main/java/sh/zeron/android/` (Zeron's own Compose app, reference) |
| `core:` | `zeron/crates/mobile/src/` (UniFFI `uniffi.zeron_core`) |

iOS file shorthands: SVC = `Session/SessionViewController.swift`, CSS = `Session/CoreSessionSource.swift`, SS = `Session/SessionSource.swift`, CB = `Composer/ComposerBar.swift`, AP = `Composer/AttachmentPicker.swift`, MEN = `Composer/Mentions.swift`, QPN = `Composer/QuestionPanel.swift`, TLV = `Transcript/TranscriptListView.swift`, RV = `Transcript/RowView.swift`, RM = `Transcript/RowModel.swift`, TV = `Transcript/ToolViews.swift`, IV = `Transcript/ImageViewer.swift`, DH = `Session/DraftHandoff.swift`.

## What changed on iOS since Roboco's port point (`edb23db4..HEAD`)

`git -C zeron log edb23db4..HEAD` on the in-scope iOS paths lists 13 commits. The ones that matter here:

| PR | What changed in this area | Already in Roboco? |
|---|---|---|
| #900 | Native visuals: `RowKind.visual`, `WidgetKind.visual`, `VisualView`, `setVisualSource` | No |
| #912 | LaTeX math: `RowDisplay.glyphs`, `BoxPrim.start`, `MathSettings` → `engine.setMath` | No |
| #966 | Cloud, provider switching, phone model picker: `modelPicker()`, default-model chip label | Picker yes (wave-10 38); default-model fallback no; Cloud no |
| #967 | Log in from error cards (`WidgetKind.action`), menu header, Delete | Yes (wave-9 #14 / wave-10 35) |
| #981 / #942 | Width-change reflow lands on the tail; `layoutWidth` minus side insets | Yes (rb:transcript/TranscriptViewport.kt:141) |
| #988 | Companions (agents, side chats, terminals): header button with badge, pill-row strip, ⋯ Terminal, `presentCompanion`, `WidgetKind.subagent` | No (Roboco has the older #899 side-chats sheet) |
| #1014 | Composer rests level with the sidebar toolbar | Yes (rb:session/BottomStackMetrics.kt:71) |
| #1022 | Question panel close-without-answering; the transcript chip shows the answers (core) | No |
| #1062 / #1068 | Sender and recipient caption over agent-sent messages (core-painted, `zeron-chat://` link, `brand:` mark) | No |

These iOS files are unchanged since the port point apart from one line: ComposerBar (gained only `restHeight`), AttachmentPicker, Mentions, DraftHandoff, ImageViewer and FrameRelay. Rows that depend only on them are marked ✅ on the strength of Roboco's line-by-line ticket port; I spot-checked the constants (8 images, 2560 px, 0.86 spring, 220 ms, 140 dp jump threshold, gate labels, delivery-menu copy).

**Access and plan-mode chips are not on iOS.** PR #1088 added them to the desktop composer only. The mobile FFI deliberately keeps the chat's own mode: `core:client_ffi/mod.rs:243-250` says "The phone does not pick a mode yet". No phone work is needed until the FFI exposes them.

---

## 1. Gap table

Status key: ✅ ported 1:1 · ⚠️ ported but different (the "how" is given) · ❌ missing · n/a not an iOS feature.

### A. Session screen, header and ⋯ menu

| # | iOS | Behaviour | Roboco status + Kotlin | zeron core API | za reference | Native-Android option |
|---|---|---|---|---|---|---|
| A1 | SVC:50-55, 751-754 | Bar shows **no title**: `titleView = UIView()`. Title and "project @ device" appear only as the ⋯ menu header. Right-side items: ⋯, companions, voice. | ⚠️ Centred two-line title in the bar. rb:session/SessionHeader.kt:180-310 | `ComposerState.title`, `SessionRow.project`, `HostInfo.name` | za:ui/SessionScreen.kt:285-320 (M3 top bar) | M3 `TopAppBar(colors = transparent)` could host the glass circles. Little gain: keep the custom bar and drop the title. |
| A2 | SVC:319-324 | A side chat's bar reads "Side chat" over the parent's title (`navigationItem.subtitle`). | ⚠️ Only the side-chat **draft** has "New Side Chat" / "Side chat · parent". rb:sidechats/SideChatDraft.kt:5-10 | `SessionRow.parentChatId`, parent row title | za:ui/SessionScreen.kt (parentId) | Same bar |
| A3 | SVC:54-55 | ⋯ button opens the session menu at once (rebuilt on each open, uncached). | ✅ rb:session/SessionHeader.kt:283, rb:session/SessionMenu.kt:229-310 (GlassMenu) | — | za:ui/SessionScreen.kt:293-317 (M3 DropdownMenu) | M3 `DropdownMenu` is a Popup window, so Haze cannot blur behind it. Keep `GlassMenu`: it is in-window with `BackHandler` and a11y focus. |
| A4 | SVC:801-805 | Menu header: `title\nproject @ device`. | ✅ rb:session/SessionMenu.kt:168-173 | `LayoutFrame` n/a | — | — |
| A5 | SVC:810-814, 337-344 | **New Side Chat** (`bubble.left.and.text.bubble.right`) creates the chat **immediately** (`createSideChat(parent root)`; from a side chat, a sibling) and opens it as a companion. | ⚠️ Opens a side-chat *draft* page; the icon is `QuestionmarkBubble`. rb:session/SessionMenu.kt:188-206, rb:sidechats/SideChatDraft.kt | `CoreClient.createSideChat(parentId)` (core:client_ffi/companions.rs:71) | za:ui/SessionScreen.kt:303 | GlassMenu row |
| A6 | SVC:815, 835-846 | **Fork Into Side Chat**: "Forking…" toast, then `forkSideChat`, a `.success` notification haptic, and open. Errors toast `forkError`. | ⚠️ No "Forking…" toast and no success haptic. Title is "Fork into Side Chat". rb:sidechats/SideChatFork.kt, rb:session/SessionMenu.kt:198 | `CoreClient.forkSideChat(sourceId)` async (companions.rs:77) | za:ui/SessionScreen.kt:304 | `View.performHapticFeedback(CONFIRM)` |
| A7 | SVC:816, 373-376 | **Terminal** in the inline group: opens the live terminal tab (companion). | ❌ No terminal anywhere in Roboco | core:terminal/ (`openTerminal`, `attachTerminal`) | za:ui/TerminalScreen.kt | — |
| A8 | SVC:820 | Pin / Unpin (`pin`, `pin.slash`). | ✅ rb:session/SessionMenu.kt:116-122. Drop the `!canWrite` disable. | `pinSession` / `unpinSession` (core:client_ffi/mod.rs:257,271) | za:ui/SessionScreen.kt:308 | — |
| A9 | SVC:821-823 | Copy Transcript = `engine.frame().plainText()`. | ✅ rb:session/SessionMenu.kt:320-375 (off-main, Binder-limit toast) | `LayoutFrame.plainText()` (core:layout/mod.rs:250) | za:ui/SessionScreen.kt:309 | `ClipboardManager` (native) |
| A10 | SVC:824-827, 850-864 | Archive, then leave (pop, or the tablet draft). | ✅ rb:session/SessionMenu.kt:142-148, 304-308. Drop the canWrite gate. | `archiveSession` (mod.rs:227) | za:ui/SessionScreen.kt:311 | — |
| A11 | SVC:828-830, 870-884 | Delete: "Delete session?" alert, Cancel / destructive Delete. Cloud copy adds "and its Cloud machine…". | ⚠️ Present without the Cloud wording. rb:session/SessionMenu.kt:149-158, rb:sessions/SessionPrompts.kt:121 | `deleteSession` (mod.rs:253), `SessionRow.isCloud` | za:ui/SessionScreen.kt:317 | `BasicAlertDialog` (M3), themeable to the glass card, gets Dialog-window focus, IME and TalkBack. Candidate swap for rb:design/Alert.kt. |
| A12 | SVC:56-60, 313-318, 347-371 | **Companions bar button** (`square.stack.3d.up`, "Agents, side chats and terminals"). Always shown, with an accent badge = running agents. Opens the hub: the inspector on a wide screen, else a sheet (medium/large, grabber). | ⚠️ A side-chats button shows only when there is a badge, opening the "Side Chats & Agents" overlay sheet. No terminals; badge semantics differ. rb:sidechats/SideChatsButton.kt, rb:sidechats/SideChatsSheet.kt, rb:session/SessionScreenRoute.kt:315-317 | `CoreClient.subagents()` (companions.rs:59), side chats from the workspace, terminals | za:ui/SessionScreen.kt:293, za:ui/Companions.kt | M3 `BadgedBox` + `Badge(containerColor = accent)` on the glass circle. M3 `ModalBottomSheet` for the hub (predictive back, drag, IME). |
| A13 | SVC:61-81 | Voice call bar item, shown when voice is available. | ❌ (Roboco has no voice) | core voice (client_ffi/voice.rs) | za:voice/VoiceControls.kt | — (voice auditor) |
| A14 | SVC:378-386 | Hardware keys: ⌘J Terminal, ⌘⌥0 Companions. | ❌ | — | — | `onPreviewKeyEvent` (Ctrl+J, Ctrl+Alt+0) and `Activity.onProvideKeyboardShortcuts` for the shortcut helper |
| A15 | SVC:410-453, 500-508 | Animated push: spinner (×1.75) while the transcript is held until the push lands and rows are in; 8 s give-up; 0.18 s fade. | ✅ rb:session/OpenReveal.kt, rb:session/OpenProbe.kt | `LayoutFrame.rowCount()` | — | — |
| A16 | SVC:699-714 | `OPEN {openMs,rows,layoutPassMs}` log. | ✅ rb:session/OpenProbe.kt | `LayoutFrame.buildMicros()` | — | — |
| A17 | SVC:561-584, DH:1-213 | Arrival from a new-session draft: scene dissolve, composer glide (easeOutQuart, live target), bubble flight. | ✅ rb:session/SessionArrival.kt, rb:newsession/DraftHandoff*.kt | — | — | — |
| A18 | SVC:388-393, 511-555 | Lifecycle: reattach on reappear; on disappear, cancel any queued edit and save the draft. Tear down only after a real pop (not a cancelled swipe), then `detach` and `engine.close()`. `markSeen` on open and close. | ✅ rb:session/SessionNavLifecycle.kt, rb:session/SessionDetachFence.kt, rb:session/SessionController.kt | `SessionHandle.setViewAttached`, `TranscriptView.close`, `CoreClient.markSeen` | — | Predictive back is already used by NavStack |
| A19 | SVC:546-550 | Teardown deletes a **fresh, unused** side chat (no send, no draft, no preview). | ❌ (the draft model never creates one) | `SessionHandle.transcriptStatus()`, `composer().queue`, `deleteSession` | — | — |
| A20 | SVC:46-49, 1066-1079 | Draft saved on background and disappear; trimmed-empty removes it. | ✅ rb:composer/Drafts.kt, SessionDraftEffect. Re-key `DraftKey.Chat(engineId, chatId)` → `chatId` only. | — | za:ui/ComposerModel.kt | — |
| A21 | SVC:120-124, 586-605 | A tap on the transcript dismisses the keyboard; controls and links keep their taps. | ✅ rb:session/DismissComposerOnTap.kt | — | — | — |
| A22 | SVC:207-219 | Bottom edge fade 44 pt above the stack (overlay, not a mask). | ✅ rb:session/SessionBottomStack.kt (EdgeFadeOverlay) | — | — | — |
| A23 | SVC:187-208, 228-241, 888-899 | Bottom stack: centred, ≤768 wide, 12 pt sides, 8 pt spacing, 8 pt above the keyboard. | ✅ rb:session/SessionBottomStack.kt, rb:session/BottomStackMetrics.kt, rb:transcript/InteractiveImeDismiss.kt | — | — | Native option: `Modifier.imePadding()` plus `WindowInsetsAnimationCompat` / `imeNestedScroll()` instead of the custom ImeDragSession. Keep the custom one only if it feels more iOS-like. |
| A24 | SVC:901-914 | Rest gap level with the sidebar toolbar on wide screens (#1014). | ✅ rb:session/BottomStackMetrics.kt:71 | — | — | — |
| A25 | SVC:614-624 | Transcript bottom inset = area covered by the stack; pinned to the tail while following. | ✅ rb:transcript/BottomInsetSync.kt | — | — | — |
| A26 | SVC:747-798 | Structural stack changes spring (0.38 s / 0.86); panels' glass materializes; first render shows panels at once. | ✅ rb:session/BottomStackState.kt, rb:session/BottomStackModel.kt | — | — | — |
| A27 | SVC:327-335 | `presentCompanion`: beside the session in the inspector when there is room, else pushed. | ⚠️ Subagents and side chats open via overlay or push only. rb:session/SessionScreenRoute.kt, rb:sidechats/ | — | za:ui/TabletShell.kt | — |
| A28 | SVC:100-103 | Subagent card tap → SubagentViewController(parentId, docId, title, running). | ⚠️ `openSubagent(docId, title, frozen)` → Roboco's `client.openSubagent(docId)`. rb:sidechats/SubagentViewer.kt:296-327 | `TranscriptView.attachSubagent(client, chatId, docId, frozen)` (core:layout/mod.rs:481) | za:ui/SubagentScreen.kt:109 | — |
| A29 | SVC:104-114, TLV:711-717 | Sender or recipient caption link `zeron-chat://open?id=` → open that chat (pushed on phone, router on tablet; ignored for itself). | ❌ No route (rb:transcript/LinkRoute.kt sends it External) | core:layout/rows.rs:62, 908-943 | za:ui/SessionScreen.kt:179, za:ui/ZeronRoot.kt:197 | — |
| A30 | SVC:90-99, CSS:339-347 | Error card "Log in" (`sign-in`) → AgentAccountsViewController(target, signIn: true) **as a sheet** (medium/large). Target = the Cloud agent or `.device(hostDevice, harness)`. | ⚠️ Pushes `errorCardLogInRoute` (a page), with a Limited exception, Roboco-only. rb:session/SessionScreen.kt:279-298, rb:accounts/AgentAccountsRoute.kt | `WidgetKind.Action{action="sign-in"}` (rows.rs:849,1078), core:client_ffi/providers.rs | za:ui/SessionScreen.kt:187-188, za:ui/AgentAccountsScreen.kt:302 | M3 `ModalBottomSheet` (partial ≈ medium, expanded ≈ large) |
| A31 | SVC:23-24, 150-153, 196-200, 919-979 | "Out of Cloud credits" notice above the composer: Add credits → billing. A refused send nudges it (warning haptic + shake). | ❌ (Cloud dropped) | core:client_ffi/cloud.rs | za:ui/SessionScreen.kt:361-380, 488-515 | M3 `Button` for "Add credits" |
| A32 | SVC:734-745 | Cloud chat banner: not-delivered becomes nothing when out of credits, or "Cloud machine: … · Tap to retry". | ❌ | cloud.rs (`sessionError`) | za:ui/SessionScreen.kt:461 | — |
| A33 | SVC:27, 115-118, 221-226, 716-722 | Jump to latest: 40 pt glass circle (`arrow.down`) 12 pt above the stack. Shown when `d > 140 && !following`; spring 0.35 / 0.8 from scale 0.6. | ✅ rb:session/JumpToLatestButton.kt:53-78 | — | za:ui/SessionScreen.kt:338 (uses 400; reference is off) | M3 `SmallFloatingActionButton` restyled, but glass needs Haze anyway: keep |
| A34 | SVC:17, 191-195, 282-311 | **CompanionStrip** at the right of the pill row: agents / side chats / terminals chips, synced ≤4 Hz, tap → hub section. | ❌ | companions.rs, terminals | za:ui/Companions.kt (CompanionStrip) | M3 `AssistChip` themed (shape, colors, leading icon) |

### B. Status pill

| # | iOS | Behaviour | Roboco status + Kotlin | zeron core API | za reference | Native option |
|---|---|---|---|---|---|---|
| B1 | SVC:982-1063 | 30 pt glass capsule: glyph (trailer, or tone dot) + Medium 13 label; the whole capsule is a tap target. | ✅ rb:session/StatusPill.kt | — | za:ui/Composer.kt:388 | — |
| B2 | CSS:139-140 | "Sending to host…" while `sendState == .sending`. | ⚠️ Gated by Roboco's `sendingBridge` (the web's rule). rb:session/StatusBanner.kt:68-126 | `ComposerState.sendState` | za:ui/SessionScreen.kt:471 | — |
| B3 | CSS:141-142, SVC:1037-1039 | "Offline — sends are saved" when device connectivity is offline. | ❌ Replaced by "Reconnecting… Cached history is read-only." (rb:session/StatusPill.kt:125) | `CoreClient.connectivity()` (mod.rs:216) | za:ui/SessionScreen.kt:472 | — |
| B4 | CSS:143-145, SVC:1040-1042 | "Reconnecting in Ns" from `room.retryAtMs`. | ❌ No countdown; driven by the engine link | `ComposerState.room.connected/retryAtMs` | za:ui/SessionScreen.kt:473 | — |
| B5 | CSS:133-138 | Queued (not Cloud): "`<host>` is offline — will send when it's back". | ❌ Roboco refuses writes instead (CanWriteFiller) | `SendState.QUEUED`, `HostInfo.name` | za:ui/SessionScreen.kt:470 | — |
| B6 | CSS:131-132, SVC:173-179 | "Not delivered · Tap to retry" → `retryDelivery()`. | ✅ rb:session/StatusBanner.kt:97, 163. Drop the "engine connected" gate. | `SessionHandle.retryDelivery()` (**sync** in zeron) | za:ui/SessionScreen.kt | — |
| B7 | CSS:129-130, 178-182 | Sticky "Couldn't send: …" until the next send. | ✅ rb:session/StatusBanner.kt:95 | `send` throws `CoreException` | — | — |
| B8 | SVC:1049-1051, 677 | "Editing queued message · Tap to cancel". | ✅ rb:session/StatusPill.kt:114 | — | za:ui/Composer.kt:261 | — |
| B9 | SVC:773-774, 780-782 | The pill row hides only when banner = none **and** the strip is empty. | ⚠️ No strip, so it hides with the banner | — | — | — |

### C. Composer (ComposerBar), chips, attachments, send/queue/steer/stop, mentions

| # | iOS | Behaviour | Roboco status + Kotlin | zeron core API | za reference | Native option |
|---|---|---|---|---|---|---|
| C1 | CB:32-40, 306-336 | Capsule ⇄ card morph (focused, images, or `chipsAlwaysVisible`); spring 0.42 / 0.86; radius 25/26; toolbar 50. | ✅ rb:composer/ComposerMorph.kt, ComposerSurface.kt, ComposerGeometry.kt | — | za:ui/ComposerSurface.kt | Already `BasicTextField` (native IME, selection, autofill) |
| C2 | CB:112-117, 533-551 | Capsule previews ≤2 lines ending in "…"; card shows ≤8 lines (3 in compact height), then scrolls. | ✅ rb:composer/ComposerTextField.kt, ComposerTextMetrics.kt | — | — | — |
| C3 | CB:134-138, 278-291 | A tap anywhere on the glass focuses; controls keep their taps. | ✅ rb:composer/ComposerSurface.kt | — | — | — |
| C4 | CB:73, CSS:103, SVC:676 | Placeholder "Message `<harness>`", or "Edit queued message". | ✅ rb:session/SessionChrome.kt:146, rb:composer/QueueEditController.kt | `SessionRow.harnessLabel` | za:ui/Composer.kt:268 | — |
| C5 | CB:148-163, SVC:129-132 | "+" 34 pt capsule; the menu is rebuilt on every open. | ✅ rb:composer/ComposerAttachButton.kt, ComposerAttachMenuButton.kt, AttachMenu.kt:69-104 | — | za:ui/ComposerParts.kt:203-250 | GlassMenu (see A3) |
| C6 | AP:19-21, 40-49 | Photo Library (system picker, images, limit = 8 − staged). | ✅ System photo picker `PickMultipleVisualMedia`. rb:composer/AttachMenu.kt:122-135 | — | za:ui/ComposerParts.kt:232 | Already native |
| C7 | AP:23-27, 51-57 | Take Photo (only if a camera exists). | ✅ rb:composer/AttachMenu.kt (TakePicture + FileProvider) | — | za:ui/ComposerParts.kt:234 | Already native |
| C8 | AP:28-30, 59-65 | Choose File (images, multiple). | ✅ rb:composer/AttachMenu.kt (OpenMultipleDocuments) | — | za:ui/ComposerParts.kt:244 | Already native (SAF) |
| C9 | AP:31-36 | Paste Image (only when the clipboard holds images). | ✅ rb:composer/AttachMenu.kt:94-101 (checks the type only, so no paste toast) | — | za:ui/ComposerParts.kt:245 | Native clipboard. Also consider `contentReceiver` on the field for keyboard GIF/image insert (Android-only bonus). |
| C10 | AP:110-122 | Staging: long side ≤2560, JPEG 0.86, ≤24 MB, 180 thumbnail. | ✅ rb:composer/ImageStaging.kt | `OutgoingAttachment` in `SendRequest` | za:ui/ComposerModel.kt | — |
| C11 | CB:474-519 | Thumbnails 56 pt r14 with hairline, 18 pt × remove, max 8. | ✅ rb:composer/StagedThumbnailStrip.kt, ComposerController.kt:255 | — | za:ui/ComposerParts.kt:179 | — |
| C12 | CSS:105-107, 352-369; SVC:134-137 | **Model chip**: brand mark; label = `row.modelLabel` ?? **host default model** (first of `listModels`, seeded by `fallbackModels`) ?? harness label. Tap opens the picker sheet. | ⚠️ No host-default fallback (`modelLabel ?: harnessLabel`). The picker itself is ported (wave-10 38). rb:session/SessionChips.kt, rb:composer/ModelPicker.kt | `listModels` (async), `fallbackModels` | za:ui/Composer.kt:321-360 | — (picker auditor) |
| C13 | CSS:108-110, 267-279 | Effort chip (`gauge.with.dots.needle.67percent`, `reasoningLabel`). Menu "Reasoning effort" with a check; levels from the current model's `reasoningLevels`. | ✅ rb:composer/SessionChipMenus.kt:614 | `listModels`, `setSessionConfig` | za:ui/Composer.kt | GlassMenu |
| C14 | CSS:111-113, 280-285 | PR chip (mono number, state tint). Menu: "Open Pull Request" / "Copy Link". | ✅ rb:composer/SessionChipMenus.kt:617-620 | `SessionRow.pullRequest` | za:ui/Composer.kt:362-363 | Custom Tabs (native) |
| C15 | CSS:114-116 | Branch chip, shown when there is no PR; no menu. | ✅ rb:session/SessionChips.kt | `SessionRow.branch` | — | — |
| C16 | CSS:117-127 | Context chip **only at ≥50 %**: "N% context"; warning symbol and tint at ≥85 %; **no menu**. | ⚠️ Shown at any fraction (android-next #01), with an account-usage menu (#05). rb:session/SessionChips.kt:105-110, rb:composer/ContextChip.kt | `ComposerState.contextUsage` | — | — |
| C17 | — | Access / plan-mode chips | n/a: desktop only (#1088); core:client_ffi/mod.rs:243-250 preserves the chat's mode | — | — | — |
| C18 | CB:424-470 | Chip strip: horizontal scroll with faded edges; menus open on tap. | ✅ rb:composer/ChipStrip.kt, ChipMenuPlatform.kt | — | — | M3 `AssistChip` is themeable (shape, colors, border, icon). The gain is small; keep the custom chips (they already carry `Role.Button`). |
| C19 | CB:580-624 | Action button: Send (accent, or disabled fill), Queue (same look; a11y "Queue message" or "Steer"), Stop (`stop.fill`, inverted); spring plus symbol bounce. | ✅ rb:composer/ComposerActionButton.kt | `LiveStatus.turnRunning`, `HostCapabilities.midTurnSteering` | za:ui/Composer.kt:272 | — |
| C20 | CB:625-638 | Long-press "While the agent works": Queue for next turn / Steer now (if `canSteer`) / Stop and send (destructive). | ✅ rb:composer/ComposerActionButton.kt:188-249 | `BusyPolicy.QUEUE/STEER`, `interrupt()` | za:ui/Composer.kt:295-318 (a chip, not a long-press: reference differs) | GlassMenu |
| C21 | CB:641-667 | Send: `.medium` haptic, mentions encoded, `SendResult` kept / sent / replaced. | ✅ rb:composer/ComposerController.kt | — | — | — |
| C22 | SVC:138-159, CSS:170-184 | onSend: a queued send behind a live turn → `expectQueuedTurn`, else `beginOwnTurn`; the keyboard goes away; a refused send keeps the text. | ✅ rb:session/SessionComposer.kt:155-168 | `SessionHandle.send(SendRequest)` / `interrupt()`, **sync** in zeron (suspend in Roboco) | za:ui/Composer.kt:209-226 | — |
| C23 | CB:643-645, CSS:188-190 | Stop: `.rigid` haptic, then `interrupt()`. | ✅ rb:session/SessionCommands.kt | `interrupt()` sync | — | — |
| C24 | CB:669-676 | Hardware ⌘↩ sends. | ✅ Ctrl/Meta+Enter, rb:composer/ComposerTextField.kt:91, 257 | — | — | Native key events |
| C25 | MEN:5-105, CB:340-402 | `@` mentions: query only after a space or newline; 120 ms debounce; ≤5 results in glass above the bar (name + mono directory); selection haptic; `@name` token (parent added when ambiguous); accent styling; encoded to `fileMentionLink`. | ✅ rb:composer/MentionIndex.kt, MentionSuggestions.kt, MentionSuggestionsState.kt, MentionStyle.kt | `CoreClient.searchFiles(deviceId, chatId, spaceId, query)` (zeron adds `spaceId`), `fileMentionLink` → `zeron-file:` | za:ui/Composer.kt, za:ui/ComposerModel.kt | Keep custom (a Popup cannot blur) |

### D. Queue panel and queued edits

| # | iOS | Behaviour | Roboco status + Kotlin | zeron core API | za reference | Native option |
|---|---|---|---|---|---|---|
| D1 | QPN:259-307, SVC:790-792 | Glass r20 panel above the composer: first 3 rows, then "+N more queued"; materializes on first render. | ✅ rb:composer/QueuePanel.kt, QueueModel.kt | `ComposerState.queue` | za:ui/Composer.kt:404-455 | — |
| D2 | QPN:309-369 | Row: clock icon, optional 26 pt thumbnail, fading "gate · text", send (`arrow.up.circle.fill`), ⋯. | ✅ rb:composer/QueuePanel.kt:288-302 | `QueueItem.visibleText` | — | — |
| D3 | CSS:153-160 | Gate labels: Editing / Being edited / Needs review / Updating. | ✅ rb:composer/QueueModel.kt:41-43 | `QueueGate`, `actionPending` | za:ui/Composer.kt:450-452 | — |
| D4 | CSS:200 | Send now → `deliverQueuedNow` (steers text; only attachment rows interrupt). | ✅ rb:composer/QueueActions.kt:85 | `deliverQueuedNow` (async) | za:ui/Composer.kt:436 | — |
| D5 | QPN:334-339, CSS:201-203 | ⋯ menu: Edit / Move Up (disabled on the first row) / Move Down (disabled on the last) / Remove (destructive). | ✅ rb:composer/QueuePanel.kt, QueueActions.kt | `moveQueuedBy` **sync** in zeron, `removeQueued` async | za:ui/Composer.kt:436-440 | GlassMenu |
| D6 | SVC:626-697, CSS:208-254 | Edit lease: `beginQueuedEdit`, 20 s renew, stash the draft and images, focus. "Can't edit right now" alert. Switching rows; leaving cancels; commit via `replacingVisible`; an unchanged edit cancels. | ✅ rb:composer/QueueEditController.kt, QueueEditText.kt, QueueEditPort.kt, QueueEditHost.kt | `begin/renew/finishQueuedEdit` (async) | za:ui/Composer.kt:130-190 | Alert → `BasicAlertDialog` |

### E. Question panel

| # | iOS | Behaviour | Roboco status + Kotlin | zeron core API | za reference | Native option |
|---|---|---|---|---|---|---|
| E1 | QPN:3-11, 131-146, SVC:767-771 | Replaces the composer: glass r26 card; header "`<header>` · i of n"; question in Semibold 17. | ✅ rb:composer/QuestionCard.kt, QuestionPanelState.kt, SessionQuestionsSlot.kt | `ComposerState.openInput` | za:ui/QuestionPanel.kt | — |
| E2 | QPN:150-170, 187-197 | Option rows: numbered circle, or check (square for multi-select); accent wash; selection haptic. | ✅ rb:composer/QuestionCard.kt | — | — | — |
| E3 | QPN:198-200 | Single-select auto-advances after 220 ms. | ✅ rb:composer/QuestionPanelState.kt:28-55 | — | — | — |
| E4 | QPN:36-56, 171-174 | "Other…" field (44 pt) or multiline editor (140 pt), with prefill. | ✅ rb:composer/QuestionCard.kt:299-330 (BasicTextField) | `InputQuestion.prefill/multiline` | — | Already native text input |
| E5 | QPN:58-70, 175-179, 220-223 | Back / Next → Submit (prominent capsule), 0.2 s cross-dissolve. | ✅ rb:composer/QuestionCard.kt:422 | — | — | — |
| E6 | QPN:225-247 | Submit once per set, `.medium` haptic → `respondInput`. | ✅ rb:composer/QuestionPanelState.kt:221-264, rb:composer/QuestionActions.kt. Remove canWrite and the RPC re-arm. | `respondInput(requestId, answers)` **sync** in zeron | za:ui/QuestionPanel.kt | — |
| E7 | QPN:71-121, 249-255 (#1022) | **Close without answering**: 36 pt glass `xmark` 8 pt in at top-right; header and question wrap 36 pt short of it; submits `[]` once. | ❌ | `respondInput(requestId, [])` | za:ui/QuestionPanel.kt:102, 184 | — |
| E8 | core:layout/rows.rs:501-512 (#1022) | The transcript chip shows what was answered ("Q → A" per line, PreLine) or that the questions were closed. | ⚠️ Comes free with the zeron core (Roboco's core still says "Answered a question"); verify that multi-line chip rows paint | `MessagePart::Input` via the layout core | — | — |

### F. Transcript list: scroll, follow, copy and select, links

| # | iOS | Behaviour | Roboco status + Kotlin | zeron core API | za reference | Native option |
|---|---|---|---|---|---|---|
| F1 | TLV:466-616 | Virtualized rows from `LayoutFrame` (`rowsIn`, overscan 700, pool, model cache 500 / evict 100, async upgrade, direction-aware prefetch ×1.5). | ✅ rb:transcript/TranscriptViewport.kt, RealizedRows.kt, ModelPipeline.kt, RowModelCache.kt | `LayoutFrame.rowsIn/placement/indexOf/display` (identical) | za:transcript/Transcript.kt | — |
| F2 | TLV:300-323 | `setViewport(width minus side insets, Dynamic Type scale)`. | ✅ rb:transcript/TranscriptState.kt:290 | `TranscriptView.setViewport` | — | — |
| F3 | TLV:333-366 | Follow-tail: a drag releases it; re-latches on release or deceleration within 70 pt. | ✅ rb:transcript/TranscriptScroll.kt, ScrollPhysics.kt | — | za:transcript/TranscriptState.kt | Owner choice: Roboco's IosFlingBehavior / TranscriptRubberBand vs native stretch overscroll |
| F4 | TLV:408-445 | While not following, the anchored row keeps its offset (momentum survives). | ✅ rb:transcript/ScrollAnchor.kt | `LayoutFrame.indexAt` | — | — |
| F5 | TLV:426-438 | The first frame lands on the tail; a width change jumps to the tail without the spring (#981). | ✅ rb:transcript/TranscriptViewport.kt:141 | `LayoutFrame.width()` | — | — |
| F6 | TLV:620-653 | Follow spring `exp(-16·dt)`; runway glide retain 0.85 per 60 fps frame. | ✅ rb:transcript/ScrollPhysics.kt, Runway.kt | — | — | — |
| F7 | TLV:133-248 | Runway: own turn holds the prompt at the top; promotes a queued turn; re-sizes with insets. | ✅ rb:transcript/Runway.kt | — | za:transcript/TranscriptState.kt | — |
| F8 | TLV:449-533 | Row animations: fold 140 ms ease-out; tool group grows over 360 ms expo; new rows fade in over 0.28 s. | ✅ rb:transcript/RowMotion.kt | — | — | — |
| F9 | TLV:282-287 | A memory warning drops models and the pool. | ✅ rb:transcript/TranscriptState.kt:446-452 | — | — | `onTrimMemory` |
| F10 | TLV:4-80, 288-293 | **MathSettings → `engine.setMath(render, face, size%)`**, re-applied on change (default on, Fira, 100 %). | ❌ (settings UI lives with the settings auditor) | `TranscriptView.setMath(enabled, MathFace, sizePercent)` | none (za doesn't paint math either) | — |
| F11 | TLV:670-676 | A tap on a painted link opens it (±4/±2 hit slop; scroller-aware). | ✅ rb:transcript/TranscriptTaps.kt | `RowDisplay.links` | — | — |
| F12 | TLV:697-725 | Routing: `zeron-preview://image?ref=` → image viewer; `zeron-chat://` → open chat; http(s) → in-app Safari; anything else → system. | ⚠️ Uses `roboco-preview` / `roboco-file` / `roboco-invoke`; no chat route; Roboco-only "file-shaped image link → viewer". rb:transcript/LinkRoute.kt, LinkRouter.kt | core:layout/rows.rs:62, 698 | za:ui/SessionScreen.kt:175-185 | Custom Tabs ✅ (native) |
| F13 | TLV:748-782 | Long-press context menu with a lifted row preview (background, inset 8, r16): Copy (block), Copy Message, Select Text. | ✅ rb:transcript/RowMenu.kt + rb:design/ContextMenu*.kt | `RowDisplay.copyText`, `LayoutFrame.messageText` | za:transcript/Transcript.kt:304-315 | No native lift-preview menu exists. M3 `DropdownMenu` at the touch point would lose the preview: keep custom. |
| F14 | TLV:784-834 | Select Text sheet: full text, select-all, Done. | ✅ rb:sheets/SelectTextSheet.kt (read-only BasicTextField: native handles and toolbar) | — | za transcript `actions.showText` | `ModalBottomSheet` for the container (predictive back, drag-dismiss) |
| F15 | RV:265-276 | Detail widget → mono sheet (medium/large, grabber). | ✅ rb:sheets/TextSheetController.kt, rb:transcript/DetailWidget.kt | `WidgetKind.Detail` + payload | za:transcript/Widgets.kt:119 | `ModalBottomSheet` |
| F16 | RV:58-99, 164-178 | a11y: one element per row ("You: …"); link elements (zeron-chat → "Open chat"); controls and visuals first; ids including `row-visual`. | ⚠️ No "Open chat" and no visual ordering. rb:transcript/TranscriptSemantics.kt | — | — | — |
| F17 | RV:131-161, RM:179-252 | Streaming veil: settled + fresh passes, 0.22 s fade; **formula boxes and glyphs split by `start`**. | ⚠️ The veil exists, but `Pass.Chrome` / `Range` know nothing of `BoxPrim.start` or glyphs. rb:transcript/Veil.kt, rb:transcript/RowModel.kt:40-51 | `GlyphPrim.start`, `BoxPrim.start` | — | — |
| F18 | RV:180-211 | Horizontal scrollers (code, tables) with fades; links and widgets inside them. | ✅ rb:transcript/RowScroller.kt, ScrollerRegistry.kt | `RowDisplay.scrollers` | — | — |
| F19 | ios:Transcript/FrameRelay.swift, SVC:125-127 | Frame coalescing; soft top edge effect settled once visible. | ✅ rb:transcript/FrameRelay.kt, rb:design/glass/ScrollEdgeEffect.kt | `LayoutListener.frameReady` | — | — |

### G. Transcript row kinds

| # | iOS | Behaviour | Roboco status + Kotlin | zeron core API | za reference | Native option |
|---|---|---|---|---|---|---|
| G1 | RV:167 | `user`: bubble, long-message disclosure, thumbnails. | ✅ rb:transcript/RowHost.kt | `RowKind.USER` | — | — |
| G2 | RV:168 | `markdown` (text, code, tables, quotes). | ✅ | `RowKind.MARKDOWN` | — | — |
| G3 | RV:169 | `tools` (groups, rail, details, diffs). | ✅ | `RowKind.TOOLS` | — | — |
| G4 | RV:170, 352-360 | `chip`: fork; **provider-switch seam with `brand:` logos**; question summary. | ⚠️ `brand:` marks don't render (see H8) | `RowKind.CHIP`, rows.rs:950-990 | — | — |
| G5 | RV:171 | `notice`: error card (DangerWash, `exclamationmark.triangle.fill`, Log in). | ✅ rb:transcript/RowHost.kt:254. Icon name: see H8. | `RowKind.NOTICE`, rows.rs:849-1080 | — | — |
| G6 | RV:172 | `image` (generated or attached image row). | ✅ | `RowKind.IMAGE` | — | — |
| G7 | RV:173 | `working` (tail Working/Writing…). | ✅ | `RowKind.WORKING` | — | — |
| G8 | RV:174 (#900) | **`visual`** row hosting a live agent visual. | ❌ `RowKind.VISUAL` is unknown. RowHost.kt:250 is an exhaustive `when`, so it won't compile. | `RowKind.VISUAL`, rows.rs:494 | za:transcript/Visual.kt | — |
| G9 | rows.rs:597-620, 897-943 (#1062/#1068) | Sender or recipient caption: "Message from [mark] Chat" right-aligned over the bubble; the title links to `zeron-chat`. | ⚠️ Painted by the core, but needs H8 (`brand:` / `bubble.left`) and A29 routing | core-only | za:ui/SessionScreen.kt:179 | — |
| G10 | RM:14-17, 49-59, 102-118, 243-251 (#912) | **LaTeX math** in markdown: glyphs from bundled math fonts. Fraction and radical rules are boxes. | ❌ (formulas would draw only their rules, with blank glyphs) | `RowDisplay.glyphs`, `mathFontData(index)`, `MathFace` | none (za also lacks it) | — |

### H. Transcript widgets

| # | iOS | Behaviour | Roboco status + Kotlin | zeron core API | za reference | Native option |
|---|---|---|---|---|---|---|
| H1 | RV:228-230, 388-414 | CopyCode: `square.on.square` → accent check for 1.4 s, light haptic. | ✅ rb:transcript/widgets/CopyCodeButton.kt | `WidgetKind.CopyCode` | za:transcript/Widgets.kt:102 | — |
| H2 | RV:231-237, 417-443; TLV:678-682 | Disclosure: invisible target, 0.5 alpha while pressed, "Expand"/"Collapse", selection haptic, then fold animation. | ✅ rb:transcript/widgets/DisclosureWidgets.kt | `Disclosure{expanded}`, `TranscriptView.toggle` | — | — |
| H3 | RV:238-239, 446-469 | ToolStatus: spinner, or failed ✕ (danger), or quiet ✓. | ✅ rb:transcript/widgets/StatusWidgets.kt | `ToolStatus{running,failed}` | — | — |
| H4 | RV:240-260, 366-369 | Image: r14/r12, tap → zoom viewer; `pending://` gets an upload ring with %. | ✅ rb:transcript/widgets/ImageTile.kt, UploadRing.kt, ImageTileStatus.kt | `Image{reference}`, `ComposerState.transferProgress` | za:transcript/Widgets.kt:116 | — |
| H5 | RV:261-262 | Spinner (StatusGlyph). | ✅ rb:transcript/RowWidget.kt:74 | `Spinner` | — | — |
| H6 | RV:263-264, 473-518 | Working: 3×3 trailer + "Working…" / "Writing…" + elapsed, 1 s tick. | ✅ rb:transcript/widgets/StatusWidgets.kt | `Working{sinceMs,streaming}` | — | — |
| H7 | RV:265-276 | Detail → mono sheet. | ✅ rb:transcript/DetailWidget.kt | `Detail{title}` | — | — |
| H8 | RV:277-288, 350-364 | Icon: `tool-*`, `fileicon-*`, **`brand:<harness>`** (BrandMarks at 32, scaled, keeps its colour), else an SF symbol. | ⚠️ No `brand:` case. SF names the zeron core emits that Roboco lacks: `bubble.left`, `arrow.left.arrow.right`, `exclamationmark.triangle.fill`. rb:transcript/widgets/TranscriptIcon.kt, rb:design/RobocoIcons.kt | `Icon{name,color}` | za:transcript/Widgets.kt:120 | — |
| H9 | RV:289-290 | Chevron (rotates). | ✅ rb:transcript/widgets/ToolRail.kt | `Chevron{expanded}` | — | — |
| H10 | RV:291-293 | ToolRail (trunk, elbows, reveal motion). | ✅ rb:transcript/widgets/ToolRail.kt, ToolRailMotion.kt | `ToolRail{…}` | za:transcript/Widgets.kt:351 | — |
| H11 | RV:294-303 | ToolToggle: "Show/Hide details", selection haptic → `toggleDetail`. | ✅ rb:transcript/widgets/DisclosureWidgets.kt | `ToolToggle{detail,open}` | — | — |
| H12 | RV:338-339 | Shimmer over the live group's title. | ✅ rb:transcript/widgets/Shimmer.kt | `Shimmer` | — | — |
| H13 | RV:304-322 | Action: DangerSoft capsule, Semibold 14 in the background colour, light haptic → `perform(action)`. | ✅ rb:transcript/widgets/ActionButton.kt | `Action{action,label}` | za:transcript/Widgets.kt:138 | M3 `Button(colors, shape = CircleShape)` would also do |
| H14 | RV:323-337, TV:315-335 | **Subagent{docId?, title, running, failed}**: wash on press (ControlFill, 0.12 / 0.28 spring); disabled while `docId == nil`; a11y "Subagent: title", value Working / Failed / Done; light haptic. The card art (spinner, chevron) is core-painted. | ⚠️ Old `SubagentLink{docId, title, frozen}` shape. rb:transcript/widgets/SubagentLink.kt, rb:transcript/RowWidget.kt:65 | `WidgetKind.Subagent` (tools.rs:1189-1200) | za:transcript/Widgets.kt:134-160 | — |
| H15 | RV:340-342, 106-115; TLV:731-741; ios:Transcript/VisualView.swift | **Visual{id}**: one live view per id (moved between reused rows); paused while the row is pooled, off-window or backgrounded; dark mode and reduce motion; pointer and controls; links go to the router. | ❌ | `TranscriptView.visual(id)`, `setVisualSource(client, deviceId, pixelScale)`, `VisualSession` / `VisualListener` | za:transcript/Visual.kt (483 lines) | — |

### I. Image viewer

| # | iOS | Behaviour | Roboco status + Kotlin | zeron core API | za reference | Native option |
|---|---|---|---|---|---|---|
| I1 | IV:6-75 | Full-screen black, zoom ≤5, double-tap 2.5×, zoom transition from the thumbnail, drag to dismiss, glass Close and Share circles. | ✅ rb:image/ImageViewer.kt, ImageViewerMath.kt, ImageShare.kt (Android Sharesheet) | — | za:ui/ImageViewer.kt | No native viewer; Sharesheet is already native. Keep. |
| I2 | TLV:700-708, CSS:391-423 | An image chip fetches the full picture, cached, with a cross-dissolve. | ✅ rb:transcript/TranscriptImageLoader.kt, ImageSources.kt. API swap: `readImage(d, r, chatId)` → `readAttachment(deviceId, path)` | `CoreClient.readAttachment` (mod.rs:528) | — | — |

### J. Haptics in this area

| # | iOS | Behaviour | Roboco status + Kotlin | zeron core API | za reference | Native option |
|---|---|---|---|---|---|---|
| J1 | CB:655 | Send: impact `.medium`. | ✅ rb:design/HapticTable.kt (Confirm) | — | — | Already `performHapticFeedback` first |
| J2 | CB:644 | Stop: impact `.rigid`. | ✅ (Stop) | — | — | — |
| J3 | CB:373, QPN:189, RV:300, TLV:679 | Selection: mention pick, question option, tool toggle, disclosure. | ✅ (Select) | — | — | — |
| J4 | QPN:245 | Question submit: `.medium`. | ✅ | — | — | — |
| J5 | RV:318, 333, 405 | Light: action button, subagent card, copy code. | ✅ (Light) | — | — | — |
| J6 | SVC:840 | Fork success: notification `.success`. | ❌ | — | — | `HapticFeedbackConstants.CONFIRM` |
| J7 | SVC:972 | Credits nudge: notification `.warning`. | ❌ (with A31) | — | — | `REJECT` |
| J8 | ios:Transcript/VisualView.swift:491 | Visual control: selection. | ❌ (with H15) | — | — | — |

**Counts (136 rows, 1 n/a): ✅ 95 · ⚠️ 21 · ❌ 19.**

### Native components vs Roboco's hand-clones (summary)

| Element | Roboco today | Native or Material option | Recommendation |
|---|---|---|---|
| Composer, "Other…" and queue-edit text fields | `BasicTextField` (native IME, selection, autofill, IME actions) | — | Already native: keep |
| Photo, camera, file pickers | System photo picker, `TakePicture`, SAF | — | Already native: keep |
| Menus (⋯, chips, attach, queue ⋯, long-press delivery) | `GlassMenu`: in-window overlay with `BackHandler`, focus and TalkBack semantics. It is deliberately not a Popup, because Haze cannot blur across windows. | M3 `DropdownMenu` + `DropdownMenuItem` (themeable shape and colours; a Popup window: no blur behind, opaque or translucent container only) | Keep `GlassMenu` for the look; add a hardware-keyboard nav check |
| Row context menu (lift preview) | rb:design/ContextMenu*.kt | None with a preview | Keep |
| Sheets (Select Text, tool detail, Log in accounts, companions hub) | rb:design/sheet/GlassSheet.kt | M3 `ModalBottomSheet`: shape, `containerColor`, `dragHandle`, scrim all themeable; predictive back, IME insets, drag-dismiss and a11y for free. A Dialog window, so no Haze blur of the session behind. | **Swap** for content sheets that don't need blur behind them: Select Text, detail, accounts |
| Alerts ("Delete session?", "Can't edit right now") | rb:design/Alert.kt + AlertCard.kt (FIFO queue) | M3 `BasicAlertDialog { custom card }`: Dialog focus, IME, TalkBack, back | **Swap**; the card composable can be reused as the content |
| Chips | rb:composer/ContextChip.kt | M3 `AssistChip` / `FilterChip` | Optional; little a11y gain |
| Jump to latest, glass circles | rb:design/glass/GlassButtons.kt | `SmallFloatingActionButton` | Keep (glass) |
| Keyboard-follow / interactive dismiss | rb:transcript/InteractiveImeDismiss.kt, ImeDragSession.kt | `imePadding()` + `WindowInsetsAnimationCompat` / `imeNestedScroll()` | Evaluate: native is fewer lines and handles OEM keyboards |
| Scroll physics | rb:transcript/IosFlingBehavior.kt, TranscriptRubberBand.kt | Default fling + stretch overscroll | Owner's call (iOS feel vs platform feel) |
| Haptics | HapticTable prefers the view constants | — | Already native |

---

## 2. Painter work for the zeron layout core

I compared the zeron `core:layout/display.rs` and `layout/mod.rs` with what Roboco's Kotlin reads from `uniffi.roboco_core`. Roboco's core source is not in this repo, so its shape is inferred from the Kotlin use sites. Confirmed identical: the `LayoutFrame` API (`revision, width, totalHeight, rowCount, buildMicros, styles, styleCount, preparedHeapBytes, rowsIn, placement, indexOf, indexAt, display, plainText, messageText`), `RowPlacement`, `TextRun`, `Fade`/`FadeEdge`, `Scroller`, `LinkHit`, `Widget`, and the `ColorRole` set (Roboco uses a subset; DangerWash and DangerSoft are present in both). `PlatformMeasurer` has `measure` and `measureRun`, and rb:core/AndroidMeasurer.kt implements both. `TextSystem(faces, measurer)` is unchanged. The core now leaks the face bytes once, so build it once, which `TextEngine.shared` already does.

The required work:

1. **`RowDisplay.glyphs: List<GlyphPrim>` (math).** In rb:transcript/RowModel.kt, add `glyphsByLayer` grouped by `scroller` the same way as runs and boxes, dropping a malformed scroller. Resolve the math fonts at model build (it is off-main safe): `mathFontData(index: UByte)` → `android.graphics.fonts.Font.Builder(ByteBuffer.allocateDirect(..))`. API 29+, so it is fine at minSdk 31. Cache per index process-wide, remember failures, and load lazily (a face is ~1–2 MB). Draw with `Canvas.drawGlyphs(intArrayOf(glyph), 0, floatArrayOf(x·d, y·d), 0, 1, font, paint)` (API 31) with `paint.textSize = size·d` and `paint.color = palette[g.color]`. `y` is already the baseline, so no flip is needed as it is on iOS. Order in `draw()`: boxes → runs → **glyphs** → fades (RM:243-252). Reference: none; za doesn't paint glyphs either (za:transcript/RowModel.kt has no glyph pass), so with math on by default za shows blank formulas. Port from iOS.
2. **Veil passes with `BoxPrim.start` and `GlyphPrim.start`.** Roboco's `Pass.Chrome` (boxes and fades, no range) must become `Chrome(veilFrom)`, skipping boxes whose `start != null && start >= veilFrom`. `Pass.Range(from, to)` must also paint the **formula boxes** with `start in [from, to)` and **whole glyphs** with `start in [from, to)`, unclipped: "a formula veils whole" (RM:185-251). rb:transcript/Veil.kt and VeilDiff.kt pass these in.
3. **Math settings.** Call `engine.setMath(enabled, MathFace, sizePercent)` when the TranscriptState is created and on a settings change. Persist `mathRender` (default true), `mathFont` (default Fira), `mathSize` (90/100/110/120/135/150); the core clamps to 75–200. The settings UI belongs to the settings auditor; rb:transcript/TranscriptState.kt owns the call.
4. **`RowKind.VISUAL` + `WidgetKind.Visual{id}`.** Add the case to the exhaustive `when` in rb:transcript/RowHost.kt:250 (`"row-visual"`), TranscriptSemantics and RowMotion (if it switches on kind). Add a `VisualHost` in TranscriptState: `Map<String, VisualView>` created from `engine.visual(id)` and kept across row reuse. It is attached only while (in window ∧ row realized ∧ app foreground) and paused when the row is pooled. It passes dark mode and reduce motion; `setAppearance(dark)` runs on theme change. Pointer events go to the session, controls (buttons, sliders, links) route URLs through the link router, and accessibility uses `session.title()`. Call `setVisualSource(client, hostDeviceId, max(2, density))` on attach and whenever the host device changes (CSS:14-16, 51-54). Reference: za:transcript/Visual.kt plus core:visual/ (`VisualSession`, `VisualListener.display/frame`, `SurfaceFrame`, `VisualPaint`). The core re-versions rows when a visual's measured height changes (`FrameRow.version` includes `epoch`), so the normal stale check rebuilds them.
5. **`WidgetKind.Subagent{docId: String?, title, running, failed}`** replaces `SubagentLink{docId, title, frozen}`: `frozen = !running`. The card is disabled while `docId == null`. Add the pressed wash (ControlFill, 0.12 s in, 0.28 s spring out), the a11y value Working / Failed / Done, and the Light haptic. The card art, spinner and chevron are separate core widgets (tools.rs:1182-1200). Update the `isControl` / `isTranscriptControl` lists in rb:transcript/RowMenu.kt:193-196 and rb:transcript/TranscriptControl.kt:79-85.
6. **Icon names.** In rb:transcript/widgets/TranscriptIcon.kt add a `Brand(harness)` source for `brand:<harness>`: rb:design/BrandMarks.kt at 32, scaled to the rect, **not tinted** (marks keep their colour). Map `bubble.left`, `arrow.left.arrow.right` and `exclamationmark.triangle.fill` in rb:design/RobocoIcons.kt `forSfName`. Today only `exclamationmark.triangle` exists there (line 427). The sender caption, switch seam and error card depend on these.
7. **Link schemes.** `zeron-preview://image?ref=`, `zeron-chat://open?id=` (new route: open the chat, ignore itself), `zeron-file:`, `zeron-invoke:`. Swap the `roboco-*` cases in rb:transcript/LinkRoute.kt.
8. **Accessibility.** A link element whose URL is `zeron-chat` reads "Open chat" (RV:77-79); visuals are ordered with the controls.
9. **`TranscriptView.attach(client, chatId)`** follows the snapshot Rust→Rust and also carries sender titles from the workspace. `close()` now also drops a subagent follow and shuts down the visuals. Use `attachSubagent(client, parentChatId, docId, frozen)` for the subagent screen instead of Roboco's `client.openSubagent`.
10. Nothing else in `display.rs` is new. `TextRun.revealMs` exists in both, and `Decoration` is unchanged.

---

## 3. Roboco-only code in this area to delete or rework

| Item | Roboco paths | Zeron equivalent | Action |
|---|---|---|---|
| Engine status banners (Parked / Revoked / IdentityChanged / Incompatible → "Session revoked · Tap to pair again", "Engine changed…", "Engine off. Cached history is read-only.", "Reconnecting… Cached history is read-only.") and the re-pair tap | rb:session/StatusBanner.kt (`EngineLink`, `deriveBanner`, `BannerTap.Repair`, `openPairPage`), rb:session/StatusPill.kt:111-125, rb:session/SessionChrome.kt:65-91, rb:session/SessionScreen.kt:394-403 (`LocalPairPage`) | CSS:129-145: Failed / NotDelivered / Queued "host offline — will send" / Sending / Offline "sends are saved" / "Reconnecting in Ns" from `connectivity()` + `room.retryAtMs` | Rewrite `deriveBanner` to the CSS precedence; delete EngineLink, Parked and Repair |
| Read-only mode (`canWrite`) | rb:session/CanWriteFiller.kt, `SessionChrome.canWrite`, SessionMenu gating, `QuestionPanelState.canWrite`, the composer "Offline" action state (rb:composer/ComposerActionButton.kt:486), the mention "offline" gallery | None. Zeron saves sends to an outbox while offline. | Delete |
| Limited banner / Resume at reset / Resume on another account | rb:session/LimitedBanner.kt, LimitedBannerCard.kt, LimitedCore.kt, `SessionChrome.limited`, the stack's Limited slot (rb:session/SessionBottomStack.kt), the `limited` branch of `errorCardLogInRoute` (rb:accounts/AgentAccountsRoute.kt); `ComposerState.limit` / `scheduledResumeFireAtMs` | None. The nearest are the error card's "Log in" (`provider_problem`) and, for Cloud, the credits notice. | Delete |
| Agent previews panel hook | ⋯ "Previews" row (rb:session/SessionMenu.kt:130-135, 276-290) → rb:browser/PreviewPanel*.kt, PreviewsCore.kt (the 7332 proxy) | None on mobile (visuals are a different feature) | Delete the row; the browser module goes with its own auditor |
| "Account & Usage" ⋯ row and the context chip's account rows | rb:session/SessionMenu.kt:136-141, 265-275; `SessionAccountUsage` filler (rb:session/SessionScreen.kt:478-511); rb:composer/ContextChip.kt menu | Usage lives in Settings → Providers (core:client_ffi/providers.rs). The iOS context chip has no menu and shows only at ≥50 %. | Delete from the session screen |
| Setting-change failure pill ("Couldn't change session settings") | `StatusBanner.settingFailure`, `SessionChipMenus.onSettingResult` | iOS `try? setSessionConfig` is silent | Optional: delete for 1:1 |
| `sendingBridge` (web rule) | rb:session/StatusBanner.kt:68-69, 123-125 | `sendState == SENDING` | Simplify |
| Engine-scoped identity | `SessionHandle.engineId()`, `SessionCore.engine()` (rb:session/SessionCore.kt:28-89), `DraftKey.Chat(engineId, chatId)`, `client.readImage(d, r, chatId)`, rb:transcript/EngineReconnects.kt (`imageRetries`) | `ComposerState.host.deviceId`, Drafts keyed by chatId, `readAttachment(deviceId, path)`, `connectivity` | Rework |
| Async wrappers for sync FFI | `suspend` `send / interrupt / respondInput / enqueue / moveQueuedBy / retryDelivery` call sites: rb:composer/QuestionActions.kt (RPC failure re-arm), rb:composer/QueueActions.kt:30-39, rb:session/SessionCommands.kt, StatusBanner `retryDelivery` | Sync in zeron (keep them off main with `withContext(IO)`) | Adapt signatures; drop the re-arm logic |
| Roboco subagent open | rb:sidechats/SubagentViewer.kt:296-327 (`client.openSubagent`) | `TranscriptView.attachSubagent` | Rework (with the companions auditor) |
| Side-chats sheet / draft model | rb:sidechats/SideChatsSheet.kt, SideChatsButton.kt, SideChatDraft.kt, SideChatsModel.kt | Companions hub + strip + `createSideChat` immediately + fresh-chat cleanup | Replace (with the companions auditor) |
| `roboco-*` link schemes and the file-shaped image rule (TRANSCRIPT-D4) | rb:transcript/LinkRoute.kt | `zeron-*`; iOS hands every non-http URL to the system | Rename; decide whether to keep the image-file convenience |
| Tool blob fetch | Not found in the Kotlin session, transcript or composer layers. The only "blob" hit, rb:sidechats/SubagentViewer.kt:109, says the viewer follows the doc, not the edge blob. | — | Nothing to delete in Kotlin. If it exists, it is inside Roboco's Rust core and disappears with the core swap. |
| ChromeFiller registry indirection | rb:session/ChromeFillerRegistry.kt, the fillers registered in `RobocoApplication` (e.g. RobocoApplication.kt:591) | One `refresh()` like CSS:69-166 | Optional simplification once Limited, CanWrite and Usage are gone |

---

## 4. Ordered tasks to reach parity

1. **Swap the bindings** in session/, transcript/, composer/, image/ and sheets/ from `uniffi.roboco_core` to `uniffi.zeron_core`, and fix the breakages:
   - `SubagentLink` → `Subagent`; new `Visual`, `RowKind.VISUAL`, `BoxPrim.start`, `RowDisplay.glyphs`.
   - Sync `SessionHandle` methods; `ComposerState` without `limit` / `scheduledResumeFireAtMs`.
   - `readImage` → `readAttachment`; `searchFiles(+spaceId)`; `engineId` → `host.deviceId`.
2. **Delete the Roboco-only chrome** listed in §3: CanWrite, the engine banners and pairing hooks, Limited, Previews, Account & Usage and the context-chip menu. Re-derive the pill per CSS:129-145, including Offline, "Reconnecting in Ns" and Queued (B2–B6).
3. **Painter**: glyph math, veil passes with `start`, `setMath` (§2.1–3); `brand:` and the new SF icons (§2.6); the Subagent widget (§2.5); `zeron-*` links plus the `zeron-chat` route and "Open chat" a11y (§2.7–8, A29, F12, F16).
4. **Visuals**: RowKind and widget host, `setVisualSource` on attach and host change, selection haptic (§2.4, G8, H15, J8). Port from za:transcript/Visual.kt into Roboco's row host.
5. **Question panel close** (E7), then check that the answered-summary chips paint (E8).
6. **Header and ⋯ menu to match iOS**: no bar title, and the side-chat title (A1, A2). Menu order: inline [New Side Chat, Fork Into Side Chat, Terminal], Pin, Copy Transcript, Archive, Delete (A5–A11). Fork toast and success haptic (A6, J6). Create side chats immediately and clean up fresh ones (A5, A19).
7. **Companions** (with the companions auditor): header button with the running-agent badge, the pill-row strip, `presentCompanion` (inspector or push), the Terminal entry, Ctrl+J and Ctrl+Alt+0 (A7, A12, A14, A27, A28, A34, B9).
8. **Error-card "Log in"** → the accounts sheet via `signInTarget` (Cloud or device), started at once (A30).
9. **Chips**: the model chip's host-default label (C12); the context chip only at ≥50 %, with no menu (C16).
10. **Cloud bits** (with the Cloud auditor): credits notice, nudge and send gate; the Cloud banner; the Cloud wording in Delete (A11, A31, A32, J7).
11. **Voice bar item** (with the voice auditor) (A13).
12. **Optional native swaps** after parity: alerts → `BasicAlertDialog` with the glass card; the Select Text, detail and accounts sheets → `ModalBottomSheet`; evaluate native IME-follow (`imePadding` / `WindowInsetsAnimationCompat`). Keep GlassMenu, the row context menu, the image viewer and the glass circles custom.
