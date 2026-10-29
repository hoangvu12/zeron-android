package sh.zeron.android.companions

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import sh.zeron.android.design.formatElapsed
import sh.zeron.android.sessions.SessionRowVM
import sh.zeron.android.sidechats.AgentState
import sh.zeron.android.sidechats.SideChatsAgent
import sh.zeron.android.sidechats.SideChatsUi
import sh.zeron.android.sidechats.SubagentFormat

/** The panel's copy (iOS `CompanionsViewController`). */
object CompanionsCopy {
    /** A terminal row's swipe (iOS `trailingSwipe`). */
    const val CloseTerminal = "Close"

    const val Subtitle = "Agents, side chats & terminals"
    const val Close = "Close"
    const val AddSideChat = "Add side chat"
    const val NewTerminal = "New terminal"
    const val NewSideChatMenu = "New Side Chat"
    const val ForkThisChatMenu = "Fork This Chat"
    const val NoAgents = "No subagents yet"
    const val SideChatsEmpty = "Ask something on the side without derailing the main thread."
    const val TerminalsEmpty = "A shell on this session's computer, in its folder."
    const val Archive = "Archive"

    fun title(section: CompanionSection): String = when (section) {
        CompanionSection.Agents -> "Agents"
        CompanionSection.SideChats -> "Side chats"
        CompanionSection.Terminals -> "Terminals"
    }

    /** iOS `updateSheetTitle`. */
    fun subtitle(runningAgents: Int): String =
        if (runningAgents > 0) "$runningAgents agent${if (runningAgents == 1) "" else "s"} working" else Subtitle
}

/** The action rows (iOS `CompanionsViewController.Action`). */
enum class CompanionAction { NewSideChat, Fork, NewTerminal }

/** One row of the panel, in order (iOS's diffable snapshot items). */
sealed interface PanelItem {
    val key: String

    data class Header(val section: CompanionSection) : PanelItem {
        override val key: String get() = "header:$section"
    }

    data class Agent(val agent: SideChatsAgent) : PanelItem {
        override val key: String get() = "agent:${agent.id}"
    }

    data class SideChat(val vm: SessionRowVM) : PanelItem {
        override val key: String get() = "side:${vm.id}"
    }

    data class Action(val action: CompanionAction) : PanelItem {
        override val key: String get() = "action:$action"
    }

    data class Empty(val section: CompanionSection) : PanelItem {
        override val key: String get() = "empty:$section"
    }

    data class Terminal(val terminal: PanelTerminal) : PanelItem {
        override val key: String get() = "terminal:${terminal.id}"
    }
}

/** A terminal row's state word (iOS `CompanionCell.configure(terminal:)`). */
enum class TerminalRowState { Live, Reconnecting, Ended }

/**
 * One terminal row (#44), pure so the panel model stays testable: the
 * tab's name, "folder · host", and its state word.
 */
data class PanelTerminal(val id: String, val name: String, val detail: String, val state: TerminalRowState) {
    /** "Live" / "Reconnecting" / "Ended". */
    val trailing: String
        get() = when (state) {
            TerminalRowState.Live -> "Live"
            TerminalRowState.Reconnecting -> "Reconnecting"
            TerminalRowState.Ended -> "Ended"
        }

    val a11y: String get() = "$name, $trailing"
}

/**
 * The panel's rows (iOS `reload`): Agents only when there are any, live
 * first then newest; Side chats always — its rows, or the empty line with
 * New side chat and Fork this chat; Terminals always — the chat's
 * [terminals], or the empty line and New terminal (#44). Pure.
 */
fun panelItems(ui: SideChatsUi, terminals: List<PanelTerminal> = emptyList()): List<PanelItem> = buildList {
    val agents = ui.running + ui.finished
    if (agents.isNotEmpty()) {
        add(PanelItem.Header(CompanionSection.Agents))
        agents.forEach { add(PanelItem.Agent(it)) }
    }
    add(PanelItem.Header(CompanionSection.SideChats))
    if (ui.chats.isEmpty()) {
        add(PanelItem.Empty(CompanionSection.SideChats))
        add(PanelItem.Action(CompanionAction.NewSideChat))
        add(PanelItem.Action(CompanionAction.Fork))
    } else {
        ui.chats.forEach { add(PanelItem.SideChat(it)) }
    }
    add(PanelItem.Header(CompanionSection.Terminals))
    if (terminals.isEmpty()) {
        add(PanelItem.Empty(CompanionSection.Terminals))
        add(PanelItem.Action(CompanionAction.NewTerminal))
    } else {
        terminals.forEach { add(PanelItem.Terminal(it)) }
    }
}

/** What an agent row shows (iOS `CompanionCell.configure(agent:)`). Pure. */
data class AgentRowText(val detail: String, val trailing: String?, val a11y: String)

fun agentRowText(agent: SideChatsAgent, nowMs: Long, label: (String, String) -> String = { _, m -> m }): AgentRowText {
    val model = listOfNotNull(agent.model?.let { SubagentFormat.model(it, label) }, agent.kind).joinToString(" · ")
    val word = when (agent.state) {
        AgentState.Running -> "working"
        AgentState.Failed -> "failed"
        AgentState.Done -> "done"
    }
    return when (agent.state) {
        AgentState.Running -> AgentRowText(
            detail = agent.tail ?: model.ifEmpty { "Working" },
            trailing = formatElapsed(((nowMs - agent.spawnedAtMs).coerceAtLeast(0L) / 1000L).toInt()),
            a11y = "${agent.title}, $word",
        )
        AgentState.Done -> AgentRowText(model.ifEmpty { "Finished" }, null, "${agent.title}, $word")
        AgentState.Failed -> AgentRowText(if (model.isEmpty()) "Didn't finish" else "Failed · $model", null, "${agent.title}, $word")
    }
}

/** An action row's title (iOS `configure(action:busy:)`). */
fun actionTitle(action: CompanionAction, busy: Boolean): String = when (action) {
    CompanionAction.NewSideChat -> "New side chat"
    CompanionAction.Fork -> if (busy) "Forking…" else "Fork this chat"
    CompanionAction.NewTerminal -> "New terminal"
}

/**
 * Whether the companions panel shows, the section to scroll to once it
 * loads (a chip's), and the one action waiting for its dismissal (iOS
 * dismisses the sheet, then pushes what was picked). Main thread only.
 */
@Stable
class CompanionsPanelController {
    var showing by mutableStateOf(false)
        private set

    /** The section a chip asked for; cleared once scrolled to (iOS `initialSection`). */
    var initialSection: CompanionSection? by mutableStateOf(null)
        internal set

    /** Someone outside the sheet asked it to go (a fork landed). */
    internal var closeRequested by mutableStateOf(false)
        private set

    private var afterDismiss: (() -> Unit)? = null

    fun open(section: CompanionSection? = null) {
        initialSection = section
        closeRequested = false
        showing = true
    }

    /** Dismiss the sheet, then run [action] (iOS `dismiss { … }`). */
    fun closeThen(action: (() -> Unit)?) {
        if (!showing) {
            action?.invoke()
            return
        }
        afterDismiss = action
        closeRequested = true
    }

    /** `GlassSheet`'s `onDismiss`: the sheet reached `Hidden`. */
    internal fun onDismissed() {
        showing = false
        closeRequested = false
        val action = afterDismiss
        afterDismiss = null
        action?.invoke()
    }
}
