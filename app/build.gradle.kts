import zeron.gradle.AndroidAssetsGen
import zeron.gradle.LicensesSync
import zeron.gradle.RepoPaths
import zeron.gradle.ZeronCoreImport
import zeron.gradle.ZeronFonts
import zeron.gradle.ZeronVersion
import java.io.File
import java.time.Duration
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // AGP 9 carries built-in Kotlin: org.jetbrains.kotlin.android is NOT applied.
    alias(libs.plugins.compose.compiler)
    // AboutLibraries (ui-parity#101): the JSON emitter only — no
    // aboutlibraries-compose/-core library artifacts (ticket rule: the
    // plugin's UI is Material, the app's is not).
    alias(libs.plugins.aboutlibraries.android)
}

// ---------------------------------------------------------------------------
// Repo geometry: this repository's root holds the app; Zeron's sources
// (shared assets, Cargo.toml, notices) are in the zeron/ submodule.
// ---------------------------------------------------------------------------
val repoRootDir: File = RepoPaths.root(project)
val zeronDir: File = RepoPaths.zeron(project)
val uiAssetsDir: File = RepoPaths.uiAssets(project)

// ---------------------------------------------------------------------------
// ABIs: -PzeronAbis / ZERON_ANDROID_ABIS, default arm64-v8a (the ABI the
// Core workflow builds). They only pick the APK splits below; the core's
// jniLibs decide which libraries exist.
// ---------------------------------------------------------------------------
val requestedAbis: List<String> = providers.gradleProperty("zeronAbis")
    .orElse(providers.environmentVariable("ZERON_ANDROID_ABIS"))
    .map { it.split(',', ' ', ';').filter(String::isNotBlank) }
    .orElse(listOf("arm64-v8a"))
    .get()

// ---------------------------------------------------------------------------
// Version derivation from Zeron's workspace Cargo.toml (android-app#13): the
// app's version is the core release it is built on.
// ---------------------------------------------------------------------------
val appVersionName: String = ZeronVersion.versionName(
    zeronDir.resolve("Cargo.toml"),
    providers.gradleProperty("zeronVersionName").orNull,
)
val appVersionCode: Int = ZeronVersion.versionCode(
    appVersionName,
    providers.gradleProperty("zeronVersionCode").map { it.toInt() }.orNull,
)

// ---------------------------------------------------------------------------
// The Rust core is never built by Gradle: the Core CI workflow runs
// zeron/scripts/android/build-core.sh into a `core-out` directory, and
// -PzeronCoreDir / ZERON_CORE_DIR points here at it. ZeronCoreImport stages
// its kotlin/ (uniffi.zeron_core), jniLibs/ (libzeron_mobile.so +
// libc++_shared.so per ABI) and host/ (JVM tests) and fails with a clear
// message when it is missing.
// ---------------------------------------------------------------------------
// Resolved eagerly (relative paths against the repo root) so no Project
// reference ends up in the configuration cache.
val zeronCoreDir: String? = providers.gradleProperty("zeronCoreDir")
    .orElse(providers.environmentVariable("ZERON_CORE_DIR"))
    .orNull
    ?.takeIf { it.isNotBlank() }
    ?.let { repoRootDir.resolve(it).absolutePath }
val coreOutDir = layout.buildDirectory.dir("generated/zeron-core")

val importCore = tasks.register<ZeronCoreImport>("importZeronCore") {
    group = "zeron"
    description = "Stages the prebuilt Zeron core (-PzeronCoreDir / ZERON_CORE_DIR)."
    coreDirPath.set(zeronCoreDir)
    zeronCoreDir?.let { coreFiles.from(fileTree(it)) }
    kotlinDir.set(coreOutDir.map { it.dir("kotlin") })
    jniLibsDir.set(coreOutDir.map { it.dir("jniLibs") })
    hostDir.set(coreOutDir.map { it.dir("host") })
}

// JVM tests load the host core through JNA.
val hostCoreDir: File = coreOutDir.get().asFile.resolve("host")

