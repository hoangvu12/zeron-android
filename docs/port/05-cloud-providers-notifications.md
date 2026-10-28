# 05 — Cloud, Providers & agent accounts, notifications/push, deep links, delivery

Port-gap audit, iOS (zeron v0.2.129, `8b7afb30`) to the Roboco Android copy. Roboco was ported from upstream `edb23db4`. The gap is measured against the iOS app as it is now.

**Path conventions**
- **iOS**: relative to `zeron/apps/ios/Zeron/`.
- **Roboco**: relative to `app/src/main/java/sh/roboco/android/`.
- **za:**: Zeron's own Android app, relative to `zeron/apps/android/app/src/main/java/sh/zeron/android/`.
- **Core**: the `uniffi.zeron_core` Kotlin name. The Rust source is in `zeron/crates/mobile/src/client_ffi/{cloud,providers,mod,types}.rs`.
- **edge**: relative to `zeron/edge/src/`.

**Status key**
- ✅ Roboco does the same thing. It only needs rewiring to the zeron core, zeron IDs or zeron copy.
- ⚠️ Partial, divergent, or Roboco-only (not in iOS).
- ❌ Absent.

**What changed upstream since the port.** `git -C zeron diff --stat edb23db4..HEAD`:
- The whole `Cloud/` folder is new, about 3.7k lines over 10 files (`cc7da5c2` "Zeron Cloud, provider switching, and the phone model picker", `5b8edb2d`, `f30a8641`).
- `MoreViewController` gained the "Agents" section (Providers, Cloud, Billing).
- `SceneDelegate` gained the onboarding entry and the debug Cloud `-route`s.
- `AppModel` gained `cloud`, `createCloudSession` and the Cloud project filter.
- `Info.plist` only added `NSLocalNetworkUsageDescription`. The URL scheme is unchanged.
- `PushNotifications.swift`, `AppDelegate.swift` and the edge push code (`push-notify.ts`, `apns.ts`) are unchanged since the port baseline. Only Cloud routes were added to `registry-room.ts`.

