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
zeron/        git submodule: zeronsh/zeron, pinned to a release tag
app/          the Android app
buildSrc/     Gradle helpers (prebuilt-core import, assets, fonts, chimes, licences)
tools/        android-assets: SVG → VectorDrawable / PNG (vendored from Roboco)
docs/port/    the iOS → Android gap audits that drive the port
.github/      CI: builds the core and the APK (nothing builds locally)
```

## Building

Builds run on GitHub Actions; no Android SDK is needed locally.

- `Core` workflow: builds `zeron-mobile` for `arm64-v8a` with cargo-ndk, a Linux
  host build for JVM tests, and the Kotlin bindings into `core-out/`. Cached
  per `zeron/` commit, so it only rebuilds when the submodule moves; it runs
  on its own only when `zeron/` changes.
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
engines speak the same protocol.

## License

MIT, like both upstreams (see `LICENSE`).
