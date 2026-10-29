package zeron.gradle

import org.gradle.api.GradleException
import org.gradle.api.Project
import java.io.File

/**
 * Repository geometry. The Gradle root project is the repository root; the
 * Zeron sources (crates, shared assets, `Cargo.toml`, notices) live in the
 * `zeron/` git submodule. Resolved at configuration time only; task inputs
 * hold plain files, so nothing reads `Project` state at execution time
 * (configuration-cache safe).
 */
object RepoPaths {

    /** This repository's root (the Gradle root project). */
    fun root(project: Project): File = project.rootDir

    /** The `zeron/` submodule, checked out (fails with a hint otherwise). */
    fun zeron(project: Project): File {
        val dir = root(project).resolve("zeron")
        if (!dir.resolve("Cargo.toml").isFile) {
            throw GradleException(
                "The zeron/ submodule is not checked out ($dir has no Cargo.toml). " +
                    "Run `git submodule update --init`.",
            )
        }
        return dir
    }

    /** Zeron's shared UI assets: `zeron/crates/ui/assets`. */
    fun uiAssets(project: Project): File = zeron(project).resolve("crates/ui/assets")

    /** The vendored icon pipeline's Cargo manifest. */
    fun androidAssetsManifest(project: Project): File =
        root(project).resolve("tools/android-assets/Cargo.toml")
}
