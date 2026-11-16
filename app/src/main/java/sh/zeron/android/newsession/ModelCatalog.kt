package sh.zeron.android.newsession

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import uniffi.zeron_core.CoreClient
import uniffi.zeron_core.CoreException
import uniffi.zeron_core.HarnessInfo
import uniffi.zeron_core.ModelInfo
import uniffi.zeron_core.fallbackHarnesses
import uniffi.zeron_core.fallbackModels

/**
 * Where the New Session model catalog comes from (android-ui-parity #92)
 * — the port of `AppModel.models(for:)` (`ios:App/AppModel.swift:579-588`)
 * behind a seam, so the model tests fake it and the chip opens on a real
 * model name while a fresh list comes back over the relay.
 */
interface ModelCatalogSource {

    /**
     * The built-in catalog: `fallbackHarnesses().filter { offered }` ×
     * `fallbackModels(harness)` — memoized by the implementation, constant
     * per process. The **only** FFI call allowed on the main thread (the
     * static catalog functions are lock-free in Rust).
     */
    fun builtIn(): List<ModelChoice>

    /**
     * The engine's own catalog: `listHarnesses` + `listModels` per
     * **offered** harness, sequentially in the order the engine lists
     * them; `[]` without a client. Call off the main thread — the model
     * launches it on the app scope with `Dispatchers.IO`.
     */
    suspend fun fetch(deviceId: String): List<ModelChoice>

    /**
     * #53: Cloud's models, as the edge lists them (iOS `AppModel.cloudModels`):
     * `cloudModels(h)` for each Cloud harness; `[]` without a client.
     */
    suspend fun fetchCloud(): List<ModelChoice> = emptyList()
}

/** One `HarnessInfo` × `ModelInfo` row of the pickers. */
private fun choice(h: HarnessInfo, m: ModelInfo): ModelChoice = ModelChoice(
    harness = h.id,
    harnessLabel = h.label,
    id = m.id,
    label = m.label,
    efforts = m.reasoningLevels,
    detail = m.description?.takeIf { it.isNotBlank() },
)

/**
 * The live [ModelCatalogSource] over the process's client, resolved at
 * call time (ticket 71's `ZeronApplication.clientOrNull()`): `null`
 * while signed out reads as an empty catalog.
 *
 * The FFI never throws by contract — `CoreClient.listHarnesses` /
 * `listModels` fall back live → disk cache → static inside the core
 * (`ListHarnesses` 30 s, `ListModels` 100 s RPC timeouts,
 * android-app#08). Should a call throw anyway, [fetchCatalog] falls back
 * per call, as iOS does, rather than dropping the whole fresh list.
 */
class CoreModelCatalogSource(
    private val client: () -> CoreClient?,
) : ModelCatalogSource {

    /** Constant per process; the first evaluation may happen on main. */
    private val builtInCache: List<ModelChoice> by lazy {
        fallbackHarnesses()
            .filter { it.offered }
            .flatMap { h -> fallbackModels(h.id).map { m -> choice(h, m) } }
    }

    override fun builtIn(): List<ModelChoice> = builtInCache

    override suspend fun fetchCloud(): List<ModelChoice> {
        val c = client() ?: return emptyList()
        return CLOUD_HARNESSES.flatMap { h ->
            val label = uniffi.zeron_core.harnessLabel(h)
            c.cloudModels(h).map { m ->
                ModelChoice(
                    harness = h,
                    harnessLabel = label,
                    id = m.id,
                    label = m.label,
                    efforts = m.reasoningLevels,
                    detail = m.description?.takeIf { it.isNotBlank() },
                )
            }
        }
    }

    override suspend fun fetch(deviceId: String): List<ModelChoice> {
        val c = client() ?: return emptyList()
        return fetchCatalog(
            listHarnesses = { c.listHarnesses(deviceId) },
            listModels = { h -> c.listModels(deviceId, h) },
            fallbackHarnesses = ::fallbackHarnesses,
            fallbackModels = ::fallbackModels,
        )
    }
}

/**
 * iOS `AppModel.models(for:)` (`AppModel.swift:854-863`): the host's
 * providers (the built-in list when that read fails), and for each offered
 * one its models (that provider's built-in models when its own read fails),
 * so one failing provider never drops the others' fresh lists. Pure over
 * its four calls, for the tests.
 */
internal suspend fun fetchCatalog(
    listHarnesses: suspend () -> List<HarnessInfo>,
    listModels: suspend (harness: String) -> List<ModelInfo>,
    fallbackHarnesses: () -> List<HarnessInfo>,
    fallbackModels: (harness: String) -> List<ModelInfo>,
): List<ModelChoice> {
    val harnesses = orFallback({ listHarnesses() }, fallbackHarnesses)
    val out = ArrayList<ModelChoice>(harnesses.size * 4)
    for (h in harnesses) {
        if (!h.offered) continue
        for (m in orFallback({ listModels(h.id) }) { fallbackModels(h.id) }) out += choice(h, m)
    }
    return out
}

/** Swift's `(try? await read()) ?? fallback()`, cancellation kept. */
private suspend fun <T> orFallback(read: suspend () -> T, fallback: () -> T): T = try {
    read()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (e: CoreException) {
    fallback()
}

/**
 * The process-static per-device model cache (ticket 92) — the port of
 * iOS's `static var modelCache` (`ios:Session/NewSessionViewController.swift:186-187`):
 * the last catalog each host reported, so a page opens on a real model
 * name in the same frame while a fresh one comes back over the relay.
 * Keyed by the **scoped** device id, shared by every page, never empty.
 */
object ModelCatalogCache {

    private val cache = ConcurrentHashMap<String, List<ModelChoice>>()

    operator fun get(deviceId: String): List<ModelChoice>? = cache[deviceId]

    operator fun set(deviceId: String, models: List<ModelChoice>) {
        cache[deviceId] = models
    }

    /** The engine-forget hook: a forgotten engine's rows can no longer be picked. */
    fun remove(deviceIds: Set<String>) {
        for (id in deviceIds) cache.remove(id)
    }

    /** The full-reset (last engine forgotten) hook; tests also clear between cases. */
    fun clear() = cache.clear()
}
