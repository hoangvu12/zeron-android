package sh.zeron.android.newsession

import sh.zeron.android.composer.ModelPickerRequest
import sh.zeron.android.composer.ModelPickerRow
import sh.zeron.android.composer.ModelPickerTab

/**
 * The New Session page's model rules (#37) — the port of
 * `NewSessionViewController`'s `draftModel` and `modelPicker()`
 * (`ios:Session/NewSessionViewController.swift:497-523`). The model chip
 * opens the shared tabbed [ModelPickerRequest] sheet (the one the session
 * screen uses), not a grouped menu.
 */

/**
 * iOS `draftModel`: the draft's own model when it names one — even one
 * this list lacks, which then resolves to `null` (it is what gets sent,
 * so the chip names it through the catalog label and offers no ladder) —
 * and the provider's first listed model only when it names none. Pure.
 */
fun draftModel(models: List<ModelChoice>, draft: NewSessionDraft): ModelChoice? =
    if (draft.model == null) {
        models.firstOrNull { it.harness == draft.harness }
    } else {
        models.firstOrNull { it.harness == draft.harness && it.id == draft.model }
    }

/**
 * One tab per provider the target runs, in draft-harness order: the
 * draft's provider first, then the others as the catalog lists them. A
 * provider with no model has no tab (so a draft harness the host does not
 * list opens on the first one that has models). Pure.
 */
fun newSessionPickerTabs(models: List<ModelChoice>, harness: String): List<ModelPickerTab> {
    val order = (listOf(harness) + models.map { it.harness }).distinct()
    return order.mapNotNull { h ->
        val first = models.firstOrNull { it.harness == h } ?: return@mapNotNull null
        ModelPickerTab(harness = h, label = first.harnessLabel)
    }
}

/** A provider's rows: its models in catalog order, the description as the detail line. Pure. */
fun newSessionPickerRows(models: List<ModelChoice>, harness: String): List<ModelPickerRow> =
    models.filter { it.harness == harness }.map { ModelPickerRow(id = it.id, label = it.label, detail = it.detail) }

/**
 * A pick (iOS `:509-512`): the harness and the model, and the effort is
 * cleared **only when the harness changes** — a model of the same
 * provider keeps the picked level. Pure.
 */
fun applyNewSessionModelPick(draft: NewSessionDraft, harness: String, model: String): NewSessionDraft =
    draft.copy(
        harness = harness,
        model = model,
        effort = if (harness != draft.harness) null else draft.effort,
    )

/**
 * The picker for the page as it is at tap time: the tabs and rows from
 * the catalog already loaded ([models], iOS's `{ rows }`), the draft's
 * provider and model current (an unpicked model ticks nothing), and the
 * computer the session runs on as the host line. [update] is the page's
 * only mutation path; the pick is applied to the draft as it is then.
 */
fun newSessionModelPicker(
    models: List<ModelChoice>,
    draft: NewSessionDraft,
    hostName: String,
    /** #53: `cloud` for a draft that runs on Cloud. */
    hostSymbol: String = sh.zeron.android.composer.HOST_SYMBOL_COMPUTER,
    update: ((NewSessionDraft) -> NewSessionDraft) -> Unit,
): ModelPickerRequest = ModelPickerRequest(
    tabs = newSessionPickerTabs(models, draft.harness),
    harness = draft.harness,
    model = draft.model,
    hostName = hostName,
    hostSymbol = hostSymbol,
    load = { h -> newSessionPickerRows(models, h) },
    onPick = { h, row -> update { applyNewSessionModelPick(it, h, row.id) } },
)

/**
 * The host line's name (iOS `app.deviceName(deviceId)`): the picked
 * checkout's device, else the projectless pick's host, else the generic
 * name the options use for an unknown device. Pure.
 */
fun newSessionPickerHostName(project: ProjectOption?, hosts: List<HostOption>, hostId: String?): String =
    project?.deviceName ?: hosts.firstOrNull { it.id == hostId }?.name ?: UNKNOWN_DEVICE

/** The options' name for a device the workspace does not know. */
internal const val UNKNOWN_DEVICE = "Unknown device"
