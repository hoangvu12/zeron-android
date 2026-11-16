# zeron-android

An Android client for [Zeron](https://github.com/zeronsh/zeron): a 1:1 port of
Zeron's iPhone app, on Zeron's own Rust mobile core.

- **UI**: Kotlin + Jetpack Compose with custom Canvas painting, carried over from
  the Roboco Android app (`hoangvu12/roboco` `apps/android` at `28f5f53f1`),
  which was itself a 1:1 port of Zeron iOS as of upstream `edb23db4`.
- **Core**: `zeron/crates/mobile` (UniFFI, `uniffi.zeron_core`) — the same
  library Zeron's iOS and Android apps link. Rust lays out the transcript;
  Kotlin paints it at Rust coordinates with the same Geist font bytes.
- **Look and behaviour**: current `zeron/apps/ios`. `zeron/apps/android` is a
  reference for how the core is wired on Android, not for its look.
- **Application id**: `sh.zeron.android` — it replaces the official Zeron
  Android app on a device (uninstall that one first: different signing keys).

## Layout

```
zeron/        git submodule: zeronsh/zeron, pinned to a release tag (v0.3.7)
core-patches/ patches applied to zeron/ before the core is built (see below)
app/          the Android app
buildSrc/     Gradle helpers (prebuilt-core import, assets, fonts, chimes, licences)
tools/        android-assets: SVG → VectorDrawable / PNG (vendored from Roboco)
docs/port/    the iOS → Android gap audits that drive the port
.github/      CI: builds the core and the APK (nothing builds locally)
```

## Building

Builds run on GitHub Actions; no Android SDK is needed locally.

- `Core` workflow: applies `core-patches/` to `zeron/`, then builds
  `zeron-mobile` for `arm64-v8a` with cargo-ndk, a Linux host build for JVM
  tests, and the Kotlin bindings into `core-out/`. Cached per `zeron/` commit
  and the patches' contents, so it only rebuilds when either changes; it runs
  on its own only when `zeron/` or `core-patches/` changes.
- `App` workflow: takes `core-out/` from the Core workflow, builds the debug
  APK with `-PzeronCoreDir=core-out` (or `ZERON_CORE_DIR`) and uploads it as
  an artifact, signed with the shared test key so builds install over each
  other.

## Updating Zeron

```sh
git -C zeron fetch --tags && git -C zeron checkout vX.Y.Z
git add zeron && git commit -m "Zeron vX.Y.Z"
```

Keep the pin at the version your desktop Zeron runs so the phone and the
engines speak the same protocol. After a bump, check the core patches still
apply: `bash scripts/core-patches.sh --check`.

To build a core yourself (NDK, cargo-ndk and the `aarch64-linux-android`
target), `bash scripts/build-core.sh core-out` does what the Core workflow
does; pass `-PzeronCoreDir=core-out` to Gradle.

## Core patches

The submodule stays on an upstream tag. `core-patches/*.patch` are applied to
its working tree, in name order, before every core build
(`scripts/core-patches.sh`, run by `core.yml` and `scripts/build-core.sh`), and
their contents are part of the core cache key. `git -C zeron status` shows them
as local changes; `bash scripts/core-patches.sh --revert` takes them back out.

- `0001-nightly-agent-messages.patch`: `crates/proto/src/agent_messages.rs`
  as the Zeron v3 nightly has it. The nightly's engines write child-chat
  reports (`[Report from Zeron chat …]`), parent→child messages and Zeron
  notes into chats; upstream's parser shows those as raw bracketed headers,
  the nightly's as "from <chat>" over the body. It is the only part of the
  nightly the phone core needs (pure string parsing: nothing on the wire
  changes), so it rides on the tag instead of pinning the nightly tree. The
  patch only adds the nightly's lines to the tag's own file, keeping what the
  tag has there (e.g. `attributed`).

  To refresh it from `hoangvu12/zeron-nightly` (its `agent_messages.rs`
  changes are in `patches/0007-child-chats-report-back.patch` and
  `patches/0009-child-reports-follow-ups.patch`; add any later patch that
  touches the file, in the nightly's order):

  ```sh
  bash scripts/core-patches.sh --revert            # zeron/ back to the tag
  for p in 0007-child-chats-report-back 0009-child-reports-follow-ups; do
    git -C ../zeron-nightly show origin/main:patches/$p.patch \
      | git -C zeron apply --include=crates/proto/src/agent_messages.rs
  done
  git -C zeron diff > /tmp/nightly.diff
  # keep the header paragraph at the top of the patch file, then the new diff
  sed -n '/^diff --git/q;p' core-patches/0001-nightly-agent-messages.patch > /tmp/header
  cat /tmp/header /tmp/nightly.diff > core-patches/0001-nightly-agent-messages.patch
  (cd zeron && cargo test -p zeron-proto agent_messages && cargo check -p zeron-mobile)
  ```

  If a hunk no longer applies after a pin bump, apply the nightly patches to
  the old tag, then rebase that change onto the new one in `zeron/` by hand.

## License

MIT, like both upstreams (see `LICENSE`).