// ---------------------------------------------------------------------------
// Release signing (android-app#31): CI env vars first, then a local
// properties file. With neither, the release build stays **unsigned** —
// never debug-signed (upstream #609's review bug 10). Read through
// `providers` so the configuration cache tracks the inputs; nothing is
// consulted unless a source is present, and only the release signingConfig
// ever sees these values.
//
// Env: ZERON_ANDROID_KEYSTORE (path), ZERON_ANDROID_KEYSTORE_PASSWORD,
//      ZERON_ANDROID_KEY_ALIAS, ZERON_ANDROID_KEY_PASSWORD
// Local: -PzeronKeystoreProperties=<path> with storeFile/storePassword/
//        keyAlias/keyPassword (path resolution: relative to app/).
// ---------------------------------------------------------------------------
/** One resolved signing source; `null` means build unsigned. */
data class ReleaseKeystore(
    val storeFile: File,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
)

fun requiredKeystoreValue(name: String, source: String, value: String?): String =
    requireNotNull(value?.takeIf { it.isNotBlank() }) {
        "$name must be set ($source)"
    }

fun keystorePropertiesFile(path: String): ReleaseKeystore {
    val props = Properties().apply { file(path).inputStream().use { load(it) } }
    fun required(key: String): String =
        requiredKeystoreValue(key, "-PzeronKeystoreProperties ($path)", props.getProperty(key))
    return ReleaseKeystore(
        storeFile = file(required("storeFile")),
        storePassword = required("storePassword"),
        keyAlias = required("keyAlias"),
        keyPassword = required("keyPassword"),
    )
}

val releaseKeystore: ReleaseKeystore? =
    providers.environmentVariable("ZERON_ANDROID_KEYSTORE").orNull
        ?.takeIf { it.isNotBlank() }
        ?.let { path ->
            ReleaseKeystore(
                storeFile = file(path),
                storePassword = requiredKeystoreValue(
                    "ZERON_ANDROID_KEYSTORE_PASSWORD",
                    "ZERON_ANDROID_KEYSTORE env vars",
                    providers.environmentVariable("ZERON_ANDROID_KEYSTORE_PASSWORD").orNull,
                ),
                keyAlias = requiredKeystoreValue(
                    "ZERON_ANDROID_KEY_ALIAS",
                    "ZERON_ANDROID_KEYSTORE env vars",
                    providers.environmentVariable("ZERON_ANDROID_KEY_ALIAS").orNull,
                ),
                keyPassword = requiredKeystoreValue(
                    "ZERON_ANDROID_KEY_PASSWORD",
                    "ZERON_ANDROID_KEYSTORE env vars",
                    providers.environmentVariable("ZERON_ANDROID_KEY_PASSWORD").orNull,
                ),
            )
        }
        ?: providers.gradleProperty("zeronKeystoreProperties").orNull
            ?.let(::keystorePropertiesFile)

// ---------------------------------------------------------------------------
// Debug signing: CI signs every debug APK with one shared test key so
// consecutive builds install over each other on a phone. The workflow
// decodes the PKCS12 keystore from repo secrets and passes its path here.
// Without it (fork PRs, other machines) the default debug key is used.
//
// Env: ZERON_DEBUG_KEYSTORE (path), ZERON_DEBUG_KEYSTORE_PASSWORD,
//      ZERON_DEBUG_KEY_ALIAS (the key password is the store password:
//      a PKCS12 keystore has one password)
// ---------------------------------------------------------------------------
val debugKeystore: ReleaseKeystore? =
    providers.environmentVariable("ZERON_DEBUG_KEYSTORE").orNull
        ?.takeIf { it.isNotBlank() }
        ?.let { path ->
            val password = requiredKeystoreValue(
                "ZERON_DEBUG_KEYSTORE_PASSWORD",
                "ZERON_DEBUG_KEYSTORE env vars",
                providers.environmentVariable("ZERON_DEBUG_KEYSTORE_PASSWORD").orNull,
            )
            ReleaseKeystore(
                storeFile = file(path),
                storePassword = password,
                keyAlias = requiredKeystoreValue(
                    "ZERON_DEBUG_KEY_ALIAS",
                    "ZERON_DEBUG_KEYSTORE env vars",
                    providers.environmentVariable("ZERON_DEBUG_KEY_ALIAS").orNull,
                ),
                keyPassword = password,
            )
        }

