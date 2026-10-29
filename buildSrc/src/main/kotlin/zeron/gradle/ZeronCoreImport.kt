package zeron.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Stages a prebuilt Zeron mobile core into the build. The core is not
 * compiled by Gradle: `zeron/scripts/android/build-core.sh <dir>` (run by the
 * `Core` CI workflow) produces a `core-out` directory:
 *
 * ```text
 * <dir>/kotlin/uniffi/zeron_core/zeron_core.kt   UniFFI Kotlin bindings
 * <dir>/jniLibs/<abi>/libzeron_mobile.so         the core, per ABI
 * <dir>/jniLibs/<abi>/libc++_shared.so           the NDK C++ runtime it links
 * <dir>/host/libzeron_mobile.so                  Linux host build (JVM tests)
 * ```
 *
 * The directory comes from `-PzeronCoreDir=<dir>` or `ZERON_CORE_DIR`. It is
 * copied (not referenced in place) so AGP's generated-source wiring sees
 * plain task outputs and the build fails here, with a clear message, when the
 * directory is missing or incomplete — not later with a wall of unresolved
 * `uniffi.zeron_core` references.
 */
abstract class ZeronCoreImport : DefaultTask() {

    /** The `core-out` directory, absent when neither property nor env is set. */
    @get:Optional
    @get:Input
    abstract val coreDirPath: Property<String>

    /** Every file under [coreDirPath], for up-to-date checks. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val coreFiles: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val kotlinDir: DirectoryProperty

    @get:OutputDirectory
    abstract val jniLibsDir: DirectoryProperty

    /** The host library for JVM tests; left empty when the core has none. */
    @get:OutputDirectory
    abstract val hostDir: DirectoryProperty

    @TaskAction
    fun stage() {
        val path = coreDirPath.orNull?.takeIf { it.isNotBlank() }
            ?: throw GradleException(
                "No Zeron core. Pass -PzeronCoreDir=<dir> (or set ZERON_CORE_DIR) to a " +
                    "directory built by `bash zeron/scripts/android/build-core.sh <dir>` " +
                    "(or the `zeron-core` artifact of the Core workflow): it needs " +
                    "kotlin/, jniLibs/<abi>/libzeron_mobile.so and, for JVM tests, " +
                    "host/libzeron_mobile.so.",
            )
        val dir = File(path)
        val kotlin = dir.resolve("kotlin")
        val jniLibs = dir.resolve("jniLibs")
        val problems = buildList {
            if (!dir.isDirectory) add("$dir does not exist")
            if (kotlin.walkTopDown().none { it.isFile && it.extension == "kt" }) {
                add("no UniFFI Kotlin bindings under $kotlin")
            }
            val abis = jniLibs.listFiles { f -> f.isDirectory }.orEmpty()
            if (abis.isEmpty()) add("no ABI directories under $jniLibs")
            for (abi in abis) {
                for (lib in listOf("libzeron_mobile.so", "libc++_shared.so")) {
                    if (!abi.resolve(lib).isFile) add("missing ${abi.name}/$lib")
                }
            }
        }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "The Zeron core at $dir is incomplete:\n  - " + problems.joinToString("\n  - "),
            )
        }
        replace(kotlin, kotlinDir.get().asFile)
        replace(jniLibs, jniLibsDir.get().asFile)
        replace(dir.resolve("host"), hostDir.get().asFile)
    }

    private fun replace(from: File, to: File) {
        to.deleteRecursively()
        to.mkdirs()
        if (from.isDirectory) from.copyRecursively(to, overwrite = true)
    }
}
