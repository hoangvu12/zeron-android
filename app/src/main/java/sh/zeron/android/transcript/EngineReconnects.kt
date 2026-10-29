package sh.zeron.android.transcript

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import sh.zeron.android.ZeronApplication
import uniffi.zeron_core.EngineState

/**
 * [TranscriptImageBinding.retries] for one session: fires each time the
 * engine serving its images ([deviceId], read per update) turns Connected
 * again in the app's workspace feed. Empty outside the app (previews,
 * tests).
 */
fun engineReconnects(context: Context, deviceId: () -> String): Flow<Unit> {
    val app = context.applicationContext as? ZeronApplication ?: return emptyFlow()
    return reconnects(
        app.workspaceFeed.engines.map { engines ->
            val id = deviceId()
            engines.any { (it.deviceId == id || it.engineId == id) && it.state is EngineState.Connected }
        },
    )
}
