# zeron-android — Spec

Status: draft for review · Zeron pin: v0.2.129 · Last updated: 2026-10-29 · Roadmap: #67

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
   window size classes and folding features. Components follow the
   Radix/shadcn model: our look on headless, accessible primitives (Compose
   Foundation, Compose Unstyled), each meeting an accessibility contract (D7).
4. **No core patches.** Adapt in Kotlin.
5. **Nothing builds locally.** All compilation and tests
   run on GitHub Actions; the owner's machine needs only git + gh.
6. **Small, reviewable PRs**, one ticket each, with CI green. The owner tests
   CI-built APKs on their own phone; there are no CI screenshot renders.

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
  runs JVM tests, and uploads the APK (signed with the shared test key from
  repo secrets) as an artifact. Every PR gets an installable APK.
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
- Quarantine: `app/src/test-quarantine/{test,androidTest}/…` mirrors the
  original source-set paths. It is not a Gradle source set, so nothing there
  compiles.
- **Revive rule**: when an area ticket ports an area, the same PR `git mv`s
  that area's tests from `app/src/test-quarantine/<set>/…` back to
  `app/src/<set>/…` and fixes or rewrites them against Zeron's core. A test
  for a removed Roboco system is deleted instead. Nothing is revived without
  compiling and passing in that PR.
- Smoke suite (`app/src/test`): pure-Kotlin tests of code that touches no
  `uniffi.*` type and no fake (design, geometry, formatting, scroll math). CI
  runs `:app:testDebugUnitTest` after `assembleDebug`.
- No CI screenshot renders: every CI run uploads a debug APK (signed with the
  shared test key, so builds install over each other) and the owner tests it
  on their phone.
- Demo mode (`Credentials.Demo`) stays available as a debug launch extra.

## 5. Decisions

| # | Decision | Status |
|---|---|---|
| D1 | applicationId and package `sh.zeron.android`; replaces the official app on a device (uninstall it first: different signing key) | decided (owner) |
| D2 | Repo = standalone app + `zeron/` submodule pinned to release tags | decided (owner) |
| D3 | CI-only builds; no local Android SDK | decided (owner) |
| D4 | Sign-in: `workosAuthorizeUrl` → Auth Tab (fallback Custom Tab) → `zeron://callback` → `parseAuthCallback` (state check) → `authExchangeCode` → `authListOrgs` (picker if >1) → `authRefresh` → `CoreClient(Credentials.WorkOs)` | decided (owner) |
| D5 | Credentials: Roboco's `KeystoreSecretCipher` (AES-GCM, non-exportable) under a small `CredentialStore` with **synchronous** writes (Zeron Android's async `apply()` can lose a rotated refresh token) | decided (owner) |
| D6 | Notifications: no push (the edge is APNs-only). A Kotlin `SessionWatcher` applies the edge's done / needs-input / failed rule to workspace snapshots; Roboco's `Notifier` + `RunWatchService` deliver; Zeron's `DeliveryService` holds the process after a send | decided (owner) |
| D7 (#1) | Components: our look on headless, accessible primitives (Compose Foundation, Compose Unstyled), each with an accessibility contract (role, label, state, focus, keyboard, predictive back, IME). Glass surfaces stay in-window (separate-window Dialog/Popup can't blur behind): overlays are Compose Unstyled `Modal`s under a root `ModalHost`, rendered through its Portal. Applied by #62; the component lab (#12) is removed | decided (owner): headless for every component |
| D8 (#2) | "Stay connected in background": **dropped** (iOS has none), and no background catch-up worker. RunWatch notifies for any run working when the app was last opened (Roboco's behaviour); runs started on the desktop while the app hasn't been opened notify only once it is | decided (owner) |
| D9 (#3) | Real Android push upstream: **not planned** | decided (owner) |
| D10 (#4) | Licences page: **none** (iOS has none); licence texts ship inside the APK assets | decided (owner) |
| D11 (#5) | Keep iOS scroll physics (rubber band, iOS fling). Drop Android-only extras: launcher shortcuts, empty/no-results/loading copy, the "● N" running-agents pill on session rows | decided (owner) |
| D12 | Demo, dev credentials and routes are debug launch extras only, as on iOS (no "Explore the demo" button) | decided (owner) |
| D13 | Voice in scope: WebRTC (`io.github.webrtc-sdk:android`), microphone foreground service + CallStyle notification as the Live Activity stand-in | decided (owner) |
| D14 | Visual shaders/3D need Vulkan; devices without it get the core's software fallback / error state | decided (owner) |

## 6. Milestones

Each milestone ends with a CI-built APK the owner can install. Ticket IDs are
GitHub issues in this repo.

| Milestone | Outcome |
|---|---|
| **M0 Foundation** | Builds on Zeron's core in CI: plumbing, rename, Roboco-only systems removed, bindings swapped, tests quarantined, in-app component lab |
| **M1 Account** | Sign in to the real workspace: session holder, credentials, Auth Tab, welcome, onboarding (non-Cloud steps), sign-out, demo/dev extras, `zeron://` links |
| **M2 Shell & sessions** | Sessions list and shell at iOS parity: live data, groups/folds, pull to refresh, drag between sections, split/foldable, New Project |
| **M3 Session, transcript, composer** | Chat screen at parity: status pill, painter (math, veil, icons, subagent card, links), header/menu, question panel, chips, error-card log-in |
| **M4 New session, picker, settings** | New session on devices, model picker, settings restructure incl. Math |
| **M5 Companions** | Side chats, companions strip/panel, subagent screen, terminals, inspector column |
| **M6 Visuals** | `show_visual` rows on Android |
| **M7 Cloud & providers** | CloudStore, Providers + agent accounts, Cloud settings/GitHub/repos/billing, Cloud in new session and sessions, Cloud onboarding steps |
| **M8 Notifications** | SessionWatcher, RunWatch on snapshots, delivery hold, notification settings |
| **M9 Voice** | Voice core and UI, settings, call notification |
| **M10 Polish & release** | Component swaps (D7), accessibility pass, signed releases |

## 7. Ticket conventions

- Title: `[M#] <outcome>`; labels `area:*` + `type:*`; GitHub milestone = M#.
- Body: **Why** · **iOS reference** (files) · **Today** (Roboco files) ·
  **Zeron refs** (core API, `zeron/apps/android` file) · **Tasks** ·
  **Acceptance** · **Audit rows** (`docs/port/0N-*.md` row IDs) ·
  **Depends on**.
- Done = CI green, acceptance met, audit rows ticked in the PR description.
  Ignore any older "attach renders" wording in tickets.
- `type:decision` issues hold owner decisions; the spec's §5 links them.
