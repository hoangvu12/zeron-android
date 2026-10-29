package sh.zeron.android.cloud

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlin.math.roundToLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.zeron.android.ZeronApplication
import sh.zeron.android.composer.openInCustomTab
import sh.zeron.android.core.CloudStore
import sh.zeron.android.core.Face
import sh.zeron.android.design.AlertButton
import sh.zeron.android.design.AlertButtonRole
import sh.zeron.android.design.Icon
import sh.zeron.android.design.LocalAlerts
import sh.zeron.android.design.LocalBottomChrome
import sh.zeron.android.design.LocalTypeScale
import sh.zeron.android.design.SymbolWeight
import sh.zeron.android.design.ZeronAlert
import sh.zeron.android.design.ZeronIcons
import sh.zeron.android.design.ZeronTheme
import sh.zeron.android.design.glass.ActivitySpinner
import sh.zeron.android.design.glass.GlassCircleButton
import sh.zeron.android.design.uiStyle
import sh.zeron.android.onboarding.Capsule
import sh.zeron.android.shell.LargeTitleScaffold
import sh.zeron.android.shell.LocalNavEntry
import sh.zeron.android.shell.LocalNavStack
import sh.zeron.android.shell.largeTitleDefaultTopInset
import sh.zeron.android.shell.NavRoute
import sh.zeron.android.shell.TitleMode
import uniffi.zeron_core.BillingInfo
import uniffi.zeron_core.BillingPlanInfo
import uniffi.zeron_core.CoreClient

/** Settings → Billing (#52; iOS `Cloud/BillingViewController.swift`). */
data object BillingRoute : NavRoute {
    override val hidesBottomChrome: Boolean get() = true
    override val paneTitle: String get() = BillingCopy.Title
}

/** The page's copy (iOS, verbatim). */
object BillingCopy {
    const val Title = "Billing"
    const val Retry = "Try again"
    const val NotSetUp = "Billing isn't set up on this server yet, so plans and credits can't be bought here."
    const val Credits = "Credits"
    const val Plan = "Plan"
    const val Extra = "Extra credits"
    const val NoBalance = "Choose a plan or buy credits to run Cloud"
    const val PlanCredits = "Plan credits"
    const val NoPlan = "No plan yet — choose one below"
    const val ExtraDetail = "Never expire · used once plan credits run out"
    const val CurrentPlan = "Current plan"
    const val Current = "Current"
    const val Switch = "Switch"
    const val Opening = "Opening…"
    const val Manage = "Manage"
    const val PastDue = "Your last payment didn't go through — update your card to keep your plan."
    const val WontRenew = "Won't renew."
    const val RenewsMonthly = "Renews monthly."
    const val PaymentsFailed = "Couldn't open payments"
    const val PerMonth = "/ month"
    const val Fewer = "Fewer packs"
    const val More = "More packs"

    fun packs(n: Int) = if (n == 1) "1 pack" else "$n packs"
    fun loadFailed(error: String) = "Couldn't load billing. $error"
    fun choose(plan: String) = "Choose $plan"
    fun buy(price: String) = "Buy for $price"
}

/** The most packs one checkout buys. */
const val BILLING_MAX_PACKS = 20

/** The desktop's text actions: solid (text colour), filled (a wash), quiet (a stroke). */
enum class BillingTone { Solid, Filled, Quiet }

/** What a plan card's button does. */
enum class PlanActionKind { None, Portal, Checkout }

data class PlanAction(val title: String, val tone: BillingTone, val enabled: Boolean, val kind: PlanActionKind)

/** The rules of the page, pure (unit-tested). */
object BillingRules {
    /** iOS `hours`: "about 750 hours" of the default machine. */
    fun hours(credits: Double): String = "about ${CloudStore.credits((credits / 60).roundToLong().toDouble())} hours"

    /** The figure's unit. */
    fun unit(total: Double): String = if (total == 1.0) "credit" else "credits"

    /** The "Plan credits" row: its caption and value. */
    fun planCredits(b: BillingInfo): Pair<String, String> {
        if (b.creditsMonthlyTotal <= 0) return BillingCopy.NoPlan to "—"
        val of = "Of ${CloudStore.credits(b.creditsMonthlyTotal)}"
        val caption = b.creditsMonthlyEndsMs?.let { "$of · resets ${CloudStore.shortDate(it)}" } ?: of
        return caption to CloudStore.credits(max(b.creditsMonthly, 0.0))
    }

    /** The busy key of a plan's button. */
    fun planKey(plan: BillingPlanInfo) = "plan:${plan.id}"