// ---------------------------------------------------------------------------
// ABI-split release APKs (android-app#31): one APK per ABI, no universal APK
// (it would double the download for every phone). Intersected with
// -PzeronAbis, so a local -PzeronAbis=arm64-v8a build produces one split.
// Splits apply to every variant: debug is also split, and Gradle's install
// task picks the APK matching the connected device's ABI.
// ---------------------------------------------------------------------------
val splitAbis: List<String> = requestedAbis.filter { it in setOf("arm64-v8a", "x86_64") }

// ---------------------------------------------------------------------------
// The asset pipeline (android-app#13): zeron-android-assets (vendored under
// tools/android-assets) converts Zeron's shared line icons and this repo's
// icons/ to VectorDrawables and rasterizes tool/file icons.
// ---------------------------------------------------------------------------
val iconsResDir = layout.buildDirectory.dir("generated/zeron-icons/res")
val iconsAssetsDir = layout.buildDirectory.dir("generated/zeron-icons/assets")

val generateAssets = tasks.register<AndroidAssetsGen>("generateZeronAssets") {
    group = "zeron"
    description =
        "Converts the shared SVG icons to VectorDrawables and rasterized PNGs " +
            "(zeron-android-assets)."
    repoRoot.set(repoRootDir)
    manifest.set(RepoPaths.androidAssetsManifest(project))
    sources.from(
        fileTree(repoRootDir.resolve("tools/android-assets")) { exclude("target/**") },
        fileTree(uiAssetsDir.resolve("icons")),
        fileTree(uiAssetsDir.resolve("file-icons")),
        fileTree(repoRootDir.resolve("icons")),
    )
    resDir.set(iconsResDir)
    assetsDir.set(iconsAssetsDir)
}

// The exact font bytes the Rust layout engine measures (the 12 faces the
// iOS app registers with CoreText), plus the licenses the OFL requires.
val fontsDir = layout.buildDirectory.dir("generated/zeron-fonts")

val syncFonts = tasks.register<zeron.gradle.FontsSync>("syncZeronFonts") {
    group = "zeron"
    description = "Copies the 12 bundled Geist faces and their licenses into the APK assets."
    fontFiles.setFrom(
        ZeronFonts.FILES.map { uiAssetsDir.resolve("fonts").resolve(it) },
    )
    licenseFiles.setFrom(
        listOf(
            uiAssetsDir.resolve("fonts/licenses/Geist-OFL.txt"),
            uiAssetsDir.resolve("file-icons/LICENSE.symbols"),
        ),
    )
    assetsDir.set(fontsDir)
}

// Zeron's distribution licenses (android-app#31): a sideloaded APK is the
// whole distribution, so zeron/LICENSE and zeron/THIRD_PARTY_NOTICES.md ship
// inside it under assets/licenses/, beside the font licenses above. The same task
// stages the committed SPDX fallback texts (ui-parity#101) — licences the
// AboutLibraries plugin cannot fill offline — under assets/licenses/spdx/.
val licensesDir = layout.buildDirectory.dir("generated/zeron-licenses")

val syncLicenses = tasks.register<LicensesSync>("syncZeronLicenses") {
    group = "zeron"
    description = "Copies the repository license, notices and SPDX texts into the APK assets."
    noticeFiles.setFrom(
        zeronDir.resolve("LICENSE"),
        zeronDir.resolve("THIRD_PARTY_NOTICES.md"),
    )
    spdxFiles.setFrom(
        fileTree(repoRootDir.resolve("licenses/spdx")) { include("*.txt") },
    )
    assetsDir.set(licensesDir)
}

// ---------------------------------------------------------------------------
// The desktop's session chimes (android-app#28): byte-identical copies into
// res/raw — the channels reference them as their sound. The "middle set"
// name is the ticket's: `syncChimes`.
// ---------------------------------------------------------------------------
val chimesDir = layout.buildDirectory.dir("generated/zeron-chimes/res")

