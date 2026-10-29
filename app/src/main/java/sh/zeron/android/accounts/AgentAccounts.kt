package sh.zeron.android.accounts

import uniffi.zeron_core.DeviceAccountInfo
import uniffi.zeron_core.UsageWindowInfo

/**
 * The Kotlin side of Roboco's account records, re-pointed at Zeron's
 * (docs/port/05 §3.2): an account is the core's [DeviceAccountInfo], a
 * usage window its [UsageWindowInfo]. Zeron returns a plain list, so the
 * snapshot wrapper lives here; it carries no warnings (Zeron has none) and
 * no usage timestamp (no "Usage updated …" line).
 *
 * TODO(#50): the Providers page and per-agent accounts replace this
 * snapshot shape with iOS's model (accounts per device id).
 */
typealias AgentAccount = DeviceAccountInfo

/** One usage meter (`UsageWindowInfo{label, usedFraction, resetsAtMs}`). */
typealias AgentUsageWindow = UsageWindowInfo

/** A harness-level note the page shows under its group; Zeron never sends one. */
data class AgentAccountWarning(val harness: String, val message: String)

/** Every account one device reported, in host order. */
data class AgentAccountsSnapshot(
    val accounts: List<AgentAccount>,
    val warnings: List<AgentAccountWarning> = emptyList(),
)

/** Roboco's name for [DeviceAccountInfo.usage]. */
val AgentAccount.usageWindows: List<AgentUsageWindow> get() = usage
