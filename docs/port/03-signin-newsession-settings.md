# 03 · Sign-in / onboarding · New session · Model picker · Settings: port-gap audit

Scope: iOS at `zeron/` v0.2.129 (`8b7afb30`). This is the source of truth. It is compared with the Roboco copy in `app/src/main/java/sh/roboco/android/`, which was ported at upstream `edb23db4`.
Upstream commits since `edb23db4` that touch this area (`git -C zeron log edb23db4..HEAD`):
`cc7da5c2` (Zeron Cloud, provider switching, the phone model picker, #966), `f30a8641` (log in from error cards, #967), `c02739da` (LaTeX math and its Settings section, #912), `fd7d268c`/`dfbb6876` (terminals, side chats), `c526d6f7` (iPhone Duo).
`SignInViewController` was rewritten in #966, from a single "Sign In" page into a welcome page that opens onboarding. `MoreViewController` gained the Agents (Providers/Cloud/Billing) and Math sections.

Paths in the tables are shortened as follows:
- `ios:` = `zeron/apps/ios/Zeron/`
- `rb:` = `app/src/main/java/sh/roboco/android/`
- `za:` = `zeron/apps/android/app/src/main/java/sh/zeron/android/`
- `core:` = `zeron/crates/mobile/src/client_ffi/` (Kotlin names are camelCase in `uniffi.zeron_core`)

Legend: ✅ ported 1:1 · ⚠️ ported with differences (see note) · ❌ missing · N/A not an iOS feature.

> **Note on "access / plan mode".** It is **not** in iOS v0.2.129 and not in the mobile FFI. `ChatConfig`
> (`core:types.rs:772-783`) has no access or plan field. Commit `1295d577` (#1088) adds access modes and plan mode on the desktop only, and
> makes `CoreClient.setSessionConfig` keep the chat's own mode ("The phone does not pick a mode yet",
> `core:mod.rs:243-252`). New chats from the phone get `AccessMode::default()`. Nothing to port; revisit when upstream adds it.

---

## 1. Gap table

### 1A. Welcome / sign-in (`ios:Shell/SignInViewController.swift`, `ios:App/AppModel.swift`, `ios:App/SceneDelegate.swift`)

| # | iOS (file:line) | Behaviour | Roboco status + Kotlin file | zeron core API | zeron android ref | Native-Android option |
|---|---|---|---|---|---|---|
| 1 | SignInVC:58-106 | Welcome page: full-bleed `WallpaperView`, Zeron mark 46×53, "Welcome to Zeron" (Sans SemiBold 30), tagline "Your coding agents, from anywhere." (17, secondary), full-width capsule **"Get started"** 24 pt side inset, 16 pt above the safe-area bottom; top stack centred −40 on the safe area | ⚠️ `rb:pairing/PairingRoot.kt` keeps the **old** iOS sign-in layout: centred on the window at −80, a 64 dp launcher tile, "Roboco" title, a status line, and a "Pair an engine" glass capsule. No wallpaper. Reskin it into the welcome page | – | `za:ui/SignInScreen.kt` (M3 look, different copy, adds a demo button iOS doesn't have) | `Button(shape = CircleShape, colors = buttonColors(palette.text, palette.background))` is enough for the capsule; the rest is plain layout |
| 2 | SignInVC:108-114 | "Get started" presents onboarding full-screen with a cross-dissolve. It opens on `.appearance` unless `onboarding.appearanceSeen`, otherwise on `.sync` | ❌ | – | `za:ui/OnboardingScreen.kt` (only runs after sign-in) | Navigation route + `AnimatedContent`/`Crossfade` |
| 3 | SignInVC:4-12, 33-43 | Endpoints: edge `https://edge.zeron.sh`, WorkOS client `client_01KWD…`, scheme `zeron`, AuthKit authorize URL (`response_type=code`, `provider=authkit`, `redirect_uri=zeron://callback`, `state`) | ❌ | `authProductionEdgeUrl()`, `authCallbackScheme()`, `workosAuthorizeUrl(state)` (`core:mod.rs:569-584`) | `za:core/AppModel.kt:309,326-330` | – |
| 4 | SignInVC:14-31; AppModel:208-214 | DEBUG only: sticky `-edge <url>` / `-workos <client>` overrides kept in UserDefaults; `prod` clears them | ❌ | `workosAuthorizeUrl` hard-codes the client id (`zeron/crates/client/src/auth.rs:77-84`), so an override needs a Kotlin URL builder | – | Debug-only launch extras (`LaunchOptions`) and SharedPreferences |
| 5 | SignInVC:125-140 | `ASWebAuthenticationSession` with a random `state` UUID and `prefersEphemeralWebBrowserSession = false` (shares browser cookies) | ❌ | – | `za:ui/SignInScreen.kt:175-176` (Custom Tab) plus a persisted `authState` (`za:core/AppModel.kt:207-210,326-330`) | **AuthTab** (`androidx.browser` ≥1.9, the counterpart of ASWebAuth: it returns a result) with a fallback to `CustomTabsIntent` and the `zeron://callback` intent filter. See §2 |
| 6 | SignInVC:130-131,141 | User cancels: no error; the button goes back to "Log in" | ❌ | – | – (za can't tell when the user cancels) | AuthTab `RESULT_CANCELED`. Custom-Tab fallback: if the activity resumes with no callback, reset quietly |
| 7 | SignInVC:142-145 | Callback must carry a `code` and the same `state`, otherwise "Sign-in didn't complete. Try again." (the same text for a provider error) | ❌ | `parseAuthCallback(url) → AuthCallback.Code{code,state}` / `.Error{error,description}` (`core:mod.rs:587-590`) | `za:core/AppModel.kt:333-347` (⚠️ uses "Sign-in was interrupted. Try again." and shows the provider's description) | – |
| 8 | AppModel:224-228 | Exchange the code (unscoped pair), list orgs. No orgs → "This account isn't in an organization yet." | ❌ | `authExchangeCode(edge, code)`, `authListOrgs(edge, access)` (`core:mod.rs:593-610`) | `za:core/AppModel.kt:349-356` | – |
| 9 | SignInVC:155-166; AppModel:229 | **Org picker**: one org is picked automatically. With several, an action sheet "Choose an organization" lists one row per `org.name` plus Cancel. Cancel stops sign-in quietly, with no error | ❌ | `AuthOrg{id, organizationId, name}` | `za:ui/SignInScreen.kt:197-216` (AlertDialog list with "Open" buttons, not 1:1) | Reuse `rb:design/ActionSheet.kt` (already iOS-exact), or a themed `ModalBottomSheet` with `ListItem` rows |
| 10 | AppModel:230 | Refresh with the chosen org to get org-scoped tokens | ❌ | `authRefresh(edge, refresh, organizationId)` (`core:mod.rs:613-625`) | `za:core/AppModel.kt:362` | – |
| 11 | AppModel:231-233 | Save the display profile: name = first + last (trimmed, blanks dropped), email, org name | ❌ | `AuthUser{id,email,firstName,lastName}` | `za:core/AppModel.kt:363-365`, `za:core/CredentialStore.kt:95-101` | – |
| 12 | AppModel:234-236 | Start the client; on failure show "Couldn't open your workspace. Try signing in again."; on success call `onSignedIn` | ❌ | `CoreClient(config, Credentials.WorkOs(userId, orgId, tokens), listener)` | `za:core/AppModel.kt:366-368` | – |
| 13 | SceneDelegate:24-29,162-181 | Signed in: root cross-fade (0.35 s) to the shell (presented sheets dismissed first), `shellShown()`, then onboarding over the shell from `.github` **without animation** | ⚠️ `rb:shell/RootCrossfade.kt` and `rb:shell/AppRoot.kt` already cross-fade Pairing↔Shell in 350 ms, but on the `hasEngines` trigger | – | `za:ui/ZeronRoot.kt:82,173` | – |
| 14 | Onboarding:326-335 | Sign-in errors appear in the Sync step's status label (danger colour); signed in shows "Signed in as <email>" | ❌ | – | `za:ui/OnboardingScreen.kt:409-425` (signed-in text only) | – |
| 15 | SignInVC:150 | Error text is `error.localizedDescription`. For `CoreError` that is `String(reflecting:)`, which is ugly | ❌ | Kotlin `CoreException.*.reason` (renamed in `zeron/crates/mobile/uniffi.toml`) | `za:core/Cloud.kt:296-309` `CloudStore.message(e)` | Show `reason` (a deliberate improvement) |

### 1B. Credentials, lifecycle, demo (`ios:App/AppModel.swift`)

| # | iOS (file:line) | Behaviour | Roboco status + Kotlin file | zeron core API | zeron android ref | Native-Android option |
|---|---|---|---|---|---|---|
| 16 | AppModel:103-140 | At launch: stored credentials → `start()` straight away (offline-first). Nothing stored → welcome | ⚠️ `rb:core/CoreStartup.kt` loads paired engines from `rb:pairing/PairedEngineStore.kt` and builds **one** long-lived `CoreClient(config, engines, rooms, listener)`. Zeron needs a **nullable, recreated** client per identity | `CoreClient(config, credentials, listener)` (`core:mod.rs:87-98`) | `za:core/AppModel.kt:218-249,277-303` | – |
| 17 | AppModel:815-900 | Keychain item `sh.zeron.ios`/`credentials`: JSON `{kind: workos\|dev, userId, orgId, access, refresh}`, `AfterFirstUnlock` | ⚠️ `rb:pairing/KeystoreSecretCipher.kt` (AES-256-GCM, AAD, StrongBox, key made on encrypt only) is a better base, but it only stores engine credentials | `Credentials.WorkOs / Dev / Demo` (`core:types.rs:185-196`) | `za:core/CredentialStore.kt` | Android Keystore (see §2.4) |
| 18 | AppModel:902-923 | `AccountProfile {name, email, orgName}` in the Keychain; cleared on sign-out | ❌ | – | `za:core/CredentialStore.kt:95-101` (plain prefs) | – |
| 19 | AppModel:155-173 | `claimCoreDir`: `appSupport/core/.owner = "<userId>/<orgId>"`. A different identity wipes the store first. Demo uses `appSupport/demo` | ❌ | – | `za:core/AppModel.kt:262-275` | `noBackupFilesDir/core` |
| 20 | AppModel:176-188 | `CoreConfig(edgeUrl, dataDir, deviceId, deviceName = UIDevice.name, platform "ios", appVersion)` | ⚠️ Roboco's `CoreConfig` has no edge, identity or platform | `CoreConfig` | `za:core/AppModel.kt:282-289` (`platform "android"`, `Settings.Global.DEVICE_NAME`) | – |
| 21 | AppModel:216-221 | Device id `ios-<8 hex>`, created once and persisted | ❌ (Roboco ids are per engine) | – | `za:core/AppModel.kt:311-318` (`android-<8>`) | – |
| 22 | AppModel:372-373 | `ClientEvent.AuthRefreshed(tokens)` → persist the new pair **now** (the old refresh token is spent) | ❌ | `ClientEvent.AuthRefreshed` (`core:types.rs:347-350`) | `za:core/AppModel.kt:435` (⚠️ `apply()` is async; see §2.4) | – |
| 23 | AppModel:374-375, 246-249 | `ClientEvent.AuthExpired(reason)` → `onSignOut` **without** forgetting the store (the outbox may hold unsent messages) → welcome | ❌ | `ClientEvent.AuthExpired` (`core:types.rs:351-353`) | `za:core/AppModel.kt:436` | – |
| 24 | AppModel:651-665 | `backfillProfile`: an old login without an org name lists orgs with the stored access token and fills `orgName` | ❌ | `authListOrgs` | `za:core/AppModel.kt:375-384` | – |
| 25 | AppModel:637-649 | `accountName`: "Demo" / "Signed out" / name ?? email ?? "Signed in". `accountDetail`: "Offline demo workspace" / "email · org" / "Zeron account" | ❌ | – | `za:core/AppModel.kt:415-425` | – |
| 26 | AppModel:84,135-151,178 | **Demo**: only `-demo`, or `-route` with nothing stored. There is **no demo button** in the iOS UI. `DemoOptions(fixture: standard/noProjects/iosOnly, transcriptScale, streamSpeed, longReply)`, its own data dir; the new-session draft stays in memory only | ❌ (Roboco has no demo) | `Credentials.Demo(DemoOptions)`, `DemoFixture.{Standard,NoProjects,IosOnly}` (`core:types.rs:121-128`), `CoreClient.isDemo()` | `za:core/AppModel.kt:236-260,305-307` (⚠️ adds the "Explore the demo" button) | Debug launch extras `--ez demo true` etc. |
| 27 | AppModel:124-134 | DEBUG `-signedout` wipes credentials; `-dev <userId> <orgId>` starts `Credentials.Dev` (not persisted) | ❌ | `Credentials.Dev` | `za:MainActivity.kt` (`signedout`) | Debug launch extras |
| 28 | AppModel:239-276; SceneDelegate:30-35 | **Sign out**: `voice.stop`, `forget = true` → `push.signingOut` (unregisters push) → `signOutLocally`: `client.shutdown()`, nil the client, clear credentials and profile, delete the core dir (forget only), `cloud.reset`, `terminals.signOut`, clear all in-memory workspace state, reset `lastDraft`/new-session text/images → welcome | ❌ | `CoreClient.shutdown()`, `unregisterPushTarget()` | `za:core/AppModel.kt:386-413` | – |
| 29 | Onboarding:44-56 | Onboarding shows once per user (`onboarded:<client.userId()>`), never in demo | ❌ | `CoreClient.userId()` | `za:ui/OnboardingScreen.kt:107-122` | SharedPreferences |

### 1C. Onboarding flow and steps (`ios:Cloud/OnboardingViewController.swift`; the Cloud pages are covered in another audit)

| # | iOS (file:line) | Behaviour | Roboco status + Kotlin file | zeron core API | zeron android ref | Native-Android option |
|---|---|---|---|---|---|---|
| 30 | Onb:10-24,188-210 | Steps `appearance, sync, github, agents, notifications, done`. Progress is 5 bars 20×3 (accent up to the current step, `controlFill` after) plus "N of 5" (12, tertiary). Done has no number | ❌ | – | `za:ui/OnboardingScreen.kt:93-105,267-282` | `LinearProgressIndicator` can't draw separate bars; a `Row` of `Box`es is simplest |
| 31 | Onb:212-260 | Footer: "Back" (ghost) on the left. Before sign-in, Back on the first step returns to the welcome page. On the right: Continue / Log in / Skip for now / Not now + Turn on. Back from Agents skips GitHub when Cloud is withheld | ❌ | – | `za:ui/OnboardingScreen.kt:207-235` | `TextButton` + filled capsule `Button`; `BackHandler` |
| 32 | Onb:145-186 | Page change: the new page slides in 20 pt and fades in over 0.3 s easeOut while the old one fades out. Instant with Reduce Motion | ❌ | – | `za:ui/OnboardingScreen.kt:190-205` | `AnimatedContent` (slideInHorizontally + fadeIn) |
| 33 | Onb:78-87; `ios:Design/Wallpaper.swift` `soften()` | Wallpaper behind every step, **softened** (240 px wide, Gaussian blur radius 14, saturation 1.15) everywhere except the Appearance step | ❌ `rb:design/WallpaperHero.kt` has no softened mode | – | `za:ui/OnboardingScreen.kt:249-265` | `RenderEffect.createBlurEffect` (API 31+) or a pre-blurred bitmap |
| 34 | Onb:262-272 | Appearance step: "Make it yours" heading, then a **segmented control** System/Light/Dark under "Color scheme" that writes `appearance` and applies it at once | ❌ | – | `za:ui/OnboardingScreen.kt:330-363` (M3 ToggleButtons) | `SingleChoiceSegmentedButtonRow` + `SegmentedButton` (icon = {}, themed colours and shape) |
| 35 | Onb:274-314, 641-673 | Wallpaper card: "Choose image"/"Change" button. When set: "Background effect" glass capsule menu (5 effects + "Remove Wallpaper"), plus a 132 pt preview above the card | ❌ (the pieces exist: `rb:settings/WallpaperRows.kt`, `rb:core/WallpaperStore.kt`) | – | `za:ui/OnboardingScreen.kt:365-405` | Photo Picker (`PickVisualMedia`); menu, see §1F/§4 |
| 36 | Onb:317-337, 616-628 | Sync step: "Sync your work" plus 3 info rows (On every device / Run agents anywhere / Zeron Cloud). "Log in" (becomes "Logging in…", disabled) runs WorkOS sign-in. Signed in: "Continue" | ❌ | (§2) | `za:ui/OnboardingScreen.kt:409-425` | – |
| 37 | Onb:339-358 | GitHub step (Cloud only; skipped when withheld). Covered by the Cloud audit | ❌ | `githubConnectStart/Poll` | `za:ui/OnboardingScreen.kt:427-` | – |
| 38 | Onb:360-410 | Agents step embeds **Settings → Providers** with summary text. Covered by the Providers audit | ❌ | – | `za:ui/OnboardingScreen.kt` `AgentsPage` | – |
| 39 | Onb:412-422,248-256 | Notifications step: 3 rows (Finished / Needs you / Something went wrong). "Turn on" sets the enabled flag and asks for permission, then Done. "Not now" goes to Done | ❌ (the pieces exist: `rb:notify/NotificationPermission.kt`, `rb:notify/NotificationPrefs.kt`) | – | `za:ui/OnboardingScreen.kt:223-231` | `ActivityResultContracts.RequestPermission(POST_NOTIFICATIONS)` |
| 40 | Onb:424-501,634-638; SceneDelegate:97-100 | Done: accent-soft check badge 52, "You're all set" (copy depends on Cloud), summary card (Appearance / Sync / GitHub / Cloud agents), "Start a session" (+) opens new session, "I'll do this later". Both set `markDone` | ❌ | – | `za:ui/OnboardingScreen.kt` `DonePage` | – |

### 1D. New session (`ios:Session/NewSessionViewController.swift`; create in `ios:App/AppModel.swift`)

| # | iOS (file:line) | Behaviour | Roboco status + Kotlin file | zeron core API | zeron android ref | Native-Android option |
|---|---|---|---|---|---|---|
| 41 | NS:112-129 | "New Session" sheet with ✕ (the iPad column is embedded, no ✕). Tapping the canvas dismisses the keyboard | ✅ `rb:newsession/NewSessionScreen.kt`, `rb:newsession/NewSessionCanvas.kt` | – | `za:ui/NewSessionScreen.kt` | – |
| 42 | NS:131-192 | Hero: the harness brand mark (34) over "What are we building?" (22 SemiBold), centred in the free space above the composer | ✅ `NewSessionCanvas.kt:337-373` | – | – | – |
| 43 | NS:281-287 | Wallpaper hero: 72% of the height (≤760) with the composer cut out | ✅ `rb:design/WallpaperHero.kt` (cutout) | – | `za:design/WallpaperHero.kt` | – |
| 44 | NS:147-165 | Composer: "Describe the task", chips always visible, attach limit 8, `@` mentions search the picked project | ✅ `NewSessionScreen.kt:227-260` | `searchFiles(deviceId, null, spaceId, q)` | – | – |
| 45 | NS:4-15,96-108; AppModel:69-98 | Sticky `NewSessionDraft {projectId, hostId, branch, worktree, harness, model, effort, cloud?}` is saved on close and when backgrounded; text/images too; reset after create; in memory only in demo | ⚠️ `rb:newsession/NewSessionDraft.kt`, `NewSessionDraftStore.kt`: **no `cloud` field**; persists every change (harmless) | – | `za:core/AppModel.kt:177-200,733-743` | DataStore |
| 46 | NS:194-198 | Defaults: the first project; with no projects, the first online host, else the first host | ✅ `rb:newsession/NewSessionModel.kt:339-360` `normalize()` (adds a stale-id rule) | `executionDevices()` | – | – |
| 47 | NS:345-398 | **Project** chip/menu: one row per repo group (sorted case-insensitively) with the host list as subtitle, a tile icon and a check. Then "No Project…" (keeps or picks a host) and "New Project…" | ✅ `rb:newsession/NewSessionProjectMenus.kt:166-205` (New Project page is a separate audit) | `createProject` | `za:ui/NewSessionScreen.kt:333-450` | Menu, see §4 |
| 48 | NS:373-384 | "New Cloud Project…" (hidden without Cloud) opens the Cloud repo picker; the result sets `projectId` and `cloud = true` | ❌ | `githubRepos`, `createCloudProject(repo)` | `za:ui/NewSessionScreen.kt:438-450`, `za:ui/CloudRepoPicker.kt` | – |
| 49 | NS:402-416 | `pickProject` keeps the host (current, then same device, online, first). `pickCheckout` clears the branch when the checkout changes **or the draft was Cloud**, and sets `cloud = isCloud ? true : nil` | ⚠️ `NewSessionProjectMenus.kt:107-130` has no cloud handling | – | `za:ui/NewSessionScreen.kt:410` | – |
| 50 | NS:310-326 | Host chip: device name (desktopcomputer). Projectless: "No project"/tray plus host or "Choose host". The branch chip appears only for git: "New worktree" / branch / "Current branch" | ⚠️ `rb:newsession/NewSessionChips.kt:96-150` adds a Roboco-only **Warning tint** while the engine is offline (remove) | – | – | – |
| 51 | NS:429-454 | **Run on** menu: the project's checkouts by device (subtitle is the path when a device has several, else Online/Offline); projectless lists every host | ✅ `NewSessionProjectMenus.kt:217-247` | – | `za:ui/NewSessionScreen.kt:520-545` | – |
| 52 | NS:438-445,419-424 | Run on → inline **Cloud** row (subtitle "N credits left" / "Its own machine") when `cloudOffered`. Picking it sets `cloud = true`, clears branch and worktree | ❌ | `cloudStatus` (via CloudStore) | `za:ui/NewSessionScreen.kt:541` | – |
| 53 | NS:217-226,310-314 | `runsOnCloud`: chips become Project, "Cloud" (cloud symbol), "From <branch>" / "Default branch" | ❌ | – | `za:ui/NewSessionScreen.kt:117-119,283-293` | – |
| 54 | NS:475-493 | Checkout menu: "New worktree" toggle, then an inline "Branch" section loaded on open (refs, current first, check = `branch ?? first`) | ✅ `rb:newsession/CheckoutModelEffortMenus.kt:96-205` | `listRefs(deviceId, repoPath)` | – | – |
| 55 | NS:457-474 | Cloud branch menu "Start from": "Default branch" plus the repo's GitHub branches (loaded on open) | ❌ | `githubBranches(repo)` (`core:cloud.rs:311`) | `za:ui/NewSessionScreen.kt` `CloudBranchChip` | – |
| 56 | NS:228-269; AppModel:682-691 | Model catalog: per-device static cache, otherwise the built-in catalog, then the host's `listHarnesses` (offered) × `listModels`, **with `description` as row detail** | ⚠️ `rb:newsession/ModelCatalog.kt`, `NewSessionOptions.kt:44-50`: `ModelChoice` has **no `detail`** and no cloud key | `listHarnesses(device)`, `listModels(device, harness)`, `fallbackHarnesses()`, `fallbackModels(h)` | `za:ui/NewSessionScreen.kt:57-70,139-170` | – |
| 57 | NS:239-247; AppModel:696-709 | Cloud catalog: `cloudModels(h)` for `claude-code` and `codex`. The draft's harness is forced to a Cloud one | ❌ | `cloudModels(harness)` (`core:mod.rs:397-407`) | `za:ui/NewSessionScreen.kt:144-160` | – |
| 58 | NS:327-331 | Model chip title chain (row label, then `modelLabel`, then the first fallback model, then `harnessLabel`) with the brand icon | ✅ `NewSessionChips.kt:96-150` | `modelLabel`, `harnessLabel` | – | – |
| 59 | NS:155-158,497-514 | **Model chip opens the ModelPicker sheet**: one tab per provider the target runs (the draft's provider first). Host is "Cloud"/cloud or the device name/desktopcomputer | ❌ The chip still opens the **old grouped Model menu** (`CheckoutModelEffortMenus.kt:150-160,221-247`). The new picker exists (`rb:composer/ModelPicker.kt`) but only the session screen uses it | – | `za:ui/NewSessionScreen.kt:288-300` + `za:ui/ModelPicker.kt` | – |
| 60 | NS:509-512 | Picking a model clears the effort **only when the harness changes** | ⚠️ `CheckoutModelEffortMenus.kt:159` always clears it | – | – | – |
| 61 | NS:332-334,525-533 | Effort chip: `effort ?? efforts[n/2]`, capitalized; menu "Reasoning effort" | ✅ `NewSessionChips.kt`, `CheckoutModelEffortMenus.kt:268-290` | – | – | Menu, see §4 |
| 62 | AppModel:773-797 | Local create: `createSession(NewSession(target project\|projectless, ChatConfig(…, sandbox WORKSPACE_WRITE), branch unless worktree, cwd nil, title nil))`, then `openSession().send(SendRequest(text, attachments, WorktreeSpec?, QUEUE))` | ✅ `rb:newsession/NewSessionCreate.kt:80-200` (the type names change from `roboco_core` to `zeron_core`) | `createSession`, `openSession`, `SessionHandle.send` | `za:core/AppModel.kt:679-701` | – |
| 63 | NS:542-546 | Failure alert: "Couldn't start the session" / "Choose a project or a host that can run it." / OK | ✅ `NewSessionCreate.kt:207-211` | – | – | `AlertDialog`, see §4 |
| 64 | NS:548-555 | Success: `askAfterFirstSession`, clear text/images, `DraftHandoff` morph into the chat | ✅ `NewSessionSend.kt`, `DraftHandoff*.kt`, `rb:notify/NotificationPermission.kt` | – | – | – |
| 65 | – (iOS has no gate) | iOS always allows Send; the core outbox queues for an offline host | ⚠️ Roboco-only `newSessionCanWrite` disables Send while the engine is offline (`NewSessionCreate.kt:225-236`). Remove for parity | – | – | – |
| 66 | NS:559-631; AppModel:739-765 | **Cloud create**: out of credits → `CloudRoutes.showOutOfCredits`. Otherwise the composer is locked, a glass "Starting on Cloud…" HUD sits above it, `createCloudSession` runs (reusing the unsent chat for the same project/branch/harness/model key), then send. Errors show "Couldn't start on Cloud" with `CloudStore.message` | ❌ | `createCloudSession(spaceId, config, branch, title)` (`core:cloud.rs:452-468`) | `za:core/AppModel.kt:655-677`, `za:ui/NewSessionScreen.kt:176-200,250-260,315-325` | HUD: `Surface`; alert: `AlertDialog` |
| 67 | AppModel:667-671 | `projectOptions` hides `isCloud` projects unless Cloud is offered (or demo); options carry `isCloud` and `githubRepo` | ❌ `NewSessionOptions.kt:18-32` | `ProjectView.isCloud`, `.githubRepo` | `za:ui/NewSessionScreen.kt:113-114` | – |
| 68 | – | Access / plan mode chip | N/A (see the note at the top) | – | – | – |

### 1E. Model / harness / effort picker (`ios:Session/ModelPickerViewController.swift`, used by NS and `ios:Session/CoreSessionSource.swift`)

| # | iOS (file:line) | Behaviour | Roboco status + Kotlin file | zeron core API | zeron android ref | Native-Android option |
|---|---|---|---|---|---|---|
| 69 | MP:38-54,224-235 | Page sheet: custom **"fit" detent** (height of the longest list, ≥280, ≤78%) plus `.large`, grabber, corner 34, scroll expands the sheet; re-fits as lists load | ⚠️ `rb:composer/ModelPicker.kt:196-205` uses `GlassSheet` with fixed **Medium/Large** detents | – | `za:ui/ModelPicker.kt:131,185` (M3 `ModalBottomSheet`) | `ModalBottomSheet` wraps its content, so its partial height *is* a fit detent; `skipPartiallyExpanded=false` gives the large state |
| 70 | MP:134-190 | Header: "Model" (22 SemiBold). Subtitle is "<Provider> · <host>" with the brand mark for one tab, or the host with a **host symbol** (desktopcomputer/cloud) for several. Glass ✕ 36 | ⚠️ `ModelPicker.kt:311-345` has no host symbol with several tabs | – | – | – |
| 71 | MP:622-847,194-219 | **Provider switcher**: capsule track, thumb **follows the pager finger for finger** (fractional), segments cross-fade muted→full. Compact mode shows logos only except the chosen one; otherwise the track scrolls, keeps the chosen one centred and fades the edges. "Claude" for Claude Code; selection haptic on settle | ⚠️ `ModelPicker.kt:350-397`: the thumb **snaps** to `currentPage`, every segment always shows its name, plain `horizontalScroll`, no edge fade, haptic only on tap | – | `za:ui/ModelPicker.kt` | `SecondaryScrollableTabRow` with a custom capsule indicator driven by `pagerState.currentPage + currentPageOffsetFraction` gives native a11y and scrolling; compact logo-only tabs are custom tab content |
| 72 | MP:75-115 | Paging pager; the current provider loads first, then the rest | ✅ `ModelPicker.kt:207-223` (`HorizontalPager`) | – | – | `HorizontalPager` (already used) |
| 73 | MP:262-447,568-620 | Page: notice plate (info icon); a **search field once a list has more than 12 models** ("Search X models"); 4 pulsing skeleton rows; "No models available"/"No matches"; dedupe by id | ✅ `ModelPicker.kt:400-452` (the search scrolls with the list here; iOS pins it above. Minor) | – | – | `TextField`/`BasicTextField` styled as a search field |
| 74 | MP:449-522 | Model row: 16 Medium label, 13 detail (2 lines). Current row: accent-soft plate and accent check. Press: controlFill plus 0.985 scale | ✅ `ModelPicker.kt:454-497` | – | – | – |
| 75 | MP:244-254 | One pick per opening, light haptic, dismiss after 0.22 s | ✅ `ModelPicker.kt:268-280` | – | – | – |
| 76 | CSS:69-87,291-334 | In session: the chat's own provider tab, plus other offered providers (footer "Your next message continues this chat with X.") while `providerSwitch && !turnRunning && !sendPending`. Notes: "You can switch provider when this turn ends." / "This computer needs a newer Zeron to switch provider." Re-checked at pick. A provider change resets reasoning and modelOptions. A chat with no model ticks the default | ⚠️ `rb:composer/SessionChipMenus.kt:396-435,583-595` ported. Copy says "newer **Roboco**" | `setSessionConfig`, `listHarnesses`, `ComposerState.host.capabilities.providerSwitch` | `za:ui/ModelPicker.kt` | – |
| 77 | CSS:300-317 | Cloud chat: host "Cloud"/cloud and Cloud's harness tabs only (`providers(cloud:)`) | ❌ `SessionChipMenus.kt:430` uses `row.deviceName` | `SessionRow.isCloud`, `cloudModels` | – | – |
| 78 | CSS:263-280 | In-session effort menu: levels from `listModels`, labelled by `reasoningLabel`, `setConfig` | ✅ `SessionChipMenus.kt` | `reasoningLabel(level)` | – | – |

### 1F. Settings (`ios:Shell/MoreViewController.swift`)

Section order on iOS: **Account · Agents · Devices · Voice\* · Notifications · Appearance · Wallpaper · Math · Sessions · " " (Sign Out)**.
Section order in Roboco: Agent Accounts · Devices · Notifications · *Background* · Appearance · Wallpaper · Sessions · *About* (`rb:settings/SettingsScreen.kt:217-236`).

| # | iOS (file:line) | Behaviour | Roboco status + Kotlin file | zeron core API | zeron android ref | Native-Android option |
|---|---|---|---|---|---|---|
| 79 | More:37-95 | "Settings" large title over an inset-grouped list. Rows: symbol, Medium 16 title, 13 subtitle. Accessories: disclosure / check / green dot / switch. Destructive rows in danger colour. Grouped headers | ✅ `rb:settings/SettingsScreen.kt`, `rb:design/GroupedList.kt`, `rb:shell/LargeTitleScaffold.kt` | – | `za:ui/SettingsScreen.kt` (M3 Expressive `SegmentedListItem`, not 1:1) | `ListItem` in a rounded `Surface` gets close, but its 56/72 dp minimum heights are taller than iOS's 44/60 pt. Keep `GroupedRow` |
| 80 | More:96-127 | Reloads on wallpaper, app, **cloud** and foreground changes; re-reads notification status | ⚠️ Same, minus the cloud observer (no CloudStore) | – | – | – |
| 81 | More:193-196 | **Account** section: one row with `accountName` / `accountDetail`, person.crop.circle icon, no accessory | ❌ Dropped (ADR 0004) | – | `za:ui/SettingsScreen.kt:86-107` (tonal hero card, not 1:1) | `GroupedRow` |
| 82 | More:199-200,257-258 | Agents → **Providers** row "Agents and their accounts on each device" (cpu) → Providers page (separate audit) | ❌ Roboco shows a per-engine **Agent Accounts** section instead (`rb:accounts/AgentAccountsSection.kt`). Replace it with this row | `setHarnessEnabled`, `deviceAgentAccounts`, … (`core:providers.rs`) | `za:ui/SettingsScreen.kt:116-117`, `za:ui/ProvidersScreen.kt` | – |
| 83 | More:201-205,259-260 | **Cloud** row (only when `cloud.available`, or demo with `-cloud`). Subtitle: `problem.title` / "GitHub @x" / "Connect GitHub" → Cloud settings (separate audit) | ❌ | `cloudStatus` | `za:ui/SettingsScreen.kt:118-119` | – |
| 84 | More:206-208,261-262 | **Billing** row: "<Plan> plan · N credits" / "Plans and credits" → Billing (separate audit) | ❌ | `billingStatus` | `za:ui/SettingsScreen.kt:120-122` | – |
| 85 | More:211-212, 316-317 | **Devices**: one row per *execution device* (`hostOptions`). Subtitle "Online"/"Offline", desktopcomputer icon, **green/grey dot**, **no tap action** (`default: break`) | ⚠️ `rb:settings/DevicesRows.kt`: paired engines with engine-connection labels (Starting…/Reconnecting…/Session revoked…, Tailcat paths), a **"Pair an engine"** row, and taps that push **Engine detail** (`rb:settings/EngineDetail*.kt`) | `executionDevices()` → `DeviceView{id,name,online}` | `za:ui/SettingsScreen.kt:215-253` (shows every device with version/count, not 1:1) | `GroupedRow` with a dot accessory |
| 86 | More:213-216,256 | **Voice** section and row (only when `voice.available`): "Codex voice · <Style>" / "Codex voice device and style" → Voice page (separate audit) | ❌ Dropped (ADR 0004) | voice FFI (`core:voice.rs`) | `za:ui/SettingsScreen.kt:191-214`, `za:voice/VoiceSettingsScreen.kt` | – |
| 87 | More:129-135 | Notifications master row (bell + switch). On = enabled **and** authorized. Subtitle "When a session finishes, needs you or fails", or when denied "Turned off in iOS Settings" | ✅ `rb:settings/NotificationSettings.kt` ("…in Android settings"; rename "Roboco" in the alert) | – | – (za has none) | `Switch`, see §4 |
| 88 | More:136-146 | Kind rows Run finished / Waiting on your input / Run failed (with symbols), shown only while on | ✅ `NotificationSettings.kt`, `rb:notify/NotificationPrefs.kt:16-18` | – | – | – |
| 89 | More:149-185 | Toggle rules: off disables. On while denied shows the alert "Notifications are off" / "Allow notifications for Zeron in iOS Settings." / Not Now / Open Settings. Otherwise enable, then request permission | ✅ `NotificationSettings.kt` | – | – | `AlertDialog`; `Settings.ACTION_APP_NOTIFICATION_SETTINGS` |
| 90 | `ios:App/PushNotifications.swift:67-135` | Delivery: APNs token registered with the edge on sign-in (`registerPushTarget`), unregistered on sign-out | ⚠️ zeron core/edge have **no FCM path** (`registerPushTarget` takes an APNs token, "production"/"sandbox"). Roboco posts **local** notifications from `rb:background/RunWatchService.kt` + `rb:notify/*`. Keep that (rewired to zeron `ClientEvent`s) as the Android substitute | `registerPushTarget` (APNs only), `unregisterPushTarget` | – | Foreground service `dataSync` while a run is working (Roboco's) |
| 91 | More:219-225,263-267 | Appearance: System / Light / Dark rows with a check on the **stored** value; applies the window style at once | ✅ `rb:settings/AppearanceSection.kt`, `rb:core/AppearanceController.kt` | – | – | – |
| 92 | More:226-227,268-269 | Wallpaper: "Choose Wallpaper…" / "Change Wallpaper…" (subtitle = name) → photo picker | ✅ `rb:settings/WallpaperRows.kt:96-108` | – | `za:ui/SettingsScreen.kt:316-357` | Photo Picker |
| 93 | More:228-231,270-279 | "Effect" row ("<Label> — <Detail>") → action sheet "Wallpaper Effect" with 5 effects and the current one checked | ✅ `WallpaperRows.kt:110-150` + `rb:design/ActionSheet.kt` | – | – | Action sheet, see §4 |
| 94 | More:231,306-307 | "Remove Wallpaper", destructive, no confirmation | ✅ `WallpaperRows.kt:118-125` | – | – | – |
| 95 | More:234-235,154-157; `ios:Transcript/TranscriptListView.swift:6-80` | **Math**: "Render Math" switch ("Off shows LaTeX as its source"), default on, applied to transcripts | ❌ (`roboco_core` has no math) | `TranscriptView.setMath(enabled, face: MathFace, sizePercent: UShort)` (`zeron/crates/mobile/src/layout/mod.rs:504`) | – (za has none) | `Switch` |
| 96 | More:237-238,280-292 | "Math Font" row ("<Label> — <Detail>") → action sheet: Fira Math, STIX Two, Computer Modern, Libertinus, Palatino, Euler (default Fira) | ❌ | `MathFace.{Fira,StixTwo,ComputerModern,Libertinus,Pagella,Euler}` | – | Action sheet |
| 97 | More:239,293-305 | "Math Size" row ("N% of the text around it") → sheet with 90/100/110/120/135/150% (default 100) | ❌ | `sizePercent` (Rust clamps to 75–200) | – | Action sheet |
| 98 | More:243-244,150-153 | Sessions: "Group by Project" switch ("Sessions outside your sections, under their project") | ✅ `rb:settings/SessionsSettingsSection.kt` | – | `za:ui/SettingsScreen.kt:173-180` | – |
| 99 | More:245,308-309 | "Archived Sessions" → the Archived folder | ✅ `SessionsSettingsSection.kt` (`FolderRoute.archived`) | `archivedSessions()` | `za:ui/ArchivedScreen.kt` | – |
| 100 | More:247-248,310-315 | **Sign Out** (destructive) in an untitled last section → action sheet "Sign out?" / "Local drafts stay on this device." / "Sign Out" (destructive) / Cancel → `app.signOut()` (also in demo) | ❌ | `CoreClient.shutdown()` | `za:ui/SettingsScreen.kt:264-289` (AlertDialog; "Leave demo") | `rb:design/ActionSheet.kt` |

**Totals (rows 1–100, N/A excluded): ✅ 28 · ⚠️ 18 · ❌ 53 · N/A 1.**

---

## 2. Auth design for the port

### 2.1 Flow (iOS-faithful)

```
Welcome ("Get started") ──► Onboarding.appearance (first time on this phone) ──► Onboarding.sync ("Log in")
                                                                                     │
  AuthSession.begin():                                                               ▼
    state = UUID().toString(); prefs.commit("authState", state)            AuthTab / Custom Tab
    url = workosAuthorizeUrl(state)   // core; debug override → own builder           │
                                                                                     ▼
  MainActivity (singleTask) onCreate/onNewIntent  ◄── zeron://callback?code=…&state=…
    parseAuthCallback(url):
      Code(code, state) where state == persisted ─► authState = null; signIn(code)
      anything else / Error / state mismatch      ─► "Sign-in didn't complete. Try again."
  signIn(code)  (suspend; uniffi async fns are already suspend)
    ex   = authExchangeCode(edge, code)                         // unscoped pair + AuthUser
    orgs = authListOrgs(edge, ex.tokens.accessToken)
      empty → "This account isn't in an organization yet."
      1     → orgs[0]
      >1    → ActionSheet "Choose an organization" (rows = org.name, Cancel) via CompletableDeferred; Cancel → stop quietly
    tokens = authRefresh(edge, ex.tokens.refreshToken, org.organizationId)
    profile = (first+" "+last).trim().ifEmpty{null}, user.email, org.name  → CredentialStore.profile
    start(Credentials.WorkOs(ex.user.id, org.organizationId, tokens))
      claimCoreDir("<userId>/<orgId>") → CoreClient(config, creds, listener) → CredentialStore.store(creds)
      failure → "Couldn't open your workspace. Try signing in again."
    onSignedIn → root cross-fade to shell (dismiss sheets) → onboarding from .github, no animation
```

- **Manifest**: on `MainActivity` (`launchMode="singleTask"`, as in `za:` and `rb:`), one VIEW/BROWSABLE filter with `scheme="zeron"` and hosts `callback` (WorkOS), `voice` (call return), `signed-in` and `cloud` (provider, GitHub and Stripe returns; other audits). This **replaces** Roboco's `roboco://{chat,new,settings,search,pair}` filter in `app/src/main/AndroidManifest.xml:63-73`. iOS only handles `zeron://voice` and the callback, so the in-app routes stay as debug launch extras (`route`), as on iOS.
- **Browser**: prefer **AuthTab** (`androidx.browser.auth.AuthTabIntent`, androidx.browser 1.9+, Chrome 137+). It is the counterpart of `ASWebAuthenticationSession`: an `ActivityResultLauncher` gets `RESULT_OK` with the callback URI, or `RESULT_CANCELED`. That gives iOS's "cancel = quiet reset" for free, and the redirect scheme is checked by the browser. When AuthTab isn't supported, fall back to `CustomTabsIntent` and the intent filter above. Like za, persist `authState` because the process can die while the browser is up. Treat "activity resumed, no callback within ~1 s" as a cancel so "Logging in…" resets. Share cookies (no ephemeral profile), matching `prefersEphemeralWebBrowserSession = false`.
- **Where the UI lives**: the Sync step owns `loggingIn` and `loginError`. The callback lands in `MainActivity`, which forwards to a single app-scoped `AuthSession`. Expose it as a `StateFlow<AuthState>` (`Idle | LoggingIn | Error(msg)`) plus an org-choice request, so it survives the onboarding composable being recreated.
- **Errors**: iOS shows `error.localizedDescription`, which for `CoreError` is `String(reflecting:)` (ugly). Show `CoreException.*.reason` instead (Kotlin renames `message` → `reason`, `zeron/crates/mobile/uniffi.toml`); reuse `za:core/Cloud.kt:296-309`.
- **Org selection** happens **only** at sign-in. iOS has no org switcher; you change org by signing out and in again.

### 2.2 Refresh / expiry

- The core refreshes tokens itself. Platform duties (`core:types.rs:346-353`):
  - `ClientEvent.AuthRefreshed(tokens)`: persist **synchronously** (the old refresh token is spent; losing the write means the next launch presents a spent token and gets `AuthExpired`). Write from the listener callback before hopping to main, or with `SharedPreferences.commit()` / an awaited DataStore edit. **Not** `apply()`, which is what `za:core/CredentialStore.kt:83` does.
  - `ClientEvent.AuthExpired(reason)`: `signOutLocally(forget = false)`. The store stays because the outbox may hold unsent messages, and the `.owner` marker still keeps other accounts out. Then the welcome page.
- On launch, stored credentials go straight to `start()` (offline-first; the client never blocks on the network). No app-side expiry check, as on iOS. `jwtExpiry(jwt)` and `CoreClient.updateTokens(tokens)` exist but iOS doesn't use them.
- `backfillProfile()` after `start()`: if `orgName` is missing, `authListOrgs` with the stored access token. Failures are silent.

### 2.3 Sign-out and demo

- **User sign-out** (Settings → Sign Out → action sheet confirm): `voice.stop()` (once voice is ported); cancel local notifications and stop `RunWatchService` (the Android stand-in for `push.signingOut` / `unregisterPushTarget`); `client.shutdown()`, client = null; clear credentials and profile; **delete `noBackupFilesDir/core`**; `cloud.reset()`; `terminals.signOut()`; clear the workspace feed, live counts, `lastDraft`, new-session text and images. Then cross-fade the root to Welcome, dismissing presented sheets and dialogs. Onboarding's `appearanceSeen` stays; `onboarded:<userId>` stays per user.
- **Demo**: launch extras only, mirroring iOS's launch args (`--ez demo true`, `noprojects`, `iosonly`, `big`/`huge`, `fast`, `longreply`, `cloud`); also used when a `route` extra is given and nothing is stored. Details:
  - Starts `Credentials.Demo(DemoOptions(...))` with data dir `filesDir/demo`.
  - Credentials are never persisted and the new-session draft stays in memory.
  - Onboarding never shows.
  - Account row reads "Demo" · "Offline demo workspace"; the Cloud row appears only with `cloud`.
  - Sign Out returns to Welcome.
  - **Do not** add za's "Explore the demo" button; iOS has none.
- **Dev credentials** (debug builds only): `--es dev "<userId> <orgId>"` → `Credentials.Dev` (not persisted). `--ez signedout true` wipes credentials. `--es edge <url|prod>` / `--es workos <client|prod>` are sticky overrides (§1A row 4).

### 2.4 Token storage: Roboco cipher vs za `CredentialStore`

| | `rb:pairing/KeystoreSecretCipher.kt` (+ `PairedEngineStore`) | `za:core/CredentialStore.kt` |
|---|---|---|
| Key | AES-256-GCM, non-exportable, StrongBox when available, randomized IV | AES-GCM (default size), non-exportable |
| Key creation | Only on **encrypt**; a missing key on decrypt is a failure ("pair again") | `key()` also runs on decrypt, so a lost key silently mints a new one and the decrypt fails → signed out |
| AAD | Binds the blob to its engine id | None |
| Storage | No-backup **Preferences DataStore**, writes serialized by a mutex | `SharedPreferences("credentials")` with **`apply()`** (async), legacy plain-JSON migration |
| Profile | – | Plain prefs keys `name`/`email`/`orgName` |

**Recommendation:** keep `SecretCipher`/`KeystoreSecretCipher` (move them out of `pairing/`, rename the alias to `sh.zeron.android.credentials.v1`, AAD `"workos:v1"`). Write a small `CredentialStore` on top that keeps za's JSON shape (`{kind: workos|dev, userId, orgId, access, refresh}`) plus `Profile`. Store it in `noBackupFilesDir` with synchronous writes (`commit()` or an awaited DataStore `edit`). Seal the profile with the same key, as iOS keeps it in the Keychain. Skip StrongBox for this key (it can be slow and the key is used on every refresh) or keep the try/fallback. Drop za's legacy plain-JSON migration: this app is a fresh install. Its signing key differs from the official app's, so replacing the official `sh.zeron.android` means uninstalling it first and signing in again.

---

## 3. Roboco-only pieces in this area to delete or rework

| Path | What it is | Action |
|---|---|---|
| `rb:pairing/` (PairPage*.kt, PairPageLauncher.kt, PairPageModel.kt, QrScan*.kt, QrScannerScreen.kt, QrDecoder.kt, ScanPayload.kt, PairShareActivity.kt, InboundPairing.kt, LaunchPairRedeemer.kt, PairingLinks.kt, EngineMetaJson.kt, PairedEngineStore.kt, DeviceLabel.kt, Redaction.kt) | LAN/Tailcat/room pairing, QR scanner, share target, engine credential store | **Delete**. Keep and move `SecretCipher.kt` + `KeystoreSecretCipher.kt` (§2.4). Turn `PairingRoot.kt` into `WelcomeScreen` (§1A row 1) |
| `rb:shell/RootKind.kt` (`RootKind.Pairing`, `RootInput.Ready(hasEngines)`, `chooseRoot(!hasEngines → Pairing)`) | Root choice keyed on paired engines | **Rework**: `RootKind.{Welcome, Phone, Split}`, `RootInput.{Loading, CoreFailed, SignedOut, SignedIn}`; the cross-fade group stays |
| `rb:shell/RobocoAppContent.kt` (`AppRootWithPairing`, `CoreClientPairingBackend`, `InboundPairingLinks`, `hub.showingPairingRoot`), `rb:shell/AppRoot.kt`, `rb:shell/DeepLinks.kt:124` (`pair` host), `rb:shell/DeepLinkHub.kt` | Pairing wiring and the `roboco://` routes | **Rework** to Welcome/Onboarding plus the `zeron://` callback; drop `DeepLink.Pair` |
| `rb:core/CoreStartup.kt` (engine store load, `EngineIdentityPinned`/`RoomEngineJoined`/`EnginesChanged`, one fixed client) | Roboco startup | **Rework** into an AppModel-style session holder (`StateFlow<CoreClient?>`, `start(credentials)`, `signOutLocally(forget)`, `claimCoreDir`, deviceId, listener handling `AuthRefreshed`/`AuthExpired`). Every `clientOrNull()` consumer must cope with the client changing |
| `rb:core/CoreLoader.kt` | Loads `uniffi.roboco_core` / `libroboco_mobile.so` | **Rework** to `uniffi.zeron_core` / `libzeron_mobile.so` (`zeron/crates/mobile/Cargo.toml` `[lib] name = "zeron_mobile"`); version handshake against the zeron version |
| `rb:settings/DevicesRows.kt`, `DevicesRowsGallery.kt` | Engine rows, Tailcat path labels, "Pair an engine" | **Rework** to iOS device rows (§1F row 85): no tap, no Pair row |
| `rb:settings/EngineDetail{Backend,Gallery,Model,Route,Screen,State}.kt` | Engine detail page (rename/forget/Tailcat address) | **Delete** (iOS device rows don't open anything) |
| `rb:settings/BackgroundSettingsSection.kt`, `rb:core/Prefs.kt` `stayConnected`, the `specialUse` mode of `rb:background/RunWatchService.kt`, `FOREGROUND_SERVICE_SPECIAL_USE` + the PROPERTY in the manifest | ADR 0010 "Stay connected in background" | **Delete** the section and the specialUse path. Keep the run-only `dataSync` watcher for local notifications (§1F row 90) |
| `rb:settings/AboutRows.kt`, `AboutGallery.kt`, `Licenses.kt`, `LicensesScreen.kt`, `rb:update/*` (UpdateCenter, ApkDownloader/Installer/Verifier, ReleaseManifest, UpdateAboutRows, …), `ApkInstallReceiver` + `REQUEST_INSTALL_PACKAGES` in the manifest | About (Version/Licenses/Update) and the in-app APK updater | **Delete** the updater and the About section (not in iOS). *Owner decision*: open-source licences still need a home. Suggestion: keep `LicensesScreen` reachable from a long-press on the Account row, or bundle `licenses/` as an asset page |
| `rb:accounts/AgentAccountsSection.kt` (Settings section) | Per-engine agent accounts summary | **Replace** with the iOS Agents section: Providers, Cloud and Billing rows. Whether `AgentAccountsScreen.kt` can become `AgentAccountsViewController` is the Providers audit's call |
| `rb:settings/SettingsScreen.kt:91-142,217-236` (`SettingsSection` enum, `settingsSectionFor`, order) | Section order | **Rework** to Account · Agents · Devices · Voice\* · Notifications · Appearance · Wallpaper · Math · Sessions · Sign Out |
| `rb:newsession/NewSessionCreate.kt:225-236` `newSessionCanWrite`, `NewSessionChips.kt` `ChipTint.Warning` | Roboco offline Send gate and tint | **Delete** (iOS sends and queues) |
| `rb:newsession/NewSessionModel.kt` `side: SideChatParent`, `NewSessionChips.kt` `SideChatChipIds`, `SideChatStartFailedAlert` | New-session page as a side-chat composer (upstream #899) | Current iOS creates side chats directly (`AppModel.createSideChat`, `fd7d268c`). **Check with the companions audit**, then likely delete |
| `rb:composer/SessionChipMenus.kt:594` | "This computer needs a newer **Roboco**…" | Rename to Zeron (and every other "Roboco" string: `NotificationCopy.AlertMessage`, `res/values/strings.xml`, app name) |
| `app/src/main/res/xml/network_security_config.xml` + manifest `networkSecurityConfig`; `CAMERA` + camera `uses-feature`; `QrScanActivity`/`PairShareActivity` entries | LAN cleartext to engines, QR scanner | **Delete**. Without the CAMERA permission the attach-menu capture intent keeps working |
| `app/build.gradle.kts:410-419` | `namespace`/`applicationId = "sh.roboco.android"` | `applicationId = "sh.zeron.android"` (renaming the package/namespace is optional but recommended) |

---

## 4. Hand-cloned iOS widgets vs. native/Material options (this area)

| Element (where used here) | What Roboco does now | Native option, themed | Verdict |
|---|---|---|---|
| UIMenu (chip menus, project/run-on/checkout/effort, wallpaper effect capsule) | `rb:design/glass/GlassMenu*.kt`: Compose-foundation glass panel (Haze blur), deferred sections, iOS geometry | M3 `DropdownMenu` (`shape`, `containerColor`, `shadowElevation` parameters; `DropdownMenuItem` with leading/trailing icon; `HorizontalDivider` for inline sections) | `DropdownMenu` runs in a popup window, so it **can't blur** what's behind it (no Haze across windows), and deferred loading still needs custom code. Use it if accessibility, keyboard and focus matter more than the glass look; otherwise keep GlassMenu |
| UIAlertController `.actionSheet` (org picker, Sign out?, Wallpaper Effect, Math Font/Size) | `rb:design/ActionSheet.kt`: in-window two-card stack, no Material | `ModalBottomSheet(dragHandle = null, containerColor = Transparent)` with two custom cards inside | **Switching is worth it**: native back/predictive back, focus trap and TalkBack, while keeping the visuals. Same look is achievable |
| UIAlertController `.alert` ("Couldn't start…", "Notifications are off") | `rb:design/Alert.kt` + `AlertCard.kt`: in-window glass card, FIFO queue | `BasicAlertDialog { RobocoAlertCard(...) }` (custom content). Window blur via `window.setBackgroundBlurRadius` on API 31+ | Feasible. The queue logic stays. Worth it for accessibility; the visuals carry over |
| Page/form sheets (model picker, new-session sheet) | `rb:design/sheet/GlassSheet.kt`, `rb:shell/PageSheet.kt`: custom detents, Haze | `ModalBottomSheet` (wraps content, which matches iOS's "fit"; expanded = large). Corner radius and colours are themable | The model picker fits `ModalBottomSheet` well (za already uses it). Keep the page sheet custom: it has the new-session ↔ chat handoff morph |
| UISegmentedControl (onboarding Color scheme) | None in Roboco | `SingleChoiceSegmentedButtonRow` + `SegmentedButton(icon = {}, shape = capsule, colors = …)` | Native; close enough. There is no sliding thumb animation, so add one with a custom indicator only if the owner wants exact parity |
| UISwitch (notification, group-by-project and Render Math rows) | `rb:design/RobocoSwitch.kt`: 63×28 capsule knob with iOS 26 lift | M3 `Switch(colors = SwitchDefaults.colors(checkedTrackColor = accent, …))` | M3's geometry is fixed (52×32 track, round thumb), so colours match but shape doesn't. Keep `RobocoSwitch` for pixel parity, or accept M3 for platform feel. It is isolated, so either is cheap |
| Inset-grouped list rows (Settings) | `rb:design/GroupedList.kt` | `ListItem` in a rounded `Surface`, or M3 Expressive `SegmentedListItem` (za) | `ListItem` minimum heights are taller than iOS's. **Keep GroupedRow** |
| Provider switcher (model picker tabs) | Custom capsule `Row` | `SecondaryScrollableTabRow` + custom capsule indicator following `pagerState` | **Switch**: fixes the snapping thumb (row 71) and adds tab semantics |
| UISearchTextField (picker search) | Custom field in `ModelPicker.kt` | `TextField`/`BasicTextField` with a search decoration and `TextFieldDefaults.colors` | Native text input is fine; style it to match |
| Web auth sheet (ASWebAuthenticationSession) | None in Roboco | **AuthTab**, falling back to Custom Tab | Native by definition |
| Org picker | None in Roboco | Same as the action sheet | Reuse the action sheet |
| Progress bars / primary capsule / ghost buttons (onboarding) | None in Roboco | `Button`/`TextButton` with capsule shape and palette colours; bars as `Box`es | Native buttons themed; trivial |

---

## 5. Ordered task list

1. **Core swap:** build `zeron-mobile` (`libzeron_mobile.so`, `uniffi.zeron_core` with `uniffi.toml` renames) in `buildSrc`. Switch `CoreLoader` to zeron and set `applicationId = "sh.zeron.android"`. Remove the pairing manifest entries, the `roboco://` filter, CAMERA, the updater and specialUse.
2. **Session holder:** replace `CoreStartup` with an AppModel-style `StateFlow<CoreClient?>`: `start(credentials)`, `claimCoreDir`, deviceId `android-<8>`, `CoreConfig(edge, …, platform "android")`, and a listener handling `AuthRefreshed` (sync persist) and `AuthExpired`. Rework `RootKind`/`RootInput` to Welcome/SignedIn. Delete `pairing/` (keep the cipher).
3. **CredentialStore** on `KeystoreSecretCipher`: synchronous writes, sealed profile (§2.4).
4. **AuthSession:** `workosAuthorizeUrl` + persisted state, AuthTab with Custom-Tab fallback, `zeron://callback` handling, `parseAuthCallback`, exchange → orgs (empty error, action-sheet picker, quiet cancel) → `authRefresh` → profile → start. Use the iOS error copy, shown via `reason`.
5. **Welcome screen** (rework `PairingRoot.kt`) and **Onboarding** (§1C): progress, footer, transitions, softened wallpaper, Appearance (segmented + wallpaper card + preview), Sync (Log in), Notifications, Done; GitHub and Agents from the Cloud/Providers audits; `onboarded:<userId>` gating; launch after sign-in from `.github`.
6. **Sign-out and demo:** Sign Out row + action sheet → full `signOutLocally(forget = true)`. Debug launch extras for demo, dev, signedout, edge and workos. Account row text.
7. **Settings restructure:** Account row; Agents section (Providers/Cloud/Billing entry rows, wired to those audits' pages); Devices → execution devices, no tap; Voice row behind `voice.available` (when voice lands); Math section + `TranscriptView.setMath` plumbing (default on / Fira / 100%). Delete Background, About/updater, Agent Accounts section and Engine detail. Add a cloud observer.
8. **New session, Cloud:** `draft.cloud`; filter `isCloud` projects; "New Cloud Project…"; Run on → Cloud; Cloud chips; "Start from" branch menu (`githubBranches`); Cloud catalog (`cloudModels`); `createCloudSession` with unsent-chat reuse, "Starting on Cloud…" HUD, out-of-credits route and "Couldn't start on Cloud" alert.
9. **New session, picker and rules:** model chip opens the shared `ModelPicker` (tabs in draft-harness order, host name and symbol); effort cleared only on harness change; `ModelChoice.detail` from `ModelInfo.description`; remove the offline Send gate and Warning tint; settle the side-chat mode with the companions audit.
10. **Model picker polish:** fitted detent (or `ModalBottomSheet`); switcher thumb follows the pager, compact logo mode, edge fades (or `SecondaryScrollableTabRow`); host symbol in the subtitle; Cloud chats show the "Cloud" host and Cloud tabs; "newer Zeron" copy.
11. **Widgets (owner's call, §4):** move the action sheet and alert onto `ModalBottomSheet`/`BasicAlertDialog`, the switcher onto `SecondaryScrollableTabRow`, and the onboarding scheme picker onto `SegmentedButton`. Keep GroupedRow and, unless the owner prefers M3, RobocoSwitch and GlassMenu.
12. **Rename** every remaining "Roboco" string and identifier in this area. Re-record goldens.
