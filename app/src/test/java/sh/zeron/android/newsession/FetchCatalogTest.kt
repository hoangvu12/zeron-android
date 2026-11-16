package sh.zeron.android.newsession

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import sh.zeron.android.composer.chipMenuModel
import uniffi.zeron_core.CoreException
import uniffi.zeron_core.HarnessInfo
import uniffi.zeron_core.ModelInfo

/**
 * [fetchCatalog]: iOS `AppModel.models(for:)` (AppModel.swift:854-863):
 * a failed read falls back per call, so one failing provider keeps the
 * others' fresh lists.
 */
class FetchCatalogTest {

    private fun harness(id: String, offered: Boolean = true) = HarnessInfo(
        id = id,
        label = id.uppercase(),
        supportsSteering = null,
        steeringMode = null,
        reasoningLevels = emptyList(),
        installed = offered,
        enabled = null,
        offered = offered,
        midTurnSteering = null,
    )

    private fun model(id: String): ModelInfo = chipMenuModel(id, id, null, emptyList(), null)

    private val builtInHarnesses = listOf(harness("claude-code"), harness("codex"))
    private fun builtInModels(h: String) = listOf(model("$h-builtin"))

    @Test
    fun aFailingProviderFallsBackAloneTheOthersStayFresh() = runTest {
        val out = fetchCatalog(
            listHarnesses = { listOf(harness("claude-code"), harness("codex"), harness("pi", offered = false)) },
            listModels = { h -> if (h == "codex") throw CoreException.HostUnavailable("slow") else listOf(model("$h-live")) },
            fallbackHarnesses = { builtInHarnesses },
            fallbackModels = ::builtInModels,
        )
        assertEquals(listOf("claude-code-live", "codex-builtin"), out.map { it.id })
        assertEquals(listOf("claude-code", "codex"), out.map { it.harness })
    }

    @Test
    fun aFailingProviderListUsesTheBuiltInProviders() = runTest {
        val out = fetchCatalog(
            listHarnesses = { throw CoreException.HostUnavailable("offline") },
            listModels = { h -> listOf(model("$h-live")) },
            fallbackHarnesses = { builtInHarnesses },
            fallbackModels = ::builtInModels,
        )
        assertEquals(listOf("claude-code-live", "codex-live"), out.map { it.id })
    }
}