val syncChimes = tasks.register<zeron.gradle.ChimesSync>("syncChimes") {
    group = "zeron"
    description =
        "Copies the desktop's session chimes into res/raw (chime_done/request/attention.wav)."
    chimeFiles.setFrom(
        uiAssetsDir.resolve("sounds/done.wav"),
        uiAssetsDir.resolve("sounds/request.wav"),
        uiAssetsDir.resolve("sounds/attention.wav"),
    )
    outputDir.set(chimesDir)
}

androidComponents {
    // Tests that reference src/debug classes (the design gallery suite of
    // android-ui-parity #22, 05's HapticsGalleryScreenshotTest, 11's gallery
    // test) do not compile for testReleaseUnitTest: the release variant has
    // no debug source set. Disable its unit tests in one place instead of
    // moving every such test to src/testDebug.
    beforeVariants(selector().withBuildType("release")) { v ->
        (v as? com.android.build.api.variant.HasHostTestsBuilder)
            ?.hostTests?.get(com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE)?.enable = false
    }
    onVariants { variant ->
        // AGP 9 variant API (no hard-coded sourceSets dirs): the staged
        // core's Kotlin and jniLibs are outputs of the import task.
        variant.sources.kotlin?.addGeneratedSourceDirectory(importCore) { it.kotlinDir }
        variant.sources.jniLibs?.addGeneratedSourceDirectory(importCore) { it.jniLibsDir }

        variant.sources.res?.addGeneratedSourceDirectory(generateAssets) { it.resDir }
        variant.sources.res?.addGeneratedSourceDirectory(syncChimes) { it.outputDir }
        variant.sources.assets?.addGeneratedSourceDirectory(generateAssets) { it.assetsDir }
        variant.sources.assets?.addGeneratedSourceDirectory(syncFonts) { it.assetsDir }
        variant.sources.assets?.addGeneratedSourceDirectory(syncLicenses) { it.assetsDir }
    }
}

// ---------------------------------------------------------------------------
// AboutLibraries (ui-parity#101): emit the JSON only. offlineMode keeps the
// build reproducible (no network in CI; the SPDX texts the plugin would
// otherwise pull from spdx.org are committed under licenses/
// and staged as assets/licenses/spdx/); fetchRemote* stays off too. The
// plugin registers the per-variant export tasks that generate
// res/raw/aboutlibraries.json from each variant's runtime classpath
// (includeTestVariants defaults to false, so Robolectric/JUnit
// never appear; debug-only debugImplementation tooling may, which is
// acceptable). No aboutlibraries-* library artifacts are used.
// ---------------------------------------------------------------------------
aboutLibraries {
    offlineMode = true
    collect {
        fetchRemoteLicense = false
        fetchRemoteFunding = false
    }
    export {
        excludeFields.addAll("developers", "funding")
        prettyPrint = false
    }
    library {
        duplicationMode = com.mikepenz.aboutlibraries.plugin.DuplicateMode.MERGE
    }
}