    /**
     * A plan card's button: the current plan's is disabled; on another plan,
     * switching goes through the portal; with none, a checkout (the first
     * plan solid).
     */
    fun planAction(plan: BillingPlanInfo, index: Int, current: String?, busy: String?, available: Boolean): PlanAction {
        val idle = busy == null && available
        val opening = busy == planKey(plan)
        return when {
            plan.id == current -> PlanAction(BillingCopy.CurrentPlan, BillingTone.Filled, false, PlanActionKind.None)
            current != null -> PlanAction(if (opening) BillingCopy.Opening else BillingCopy.Switch, BillingTone.Filled, idle, PlanActionKind.Portal)
            else -> PlanAction(
                if (opening) BillingCopy.Opening else BillingCopy.choose(plan.name),
                if (index == 0) BillingTone.Solid else BillingTone.Filled,
                idle,
                PlanActionKind.Checkout,
            )
        }
    }

    /** The current plan's line: its detail, and whether it's a warning. */
    fun currentPlanDetail(b: BillingInfo): Pair<String, Boolean> = when {
        b.planStatus == "past_due" -> BillingCopy.PastDue to true
        b.cancelAtPeriodEnd ->
            (b.periodEndMs?.let { "Ends ${CloudStore.shortDate(it)}. You keep its credits until then." } ?: BillingCopy.WontRenew) to false
        else -> (b.periodEndMs?.let { "Renews ${CloudStore.shortDate(it)}." } ?: BillingCopy.RenewsMonthly) to false
    }

    /** The split bar's shares (plan, extra) of their sum; none when both are 0. */
    fun splitShares(monthly: Double, extra: Double): List<Float>? {
        val m = max(monthly, 0.0)
        val e = max(extra, 0.0)
        if (m + e <= 0) return null
        val total = max(m + e, 1.0)
        return listOf((m / total).toFloat(), (e / total).toFloat())
    }

    /** One more or fewer pack, within 1…20. */
    fun step(packs: Int, delta: Int): Int = (packs + delta).coerceIn(1, BILLING_MAX_PACKS)

    /** Back from Stripe: what to compare until the webhook's change shows. */
    fun fingerprint(b: BillingInfo?): Pair<String?, Double?> = b?.plan?.id to b?.creditsTotal
}

/**
 * Settings → Billing: the balance and the time it buys, the monthly plans,
 * and extra credits that never expire. Payment pages open in a Custom Tab;
 * the page re-reads when the app comes back to it (iOS
 * `safariViewControllerDidFinish`: up to 6 reads, 2 s apart, until the plan
 * or the credits change — Checkout lands through a webhook).
 */
