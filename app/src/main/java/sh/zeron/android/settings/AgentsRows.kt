package sh.zeron.android.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Immutable
import sh.zeron.android.accounts.ProvidersRoute
import sh.zeron.android.cloud.CloudSettingsRoute
import sh.zeron.android.core.CloudStore
import sh.zeron.android.design.GroupedRow
import sh.zeron.android.design.ZeronIcon
import sh.zeron.android.design.ZeronIcons
import sh.zeron.android.design.settingsTag
import sh.zeron.android.design.groupedSection
import sh.zeron.android.shell.NavStack

/**
 * Settings' **Agents** section (#49): the port of
 * `MoreViewController.swift:197-210, 257-262`. Agents and where they run,
 * as the desktop groups them: **Providers**, then **Cloud** and **Billing**
 * when this account is offered Cloud (or the demo was launched with
 * `-cloud`). Replaces Roboco's per-device Agent Accounts summary.
 */

/** The row ids, iOS's. */
object AgentsRowIds {
    const val Providers = "providers"
    const val Cloud = "cloud"
    const val Billing = "billing"
}

/** One row of the section. */
@Immutable
data class AgentsRow(val id: String, val title: String, val subtitle: String, val icon: ZeronIcon)

/** What the section reads from Cloud (a pure snapshot of [CloudStore]). */
@Immutable
data class AgentsCloud(
    val available: Boolean = false,
    /** `CloudStore.problem?.title` (an error, or out of credits). */
    val problem: String? = null,
    /** The connected GitHub account, if any. */
    val githubAccount: String? = null,
    val credits: Double? = null,
    val planName: String? = null,
) {
    companion object {
        fun of(cloud: CloudStore) = AgentsCloud(
            available = cloud.available,
            problem = cloud.problem?.title,
            githubAccount = cloud.githubAccount,
            credits = cloud.credits,
            planName = cloud.billing?.plan?.name,
        )
    }
}

/**
 * The rows (iOS `reload`): Providers always; Cloud and Billing only when
 * Cloud is offered — never in the demo unless it was launched with
 * `-cloud`. Pure.
 *
 * - Cloud: the problem's title, else "GitHub @x", else "Connect GitHub";
 * - Billing: "<Plan> plan · N credits" (either part alone), else "Plans and
 *   credits".
 */
fun agentsRows(
    cloud: AgentsCloud,
    demo: Boolean,
    demoCloud: Boolean,
    credits: (Double) -> String = CloudStore::credits,
): List<AgentsRow> = buildList {
    add(AgentsRow(AgentsRowIds.Providers, "Providers", "Agents and their accounts on each device", ZeronIcons.Cpu))
    if ((!demo && cloud.available) || (demo && demoCloud)) {
        val github = cloud.githubAccount?.let { "GitHub @$it" } ?: "Connect GitHub"
        add(AgentsRow(AgentsRowIds.Cloud, "Cloud", cloud.problem ?: github, ZeronIcons.Cloud))
        val billing = listOfNotNull(
            cloud.planName?.let { "$it plan" },
            cloud.credits?.let { "${credits(it)} credits" },
        ).joinToString(" · ").ifEmpty { "Plans and credits" }
        add(AgentsRow(AgentsRowIds.Billing, "Billing", billing, ZeronIcons.CreditCard))
    }
}

/** The section: a disclosure row each; [onOpen] gets the row's id. */
fun LazyListScope.agentsSection(rows: List<AgentsRow>, onOpen: (id: String) -> Unit) {
    groupedSection(
        key = "agents",
        header = "Agents",
        items = rows,
        itemKey = { it.id },
    ) { row, position ->
        GroupedRow(
            title = row.title,
            position = position,
            subtitle = row.subtitle,
            icon = row.icon,
            testTag = settingsTag(row.id),
            onClick = { onOpen(row.id) },
        )
    }
}

/**
 * A tap on an Agents row (iOS `didSelectItemAt`): each pushes its page.
 * Cloud pushes Settings → Cloud (#51).
 * TODO(#52): Billing pushes `BillingViewController`.
 */
fun openAgentsRow(nav: NavStack, id: String) {
    when (id) {
        AgentsRowIds.Providers -> {
            val route = ProvidersRoute()
            if (nav.top.route != route) nav.push(route, animated = true)
        }
        AgentsRowIds.Cloud -> if (nav.top.route !is CloudSettingsRoute) nav.push(CloudSettingsRoute(), animated = true)
        AgentsRowIds.Billing -> Unit
    }
}
