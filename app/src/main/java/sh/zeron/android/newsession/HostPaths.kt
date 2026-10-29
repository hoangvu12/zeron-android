package sh.zeron.android.newsession

/**
 * Seam over the host-path rules so callers and tests can swap them: one
 * level up, a join, the last component. Paths are the **host's** (a
 * Windows engine answers `C:\Users\…`), so the rules cover POSIX, drive
 * and UNC paths — `pr621:apps/android/app/src/main/java/sh/zeron/android/ui/FolderBrowser.kt:57-130`
 * is the logic reference (iOS's `NewProjectViewController.parent(of:)`
 * handles POSIX only).
 */
interface HostPathOps {
    /** `C:\Users` → `C:\`; `C:\` → null; `/home` → `/`; `/` → null; `\\srv\share\x` → `\\srv\share`. */
    fun parent(path: String): String?

    /** `\` on a Windows base, `/` otherwise; no separator if the base ends in one. */
    fun join(base: String, name: String): String

    /** The last component, or the path itself for a root (`C:\`, `/`). */
    fun name(path: String): String
}

/**
 * The host-path rules in Kotlin. Roboco called its core's `host_path_*`
 * helpers; Zeron's core has none, so the rules live here (moved from the
 * debug `FakeHostPaths`, which now delegates).
 */
object HostPaths : HostPathOps {
    /** `^[A-Za-z]:([\\/].*)?$` — `C:`, `C:\`, `C:/x` — or a UNC `\\server\share`. */
    private val drive = Regex("^[A-Za-z]:([\\\\/].*)?$")

    fun isWindows(path: String): Boolean = drive.matches(path) || path.startsWith("\\\\")

    private fun isSep(c: Char, windows: Boolean) = c == '/' || (windows && c == '\\')

    /** `C:\` or `C:` (a drive root), `\\server\share`, or `/`. */
    fun isRoot(path: String): Boolean {
        if (isWindows(path)) {
            val t = path.trimEnd('\\', '/')
            if (t.length == 2 && t[1] == ':') return true
            if (path.startsWith("\\\\")) return t.drop(2).count { it == '\\' || it == '/' } <= 1
            return false
        }
        return path.trimEnd('/').isEmpty()
    }

    override fun join(base: String, name: String): String {
        val windows = isWindows(base)
        if (base.isEmpty()) return name
        return if (isSep(base.last(), windows)) base + name else base + (if (windows) '\\' else '/') + name
    }

    /** One level up, or null at a root. `C:\Users` → `C:\`, `/home` → `/`, `\\srv\share\x` → `\\srv\share`. */
    override fun parent(path: String): String? {
        if (path.isEmpty() || isRoot(path)) return null
        val windows = isWindows(path)
        var end = path.length
        while (end > 1 && isSep(path[end - 1], windows)) end--
        val trimmed = path.substring(0, end)
        val cut = trimmed.indexOfLast { isSep(it, windows) }
        if (cut < 0) return null
        val up = trimmed.substring(0, cut)
        return when {
            windows && up.length == 2 && up[1] == ':' -> "$up\\"
            windows && up.isEmpty() -> null
            !windows && up.isEmpty() -> "/"
            else -> up
        }
    }

    /** Last component, or the path itself for a root (`C:\`, `/`). */
    override fun name(path: String): String {
        if (isRoot(path)) return path
        val windows = isWindows(path)
        val t = path.trimEnd { isSep(it, windows) }
        return t.substring(t.indexOfLast { isSep(it, windows) } + 1)
    }
}
