package zeron.gradle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

/**
 * The versionCode formula and the `[workspace.package]` parse (android-app#13).
 * Expected values are worked literals from the ticket, not recomputations.
 */
class ZeronVersionTest {

    @Test
    fun versionCodeFormula() {
        assertEquals(9002, ZeronVersion.versionCode("0.9.2"))
        assertEquals(1_000_000, ZeronVersion.versionCode("1.0.0"))
        assertEquals(10_000, ZeronVersion.versionCode("0.10.0-beta.1"))
        assertEquals(0, ZeronVersion.versionCode("0.0.0"))
    }

    @Test
    fun minorOrPatchAt1000FailsTheBuild() {
        assertThrows(IllegalArgumentException::class.java) {
            ZeronVersion.versionCode("0.1000.0")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ZeronVersion.versionCode("0.1.1000")
        }
    }

    @Test
    fun readsTheWorkspacePackageVersion() {
        val cargo = File.createTempFile("Cargo", ".toml")
        cargo.writeText(
            """
            [workspace]
            members = ["crates/mobile"]

            [workspace.package]
            version = "1.2.3"
            edition = "2024"

            [workspace.dependencies]
            anyhow = "1"
            """.trimIndent(),
        )
        assertEquals("1.2.3", ZeronVersion.versionName(cargo))
        cargo.delete()
    }

    @Test
    fun aMissingWorkspaceVersionFails() {
        val cargo = File.createTempFile("Cargo", ".toml")
        cargo.writeText(
            """
            [workspace]
            members = ["crates/mobile"]

            [package]
            name = "unrelated"
            version = "9.9.9"
            """.trimIndent(),
        )
        assertThrows(IllegalStateException::class.java) {
            ZeronVersion.versionName(cargo)
        }
        cargo.delete()
    }
}
