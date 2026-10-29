# zeron-android — Spec

Status: draft for review · Zeron pin: v0.2.129 · Last updated: 2026-10-29

## 1. Goal

An Android app for Zeron that is a **1:1 port of the current Zeron iPhone app**
(`zeron/apps/ios`), in look and behaviour, running on **Zeron's own Rust mobile
core** (`zeron/crates/mobile`, `uniffi.zeron_core`) against the real Zeron
backend (WorkOS sign-in, the edge, synced workspace, command ledger).

The starting point is the Roboco Android app (imported at the repo root),
which already ports iOS as of upstream `edb23db4` (2026-10-11) on Roboco's own
LAN core. The work is: move it onto Zeron's core, remove Roboco-only systems,
and port everything iOS has gained or Roboco dropped.

Scope is **everything the current iOS app has**: sign-in and onboarding,
sessions, transcript, composer, model picker, new session (device + Cloud),
companions (subagents, side chats, terminals), agent visuals, LaTeX math,
Cloud / GitHub / billing, providers and agent accounts, voice, settings,
tablet/foldable layouts, notifications.

## 2. Non-goals

- Patching Zeron's Rust core or edge. The core is consumed as-is from the
  submodule. If a gap truly needs a core/edge change it becomes an upstream PR
  ticket, never a fork-only patch (see D9).
- iOS-only surfaces: Live Activity / Dynamic Island (→ an ongoing call
  notification), iPhone Duo-specific code (→ Android foldable handling), the
  iOS debug transcript lab.
- Roboco features with no iOS counterpart: LAN pairing, QR scanner, Tailcat,
  rooms/fleets, agent previews panel + in-app browser, account failover /
  "resume on another account", the self-updater, read-only offline mode,
  engine detail pages.
- Play Store distribution (sideloaded APKs from GitHub; may revisit).

## 3. Principles

1. **iOS is the source of truth** for layout, copy, states, gestures and
   ordering. Every ticket cites the iOS file(s) it ports.
2. **Zeron's Android app is the reference for wiring**, not for looks: how it
   calls the core (`AppModel.kt`, `Cloud.kt`, `Terminals.kt`, `Visual.kt`,
   `voice/*`, `DeliveryService.kt`) is lifted; its Material 3 Expressive UI is
   not.
3. **Our look, Android's behaviour.** Keep Roboco's custom design system
   (glass, palette, Geist type, painter) for anything visible. Use the
   platform wherever the system owns the experience: photo/file/camera
   pickers, share sheet, Auth Tab / Custom Tab, permission prompts,
   notifications, IME insets, text selection, predictive back, TalkBack,
   window size classes and folding features. Swapping a custom component for
   a restyled Material one is a per-component owner decision (D7).
4. **No core patches.** Adapt in Kotlin.
5. **Nothing builds locally.** All compilation, tests and screenshot renders
   run on GitHub Actions; the owner's machine needs only git + gh.
6. **Small, reviewable PRs**, one ticket each, with CI green and screenshots
   attached for UI changes.

## 4. Architecture

### 4.1 Repository

```
zeron/          submodule → zeronsh/zeron, pinned to a release tag (v0.2.129)
app/            the Android app (package sh.zeron.android)
buildSrc/       Gradle helpers (prebuilt-core import, assets, fonts, chimes, licences)
tools/          android-assets: SVG → VectorDrawable / PNG (vendored from Roboco)
docs/SPEC.md    this file
docs/port/      the five iOS → Android gap audits (row IDs referenced by tickets)
.github/        CI
```

- **Zeron pin**: bump the submodule to the release the desktop runs. A bump is
  its own PR; CI rebuilds the core and the app shows the binding diffs.
- **Application id / package**: `sh.zeron.android` (D1).

### 4.2 Build and CI

- `core` workflow: `zeron/scripts/android/build-core.sh` on ubuntu
  (cargo-ndk, NDK 29.0.14206865, arm64-v8a, profile `mobile`; `mobile-dist`
  for releases) → `core-out/{kotlin,jniLibs,host}`. `host/` is a Linux build
  of the core for Robolectric (JNA). Cached by submodule SHA; only runs when
  `zeron/` or the workflow changes.
- `app` workflow: consumes `core-out` (`-PzeronCoreDir`), builds the debug APK,
  runs JVM tests and Roborazzi screenshot renders, uploads the APK and the
  screenshots as artifacts. Every PR gets an installable APK.
- `release` workflow (M10): signed arm64 APK on a GitHub Release.
- Packaging ships `libc++_shared.so` beside `libzeron_mobile.so`; R8 keep
  rules for JNA/UniFFI (and WebRTC once voice lands).

### 4.3 App layers

