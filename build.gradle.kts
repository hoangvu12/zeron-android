// :app only; buildSrc carries the prebuilt-core import, the asset tasks and
// the version derivation. See README.md.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
}