@Composable
fun BillingScreen(
    modifier: Modifier = Modifier,
    /**
     * Set when Billing is a presented sheet (#63, iOS `openBilling`): no back
     * chevron, a Done circle that calls this, and the sheet's own insets.
     */
    onDone: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val app = context.applicationContext as ZeronApplication
    val cloud = app.cloud
    val dark = ZeronTheme.isDark
    val alerts = LocalAlerts.current
    val chrome = LocalBottomChrome.current
    val scope = rememberCoroutineScope()
    var packs by remember { mutableIntStateOf(1) }
    var busy by remember { mutableStateOf<String?>(null) }
    // Set when a payment page opened: the resume after it closes re-reads.
    var awaiting by remember { mutableStateOf<Pair<String?, Double?>?>(null) }
    LaunchedEffect(cloud) { cloud.refreshBilling() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val before = awaiting ?: return@LifecycleEventEffect
        awaiting = null
        scope.launch {
            repeat(6) {
                cloud.refreshBilling()
                if (BillingRules.fingerprint(cloud.billing) != before) return@launch
                delay(2_000)
            }
        }
    }

    fun open(key: String, link: suspend (CoreClient) -> String) {
        val client = app.clientOrNull() ?: return
        if (busy != null) return
        busy = key
        scope.launch {
            try {
                val url = link(client)
                awaiting = BillingRules.fingerprint(cloud.billing)
                openInCustomTab(context, url, dark)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                alerts.show(
                    ZeronAlert(
                        title = BillingCopy.PaymentsFailed,
                        message = CloudStore.message(e),
                        buttons = listOf(AlertButton("OK", AlertButtonRole.Default)),
                    ),
                )
            } finally {
                busy = null
            }
        }
    }
    val checkout: (String, String?, Int?, String) -> Unit = { kind, plan, n, key ->
        open(key) { it.billingCheckout(kind, plan, n?.toUInt()) }
    }
    val portal: (String) -> Unit = { key -> open(key) { it.billingPortal() } }

    val billing = cloud.billing
    val billingError = cloud.billingError
    LargeTitleScaffold(
        title = BillingCopy.Title,
        modifier = modifier.testTag("billing"),
        mode = TitleMode.InlineOnly,
        listState = rememberLazyListState(),
        topInset = if (onDone != null) 0.dp else largeTitleDefaultTopInset(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 8.dp,
            bottom = (if (onDone != null) WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() else chrome.contentPadding) + 32.dp,
        ),
        showBack = if (onDone != null) false else LocalNavStack.current.entries.firstOrNull()?.id != LocalNavEntry.current.id,
        onBack = if (onDone != null) ({}) else LocalNavStack.current.let { stack -> { stack.pop() } },
        trailing = {
            if (onDone != null) {
                GlassCircleButton(
                    icon = ZeronIcons.Checkmark,
                    contentDescription = "Done",
                    onClick = onDone,
                    modifier = Modifier.testTag("billing-done"),
                    filled = ZeronTheme.palette.text,
                    ink = ZeronTheme.palette.background,
                )
            }
        },
        content = {
            if (billing == null) {
                item(key = "loading") {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (billingError != null) {
                            // A failed read is said, with a way forward — never
                            // a spinner that runs forever.
                            Note(BillingCopy.loadFailed(billingError))
                            Capsule(
                                title = BillingCopy.Retry,
                                fill = ZeronTheme.palette.controlFill,
                                ink = ZeronTheme.palette.text,
                                face = Face.SansMedium,
                                size = 16f,
                                horizontal = 20.dp,
                                vertical = 14.dp,
                                tag = "billing-retry",
                                leading = ZeronIcons.Refresh,
                                onClick = { scope.launch { cloud.refreshBilling() } },
                            )
                        } else {
                            ActivitySpinner(ZeronTheme.palette.secondary)
                        }
                    }
                }
                return@LargeTitleScaffold
            }
            if (!billing.available) item(key = "not-set-up") { Note(BillingCopy.NotSetUp) }
            item(key = "credits-label") { SectionLabel(BillingCopy.Credits) }
            item(key = "credits") { CreditsCard(billing) }
            item(key = "plan-label") { SectionLabel(BillingCopy.Plan) }
            billing.plans.forEachIndexed { ix, plan ->
                item(key = "plan:${plan.id}") {
                    val action = BillingRules.planAction(plan, ix, billing.plan?.id, busy, billing.available)
                    PlanCard(
                        plan = plan,
                        current = plan.id == billing.plan?.id,
                        action = action,
                        modifier = Modifier.padding(bottom = 10.dp),
                        onAction = {
                            when (action.kind) {
                                PlanActionKind.Portal -> portal(BillingRules.planKey(plan))
                                PlanActionKind.Checkout -> checkout("subscription", plan.id, null, BillingRules.planKey(plan))
                                PlanActionKind.None -> Unit
                            }
                        },
                    )
                }
            }
            val current = billing.plan
            if (current != null) {
                item(key = "current-plan") {
                    val (detail, warning) = BillingRules.currentPlanDetail(billing)
                    Card(
                        rows = listOf<@Composable () -> Unit>({
                            Line(
                                title = "${current.name} · ${CloudStore.price(current.priceCents)} a month",
                                detail = detail,
                                detailColor = if (warning) ZeronTheme.palette.warning else ZeronTheme.palette.secondary,
                            ) {
                                BillingButton(
                                    if (busy == "portal") BillingCopy.Opening else BillingCopy.Manage,
                                    BillingTone.Quiet,
                                    enabled = busy == null,
                                    tag = "billing-manage",
                                ) { portal("portal") }
                            }
                        }),
                    )
                }
            }
            item(key = "extra-label") { SectionLabel(BillingCopy.Extra) }
            item(key = "extra") {
                val credits = billing.packCredits * packs
                Card(
                    separators = false,
                    rows = listOf<@Composable () -> Unit>(
                        {
                            Line(
                                title = "${CloudStore.credits(credits)} credits",
                                detail = "Never expire · ${BillingRules.hours(credits)}",
                            ) {
                                PackStepper(packs) { packs = it }
                            }
                        },
                        {
                            Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
                                BillingButton(
                                    if (busy == "credits") {
                                        BillingCopy.Opening
                                    } else {
                                        BillingCopy.buy(CloudStore.price(billing.packPriceCents * packs.toUInt()))
                                    },
                                    BillingTone.Solid,
                                    enabled = busy == null && billing.available,
                                    modifier = Modifier.fillMaxWidth(),
                                    tag = "buy-credits",
                                ) { checkout("credits", null, packs, "credits") }
                            }
                        },
                    ),
                )
            }
        },
    )
}