// ---------------------------------------------------------------------------
android {
    namespace = "sh.zeron.android"
    compileSdk = 37
    // Zeron's pin (zeron/apps/android): the NDK the core is built with,
    // whose libc++_shared.so ships beside libzeron_mobile.so.
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "sh.zeron.android"
        minSdk = 31
        targetSdk = 36 // compile 37; target stays 36 for local-network permission
        versionCode = appVersionCode
        versionName = appVersionName
        // android-app#30: AndroidJUnitRunner + the in-process device filters
        // (@TabletOnly / @PhysicalDeviceOnly / @Tour / @Ignore) — see
        // ZeronTestRunner for why not a comma-separated `notAnnotation`.
        testInstrumentationRunner = "sh.zeron.android.ZeronTestRunner"
        // android-app#30: the flow suite's isolation — each instrumented
        // test runs in its own process with wiped app data, so every test
        // starts unpaired and clean (the rig re-pairs via the `pair`
        // extra). Requires the ANDROIDX_TEST_ORCHESTRATOR execution below
        // and the `androidTestUtil` orchestrator APK.
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
        // No ndk.abiFilters here: AGP refuses abiFilters alongside the ABI
        // splits below, and the splits' include (intersected with
        // -PzeronAbis) decides which libzeron_mobile.so is packaged per APK.
    }

    signingConfigs {
        // Filled only when a signing source resolved above. PKCS12 needs no
        // storeType: the JDK's default KeyStore type handles it. Declared
        // before buildTypes, which look the config up by name.
        releaseKeystore?.let { keystore ->
            create("release") {
                storeFile = keystore.storeFile
                storePassword = keystore.storePassword
                keyAlias = keystore.keyAlias
                keyPassword = keystore.keyPassword
            }
        }
        // The shared CI test key (above); otherwise AGP's default debug key.
        debugKeystore?.let { keystore ->
            getByName("debug") {
                storeFile = keystore.storeFile
                storePassword = keystore.storePassword
                keyAlias = keystore.keyAlias
                keyPassword = keystore.keyPassword
            }
        }
    }

    buildTypes {
        release {
            // R8 + resource shrinking (android-app#31). The keep rules for the
            // reflection- and native-reached JNA/UniFFI layers live in
            // proguard-rules.pro; the R8 mapping lands in
            // build/outputs/mapping/release/ and ships as a release asset.
            // Minification is unconditional: the unsigned and signed paths
            // must produce the same R8 output.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Only when a signing source is present; otherwise the APK is
            // built unsigned (CI's workflow_dispatch path) — never the
            // debug key.
            releaseKeystore?.let { signingConfig = signingConfigs.getByName("release") }
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include(*splitAbis.toTypedArray())
            // A universal APK would double the download for every phone.
            // versionCode stays identical across splits: sideload-only, a
            // device installs exactly one.
            isUniversalApk = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // BuildConfig.VERSION_NAME backs the core handshake in CoreLoader.
        buildConfig = true
    }

    testOptions {
        // android-app#30: every instrumented test in its own process with
        // `clearPackageData` (above), so the flow suite's stored state
        // never bleeds between tests.
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
        unitTests {
            // Robolectric needs the app resources/assets (fonts, icons).
            isIncludeAndroidResources = true
        }
    }

    packaging {
        jniLibs {
            // Sideloaded APKs are downloaded from GitHub without store-side
            // compression; extracting the .so at install keeps the APK small.
            // 16 KB compatibility comes from the core's ELF alignment.
            useLegacyPackaging = true
        }
    }
}

