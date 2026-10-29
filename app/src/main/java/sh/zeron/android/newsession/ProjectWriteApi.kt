package sh.zeron.android.newsession

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.zeron_core.CoreClient

/**
 * The New Project write seam (android-ui-parity #111): the FFI call the
 * bottom bar's Use action needs — `createProject` (Zeron has no
 * `prepareSpacePath`; the folder picked from `listFolders` is used as is,
 * as on iOS). A seam, not the client itself,
 * so the plain-JVM tests script it without the native core, exactly as
 * 110's [FolderBrowserSource] splits browsing off.
 */
interface ProjectWriteApi {

    /**
     * android-app#09 `createProject`: the **scoped** space id. Dedupes
     * against an existing space in the same folder on the same device —
     * never dedupe again in Kotlin. Throws on failure (CoreException;
     * HostUnavailable when the engine is offline).
     */
    suspend fun createProject(deviceId: String, path: String, gitDetected: Boolean): String
}

/**
 * The live [ProjectWriteApi] over the app's [CoreClient] — the same
 * provider 110's [CoreFolderBrowserSource] uses (`app.clientOrNull()`).
 * With no client it throws `IllegalStateException("Zeron is disconnected.")`
 * (the alert's message maps that the same way iOS maps `CoreError.Closed`).
 *
 * The FFI calls run on [io] (never main, never `runBlocking` — the #609
 * trap: create must not run synchronously on the main thread), and
 * failures propagate to the caller, which owns the copy.
 */
class CoreProjectWriteApi(
    private val client: () -> CoreClient?,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ProjectWriteApi {

    override suspend fun createProject(deviceId: String, path: String, gitDetected: Boolean): String {
        val c = client() ?: throw IllegalStateException("Zeron is disconnected.")
        return withContext(io) { c.createProject(deviceId, path, gitDetected) }
    }
}