Roboco has no Cloud, billing, GitHub, onboarding, WorkOS sign-in or push. Its `accounts/` package already ports most of the iOS **device** half of `AgentAccountsViewController` and `ProvidersViewController`. It does this through Roboco-core APIs and a different page structure (desktop PR #578 "Accounts & Usage").

---

## 1. Gap table

### A. Cloud data layer: `CloudStore`

| # | iOS (file:line) | Behaviour | Roboco status + Kotlin file | zeron core API | za: reference | Native-Android component option |
|---|---|---|---|---|---|---|
| A1 | Cloud/CloudStore.swift:10-25 | Static table of Cloud agents: Claude Code (`claude` login, `anthropic-key`, "Sign in with Claude", "Anthropic API key") and Codex (`codex`, `openai-key`, "Sign in with ChatGPT", "OpenAI API key"). | ❌ none | none (static) | core/Cloud.kt:44-54, 285-291 | n/a (Kotlin object) |
| A2 | Cloud/CloudStore.swift:150-209 | `refresh()`: reads `cloudStatus` first. If it is available and `state=="off"`, it calls `cloudEnable()` automatically. Then it reads vault, repos (no query), billing and `vaultAccounts` for all 4 providers in parallel, then `refreshUsage()`. Every screen observes the store; reads are cached so a page paints at once. | ❌ none | `cloudStatus`, `cloudEnable`, `vaultStatus`, `githubRepos(null)`, `billingStatus`, `vaultAccounts(provider)` | core/Cloud.kt:122-171 | `@Stable` holder with Compose snapshot state, or a ViewModel with `StateFlow`. No UI widget. |
| A3 | Cloud/CloudStore.swift:192-207, 211-212 | A failed status read is retried after 30 s, doubling up to 5 min. Until a read answers, Cloud reads as not offered. | ❌ | `cloudStatus` | core/Cloud.kt:150-159 | coroutine `delay` |
| A4 | Cloud/CloudStore.swift:151-171, 186, 193 | One refresh in flight at a time. Every await re-checks `self.client === client` so a sign-out or account switch never clobbers the new account's state. | ❌ | none | core/Cloud.kt:124, 131, 140 (`client() !== c`) | none |
| A5 | Cloud/CloudStore.swift:55-59 | `available`; `credits` (billing total, else status credits); `outOfCredits` = credits ≤ 0 (the desktop's rule). | ❌ | `CloudInfo.credits`, `BillingInfo.creditsTotal` | core/Cloud.kt:76-80 | none |
| A6 | Cloud/CloudStore.swift:62-78 | `problem`: either "Cloud ran into a problem" (state `error`, edge message) or "Out of credits…" (leads to Billing). | ❌ | `CloudInfo.state/error` | core/Cloud.kt:82-89 | none |
| A7 | Cloud/CloudStore.swift:81-102 | `sessions` plus `sessionError(chat, device)`, which is "Its machine failed to start." when state is `error`. `refreshSessions()` is throttled to 8 s and also re-reads status. | ❌ | `cloudSessions`, `cloudStatus` | core/Cloud.kt:91-95, 174-187 | none |
| A8 | Cloud/CloudStore.swift:104-126 | `githubAccount` / `githubConnected` (a vault `github` connection that does not need reconnect). `activeAccount(agent)` prefers the login over the key. `hasAccount`. `reaches(repo)` is a case-insensitive `owner/name` match against `repos`. | ❌ | `VaultInfo.connections`, `GithubRepoInfo.fullName` | core/Cloud.kt:97-112 | none |
| A9 | Cloud/CloudStore.swift:214-262 | `refreshAccounts()` re-reads the 4 provider lists plus vault. `refreshUsage()` reads each OAuth login's usage keyed `provider:slot` and keeps a per-key error. Keys have no usage. | ❌ | `vaultAccounts`, `vaultStatus`, `vaultUsage(provider, slot)` | core/Cloud.kt:189-218 | none |
| A10 | Cloud/CloudStore.swift:264-293 | `refreshGitHub()` reads vault and repos. `refreshBilling()` reads billing and status, and keeps `billingError` for the Retry state. | ❌ | `vaultStatus`, `githubRepos`, `billingStatus`, `cloudStatus` | core/Cloud.kt:220-236 | none |
| A11 | Cloud/CloudStore.swift:296-312; App/AppModel.swift:250-262 | `reset()` on sign-out: forget everything and reset the retry delay. | ❌ (Roboco has no sign-out, only "forget engine") | none | core/Cloud.kt:239-255 | none |
| A12 | Cloud/CloudStore.swift:316-333 | `activate` / `remove` (each returns the provider's new list) and `saveKey`, followed by `refreshAccounts`. | ❌ | `vaultActivate`, `vaultForget`, `vaultPutApiKey` | core/Cloud.kt:259-274 | none |
| A13 | Cloud/CloudStore.swift:337-343 | `createProject(repo)`, then `app.workspaceDidChange()` (rebuild now). | ❌ | `createCloudProject(repo)` | core/Cloud.kt:279-282 | none |
| A14 | Cloud/CloudStore.swift:345-354 | `message(error)`: each `CoreError` case maps to its message; `Closed` becomes "You're signed out." | ❌ (Roboco has `accounts/AccountsFormat.kt:234` `describeAccountsError`, but with Roboco wording) | `CoreException.*.reason` | core/Cloud.kt:296-309 | none |
| A15 | Cloud/CloudStore.swift:357-394 | `ProviderSignIn` (Claude / ChatGPT for the Cloud vault): `providerLoginStart(login, "zeron://signed-in")` opens an `ASWebAuthenticationSession` (non-ephemeral, shares browser cookies) that closes itself on `zeron://signed-in`. On cancel it calls `providerLoginCancel` and throws. Otherwise `providerLoginFinish`. No token stays on the phone. | ❌ | `providerLoginStart`, `providerLoginFinish`, `providerLoginCancel` | core/Cloud.kt:329-402 (`BrowserReturns.browseUntil`, `signInToCloud`) | **Auth Tab** (`androidx.browser` `AuthTabIntent`, which returns an ActivityResult for a custom-scheme redirect; the closest match to ASWebAuthenticationSession). Fall back to a Custom Tab plus the `zeron://signed-in` intent filter plus a resume heuristic, as in za:. |

### B. Cloud routes, out-of-credits, and Cloud inside sessions

| # | iOS (file:line) | Behaviour | Roboco status + Kotlin file | zeron core API | za: reference | Native-Android component option |
|---|---|---|---|---|---|---|
| B1 | Cloud/CloudRoutes.swift:11-19 | `openBilling`: Billing as a page sheet with Done, over whatever is presented. | ❌ | none | ui/ZeronRoot.kt (Routes.BILLING) | Push a nav route, or a full-height `ModalBottomSheet`. |
| B2 | Cloud/CloudRoutes.swift:8, 22-28 | "Out of Cloud credits" toast with an **Add credits** action that opens Billing. Plays a warning haptic and sits above the composer. | ⚠️ `design/Toast.kt` (`Toaster.show(text, action, onAction)`) and `design/HapticTable.kt` exist, but are not wired to Cloud | none | ui/NewSessionScreen.kt:180 (Snackbar with action) | M3 `Snackbar` with `actionLabel` plus `HapticFeedbackConstants.REJECT`. Replace the hand-cloned glass toast. |
| B3 | Cloud/CloudRoutes.swift:31-33 | `isOutOfCredits(error)`: the message contains "out of Cloud credits". | ❌ | none | core/Cloud.kt:312 | none |
| B4 | Session/NewSessionViewController.swift:563-566, 587-593 | A Cloud start is refused up front when out of credits (toast). A start error shows the toast or the "Couldn't start on Cloud" alert. | ❌ (`newsession/` has no Cloud) | `createCloudSession` | ui/NewSessionScreen.kt:180-195 | Snackbar or `AlertDialog` |
| B5 | Session/SessionViewController.swift:24, 196-200, 776, 919-976 | `CloudCreditsNotice`: a glass capsule above a Cloud chat's composer (red triangle, "Out of Cloud credits", filled **Add credits**) while `isCloud && outOfCredits`. | ❌ | none | ui/SessionScreen.kt:~490-512 | M3 `Surface(errorContainer)` pill with `FilledTonalButton`. Keep glass only if the session chrome stays glass. |
| B6 | Session/SessionViewController.swift:148-152, 965-974 | A send in an out-of-credits Cloud chat is kept, not sent. The notice shakes and a warning haptic plays. | ❌ | none | ui/SessionScreen.kt:~490-497 (shake) | `Animatable` shake plus `REJECT` haptic |
| B7 | Session/SessionViewController.swift:727-745 | Cloud banner: "not delivered" becomes no pill when out of credits, or "Cloud machine: \<error> · Tap to retry" when the session machine errored. Calls `refreshSessions()`. | ❌ | `cloudSessions` | ui/SessionScreen.kt:464 | Existing status pill |
| B8 | Session/SessionViewController.swift:89-99; Session/CoreSessionSource.swift:339-347 | Error card **Log in**: opens the chat's provider accounts with `signIn: true` as a medium/large sheet. The target is the Cloud agent for a Cloud chat, otherwise the host device's agent. | ⚠️ `session/SessionScreen.kt:282-295`, `accounts/AgentAccountsRoute.kt:37-42`: device only, pushed as a page, with a Roboco "Limited" exception | `deviceLoginStart` / `providerLoginStart` | ui/SessionScreen.kt:187-188, 479 | `ModalBottomSheet` with partial expansion (`skipPartiallyExpanded = false`), the equivalent of the medium/large detents |
| B9 | App/AppModel.swift:739-765; Session/NewSessionViewController.swift:567-584, 598+ | `createCloudSession`: the edge mints the machine and then the first message is sent. A retry reuses the unsent chat when the key (space, branch, harness, model) is unchanged. Shows a "Starting on Cloud…" HUD. Then `askAfterFirstSession`. | ❌ | `createCloudSession(spaceId, config, branch, title)`, `openSession`, `send` | core/AppModel.kt:664-671; ui/NewSessionScreen.kt:189, 250-256 | `LinearProgressIndicator` / `CircularProgressIndicator` capsule |
| B10 | Session/NewSessionViewController.swift:209, 215-226, 373-383 | New session menu: **New Cloud Project…** (hidden unless Cloud is available) opens the repo picker, then `draft.cloud = true`. `runsOnCloud` / `cloudOffered` decide whether to run on Cloud. (Cross-reference the new-session audit.) | ❌ | `githubBranches(repo)` for Cloud branches | ui/NewSessionScreen.kt:557 | `DropdownMenu` item |
| B11 | App/AppModel.swift:668-670 | Cloud projects are hidden when the account is not offered Cloud. | ❌ | `ProjectView.isCloud` | core/AppModel.kt | none |

### C. Settings → Cloud (`CloudSettingsViewController`)

| # | iOS (file:line) | Behaviour | Roboco status + Kotlin file | zeron core API | za: reference | Native-Android component option |
|---|---|---|---|---|---|---|
| C1 | Cloud/CloudSettingsViewController.swift:26-48, 99-102 | "Cloud" page: inset-grouped list with pull-to-refresh calling `cloud.refresh()`. Observes the store. | ❌ | see A2 | ui/CloudSettingsScreen.kt:29 | `LazyColumn` of M3 `ListItem`s in a rounded `Surface`, with `PullToRefreshBox`. Do not reuse Roboco's `design/GroupedList.kt` / `design/PullToRefresh.kt` clones. |
| C2 | Cloud/CloudSettingsViewController.swift:107-111 | Row "Cloud isn't available" with the error or "This account can't use Cloud yet." | ❌ | `CloudInfo.available` | ui/CloudSettingsScreen.kt | `ListItem` |
| C3 | Cloud/CloudSettingsViewController.swift:113-116, 156-157 | Problem row: warning triangle. An out-of-credits problem has an accent "Add credits" accessory and pushes Billing. | ❌ | see A6 | ui/CloudSettingsScreen.kt | `ListItem` with `trailingContent` `TextButton` |
| C4 | Cloud/CloudSettingsViewController.swift:117-124, 150-155 | GitHub row: title "@account" or "GitHub", subtitle "Connected" or "Connect so Cloud agents can clone and push", "Connect" accessory. Tap opens the GitHub connect sheet, or the actions when already connected. | ❌ | `VaultInfo` | ui/CloudSettingsScreen.kt | `ListItem` |
| C5 | Cloud/CloudSettingsViewController.swift:125-141, 158-161 | Repositories: sorted by `pushedAt` descending, first 8 shown plus "N more", lock or book icon, "Private". Summary copy changes for nil / 0 / 1 / n. "Choose on GitHub" opens `githubInstallUrl` in an in-app browser. | ❌ | `githubRepos`, `VaultInfo.githubInstallUrl` | ui/CloudSettingsScreen.kt:131 | `ListItem` rows plus a **Custom Tab** |
| C6 | Cloud/CloudSettingsViewController.swift:83-90 | Footer: "Cloud agents clone and push to the repositories the Zeron GitHub App is installed on." | ❌ | none | ui/CloudSettingsScreen.kt | `supportingText` / caption `Text` |
| C7 | Cloud/CloudSettingsViewController.swift:167-181 | Connected GitHub tap opens an action sheet: "Choose Repositories on GitHub", "Reconnect", "Cancel". | ❌ (Roboco has the hand-cloned `design/ActionSheet.kt`) | none | ui/CloudSettingsScreen.kt:90 (MenuAction) | `ModalBottomSheet` list, or a `DropdownMenu` anchored to the row |

### D. Connect GitHub (`GitHubConnectViewController`)

| # | iOS (file:line) | Behaviour | Roboco status + Kotlin file | zeron core API | za: reference | Native-Android component option |
|---|---|---|---|---|---|---|
| D1 | Cloud/GitHubConnectViewController.swift:19-28, 42-44 | Sheet with medium/large detents, grabber and Close. Calls `onConnected` when done. | ❌ | none | ui/GitHubConnect.kt:74 | `ModalBottomSheet` with drag handle |
| D2 | Cloud/GitHubConnectViewController.swift:45-66, 102-118 | GitHub tile and header. Large monospaced code card (34 pt, starts as "· · · ·"). Status line with spinner: "Getting a code…", then "Waiting for GitHub…". Errors in the danger colour. | ❌ | `githubConnectStart()` → `GithubDeviceFlowInfo` | ui/GitHubConnect.kt | `Card` plus `Text(fontFamily=Monospace)` plus `CircularProgressIndicator` |
| D3 | Cloud/GitHubConnectViewController.swift:74-76, 120-124 | "Copy Code & Open GitHub" (disabled until a code arrives) copies `userCode` and opens `verificationUri` in an in-app browser. | ❌ | none | ui/GitHubConnect.kt:121-123 | `Button` plus `ClipboardManager` plus Custom Tab. Android 13+ shows its own clipboard confirmation, so don't add a toast. |
| D4 | Cloud/GitHubConnectViewController.swift:126-146 | Polls every `max(interval, 2)` s; handles `connected` and `failed` (with message). Cancelled on deinit. | ❌ | `githubConnectPoll(flowId)` | ui/GitHubConnect.kt | `LaunchedEffect` loop |
| D5 | Cloud/GitHubConnectViewController.swift:148-160 | Connected: success haptic, green "Connected", "Signed in to GitHub as @x." Closes the browser, `refreshGitHub()`, waits 0.9 s, then dismisses and calls `onConnected(account)`. | ❌ | see A10 | ui/GitHubConnect.kt | `HapticFeedbackConstants.CONFIRM` |

### E. New Cloud Project (`CloudRepoPickerViewController`)

| # | iOS (file:line) | Behaviour | Roboco status + Kotlin file | zeron core API | za: reference | Native-Android component option |
|---|---|---|---|---|---|---|
| E1 | Cloud/CloudRepoPickerViewController.swift:19, 30-38 | "New Cloud Project" with Cancel and a pinned search field "Search repositories". | ❌ | none | ui/CloudRepoPicker.kt:42 | M3 `SearchBar` / `DockedSearchBar`, or a `TopAppBar` plus `TextField` |
| E2 | Cloud/CloudRepoPickerViewController.swift:44-60, 112-115 | Loads via `refreshGitHub()`. Filters by `fullName` contains (lowercase). Sorted by `pushedAt` descending. | ❌ | `githubRepos(null)`, `vaultStatus` | ui/CloudRepoPicker.kt | none |
| E3 | Cloud/CloudRepoPickerViewController.swift:62-110 | Empty states: spinner; "Couldn't load repositories" with **Try again**; "No repositories" (connected) or "Connect GitHub" with a Connect button that opens D1. | ❌ | none | ui/CloudRepoPicker.kt | `OutlinedButton` / `Button` |
| E4 | Cloud/CloudRepoPickerViewController.swift:117-147 | Row: GitHub mark, `fullName`, "Private · description" on one line, spinner while creating. Footer: "Each session in a Cloud project runs on its own Cloud machine." | ❌ | `GithubRepoInfo` | ui/CloudRepoPicker.kt | `ListItem` |
| E5 | Cloud/CloudRepoPickerViewController.swift:149-169 | Tap: `createProject`, success haptic, dismiss, `onCreated(id)`. On error: alert "Couldn't add the project". | ❌ | `createCloudProject` | ui/CloudRepoPicker.kt | `AlertDialog` |

### F. Billing (`BillingViewController`)

| # | iOS (file:line) | Behaviour | Roboco status + Kotlin file | zeron core API | za: reference | Native-Android component option |
|---|---|---|---|---|---|---|
| F1 | Cloud/BillingViewController.swift:17-51 | "Billing" page: scroll stack, observes the store, `refreshBilling()` on load. | ❌ | `billingStatus`, `cloudStatus` | ui/BillingScreen.kt:75 | `LazyColumn` |
| F2 | Cloud/BillingViewController.swift:57-76 | No billing yet: "Couldn't load billing. \<err>" with **Try again**, or a spinner. Never a spinner that runs forever. | ❌ | none | ui/BillingScreen.kt | `CircularProgressIndicator`, `OutlinedButton` |
| F3 | Cloud/BillingViewController.swift:77-79 | `!available`: "Billing isn't set up on this server yet…" | ❌ | `BillingInfo.available` | ui/BillingScreen.kt | `Text` |
| F4 | Cloud/BillingViewController.swift:105-129, 377-451 | **Credits** card. A figure row (30 pt total plus unit plus "about N hours", or "Choose a plan or buy credits to run Cloud"). A 4 pt `SplitBar` with plan at alpha 0.7 and extra at 0.3. A "Plan credits" row ("Of X · resets MMM d" or "No plan yet — choose one below"). An "Extra credits" row ("Never expire · used once plan credits run out"). | ❌ | `creditsTotal`, `creditsMonthly`, `creditsMonthlyTotal`, `creditsMonthlyEndsMs`, `creditsExtra` | ui/BillingScreen.kt:221-285 | `Card` plus a custom two-segment bar (M3 `LinearProgressIndicator` has no two-segment mode, so a small `Canvas`). Use `fontFeatureSettings="tnum"` for figures. |
| F5 | Cloud/BillingViewController.swift:133-155, 455-510 | **Plan** cards: name, "Current" badge and 1 px border on the current plan, "$X / month", "N credits a month", hours. Action: "Current plan" (disabled), "Switch" (opens the portal), or "Choose \<plan>" (subscription checkout; the first plan is solid). | ❌ | `plans`, `plan`, `billingCheckout("subscription", plan, null)`, `billingPortal()` | ui/BillingScreen.kt:287-329 | `OutlinedCard` (current) / `ElevatedCard`. `Button` / `FilledTonalButton`. Badge via `Badge` or `AssistChip`. |
| F6 | Cloud/BillingViewController.swift:156-174 | Current plan row: "\<name> · $X a month". Detail is either "past_due" (warning: "Your last payment didn't go through…"), "Ends MMM d…" (cancel at period end), or "Renews MMM d." **Manage** (quiet, stroked) opens the portal. | ❌ | `planStatus`, `cancelAtPeriodEnd`, `periodEndMs` | ui/BillingScreen.kt | `OutlinedButton` |
| F7 | Cloud/BillingViewController.swift:179-193, 512-553 | **Extra credits**: a − count + pill (1…20, ends disabled), "N credits", "Never expire · about N hours", solid **Buy for $X** that opens credits checkout. | ❌ | `packCredits`, `packPriceCents`, `billingCheckout("credits", null, packs)` | ui/BillingScreen.kt:331-367 | No M3 stepper exists: use two `IconButton`s in a `Surface` pill, or an M3 Expressive `ButtonGroup`. Don't clone `UIStepper`. |
| F8 | Cloud/BillingViewController.swift:281-322 | Three button tones (solid, filled, quiet with stroke), 10 pt corner radius. Disabled is 50 % alpha, not grey. | ❌ | none | ui/BillingScreen.kt | `Button` / `FilledTonalButton` / `OutlinedButton` with `shape = RoundedCornerShape(10.dp)` |
| F9 | Cloud/BillingViewController.swift:331-359 | Checkout and portal: the busy key shows "Opening…" and the other buttons are disabled. The URL opens in an in-app browser. On error: "Couldn't open payments". | ❌ | `billingCheckout`, `billingPortal` | ui/BillingScreen.kt:95-110 | Custom Tab |
| F10 | Cloud/BillingViewController.swift:361-372 | Back from Stripe (the browser was closed): re-read up to 6 times, every 2 s, until the plan or credits change (the Stripe webhook lands late). Stripe returns to the https page `{edge}/billing/done` (edge cloud/routes.ts:295), not to `zeron://`. | ❌ | `billingStatus` | ui/BillingScreen.kt:86-105 (`settle()` on `browser.resumes`) | Custom Tab close is detected by `onResume` |
| F11 | Cloud/CloudUI.swift:113-130; BillingViewController.swift:324-327 | Formatters: credits "41,250" (floored), price "$20" / "$12.50", `shortDate` "MMM d", `hours` = credits/60 rounded ("about N hours"). | ❌ | none | core/Cloud.kt:315-320; ui/BillingScreen.kt:66 | `NumberFormat`, `DateTimeFormatter` |

### G. Settings → Providers (`ProvidersViewController`) and the Settings rows

| # | iOS (file:line) | Behaviour | Roboco status + Kotlin file | zeron core API | za: reference | Native-Android component option |
|---|---|---|---|---|---|---|
| G1 | Shell/MoreViewController.swift:99-101, 197-210, 257-262 | Settings "Agents" section. **Providers** row ("Agents and their accounts on each device", cpu icon). Then, only when Cloud is available: **Cloud** row (subtitle is the problem, "GitHub @x" or "Connect GitHub") and **Billing** row ("\<Plan> plan · N credits" or "Plans and credits"). Calls `cloud.refresh()` on load. | ⚠️ `settings/SettingsScreen.kt:185-186, 218` plus `accounts/AgentAccountsSection.kt`: a Roboco-only "Agent Accounts" summary (one row per harness with a mini meter). No Providers, Cloud or Billing rows. | see A2 | ui/SettingsScreen.kt:108-122 | M3 `ListItem` with leading icon tile |
| G2 | Cloud/ProvidersViewController.swift:45-56, 251-257 | Initial device, in order: explicit target, then the remembered `providersDevice` (if still valid), then the first online computer, then the first computer, then Cloud. The choice is persisted. | ⚠️ `accounts/AgentAccountsScreen.kt:941-945` `primaryDevice()` (the online device with most sessions). Not persisted, no Cloud. | `devices()` / `executionDevices()` | ui/ProvidersScreen.kt:47 (`CLOUD`) | DataStore / SharedPreferences |
| G3 | Cloud/ProvidersViewController.swift:117-128, 233-249 | "Device" row with the current name and a pop-up menu: computers (laptop icon, "Offline" subtitle, check on current) and **Cloud** when offered. No menu at all when it would be empty (avoids a UIKit abort). | ⚠️ `accounts/AgentAccountsScreen.kt:258-290` `DevicePicker`: hand-cloned glass circle button plus glass menu, computers only | `DeviceView{id,name,online}` | ui/ProvidersScreen.kt | `ExposedDropdownMenuBox` or `DropdownMenu` on a trailing `TextButton` (replace `design/glass/Menu`) |
| G4 | Cloud/ProvidersViewController.swift:189-197 | Agents footer, three variants: Cloud ("…stores the login in Cloud, not on this phone."), offline ("\<name> is offline — showing what it last reported."), or computer ("Agents run with the logins on \<name>…"). | ⚠️ `accounts/AccountSignIn.kt:103-104, 184` (`SignInCopy.about`, `AgentsCopy.Footer`): different copy, no Cloud variant | none | ui/ProvidersScreen.kt | `Text` caption |
| G5 | Cloud/ProvidersViewController.swift:183-187, 201-231 | Load: `listHarnesses(device)`, then (only if online) `deviceAgentAccounts(device)`. Failures are kept per device. Accounts are re-read on `viewWillAppear` after returning from an agent's page. | ⚠️ `accounts/AgentAccountsModel.kt:239-333` (`AccountSignIn.harnesses` plus Roboco `listAgentAccounts(force)` / `cachedAgentAccounts`) | `listHarnesses`, `deviceAgentAccounts` | ui/ProvidersScreen.kt:92 | none |
| G6 | Cloud/ProvidersViewController.swift:261-283 | If Cloud was remembered but is not offered, fall back to a computer. `onSummary(place, cloud, names)` feeds onboarding's headline. | ❌ | none | ui/OnboardingScreen.kt (AgentsPage) | none |
| G7 | Cloud/ProvidersViewController.swift:285-308, 129-135 | Cloud rows, one per agent. Subtitle is the active "email · plan" (or key name) with the count summary, or "Finishing sign-in…". A **Sign in** capsule shows when there are no accounts. "Loading…" and "No computers yet" placeholders. | ❌ | `vaultAccounts`, `providerLoginStart` | ui/ProvidersScreen.kt | `ListItem` with trailing `FilledTonalButton` |
| G8 | Cloud/ProvidersViewController.swift:310-343, 140-151 | Computer rows: installed first, then by name. Dimmed brand mark when not installed. Subtitle is "Not installed", the load failure, "Installed" / "Offline", or the account summary. Switch is locked when offline, not installed, or it is the last agent on. Disclosure when the agent signs in or has logins. Spinner while toggling. | ⚠️ `accounts/AccountSignIn.kt:206-225` `agentToggleRows` (same lock rules) inside the combined accounts page. The subtitle has no account summary. Uses `design/RobocoSwitch.kt` (a UISwitch clone). | `HarnessInfo{installed,offered,label}`, `agentLoginRules(h).signsIn` | ui/ProvidersScreen.kt:200 | M3 `Switch` (themed) inside `ListItem` |
| G9 | Cloud/ProvidersViewController.swift:345-357 | `accountTitle` = email, else display name, else "API key" / "Account". Summary: "\<active> · N accounts", "No account", "1 account", "N accounts". | ⚠️ `accounts/AccountsFormat.kt:123` (fallback "Unknown account") | `DeviceAccountInfo` | ui/ProvidersScreen.kt | none |
| G10 | Cloud/ProvidersViewController.swift:361-380 | Toggle: `setHarnessEnabled` replaces the catalog, plays a selection haptic. On error: "Couldn't turn it on" / "Couldn't turn it off" alert. | ✅ `accounts/AgentAccountsModel.kt:471-495` (same call and titles; the message comes from `agentToggleFailure`) | `setHarnessEnabled(deviceId, harness, enabled)` | ui/ProvidersScreen.kt:128 | `AlertDialog` |
| G11 | Cloud/ProvidersViewController.swift:382-400 | The Cloud row's **Sign in** runs `ProviderSignIn` (success haptic, or "Couldn't sign in"), then `refreshAccounts`. | ❌ | see A15 | core/Cloud.kt:390 | Auth Tab / Custom Tab |
| G12 | Cloud/ProvidersViewController.swift:404-412 | Pushed inside Settings. Shown as a sheet with Done where there is no navigation stack (onboarding). | ⚠️ Roboco always pushes `AgentAccountsRoute` | none | ui/ProvidersScreen.kt | nav push, or `ModalBottomSheet` |
| G13 | Cloud/ProvidersViewController.swift:414-434 | Row tap opens that **one agent's** accounts page: `.cloud(agent)`, or `.device(id, name, harness, label)` with the accounts passed in plus `onChange`. | ⚠️ Roboco has **one combined** "Accounts & Usage" page for all harnesses on a device, with focus-scroll (`accounts/AgentAccountsRoute.kt:21-27`, `AgentAccountsScreen.kt:293-321`) | none | ui/AgentAccountsScreen.kt | Separate route per agent |

### H. One agent's accounts (`AgentAccountsViewController`, `DeviceSignIn`, `AccountCell`, `UsageMeter`)

| # | iOS (file:line) | Behaviour | Roboco status + Kotlin file | zeron core API | za: reference | Native-Android component option |
|---|---|---|---|---|---|---|
| H1 | Cloud/AgentAccountsViewController.swift:58-77, 120-126 | Target is Cloud(agent) or Device(id, name, harness, label). Title is the agent name or `rules.name`. `signIn: true` starts the first login option on appear. | ⚠️ `accounts/AgentAccountsRoute.kt:21-27` plus `AgentAccountsModel.startFirstLogin` (l.544-551), device only | `agentLoginRules(harness)` | ui/AgentAccountsScreen.kt:97 | none |
| H2 | Cloud/AgentAccountsViewController.swift:101-107 | Done button when the page is the root of a presented sheet. | ❌ (always pushed) | none | ui/AgentAccountsScreen.kt | `TopAppBar` action / sheet |
| H3 | Cloud/AgentAccountsViewController.swift:111-118, 130-153 | Pull to refresh. Cloud calls `refreshAccounts`. Device calls `deviceAgentAccounts` and reports `onChange` to Providers. | ⚠️ `accounts/AgentAccountsModel.kt:239-333`: cache, then plain read, then forced re-probe (Roboco-core-only `listAgentAccounts(force)` / `cachedAgentAccounts`) | `deviceAgentAccounts(deviceId)`; Cloud: `vaultAccounts` | ui/AgentAccountsScreen.kt:122 | `PullToRefreshBox` |
| H4 | Cloud/AgentAccountsViewController.swift:155-185 | Account model. **Cloud logins**: title is email, display name or "\<Agent> account". Detail is the live usage plan (else the stored plan) · organization. Windows come from `vault_usage`. Note is "Sign in again" when reconnect is needed, else the usage error. **Cloud keys**: titled with `keyName`. **Device**: title as in G9. Detail is planLabel · organization · option label (only when there are >1 options). Usage only if `rules.reportsUsage`. `switchable && rules.switches`. | ⚠️ device: `accounts/AccountsFormat.kt:123-150` (`accountDetail`, drops a duplicate organization; `missingUsage` copy differs). Cloud: ❌ | `DeviceAccountInfo`, `CloudAccountInfo`, `CloudUsageInfo` | ui/AgentAccountsScreen.kt | none |
| H5 | Cloud/AgentAccountsViewController.swift:187-207 | Add rows: one per login option, using `firstTitle` when empty and `moreTitle` otherwise. None when the agent `keepsOneLogin` and already has one. Cloud also gets "Add \<keyName>". | ⚠️ `accounts/AccountSignIn.kt:122-134` `addAccountRows` (identical for devices). Cloud add-key missing. | `LoginOptionInfo` | ui/AgentAccountsScreen.kt | none |
| H6 | Cloud/AgentAccountsViewController.swift:211-223, 255-257 | Items: placeholder "Loading accounts…" or "Couldn't load accounts" plus the error with a triangle, then accounts, add rows, waiting row. | ⚠️ `accounts/AgentAccountsModel.kt:37-123` (`AccountsCopy.Loading` same; failure copy from `describeAccountsError`; Roboco adds an "Unsupported engine" state) | none | ui/AgentAccountsScreen.kt | `ListItem` |
| H7 | Cloud/AgentAccountsViewController.swift:231-251 | Header "Accounts on \<device>". Footer is `rules.note` plus the where-copy (Cloud: "…kept in Cloud, never on this phone."; device: "Account changes affect new sessions on \<name>. Signing in runs on \<name>…"). | ⚠️ `accounts/AccountSignIn.kt:103-104` (device copy close; no `rules.note`; no Cloud copy). Header is per harness (`HarnessHeader`). | `AgentLoginRules.note` | ui/AgentAccountsScreen.kt | Section header `Text` |
| H8 | Cloud/AgentAccountsViewController.swift:594-721 | `AccountCell`: 22 pt brand mark (or key icon). Title is middle-truncated, detail up to 2 lines, up to 3 meters, warning note. Trailing: **In use** badge (success at 12 %), **Switch** capsule, spinner, or nothing. A fixed account's title is dimmed. | ⚠️ `accounts/AgentAccountsScreen.kt:619-776` `AgentAccountRow` (avatar, meta line "In use", desktop-#578 look) | none | ui/AgentAccountsScreen.kt | `ListItem` with `trailingContent` `AssistChip` / `FilledTonalButton` (small) |
| H9 | Cloud/AgentAccountsViewController.swift:269-288 | Add row: muted plus and label lined up with names. Disabled (tertiary) while busy. | ✅ `accounts/AgentAccountsScreen.kt:507` `AddAccountRowView` | none | ui/AgentAccountsScreen.kt | `ListItem` with leading `Icons.Add` |
| H10 | Cloud/AgentAccountsViewController.swift:289-300, 324-330 | Waiting row: "Finish signing in in the browser…" plus message · "Tap to cancel" (device only), spinner. Tap cancels. | ✅ `accounts/AgentAccountsScreen.kt:549` `SignInWaitingRow` plus `AccountSignIn.kt:163-164` | `deviceLoginCancel` | ui/AgentAccountsScreen.kt | `ListItem` plus `CircularProgressIndicator` |
| H11 | Cloud/AgentAccountsViewController.swift:349-354 | Leaving the page cancels a device sign-in. | ✅ `accounts/AgentAccountsScreen.kt:187-188` (`DisposableEffect` calling `cancelSignIn`) | `deviceLoginCancel` | ui/AgentAccountsScreen.kt | none |
| H12 | Cloud/AgentAccountsViewController.swift:356-363 | Trailing swipe **Remove** (destructive, no confirmation). | ⚠️ No swipe. Remove goes through a confirmation action sheet (`AgentAccountsModel.onRemove` l.406-426, hand-cloned `design/ActionSheet.kt`). | `deviceForgetAccount` / `vaultForget` | ui/AgentAccountsScreen.kt | M3 `SwipeToDismissBox` (end-to-start) |
| H13 | Cloud/AgentAccountsViewController.swift:365-375 | Context menu: "Switch to This Account" (if inactive) and "Remove Account" (destructive). | ⚠️ `accounts/AgentAccountsScreen.kt:778` (`Use This Account`, `Copy Email`, `Remove Account…`; hand-cloned context menu) | none | ui/AgentAccountsScreen.kt | `DropdownMenu` on long-press (`combinedClickable`) |
| H14 | Cloud/AgentAccountsViewController.swift:379-390, 411-425 | Switch: busy spinner on the row, then the reply replaces the list. Selection haptic. On error: "Couldn't update the account". | ⚠️ `accounts/AgentAccountsModel.kt:360-398`: **optimistic** flip with revert, a "\<harness> now uses \<login>" toast, and "Couldn't switch accounts" | `deviceActivateAccount`, `vaultActivate` | ui/AgentAccountsScreen.kt:204 | none |
| H15 | Cloud/AgentAccountsViewController.swift:392-409 | Remove: `vaultForget` (provider, slot), or `deviceForgetAccount`. | ⚠️ `accounts/AgentAccountsModel.kt:428-448` (device only; "Couldn't remove the account") | `deviceForgetAccount`, `vaultForget` | ui/AgentAccountsScreen.kt:214 | none |
| H16 | Cloud/AgentAccountsViewController.swift:427-454 | Add or sign in: Cloud uses `ProviderSignIn` then `refreshAccounts`. Device uses `DeviceSignIn`. Success haptic; "Couldn't sign in". | ⚠️ device: `accounts/AgentAccountsModel.kt:526-635`. Cloud: ❌ | see A15 / H18 | ui/AgentAccountsScreen.kt:234-260 | none |
| H17 | Cloud/AgentAccountsViewController.swift:456-471 | Cloud API key alert: "Cloud sessions use this key instead of a subscription sign-in." Secure field with placeholder "sk-ant-…" or "sk-…", no autocorrect. **Save Key** calls `vaultPutApiKey`. | ❌ | `vaultPutApiKey(provider, key)` | core/Cloud.kt:270-274 | `AlertDialog` with `OutlinedTextField(visualTransformation = PasswordVisualTransformation(), keyboardType = Password)` |
| H18 | Cloud/AgentAccountsViewController.swift:485-549 | `DeviceSignIn.run`: `deviceLoginStart(..., doneUrl="zeron://signed-in")`. **pasteCode**: open the page, ask for the code, `deviceLoginComplete`. Otherwise open the URL (or the URL from a later poll), poll every 1.5 s for up to 10 min, show the message, copy any "ABCD-1234" device code to the clipboard. `done` → re-read; `error` → throw; timeout → cancel. | ⚠️ `accounts/AgentAccountsModel.kt:563-635` (same loop, copy, deadline). Roboco core's `deviceLoginStart/Poll` take **no `doneUrl`** and rely on a phone **loopback** relay (`callbackPort`, l.583-586); zeron relays through `doneUrl`. | `deviceLoginStart(deviceId, harness, provider, doneUrl)`, `deviceLoginPoll(deviceId, loginId, doneUrl)`, `deviceLoginComplete`, `deviceLoginCancel` | ui/AgentAccountsScreen.kt:234-260 | none |
| H19 | Cloud/AgentAccountsViewController.swift:551-564 | The page opens in an auth sheet that **closes itself** when the relayed return redirects to `zeron://signed-in`. Closing it by hand does not end the sign-in. | ⚠️ Roboco `openUrl` is a plain Custom Tab, which never closes on its own | none | core/Cloud.kt:329-382 | Auth Tab; or a Custom Tab plus `zeron://signed-in` on a `singleTask` `MainActivity` (the tab above the root is cleared when the intent arrives) |
| H20 | Cloud/AgentAccountsViewController.swift:566-582 | "Paste the code" alert (Cancel / Continue, trimmed, empty means cancel). | ✅ `accounts/AgentAccountsModel.kt:648-671` (verbatim copy) | `deviceLoginComplete` | ui/AgentAccountsScreen.kt | `AlertDialog` with `OutlinedTextField` (replace the `design/Alert.kt` clone) |
| H21 | Cloud/AgentAccountsViewController.swift:723-776 | `UsageMeter`: 56 pt label, 4 pt track, fill at text colour 55 % (danger above 90 %), "34% · 3h" (m/h/d until reset). | ⚠️ `accounts/AgentAccountsScreen.kt:827-940` (desktop thresholds: amber ≥ 80 %, red ≥ 95 %; "Resets in 2h 13m" wording) | `UsageWindowInfo{label, usedFraction, resetsAtMs}` | ui/AgentAccountsScreen.kt | M3 determinate `LinearProgressIndicator` (custom `color` / `trackColor`, `gapSize = 0`) |

### I. Roboco-only account surfaces (no iOS counterpart)

| # | iOS (file:line) | Behaviour | Roboco status + Kotlin file | zeron core API | za: reference | Native-Android component option |
|---|---|---|---|---|---|---|
| I1 | none | Plan usage folded into the composer's context chip (tint, plus an "Account" section in its card, plus switching from the chip). | ⚠️ Roboco-only: `accounts/SessionUsageChip.kt` (`SessionAccountUsage`). Remove for a 1:1 port, or reimplement on `deviceAgentAccounts` (zeron has no forced probe and no cache). | `deviceAgentAccounts` only | none | none |
| I2 | none | Session ⋯ menu "Account & Usage" row. | ⚠️ Roboco-only: `session/SessionMenu.kt:262-272`. Remove. | none | none | none |

### J. Onboarding (`OnboardingViewController`): Cloud, agents and notification steps

| # | iOS (file:line) | Behaviour | Roboco status + Kotlin file | zeron core API | za: reference | Native-Android component option |
|---|---|---|---|---|---|---|
| J1 | Cloud/OnboardingViewController.swift:10-24, 44-56, 58-63 | Steps: appearance, sync, github, agents, notifications, done. Shown when key `onboarded:<userId>` is unset. Full-screen, over the wallpaper (blurred except on the appearance step). | ❌ (Roboco has no onboarding; its first run is pairing) | `CoreClient.userId()` | ui/OnboardingScreen.kt:92-203 | `AnimatedContent` (slide + fade, honour reduce-motion) |
| J2 | Cloud/OnboardingViewController.swift:71-74, 132-137, 146, 221 | `cloudWithheld` (status answered "not available"): skip GitHub; if the status lands while on the GitHub step, move on; Back from Agents goes to Sync. | ❌ | `CloudInfo.available` | ui/OnboardingScreen.kt:149-156 | none |
| J3 | Cloud/OnboardingViewController.swift:188-210 | Progress: five 20×3 pt bars plus "N of 5". | ❌ | none | ui/OnboardingScreen.kt | Row of `Box`es, or an M3 segmented `LinearProgressIndicator` |
| J4 | Cloud/OnboardingViewController.swift:213-260 | Footer: Back (ghost); then Continue, **Log in** / "Logging in…", Skip for now (GitHub not connected), or **Not now** plus **Turn on** (notifications). | ❌ | none | ui/OnboardingScreen.kt | `TextButton` plus `Button` |
| J5 | Cloud/OnboardingViewController.swift:262-315 | Appearance page: System/Light/Dark segmented control, wallpaper row, effect menu, preview. (Cross-reference the appearance audit.) | ❌ | none | ui/OnboardingScreen.kt | `SingleChoiceSegmentedButtonRow` |
| J6 | Cloud/OnboardingViewController.swift:317-337, 616-628 | Sync page: three feature rows; "Signed in as \<email>" or the login error. **Log in** runs WorkOS (see L2). | ❌ | see L2 | ui/OnboardingScreen.kt; core/AppModel.kt:329-360 | `ListItem`s in a `Card` |
| J7 | Cloud/OnboardingViewController.swift:339-358, 630-632 | GitHub page: a row with **Connect** (opens D1), or a check plus "@acct" plus "N repositories". | ❌ | see A8 | ui/OnboardingScreen.kt:200 | `ListItem` with trailing button or check icon |
| J8 | Cloud/OnboardingViewController.swift:360-410 | Agents page embeds Providers itself (device switcher and switches). The headline comes from `onSummary` ("Looking for coding agents on…", "Cloud runs … with your own subscriptions…", "Found … on \<place>…", "…none are on \<place> yet."). The list fades out at the bottom edge. | ❌ | see G | ui/OnboardingScreen.kt:201 | Reuse the Providers composable. Fade via `Modifier.drawWithContent` gradient mask. |
| J9 | Cloud/OnboardingViewController.swift:412-422, 249-256 | Notifications page ("Stay in the loop": Finished / Needs you / Something went wrong). **Turn on** sets `enabled = true`, requests permission, then goes to Done. | ❌ (Roboco has the machinery: `notify/NotificationPermission.kt`, `notify/NotificationPrefs.kt`) | none | ui/OnboardingScreen.kt:168, 202, 229 | `rememberLauncherForActivityResult(RequestPermission)` |
| J10 | Cloud/OnboardingViewController.swift:424-501, 634-638 | Done page: check badge; "You're all set" (copy depends on whether Cloud is withheld); summary card (Appearance, Sync, GitHub, Cloud agents); **Start a session** / "I'll do this later". `markDone`, then `onFinish(startSession)`. | ❌ | `CloudStore.activeAccount` | ui/OnboardingScreen.kt:203 | `Card` plus `Button` / `TextButton` |
| J11 | App/SceneDelegate.swift:24-29, 66, 85-104 | Shown right after sign-in (no slide-up) and at launch if not done. `-onboarding <step>` forces a step. Finishing with "Start a session" presents New Session. | ❌ | none | ui/ZeronRoot.kt | none |

### K. Notifications

| # | iOS (file:line) | Behaviour | Roboco status + Kotlin file | zeron core API | za: reference | Native-Android component option |
|---|---|---|---|---|---|---|
| K1 | App/PushNotifications.swift:13-23 | Kinds done / input / failed, with titles "Run finished" / "Waiting on your input" / "Run failed". The title is both the row title and the banner body. | ✅ `notify/NotificationPrefs.kt:15-22` | `PushPrefs{done,input,failed}` | none | none |
| K2 | App/PushNotifications.swift:32-55 | Preferences `notifications.enabled` and `notifications.<kind>`, default true; `notifications.asked`. | ✅ `notify/NotificationPrefs.kt:40-142` (same keys) | none | none | SharedPreferences (keep: read off the main thread on the delivery path) |
| K3 | App/PushNotifications.swift:59-74; App/SceneDelegate.swift:156-160; App/AppDelegate.swift:10-21 | Delivery mechanism. The delegate is installed before launch finishes. On sign-in or shell shown, `registerForRemoteNotifications` runs if allowed. **The edge decides and sends via APNs.** | ⚠️ Roboco watches locally (`notify/NotificationCenter.kt` implements `uniffi.roboco_core.NotificationSink`, the run-watch FGS). The zeron core has no sink, so this is replaced by the §2 design. | none (see §2) | none (za: has no session notifications) | Local `NotificationManagerCompat` plus FGS (§2) |
| K4 | App/PushNotifications.swift:76-82; Session/NewSessionViewController.swift:549, 580 | Ask after the first session started from this phone (once, if enabled, not demo), on both the local and the **Cloud** create paths. | ✅ `notify/NotificationPermission.kt:121-130`, `newsession/NewSessionSend.kt:74-109` (wire it to the Cloud path when B9 lands) | none | none | `POST_NOTIFICATIONS` (API 33+) |
| K5 | App/PushNotifications.swift:84-105 | `requestPermission`: ask when not determined, otherwise read the status. | ✅ `notify/NotificationPermission.kt` | none | ui/OnboardingScreen.kt:168 | `ActivityResultContracts.RequestPermission` |
| K6 | App/PushNotifications.swift:107-112; App/SceneDelegate.swift:30-35 | Sign-out: `unregisterPushTarget`, then unregister for remote notifications. | ❌ (no sign-out). On Android: cancel all chat notifications, stop the watcher, reset baselines. Calling `unregisterPushTarget` is harmless (it DELETEs this device's row). | `unregisterPushTarget()` | none | `NotificationManager.cancelAll()` |
| K7 | App/PushNotifications.swift:114-153 | Token sync: `registerPushTarget(hexToken, "production"/"sandbox", prefs)` when enabled; `unregisterPushTarget` when disabled. The APNs environment is read from the provisioning profile. | ❌ Not usable on Android without an edge change (§2.1, §2.5) | `registerPushTarget(token, environment, prefs)`, `unregisterPushTarget()` | none | none |
| K8 | App/PushNotifications.swift:160-167 | In the app: no banner and no sound (`willPresent` returns `[]`). | ✅ `notify/NotificationPolicy.kt:21-29` (foreground gate, using `ProcessLifecycleOwner`) | none | none | none |
| K9 | App/PushNotifications.swift:169-193; App/SceneDelegate.swift:17-23, 156-160 | Tap opens `userInfo.chatId`. If the shell isn't up, it is held in `pendingChat` until `shellReady()`. | ⚠️ `notify/Notifier.kt:338-352` (`roboco://chat/<scopedId>`) plus `shell/DeepLinkHub.kt` pending slot. Rewire to an explicit intent extra `chatId` with zeron (unscoped) ids; drop `ScopedIds` / `ChatIds`. | `openSession` | none | Explicit `PendingIntent` to the `singleTask` `MainActivity` |
| K10 | edge push-notify.ts:22-69 (rule); registry-room.ts:641-656 | Which change notifies: failed / input / done; side and archived chats never notify; title is the chat title or "New session". | ⚠️ In Roboco the rule lives in the **Roboco Rust core** (`SessionEvent`), which zeron lacks. It must be re-implemented in Kotlin from `WorkspaceChanged` rows (§2.4). | `ClientListener.onEvent(WorkspaceChanged)`, `workspace()`, `SessionRow.hostIndicator/unseen/archived/parentChatId/workingSinceMs` | none | none |
| K11 | edge push-notify.ts:91-99; apns.ts:62-71 | One thread per chat; a newer notification replaces the older (`thread-id` / `collapse-id` = chatId); default sound; 24 h expiry. | ✅ `notify/Notifier.kt:122-159` (tag `chat:<engine>:<chat>`, id 1 replaces). Retag to `chat:<chatId>`. | none | none | `NotificationCompat` tag replacement |
| K12 | Shell/MoreViewController.swift:103-127 | Permission status re-read on appear and on return from Settings. | ✅ `settings/SettingsScreen.kt:205-210` (`ON_RESUME`) | none | none | `LifecycleEventEffect(ON_RESUME)` |
| K13 | Shell/MoreViewController.swift:129-147, 217-218 | Settings "Notifications": master row (subtitle "When a session finishes, needs you or fails", or the denied copy) plus the three kind rows while effectively on. | ✅ `settings/NotificationSettings.kt` (denied copy: "Turned off in Android settings") | none | none (za: has none) | M3 `Switch` in `ListItem` (replace `design/RobocoSwitch.kt`) |
| K14 | Shell/MoreViewController.swift:158-185 | Toggles: off persists `enabled=false`. Denied shows the "Notifications are off" alert (Not Now / Open Settings). On sets `enabled=true` then requests. Kinds only write their preference. | ✅ `settings/NotificationSettings.kt:33-60+` | none | none | `AlertDialog`; `Settings.ACTION_APP_NOTIFICATION_SETTINGS` |
| K15 | none (Android-only) | Versioned channels with the desktop chimes and vibration; "Runs in progress" ongoing notification with chronometer; API 36 Live Update promotion; "Stay connected in background". | ⚠️ Keep: `notify/Notifier.kt:55-118, 239-291`, `notify/OngoingContent.kt`, `background/RunWatchService.kt`, `settings/BackgroundSettingsSection.kt` | none | none | Native already (`NotificationChannel`, `ProgressStyle`, FGS) |
| K16 | none (Android-only) | "Connection unavailable" notification when an engine link drops. | ⚠️ `notify/Notifier.kt:161-185`. iOS has none. Drop for 1:1, or drive from `ClientEvent.ConnectivityChanged` and keep off by default. | `ConnectivityChanged` | none | none |
| K17 | none (iOS has no watcher; the edge does it) | Run watcher input: the working set and awaiting count feed the FGS. | ⚠️ `background/RunWatchController.kt:149-169` consumes the Roboco core's `RunActivity`. Re-derive it from zeron rows (§2.4). | `workspace()` rows `hostIndicator == WORKING`, `workingSinceMs`, `sendState` | core/DeliveryService.kt (send hold only) | FGS `dataSync` / `specialUse` |

### L. Deep links and URL schemes

| # | iOS (file:line) | Behaviour | Roboco status + Kotlin file | zeron core API | za: reference | Native-Android component option |
|---|---|---|---|---|---|---|
| L1 | Info.plist:25-33 | The only URL scheme: `zeron` (`sh.zeron.ios.auth`). | ❌ The Roboco manifest declares only `roboco` (chat/new/settings/search/pair) | `authCallbackScheme()` returns `"zeron"` | AndroidManifest.xml (`zeron` with hosts callback / voice / signed-in / cloud) | `<intent-filter>` VIEW + BROWSABLE |
| L2 | Shell/SignInViewController.swift:4-43, 118-147 | WorkOS AuthKit: authorize with `redirect_uri=zeron://callback`. The state is checked, the code exchanged, and an organisation picked when there are several. | ❌ (Roboco has no account sign-in) | `workosAuthorizeUrl(state)`, `parseAuthCallback(url)`, `authExchangeCode`, `authListOrgs` | core/AppModel.kt:329-360; MainActivity.kt:91-100 | Auth Tab (custom scheme), with a Custom Tab fallback. Org picker: `ModalBottomSheet` / `AlertDialog` list. |
| L3 | App/SceneDelegate.swift:183-189 | `zeron://voice` (from the call's Live Activity) returns to the voice stage while a call is live. | ❌ (no voice in Roboco) | none | MainActivity.kt:92-95 (`VoiceController.opensStage`) | Call notification `contentIntent` |
| L4 | Cloud/CloudStore.swift:365; Cloud/AgentAccountsViewController.swift:503, 557 | `zeron://signed-in`: the done page of provider and device logins; it closes the auth sheet. | ❌ | `providerLoginStart(…, doneUrl)`, `deviceLoginStart(…, doneUrl)` | core/Cloud.kt:380, 393 | Auth Tab result, or an intent filter |
| L5 | App/SceneDelegate.swift:70-82, 106-152 | `-route` launch argument: `chat:<id>`, `new`, `more`, `search`, plus the DEBUG Cloud routes `cloud`, `cloud:billing`, `cloud:agent:<h>`, `cloud:repos`, `cloud:github`, `billing`, `providers`, `providers:cloud`, `providers:<id>`, `accounts:<h>`, `welcome`. | ⚠️ `shell/DeepLinks.kt:138-152` `parseRoute` (chat/new/more/search only) | none | ui/ZeronRoot.kt:157-161; MainActivity.kt:26-47 | Intent extras (`--es route …`) |
| L6 | none | `roboco://chat/<id>`, `roboco://new?prompt=`, `roboco://settings`, `roboco://search`, `roboco://pair#url=`. | ⚠️ Roboco-only: `shell/DeepLinks.kt`, `pairing/*`, `notify/Notifier.kt:342`. See §4. | none | none | none |

### M. Delivery reliability

| # | iOS (file:line) | Behaviour | Roboco status + Kotlin file | zeron core API | za: reference | Native-Android component option |
|---|---|---|---|---|---|---|
| M1 | App/AppModel.swift:278-311, 250-256 | On backgrounding: if something was sent in the last 120 s, or any row is `sending` / `queued`, hold the process for 25 s (`beginBackgroundTask`). Ended on foreground or sign-out. `noteDeliveryActivity()` runs on each send. | ❌ Roboco only holds the process while a run is Working **and** notifications are on (`background/RunWatchController.kt:261-272`) | `SessionRow.sendState`, `onBackground()` | core/DeliveryService.kt (FGS `shortService`, falling back to `dataSync`, `onTimeout`); core/AppModel.kt:462-482 | FGS `shortService` (API 34+) |
| M2 | App/AppModel.swift:293-316 | `client.onBackground()` / `onForeground()` plus a workspace refresh; network path hooks. | ✅ `core/AppLifecycleHooks.kt` (`ProcessLifecycleOwner`, network callbacks). zeron has the same calls. | `onForeground`, `onBackground`, `setNetworkOnline`, `networkPathChanged` | ZeronApplication.kt:22-23 | none |

**Totals: 126 rows. ✅ 15, ⚠️ 36, ❌ 75.** Most of the ⚠️ rows are the device-side accounts work Roboco already has, built on Roboco-core APIs and a different page structure.

### Widget recommendations (Roboco hand-clones of iOS widgets, all used on these pages)

| Roboco clone | iOS original | Recommendation |
|---|---|---|
| `design/GroupedList.kt` (`groupedSection`, `GroupedRow`) | inset-grouped `UICollectionView` list | M3 `ListItem` inside a `Surface(shape = MaterialTheme.shapes.large)`, with section headers as `Text(labelLarge)`. Keep the Palette colours. |
| `design/RobocoSwitch.kt` | `UISwitch` | M3 `Switch` with `SwitchDefaults.colors(checkedTrackColor = accent)` |
| `design/ActionSheet.kt`, `ActionSheetLayout.kt` | `UIAlertController(.actionSheet)` | `ModalBottomSheet` with a `ListItem` column; the destructive item uses `colorScheme.error` |
| `design/Alert.kt`, `AlertCard.kt` (incl. text field) | `UIAlertController(.alert)` | `AlertDialog` / `BasicAlertDialog` with `OutlinedTextField` |
| `design/glass/Menu*`, `ContextMenu.kt` | `UIMenu` / context menu | `DropdownMenu`, or `ExposedDropdownMenuBox` for the device switcher |
| `design/Toast.kt` | `Toast` (iOS custom) | M3 `Snackbar` (with action) |
| `design/PullToRefresh.kt` | `UIRefreshControl` | M3 `PullToRefreshBox` |
| In-app browser: Custom Tabs (already native) | `SFSafariViewController` / `ASWebAuthenticationSession` | Custom Tabs for Stripe / GitHub pages; **Auth Tab** for the `zeron://` callback flows |

The only places where a custom widget remains justified: the billing split bar (F4), the pack stepper pill (F7), and the onboarding progress bars (J3).

---

## 2. Notifications deep-dive

### 2.1 What exists today, end to end (read from source)

1. **iOS** (`App/PushNotifications.swift:114-137`): after the APNs token arrives, `sync()` calls `CoreClient.registerPushTarget(token: <hex>, environment: "production"|"sandbox", prefs: PushPrefs)`. If notifications were turned off, it calls `unregisterPushTarget()` instead.
2. **FFI** (`crates/mobile/src/client_ffi/mod.rs:408-433`; `types.rs:1099-1103`) is a thin pass-through to the client.
3. **Client** (`crates/client/src/client.rs:1391-1453`; `live/urls.rs:63-71`): `POST {edge}/registry/{orgId}/push-target?device={deviceId}` with a bearer token and body `{"token", "environment", "prefs":{"done","input","failed"}}`. DELETE on the same URL to unregister. A demo backend no-ops. **The token and environment strings are passed through verbatim.**
4. **Edge router** (`edge/src/index.ts:314-318`): forwards to the `RegistryRoom` Durable Object for that org/user. `registry-room.ts:270-273` refuses runner tokens on this route.
5. **RegistryRoom** (`registry-room.ts:314-344`): validates `TOKEN_RE = /^[0-9a-fA-F]{32,512}$/` (l.100) and `environment ∈ {production, sandbox}`, otherwise returns 400. It upserts `push_targets(device PK, token, environment, prefs, updated_at)` (table at l.122). DELETE removes the device's row.
6. **Trigger** (`registry-room.ts:548-627` → `notifySessions` at 641-690): runs after **every committed registry push batch**, whether over WebSocket or `POST /push`, from any engine (desktop, server, Cloud runner). For each changed `sessions` row:
   - It computes `notificationFor(before, after, now)` (`push-notify.ts:45-56`).
   - It filters by the chat row (`chatForNotification`, l.63-69: side chats and archived chats are dropped; the title falls back to "New session").
   - For every target whose preference for that category is on, it calls `sendApns`.
   - Delivery is best effort with no retry. The last 30 results are kept in `meta.pushLog`. Dead tokens (410, `BadDeviceToken`, `Unregistered`, `DeviceTokenNotForTopic`) are deleted.
7. **APNs sender** (`edge/src/apns.ts:52-83`, config in `env.ts:112-120`): HTTP/2 to `api.push.apple.com` or the sandbox host. ES256 provider JWT from `APNS_KEY_P8` / `APNS_KEY_ID` / `APNS_TEAM_ID`. Topic `APNS_TOPIC`, default `sh.zeron.ios`. Headers: `apns-push-type: alert`, priority 10, 24 h expiration, `apns-collapse-id = chatId`. Payload: `aps.alert{title, body}`, `sound: default`, `thread-id: chatId`, plus `chatId` and `category`.

**Conclusion: only APNs is supported.** There is no FCM, Web Push, UnifiedPush or ntfy code anywhere in `edge/src`, `crates/client`, or `apps/android` (searched by grep). An FCM token contains `:`, `-` and `_`, so it fails `TOKEN_RE` and gets a 400. A hex-encoded token would pass, be sent to Apple, and be deleted as `BadDeviceToken`. **Android must not call `registerPushTarget` against the production edge today.** Zeron's own Android app doesn't. It has **no session notifications at all**: it only requests `POST_NOTIFICATIONS` during onboarding (`za: ui/OnboardingScreen.kt:168, 229`), and its notification channels exist only for `DeliveryService` and the voice call.

### 2.2 The rule (identical on the edge, the desktop engine, and Roboco)

The edge rule is in `push-notify.ts:22-56`. The desktop engine's version is `crates/engine/src/desktop_notify.rs:56-93`, built on `crates/proto/src/view.rs:41-92`.

- **Indicator** of a `sessions` row: `errored` gives Errored. `working` / `awaitingInput` give Working / AwaitingInput **only if `updatedAt` is no more than 45 s old** (`SESSION_STALE_MS`); otherwise None. Anything else is None.
- **failed**: the indicator becomes Errored when it wasn't before.
- **input**: the indicator becomes AwaitingInput when it wasn't before (a question or permission prompt).
- **done**: `lastCompletedTurn` changed **and** the row is fresh (no more than 45 s). The desktop additionally suppresses this while a send is pending.
- A row seen for the first time (or revived) only seeds the baseline, so replays never ring. Working → Idle without a completion (interrupt or expiry) is silent.
- Never notify for side chats (`parentChatId`) or archived chats. Body copy: "Run finished", "Waiting on your input", "Run failed". Per-device preferences, all on by default.
- The iOS client additionally hides banners while the app is in front.

### 2.3 What the zeron core gives Android without a patch

- `ClientListener.onEvent(ClientEvent.WorkspaceChanged{revision})`, coalesced to at most one burst per frame. Then `client.workspace()` returns a `WorkspaceSnapshot` (`types.rs:699-714`).
- `SessionRow` (`types.rs:466-512`) has the fields needed:
  - `id`, `title` (already falls back to "New session"), `archived`, `parentChatId`, `unseen`.
  - `hostIndicator`: host-reported and staleness-gated, with no local-send override. Use this one, **not** `indicator`, which reads Working during an in-flight send.
  - `workingSinceMs`: the run start of the live turn.
  - `lastActivityMs`, `sendState`, `isCloud`, `deviceId`.
- `ChatIndicator` (`crates/proto/src/entities.rs:306-348`): Working / AwaitingInput / Errored (live errored **and unseen**) / **Completed** (unseen and not interrupted) / Idle.
- **Not exposed:** `lastCompletedTurn`. There is also no `NotificationSink` and no `RunActivity`. Roboco's `notify/NotificationCenter.kt` implements `uniffi.roboco_core.NotificationSink`, which does not exist in zeron.
- **Background behaviour** (`crates/client/src/client.rs:1681-1713, 662-674`): `onBackground()` flushes, stops PR watches and pauses the 1 s tick (staleness re-evaluation). The **registry socket stays up**, so `WorkspaceChanged` keeps firing on every registry frame for as long as the process is not frozen.

### 2.4 Recommended Android design, no backend change and no core patch

**SessionWatcher.** Kotlin, a process singleton. It replaces the rule that sat in the Roboco core.
- Subscribe to the same `WorkspaceChanged` fan-out `AppModel` already uses. On each revision, gather the active rows (front, projects and projectless, de-duplicated by id). Skip `archived` and `parentChatId != null`.
- Keep `baseline[chatId] = (hostIndicator, workingSinceMs, title)`. A chat seen for the first time only seeds the baseline. This matches the edge's "revived row only sets the baseline", and means a process restart never replays.
- Transitions:
  - **failed**: `host == ERRORED && prev != ERRORED`.
  - **input**: `host == AWAITING_INPUT && prev != AWAITING_INPUT`.
  - **done**, either of:
    - `prev ∈ {WORKING, AWAITING_INPUT} && host == COMPLETED`, or
    - `prev == WORKING && host == WORKING && workingSinceMs changed`. This is a turn boundary that coalesced away the Completed frame (a queued message started the next turn).
  - Working → Idle stays silent. That covers an interrupt, a stale row, or a turn already seen on another device. The last case is a deliberate small divergence from the edge, which would still ring.
  - Optional exact parity: expose `last_completed_turn` on `SessionRow`. That is a two-line core addition, and this design does not need it.
- **Gates.** Reuse `notify/NotificationPolicy.kt` unchanged: not in the foreground (K8), master preference plus permission, and the kind preference.
- **Delivery.** Through `notify/Notifier.kt`: tag `chat:<chatId>`, the existing channels and chimes, `setWhen(lastActivityMs)`. The tap is an explicit Intent with a `chatId` extra, routed through `DeepLinkHub`'s pending slot (K9). Cancel a chat's notification when the chat is opened (`Notifier.cancelChatFor`) and on sign-out.

**RunWatchController inputs.** Derive them from the same snapshot instead of the core's `RunActivity`:
- `working` = rows whose `hostIndicator == WORKING` (title, with `workingSinceMs` as the chronometer base).
- `awaiting` = the count of `AWAITING_INPUT` rows.
- `fromThisDevice` = chats this phone sent into in the last N minutes. Record these in `AppModel.noteDeliveryActivity(chatId)` or read them from `sendState`. Used for the API 36 Live Update promotion.
- `engineCount` becomes "signed in" (1 or 0).

The controller's rules stay as they are:
- Start only while in the foreground, when there is Working and notifications are on, or when Stay connected is on.
- Keep running while anything is Working; 2 min linger after it stops.
- `dataSync`; `specialUse` for Stay connected; latch on `onTimeout`.

**Delivery hold.** Keep za:'s `DeliveryService` (25 s `shortService` after a send, M1). It runs alongside: `shortService` does not draw on the `dataSync` budget, and RunWatch takes over once the run is Working.

**Battery, Doze and platform limits**
- While a foreground service runs, the app's process state is exempt from Doze and App Standby network blocking, so the registry WebSocket keeps receiving. No wake lock is needed: incoming frames wake the CPU. The edge uses hibernatable WebSockets, so an idle socket is cheap on both ends. The core's background tick pause (`client.rs:671`) means only registry frames cause work.
- Run-scoped plus a 2 min linger is the low-cost default. Stay connected is an explicit opt-in with continuous cost: one TLS socket plus pings.
- **Gap that cannot be closed without push:** Android 12+ forbids starting a foreground service from the background, and Android 14+ freezes cached apps about 10 s after they leave. A run started from the desktop while the phone sits in a pocket is **not** seen until the app is opened, unless Stay connected is on. iOS gets these through APNs.
- API 35+ caps `dataSync` at 6 h per 24 h. `RunWatchService.onTimeout` already handles this.
- `specialUse` needs a Play Console justification if Zeron ships on Play.
- OEM task killers (Xiaomi, Samsung "sleeping apps") can still kill the process. Link dontkillmyapp.com from the Stay connected footer.
- Optional catch-up (P2): a `WorkManager` periodic worker (at least 15 min, deferred to Doze maintenance windows). If no client is alive, it opens a client from `CredentialStore`, waits for `synced`, diffs against **persisted** baselines, posts late notifications (with `setWhen` set to the real time), and shuts down. It needs a JSON baseline in SharedPreferences and a headless client boot. Medium effort, and the notifications arrive late.

### 2.5 If the backend may change

Both options below need **no core patch**. `register_push_target` forwards `token` and `environment` verbatim (`client.rs:1404-1408`). Android would call `registerPushTarget(token = <FCM token | UP endpoint>, environment = "fcm" | "unifiedpush", prefs)` and `unregisterPushTarget()` on sign-out or when turned off. The production edge (`edge.zeron.sh`) is upstream's, so either option needs an upstream PR or a self-hosted edge.

**A. FCM (HTTP v1)**

Edge files:
- `edge/src/registry-room.ts`: accept `environment: "fcm"`, a per-environment token check (FCM token: about 140-250 characters of `[A-Za-z0-9_:-]`), dispatch `sendApns` vs `sendFcm` in `notifySessions`, and delete dead tokens on 404 `UNREGISTERED` or 400 `INVALID_ARGUMENT`.
- New `edge/src/fcm.ts`: a service-account RS256 JWT (`jose` is already a dependency), exchanged at `oauth2.googleapis.com/token` (cache about 55 min). Then `POST https://fcm.googleapis.com/v1/projects/{id}/messages:send` with `{token, data:{chatId, category, title, body}, android:{priority:"HIGH", ttl:"86400s", collapse_key:chatId}}`. Use data-only messages so the app applies foreground suppression, preferences and its channels.
- `edge/src/env.ts`: `FCM_SERVICE_ACCOUNT` (secret) and `FCM_PROJECT_ID`.
- `push-notify.ts`: `fcmPayload()`.
- Tests: `fcm.test.ts`, `push-notify.test.ts`.

Android: a Firebase project plus `google-services.json` plus `firebase-messaging`. A `FirebaseMessagingService` re-registers in `onNewToken` and calls the same `Notifier` / `NotificationPolicy` in `onMessageReceived`. A high-priority FCM message also allows starting the RunWatch FGS from the background, which closes the "desktop-started run" gap.

Costs: requires Google Play services (no de-Googled or Huawei devices). Chat titles transit Google, just as they transit Apple today.

**Effort:** edge about 1-1.5 days with tests and secrets; Android about 1 day; plus upstream review.

**B. UnifiedPush (ntfy, NextPush, or the embedded FCM distributor)**

The "token" is the distributor's https endpoint plus the Web Push keys (`p256dh`, `auth`). To avoid a core patch, pack them into `token` (for example JSON, or `endpoint#p256dh#auth`).

Edge files:
- `registry-room.ts`: `environment: "unifiedpush"`, URL validation (https only, a length cap, an SSRF guard against private hosts), dispatch, and dead-endpoint deletion on 404 or 410.
- New `edge/src/webpush.ts`: RFC 8291 `aes128gcm` encryption (ECDH P-256 + HKDF + AES-GCM in WebCrypto) and RFC 8292 VAPID (ES256 through `jose`). Headers `TTL: 86400`, `Urgency: high`, `Topic: <chatId>` (collapse). About 150-200 lines.
- `env.ts`: `VAPID_PUBLIC` and `VAPID_PRIVATE`.

Android: `org.unifiedpush.android:connector`. A `MessagingReceiver` registers in `onNewEndpoint`, posts through `Notifier` in `onMessage`, and unregisters in `onUnregistered`. The user needs a distributor app installed (for example ntfy), or the app uses the embedded FCM distributor.

Plaintext ntfy without UnifiedPush or encryption is about 30 lines of edge code, but it leaks chat titles to a third party. Not recommended.

**Effort:** edge about 2-3 days; Android about 1-1.5 days. Works without Google; the battery cost moves to the distributor.

**Recommendation:** ship v1 with the §2.4 local watcher, which needs no backend change. Separately, offer upstream one PR adding an `environment`-dispatched FCM sender, with UnifiedPush as a follow-up. Both are invisible to the core and to iOS.

---

## 3. Mapping Roboco `accounts/` onto the zeron APIs

### 3.1 Calls

| Roboco seam (Kotlin) | Roboco core call | zeron core call | Notes |
|---|---|---|---|
| `AccountsCore.list(deviceId, forceUsage)` (`accounts/AccountsCore.kt:23, 55-56`) | `listAgentAccounts(deviceId, forceUsage): AgentAccountsSnapshot` | `deviceAgentAccounts(deviceId): List<DeviceAccountInfo>` | No `forceUsage` (usage is whatever the host last read) and no snapshot wrapper. The cache-plain-forced chain in `AgentAccountsModel.loadOnce` (l.303-333) becomes one read. |
| `AccountsCore.activate` (l.29, 58-63) | `activateAgentAccount` | `deviceActivateAccount(deviceId, harness, accountId): List<DeviceAccountInfo>` | Same semantics. |
| `AccountsCore.forget` (l.35, 65-70) | `forgetAgentAccount` | `deviceForgetAccount(deviceId, harness, accountId): List<DeviceAccountInfo>` | Same semantics. |
| `AccountsCore.cached` (l.43, 72-73) | `cachedAgentAccounts` | none | Keep a Kotlin map `accounts[deviceId]`, as iOS Providers does (`ProvidersViewController.swift:17, 224`), and pass it into the agent page (`:425`). |
| `AccountSignIn.harnesses` (`accounts/AccountSignIn.kt:26, 51-52`) | `listHarnesses` | `listHarnesses(deviceId): List<HarnessInfo>` | Same (suspend, never throws). |
| `AccountSignIn.setEnabled` (l.29, 54-55) | `setHarnessEnabled` | `setHarnessEnabled(deviceId, harness, enabled)` | Same. |
| `AccountSignIn.start` (l.32, 57-58) | `deviceLoginStart(deviceId, harness, provider)` | `deviceLoginStart(deviceId, harness, provider, doneUrl = "zeron://signed-in")` | New `doneUrl`. Drop the Roboco loopback/`callbackPort` assumption (`AgentAccountsModel.kt:583-586`). |
| `AccountSignIn.poll` (l.35, 60-61) | `deviceLoginPoll(deviceId, loginId)` | `deviceLoginPoll(deviceId, loginId, doneUrl)` | New `doneUrl`. Statuses `pending` / `done` / `error` are the same. |
| `AccountSignIn.complete` (l.38, 63-64) | returns `AgentAccountsSnapshot` | `deviceLoginComplete(...)` returns `List<DeviceAccountInfo>` | none |
| `AccountSignIn.cancel` / `rules` (l.41-44, 66-70) | `deviceLoginCancel`, `agentLoginRules` | same names | `AgentLoginRules{name, signsIn, switches, keepsOneLogin, reportsUsage, note, options[LoginOptionInfo{provider,label,firstTitle,moreTitle}]}` is the same shape. |
| none (new for Cloud) | none | `vaultAccounts(provider)`, `vaultUsage(provider, slot)`, `vaultActivate`, `vaultForget`, `vaultPutApiKey`, `vaultStatus`, `providerLoginStart/Finish/Cancel` | Through a new `CloudStore` (copy `za: core/Cloud.kt`). |

### 3.2 Records

| Roboco `AgentAccount` | zeron `DeviceAccountInfo` (`providers.rs:16-36`) |
|---|---|
| `id`, `harness`, `email`, `displayName`, `planLabel`, `active`, `switchable`, `apiKey`, `organization`, `provider` | same names |
| `usageWindows: List<AgentUsageWindow{label, usedFraction, resetsAtMs}>` | `usage: List<UsageWindowInfo{label, usedFraction: Float, resetsAtMs: Long?}>` |
| `usageError` | `usageError` |
| `usageFetchedAtMs` | **missing**: drop `updatedText()` ("Usage updated …", `AccountsFormat.kt:197-210`) |
| `AgentAccountsSnapshot.accounts` / `.warnings` | plain list / **no warnings**: drop the warning rows in `providerGroups` |

| zeron Cloud record | Fields |
|---|---|
| `CloudAccountInfo` | `slot`, `active`, `kind` (`oauth` / `key`), `email`, `displayName`, `organization`, `plan`, `needsReconnect` |
| `CloudUsageInfo` | `windows`, `plan` |

Errors: the zeron Kotlin `CoreException` keeps the variant names `HostUnavailable`, `Unsupported` and `HostException` (field `reason`), so `describeAccountsError`, `agentToggleFailure` and `signInFailure` port with copy changes only ("Roboco" becomes "Zeron"). The terminal "Unsupported engine" state (`AccountsLoad.Unsupported`) has no iOS counterpart; keep it as a plain failure.

Device ids: Roboco `DeviceView.engineId` / scoped ids become zeron `DeviceView.id` (`types.rs:666-678`). Pick computers from `executionDevices()` (where `isExecutionHost` is true), as iOS `hostOptions` does.

### 3.3 Call sites to rewrite

- `accounts/AccountsCore.kt`, `accounts/AccountSignIn.kt`: re-point `CoreAccountsCore` / `CoreAccountSignIn` at `uniffi.zeron_core` (or delete the seams and call through the new `ProvidersModel`).
- `accounts/AgentAccountsModel.kt`: single-read load; `runSignIn` passes `doneUrl` and closes the Auth Tab on `zeron://signed-in`; switching becomes a busy spinner, then the reply, then a selection haptic (iOS is **not** optimistic and shows no toast). Add the Cloud target (A12, H17).
- `accounts/AgentAccountsScreen.kt`: split into a **ProvidersScreen** (device switcher including Cloud, Agents switches, per-agent summary rows; G1-G13) and a **per-agent AgentAccountsScreen** (H1-H21).
- `accounts/AgentAccountsRoute.kt` / `errorCardLogInRoute`: add a Cloud target when `row.isCloud` (B8); present as a sheet.
- `accounts/AgentAccountsSection.kt` plus `settings/SettingsScreen.kt:185-186, 218`: replace with the "Agents" rows (G1).
- `accounts/SessionUsageChip.kt`, `session/SessionMenu.kt:262-272`: Roboco-only (I1, I2). Remove, or reimplement on `deviceAgentAccounts`.
- `accounts/AccountsFormat.kt`: account-title fallback "API key" / "Account"; meter danger above 90 %; iOS reset span ("3h").

---

## 4. Deep links

| Link | Producer, then consumer | Android handling |
|---|---|---|
| `zeron://callback?code=…&state=…` (or `?error=…`) | WorkOS AuthKit redirect, then sign-in (`SignInViewController.swift:33-43, 129-147`; core `auth.rs:33-34, 77-84`, `mod.rs:575-591`) | `MainActivity` (`singleTask`) filter `scheme=zeron host=callback`. `parseAuthCallback`, check `state`, `authExchangeCode`, `authListOrgs`, org picker. Prefer an Auth Tab and fall back to a Custom Tab. The redirect URI is already registered in WorkOS for iOS, so no backend change. |
| `zeron://voice` | The call's Live Activity or notification returns to the voice stage while a call is live (`SceneDelegate.swift:183-189`) | The call notification's `contentIntent`, plus host `voice` on the filter (`za: MainActivity.kt:92-95`). |
| `zeron://signed-in` | The done page of provider (Cloud vault) and device logins (`CloudStore.swift:365`; `AgentAccountsViewController.swift:503, 557`) | Auth Tab result, or the `host=signed-in` filter. With `singleTask`, the tab above the root is cleared and `onNewIntent` completes the waiting flow (`za: core/Cloud.kt:329-382`). |
| `zeron://cloud` | Declared in za:'s manifest; **unused**. Stripe returns to the https page `{edge}/billing/done` (`edge/src/cloud/routes.ts:295`). | Optional reserve. Billing re-reads on resume (F10). |
| Notification tap | iOS uses `userInfo.chatId`, not a URL. | Explicit Intent extra `chatId` (not exported), into `DeepLinkHub`. |
| `-route` launch argument | Debug and screenshot routing (`SceneDelegate.swift:70-82, 106-152`) | Intent extra `route`. Extend `shell/DeepLinks.kt:parseRoute` with the Cloud, providers and accounts routes (`za: ui/ZeronRoot.kt:157-161`). |

**What Roboco's `roboco://` links become** (`AndroidManifest.xml` filter, `shell/DeepLinks.kt`):

| Roboco link | Becomes |
|---|---|
| `roboco://chat/<id>` (notification taps, `notify/Notifier.kt:342`) | An explicit intent extra. If an external link is wanted, add `zeron://chat/<id>` as an Android-only host. Delete `ScopedIds` / `ChatIds`: zeron chat ids are plain. |
| `roboco://new?prompt=`, `roboco://settings`, `roboco://search` | Not in iOS. Either drop them, or keep them as Android-only `zeron://new`, `zeron://settings` and `zeron://search` hosts for launcher shortcuts (`shell/ShellShortcuts.kt`). |
| `roboco://pair#url=` | Drop, together with `pairing/*` (`PairShareActivity`, `QrScanActivity`, the share target and the `CAMERA` permission used only for the QR scanner). Zeron devices join through the WorkOS account, not pairing. |

A custom-scheme collision is possible with upstream's own `sh.zeron.android` app, which uses the same scheme and **the same applicationId**, so the two cannot be installed side by side. Debug variants with an `applicationIdSuffix` would show an app chooser for `zeron://` links; avoid installing both during development.

---

## 5. Ordered task list

1. **Core wiring.** Point the Gradle core build at `zeron/crates/mobile` (`app/build.gradle.kts:71-170` expects Roboco's crates). Set applicationId to `sh.zeron.android` and switch the bindings to `uniffi.zeron_core`.
2. **Manifest and deep links (L1-L4).** Add the `zeron` filter (callback, voice, signed-in); remove `roboco://` and pairing; route notification taps by explicit extra (K9). Add WorkOS sign-in through `zeron://callback` (L2). This is the prerequisite for everything else.
3. **CloudStore (A1-A15).** Port `za: core/Cloud.kt` nearly verbatim: `BrowserReturns`, `signInToCloud`, with an Auth Tab where available.
4. **Settings "Agents" section (G1).** Providers, Cloud and Billing rows replace Roboco's Agent Accounts summary. Remove I1 and I2.
5. **Providers plus per-agent AgentAccounts (G2-G13, H1-H21).** Rework `accounts/` onto the zeron APIs (§3): device and Cloud targets, the `doneUrl` sign-in, API key entry, swipe-to-remove, the Log-in sheet (B8).
6. **Cloud pages.** Cloud settings (C1-C7), GitHub connect (D1-D5), repo picker (E1-E5), Billing (F1-F11). Use native M3 components as listed.
7. **Cloud inside sessions and new session.** Out-of-credits snackbar and notice, nudge, machine-error pill, `createCloudSession` with "Starting on Cloud…", "New Cloud Project…", Cloud project filter (B1-B11).
8. **Notifications on zeron (§2.4).** `SessionWatcher` with the edge rule, using `hostIndicator` and `workingSinceMs`. Feed `RunWatchController` from the snapshot; keep the channels, policy, settings and Stay connected; decide on link-lost (K16); sign-out clearing (K6).
9. **Delivery hold (M1).** Port `za: core/DeliveryService.kt` (`shortService`, 25 s) next to RunWatch.
10. **Onboarding (J1-J11)** after sign-in, including the Notifications step that drives `NotificationPermission`.
11. **Swap hand-cloned widgets for M3 on these pages:** list, switch, action sheet, alert, menu, toast, pull-to-refresh (widget table above).
12. **Optional P2.** WorkManager catch-up for background-started runs. Propose upstream an FCM sender and a UnifiedPush sender (§2.5); neither needs a core patch.