// ── pieces ────────────────────────────────────────────────────────────────

@Composable
private fun SectionLabel(text: String) {
    BasicText(
        text,
        Modifier.padding(start = 4.dp, top = 20.dp, bottom = 10.dp).semantics { heading() },
        style = uiStyle(Face.SansMedium, LocalTypeScale.current.fixed(13f), ZeronTheme.palette.secondary),
    )
}

@Composable
private fun Note(text: String) {
    BasicText(
        text,
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        style = uiStyle(Face.Sans, LocalTypeScale.current.fixed(14f), ZeronTheme.palette.secondary),
    )
}

/** A card of rows, hairlines between them inset 16 (iOS `card`). */
@Composable
private fun Card(separators: Boolean = true, rows: List<@Composable () -> Unit>) {
    val palette = ZeronTheme.palette
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(palette.elevated),
    ) {
        rows.forEachIndexed { ix, row ->
            if (separators && ix > 0) {
                Box(
                    Modifier
                        .padding(horizontal = 16.dp)
                        .fillMaxWidth()
                        .height(0.5.dp)
                        .background(palette.hairline),
                )
            }
            row()
        }
    }
}

/** A titled row with a muted line under it and a value or control after (iOS `line`). */
@Composable
private fun Line(
    title: String,
    detail: String,
    value: String? = null,
    detailColor: Color = ZeronTheme.palette.secondary,
    accessory: (@Composable () -> Unit)? = null,
) {
    val palette = ZeronTheme.palette
    val ts = LocalTypeScale.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(title, style = uiStyle(Face.SansMedium, ts.fixed(15f), palette.text))
            BasicText(detail, style = uiStyle(Face.Sans, ts.fixed(13f), detailColor))
        }
        if (value != null) BasicText(value, style = figures(uiStyle(Face.SansMedium, ts.fixed(15f), palette.text)))
        accessory?.invoke()
    }
}

/** The balance: one number and the time it buys, the split bar, then a row for each kind. */
@Composable
private fun CreditsCard(b: BillingInfo) {
    val total = max(b.creditsTotal, 0.0)
    val (planCaption, planValue) = BillingRules.planCredits(b)
    Card(
        rows = listOf<@Composable () -> Unit>(
            {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    FigureRow(
                        figure = CloudStore.credits(total),
                        unit = BillingRules.unit(total),
                        size = 30f,
                        trailing = if (total > 0) BillingRules.hours(total) else BillingCopy.NoBalance,
                        tag = "cloud-credits",
                    )
                    BillingRules.splitShares(b.creditsMonthly, b.creditsExtra)?.let { SplitBar(it) }
                }
            },
            { Line(BillingCopy.PlanCredits, planCaption, value = planValue) },
            { Line(BillingCopy.Extra, BillingCopy.ExtraDetail, value = CloudStore.credits(max(b.creditsExtra, 0.0))) },
        ),
    )
}