// ---------------------------------------------------------------------------
// JVM tests: JDK 21 launcher (Robolectric needs it for SDK 36+), the host
// core on jna.library.path, and the generated dirs for the plain tests.
// ---------------------------------------------------------------------------
tasks.withType<Test>().configureEach {
    dependsOn(
        importCore,
        generateAssets,
        syncFonts,
        syncLicenses,
        syncChimes,
    )
    javaLauncher.set(
        javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) },
    )
    // A custom launcher means no IDE/AGP-injected module flags; Robolectric's
    // interceptors (SharedSecrets et al.) need the opens/exports on JDK 21.
    jvmArgs(
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
        "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        "--add-opens=java.base/java.net=ALL-UNNAMED",
        "--add-opens=java.base/java.nio=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED",
        "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
        "--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED",
        "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
        "--add-opens=java.base/sun.security.action=ALL-UNNAMED",
        "--add-opens=java.base/sun.util.calendar=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.reflect=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.vm=ALL-UNNAMED",
        // UniFFI objects register with android.system.SystemCleaner, which
        // Robolectric backs with jdk.internal.ref.CleanerFactory.
        "--add-exports=java.base/jdk.internal.ref=ALL-UNNAMED",
    )
    maxHeapSize = "2g"
    // A failure prints its message, causes and stack in the job log. The
    // default short form is just "IllegalStateException at X.kt:32". Record
    // run 38595804909 failed 12 captures that way: no message, and on the
    // record path nothing else was uploaded.
    // STARTED too (round 51): a hung test then shows as the last STARTED
    // line in the job log. Run 88b067d0's testDebugUnitTest hung for
    // 73 min with no output at all, so nothing named the hanging test.
    testLogging {
        events(
            org.gradle.api.tasks.testing.logging.TestLogEvent.STARTED,
            org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED,
        )
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showCauses = true
        showStackTraces = true
    }
    // Safety net (round 51): a hang fails the task after 45 min instead of
    // eating the whole 90-min CI job. Imported: inside this block `java`
    // resolves to the project's java extension, not the package.
    timeout.set(Duration.ofMinutes(45))
    systemProperty("jna.library.path", hostCoreDir.absolutePath)
    systemProperty("robolectric.graphicsMode", "NATIVE")
    systemProperty("robolectric.pixelCopyRenderMode", "hardware")
    systemProperty("zeron.repoRoot", repoRootDir.absolutePath)
    systemProperty("zeron.generatedAssets", iconsAssetsDir.get().asFile.absolutePath)
    systemProperty("zeron.generatedRes", iconsResDir.get().asFile.absolutePath)
    systemProperty("zeron.generatedFonts", fontsDir.get().asFile.absolutePath)
    systemProperty("zeron.generatedLicenses", licensesDir.get().asFile.absolutePath)
    systemProperty("zeron.generatedChimes", chimesDir.get().asFile.absolutePath)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.window)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    // iOS continuous (squircle) corners for SmoothRoundedShape (ui-parity#01).
    implementation(libs.graphics.shapes)
    // Liquid-glass backdrop blur (ui-parity#07): pulls in the core `haze`
    // and `haze-utils` artifacts transitively. Deliberately NOT
    // haze-blur-materials (HazeMaterials are not the iOS look). Its POM
    // needs Compose ui ≥ 1.12.1 and Kotlin stdlib ≥ 2.4.20 — both already
    // pinned (BOM 2026.09.00 / Kotlin 2.4.21).
    implementation(libs.haze.blur)
    // Custom Tabs (ui-parity#89): PR chip "Open Pull Request" (a PR link
    // and a transcript link must look identical — ticket 126's recipe).
    // Not a Material artifact; version shared with 126.
    implementation(libs.androidx.browser)

    // Pinned-folder drag-to-reorder (ui-parity#47): Compose-foundation
    // only (runtime, animation, foundation — no material3), Apache-2.0.
    // The library is picked up by the AboutLibraries JSON (101) from its
    // POM licence automatically.
    implementation(libs.reorderable)

    implementation(libs.kotlinx.coroutines.android)
    // Engine-credential storage (ticket 71): no-backup Preferences DataStore.
    // androidx.security:security-crypto is deliberately absent (deprecated
    // and forbidden by android-app#18).
    implementation(libs.androidx.datastore.preferences)
    // The AAR carries arm64/x86_64 libjnidispatch.so (16 KB aligned).
    implementation("${libs.jna.get()}@aar")

    debugImplementation(libs.compose.ui.tooling)
    // JankStats, the lab bench's cross-check (ui-parity #136): debug only —
    // the lab code that imports it lives in src/debug.
    debugImplementation(libs.androidx.metrics.performance)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // ApplicationProvider for the Robolectric core tests.
    testImplementation(libs.androidx.test.core)
    // Plain jar: the host libjnidispatch for JVM (Robolectric) tests.
    testImplementation(libs.jna)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)

    debugImplementation(libs.compose.ui.test.manifest)

    // Instrumented Keystore tests (ticket 71) — CI-only, no emulator locally.
    // androidx-test-runner/ext-junit (and plain JUnit for the accuracy
    // tests' asserts, ui-parity #115) are on the androidTest classpath.
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.junit)
    // Instrumented compose tests (ui-parity #97's WallpaperSettingsFlowTest,
    // CI-only): the compose test rule and its matchers need the BOM and
    // ui-test-junit4 on the androidTest classpath as well.
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    // androidTestUtil: the Orchestrator APK itself (android-app#30) —
    // installed on the device beside the test APK, not on any classpath.
    androidTestUtil(libs.androidx.test.orchestrator)
    // android-app#28's NotificationFlowTest: the system permission dialog,
    // pressHome and the notification shade (UiDevice), CI-only. Also
    // ui-parity #133's KeyboardFlowTest (raw swipes over the IME).
    androidTestImplementation(libs.androidx.test.uiautomator)
    // ui-parity #137's MarkdownImageLabTest: Intents.init/intended pins
    // the viewer's share chooser (ACTION_CHOOSER), CI-only.
    androidTestImplementation(libs.androidx.test.espresso.intents)
}