- **Session holder** (`AppModel`-style, replaces Roboco's `CoreStartup`):
  owns `StateFlow<CoreClient?>`; `start(credentials)`, `signOutLocally(forget)`,
  per-account core dir, device id, `ClientListener` (persists
  `AuthRefreshed` synchronously, handles `AuthExpired`), lifecycle hooks
  (`onForeground/onBackground`, `setNetworkOnline`, `networkPathChanged`).
- **Feature ports** stay: Roboco's narrow, fakeable interfaces per feature
  (`SessionCore`, `NewSessionCore`, `WorkspaceFeed`, …) are re-pointed at
  `uniffi.zeron_core`. Sync FFI calls run off main (`Dispatchers.IO`).
- **Painter** consumes `LayoutFrame` / `RowDisplay` (same API as Roboco's)
  plus Zeron additions: `GlyphPrim` (math), `BoxPrim.start`, `RowKind.Visual`,
  `WidgetKind.Visual`, `WidgetKind.Subagent`.
- **Root**: `Welcome → Onboarding → Shell` (phone stack or split), replacing
  Roboco's pairing root.

### 4.4 Tests and previews

- Roboco's ~136k lines of JVM tests target Roboco's core and fakes. They are
  **quarantined** (excluded from compilation) in M0 and revived per area as
  that area is ported; each area ticket revives or rewrites its tests.
- Screenshot renders (Roborazzi, JVM, no emulator) are the preview channel:
  CI uploads them; UI tickets attach before/after next to the iOS reference.
- Demo mode (`Credentials.Demo`, launch extras) gives renders and manual QA a
  workspace without signing in.

## 5. Decisions

| # | Decision | Status |
|---|---|---|
| D1 | applicationId and package `sh.zeron.android`; replaces the official app on a device (uninstall it first: different signing key) | decided (owner) |
| D2 | Repo = standalone app + `zeron/` submodule pinned to release tags | decided (owner) |
| D3 | CI-only builds; no local Android SDK | decided (owner) |
| D4 | Sign-in: `workosAuthorizeUrl` → Auth Tab (fallback Custom Tab) → `zeron://callback` → `parseAuthCallback` (state check) → `authExchangeCode` → `authListOrgs` (picker if >1) → `authRefresh` → `CoreClient(Credentials.WorkOs)` | proposed |
| D5 | Credentials: Roboco's `KeystoreSecretCipher` (AES-GCM, non-exportable) under a small `CredentialStore` with **synchronous** writes (Zeron Android's async `apply()` can lose a rotated refresh token) | proposed |
| D6 | Notifications: no push (the edge is APNs-only). A Kotlin `SessionWatcher` applies the edge's done / needs-input / failed rule to workspace snapshots; Roboco's `Notifier` + `RunWatchService` deliver; Zeron's `DeliveryService` holds the process after a send | proposed |
| D7 | Custom vs Material components: decided per component after the component lab renders (custom / Material restyled / stock side by side) | **owner decision** |
| D8 | "Stay connected in background" (Roboco's opt-in foreground service): keep as an Android-only setting, because without push it is the only way to hear about runs started on the desktop while the phone is idle | **owner decision** |
| D9 | Real Android push: propose FCM and/or UnifiedPush support to upstream (edge only, no core patch) — later, separate track | **owner decision** |
| D10 | Open-source licences page: needs a home since iOS has no About section | **owner decision** |
| D11 | Android-only extras kept or dropped: launcher shortcuts (`zeron://new|settings|search`), empty/no-results copy, the "● N agents" pill, iOS scroll physics vs platform fling | **owner decision** |
| D12 | Demo, dev credentials and routes are debug launch extras only, as on iOS (no "Explore the demo" button) | proposed |
| D13 | Voice in scope: WebRTC (`io.github.webrtc-sdk:android`), microphone foreground service + CallStyle notification as the Live Activity stand-in | proposed |
| D14 | Visual shaders/3D need Vulkan; devices without it get the core's software fallback / error state | proposed |

## 6. Milestones

Each milestone ends with a CI-built APK the owner can install. Ticket IDs are
GitHub issues in this repo.

| Milestone | Outcome |
|---|---|
| **M0 Foundation** | Builds on Zeron's core in CI: plumbing, rename, Roboco-only systems removed, bindings swapped, tests quarantined, component lab renders |
| **M1 Account** | Sign in to the real workspace: session holder, credentials, Auth Tab, welcome, onboarding (non-Cloud steps), sign-out, demo/dev extras, `zeron://` links |
| **M2 Shell & sessions** | Sessions list and shell at iOS parity: live data, groups/folds, pull to refresh, drag between sections, split/foldable, New Project |
| **M3 Session, transcript, composer** | Chat screen at parity: status pill, painter (math, veil, icons, subagent card, links), header/menu, question panel, chips, error-card log-in |
| **M4 New session, picker, settings** | New session on devices, model picker, settings restructure incl. Math |
| **M5 Companions** | Side chats, companions strip/panel, subagent screen, terminals, inspector column |
| **M6 Visuals** | `show_visual` rows on Android |
| **M7 Cloud & providers** | CloudStore, Providers + agent accounts, Cloud settings/GitHub/repos/billing, Cloud in new session and sessions, Cloud onboarding steps |
| **M8 Notifications** | SessionWatcher, RunWatch on snapshots, delivery hold, notification settings |
| **M9 Voice** | Voice core and UI, settings, call notification |
| **M10 Polish & release** | Component swaps (D7), accessibility pass, parity screenshot board, licences, signed releases |

## 7. Ticket conventions

- Title: `[M#] <outcome>`; labels `area:*` + `type:*`; GitHub milestone = M#.
- Body: **Why** · **iOS reference** (files) · **Today** (Roboco files) ·
  **Zeron refs** (core API, `zeron/apps/android` file) · **Tasks** ·
  **Acceptance** · **Audit rows** (`docs/port/0N-*.md` row IDs) ·
  **Depends on**.
- Done = CI green, acceptance met, screenshots (UI) attached next to iOS,
  audit rows ticked in the PR description.
- `type:decision` issues hold owner decisions; the spec's §5 links them.