/** A large figure, its small unit on the same baseline, and an optional trailing caption (iOS `FigureRow`). */
@Composable
private fun FigureRow(figure: String, unit: String, size: Float, trailing: String? = null, tag: String? = null) {
    val palette = ZeronTheme.palette
    val ts = LocalTypeScale.current
    // #63: "1,250 credits, about 20 hours" as one TalkBack stop.
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        BasicText(
            figure,
            Modifier.alignByBaseline().then(if (tag != null) Modifier.testTag(tag) else Modifier),
            style = figures(uiStyle(Face.SansSemibold, ts.fixed(size), palette.text)),
        )
        Spacer(Modifier.width(6.dp))
        BasicText(unit, Modifier.alignByBaseline(), style = uiStyle(Face.Sans, ts.fixed(13f), palette.secondary))
        if (trailing != null) {
            BasicText(
                trailing,
                Modifier.alignByBaseline().weight(1f).padding(start = 12.dp),
                style = uiStyle(Face.Sans, ts.fixed(13f), palette.secondary).copy(textAlign = TextAlign.End),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A 4 dp track split into the plan (0.7) and extra (0.3) shares of the text colour (iOS `SplitBar`). */
@Composable
private fun SplitBar(shares: List<Float>) {
    val palette = ZeronTheme.palette
    val alphas = listOf(0.7f, 0.3f)
    Row(
        Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(palette.controlFill)
            // Drawn only: the rows under it say the same in words.
            .clearAndSetSemantics { },
    ) {
        shares.forEachIndexed { ix, share ->
            if (share > 0f) {
                Box(Modifier.weight(share).height(4.dp).background(palette.text.copy(alpha = alphas[ix])))
            }
        }
        val rest = 1f - shares.sum()
        if (rest > 0.0001f) Spacer(Modifier.weight(rest))
    }
}

/** A plan: its name (and "Current"), the price, the credits it brings and their time, then its action (iOS `PlanCard`). */
@Composable
private fun PlanCard(plan: BillingPlanInfo, current: Boolean, action: PlanAction, modifier: Modifier, onAction: () -> Unit) {
    val palette = ZeronTheme.palette
    val ts = LocalTypeScale.current
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(palette.elevated)
            .then(if (current) Modifier.border(1.dp, palette.text.copy(alpha = 0.25f), shape) else Modifier)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(plan.name, Modifier.weight(1f), style = uiStyle(Face.SansMedium, ts.fixed(14f), palette.secondary))
            if (current) {
                BasicText(
                    BillingCopy.Current,
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(palette.controlFill)
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                    style = uiStyle(Face.SansMedium, ts.fixed(11f), palette.secondary),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        FigureRow(CloudStore.price(plan.priceCents), BillingCopy.PerMonth, size = 24f)
        Spacer(Modifier.height(8.dp))
        BasicText(
            "${CloudStore.credits(plan.monthlyCredits)} credits a month",
            style = uiStyle(Face.Sans, ts.fixed(14f), palette.text),
        )
        Spacer(Modifier.height(4.dp))
        BasicText(BillingRules.hours(plan.monthlyCredits), style = uiStyle(Face.Sans, ts.fixed(13f), palette.secondary))
        Spacer(Modifier.height(14.dp))
        BillingButton(
            action.title,
            action.tone,
            enabled = action.enabled,
            modifier = Modifier.fillMaxWidth(),
            tag = "plan-${plan.id}",
            onClick = onAction,
        )
    }
}

/** iOS `BillingViewController.button`: 10 dp corners, 9 × 14 insets, dimmed (not grey) when disabled. */
@Composable
private fun BillingButton(
    title: String,
    tone: BillingTone,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    tag: String? = null,
    onClick: () -> Unit,
) {
    val palette = ZeronTheme.palette
    val shape = RoundedCornerShape(10.dp)
    val (fill, ink) = when (tone) {
        BillingTone.Solid -> palette.text to palette.background
        BillingTone.Filled -> palette.controlFill to palette.text
        BillingTone.Quiet -> Color.Transparent to palette.text
    }
    Box(
        modifier
            .alpha(if (enabled) 1f else 0.5f)
            .clip(shape)
            .background(fill)
            .then(if (tone == BillingTone.Quiet) Modifier.border(1.dp, palette.hairline, shape) else Modifier)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(title, style = uiStyle(Face.SansMedium, LocalTypeScale.current.fixed(14f), ink), maxLines = 1)
    }
}

/** − count + in one quiet pill (iOS `PackStepper`); the ends dim at 1 and 20. */
@Composable
private fun PackStepper(count: Int, onChange: (Int) -> Unit) {
    val palette = ZeronTheme.palette
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(palette.controlFill),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepButton(ZeronIcons.Minus, BillingCopy.Fewer, enabled = count > 1) { onChange(BillingRules.step(count, -1)) }
        BasicText(
            "$count",
            Modifier
                .widthIn(min = 22.dp)
                .semantics {
                    // #63: the count is announced as it steps.
                    contentDescription = BillingCopy.packs(count)
                    liveRegion = LiveRegionMode.Polite
                }
                .testTag("packs"),
            style = figures(uiStyle(Face.SansMedium, LocalTypeScale.current.fixed(14f), palette.text))
                .copy(textAlign = TextAlign.Center),
        )
        StepButton(ZeronIcons.Plus, BillingCopy.More, enabled = count < BILLING_MAX_PACKS) { onChange(BillingRules.step(count, 1)) }
    }
}

@Composable
private fun StepButton(icon: sh.zeron.android.design.ZeronIcon, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .alpha(if (enabled) 1f else 0.35f)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { contentDescription = label }
            .padding(horizontal = 11.dp, vertical = 10.dp),
    ) {
        Icon(icon, ZeronTheme.palette.secondary, 12f, SymbolWeight.Semibold)
    }
}

/** Tabular figures (iOS `withMonospacedDigits`). */
private fun figures(style: TextStyle): TextStyle = style.copy(fontFeatureSettings = "tnum")
