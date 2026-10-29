package sh.zeron.android.newsession

/**
 * The galleries' and previews' host-path rules: the main [HostPaths]
 * (pure Kotlin since Zeron's core has no `host_path_*` helpers). Kept as
 * a name so the galleries and the quarantined tests read unchanged.
 */
object FakeHostPaths : HostPathOps by HostPaths
