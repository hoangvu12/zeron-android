package sh.zeron.android.terminal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import sh.zeron.android.ZeronApplication
import sh.zeron.android.companions.PanelTerminal
import sh.zeron.android.composer.DraftKey
import sh.zeron.android.companions.TerminalRowState
import sh.zeron.android.shell.NavRoute
import uniffi.zeron_core.CoreException
import uniffi.zeron_core.TerminalPhase

/**
 * A chat's terminal page (#44, iOS `TerminalViewController`): pushed over
 * the session (the phone stack, or the split's detail column, which
 * renders the same route table), or into the inspector over the chat's
 * panel (#45). [terminalId] picks a tab (a panel row); `null` opens a new
 * shell, unless the caller passed the chat's live one. [script] is the
 * `terminal-demo` launch extra's lines (typed once the shell is up).
 */
class TerminalRoute(
    val chatId: String,
    val terminalId: String?,
    internal val script: List<String> = emptyList(),
) : NavRoute {
    /** iOS `hidesBottomBarWhenPushed = true`. */
    override val hidesBottomChrome: Boolean get() = true
    override val paneTitle: String get() = TerminalCopy.Terminal

    @Composable
    fun Content() {
        TerminalScreen(this)
    }
}

/** The page's copy, verbatim from `TerminalViewController.swift` / `TerminalKeyBar.swift`. */
object TerminalCopy {
    const val Terminal = "Terminal"
    const val NewTerminal = "New terminal"
    const val NewTerminalMenu = "New Terminal"
    const val ShowKeyboard = "Show keyboard"
    const val HideKeyboard = "Hide keyboard"
    const val TextSize = "Text Size"
    const val Smaller = "Smaller"
    const val Reset = "Reset"
    const val Bigger = "Bigger"
    const val AddOutput = "Add Output to Message"
    const val CopyAll = "Copy All Output"
    const val ClearScreen = "Clear Screen"
    const val CloseTerminal = "Close Terminal"
    const val Copied = "Copied"
    const val AddedToMessage = "Added to message"
    const val Reconnecting = "Reconnecting…"
    const val TryAgain = "Try Again"
    const val NewTerminalAction = "New Terminal"
    const val EndedOnHost = "This terminal ended on its host"
    const val SwitchTerminal = "Switch terminal"
    const val Ended = " · ended"

    /** `Ended by SIG`, `Process exited`, `Exited with code N`. */
    fun exited(phase: TerminalPhase.Exited): String =
        phase.signal?.let { "Ended by $it" } ?: if (phase.code == 0) "Process exited" else "Exited with code ${phase.code}"

    /** iOS `describe(_:)`. */
    fun openFailed(e: Throwable): String = when (e) {
        is CoreException.Unsupported -> "This computer needs a newer Zeron for terminals"
        is CoreException.HostUnavailable -> "The computer isn't reachable right now"
        else -> "Couldn't open a terminal"
    }
}

/** The `terminal-demo` launch extra's script (iOS `SessionViewController.swift:468`). */
val TerminalDemoScript = listOf("git log --oneline -5", "cargo test -p zeron-veil")

/**
 * Terminal output into a message (iOS `addToMessage`): the text trimmed of
 * leading/trailing newlines as a fenced block the agent can read, after
 * whatever is there (a newline between). Null when there is nothing to add.
 */
fun terminalOutputBlock(text: String): String? {
    val trimmed = text.trim('\n')
    if (trimmed.isEmpty()) return null
    return "```\n$trimmed\n```\n"
}

/** [existing] with [block] appended (iOS `draft.isEmpty ? block : draft + "\n" + block`). */
fun appendBlock(existing: String, block: String): String = if (existing.isEmpty()) block else existing + "\n" + block

/**
 * The chats whose session is on screen, each with a way into its composer
 * (iOS `SessionViewController.live(chatId)` / `appendToComposer`). A
 * session route registers while composed; a second copy of a chat stacks
 * on top and the newest wins. Main thread.
 */
object LiveComposers {
    private val live = mutableMapOf<String, MutableList<(String) -> Unit>>()

    /** [append] puts text into [chatId]'s message; close the result when the session leaves. */
    fun register(chatId: String, append: (String) -> Unit): AutoCloseable {
        live.getOrPut(chatId) { mutableListOf() }.add(append)
        return AutoCloseable {
            live[chatId]?.let { list ->
                list.remove(append)
                if (list.isEmpty()) live.remove(chatId)
            }
        }
    }

    /** Appends to [chatId]'s live composer; false when no session of it is up. */
    fun append(chatId: String, text: String): Boolean {
        val target = live[chatId]?.lastOrNull() ?: return false
        target(text)
        return true
    }
}

/**
 * iOS `addToMessage`'s destination: the chat's live composer, else its
 * saved draft (keyed by the chat's host, which is the terminal's).
 */
fun ZeronApplication.appendToMessage(chatId: String, hostDeviceId: String, block: String) {
    if (LiveComposers.append(chatId, block)) return
    val key = DraftKey.Chat(hostDeviceId, chatId)
    drafts.save(key, appendBlock(drafts.peek(key), block))
}

/** The chat's tabs, re-read whenever the store reports a change for [chatId] (tabs come, go, or change phase). */
@Composable
fun rememberChatTerminals(chatId: String): List<TerminalTab> {
    val app = LocalContext.current.applicationContext as? ZeronApplication ?: return emptyList()
    val store = app.terminals
    var revision by remember { mutableIntStateOf(0) }
    LaunchedEffect(store, chatId) { store.changes.collect { if (it == chatId) revision++ } }
    val client = app.clientOrNull()
    return remember(store, chatId, revision, client) { store.tabs(chatId) }
}

/** The live tab iOS `openTerminal` reopens (the first live one), or null for a new shell. */
fun List<TerminalTab>.liveTerminalId(): String? = firstOrNull { it.isLive }?.ref?.id

/** A device's display name (iOS `app.deviceName`), or "Computer". */
fun ZeronApplication.deviceName(deviceId: String): String =
    workspaceFeed.devices.value.firstOrNull { it.id == deviceId }?.name ?: "Computer"

/** The panel row for a tab (iOS `CompanionCell.configure(terminal:host:)`). */
fun TerminalTab.panelRow(host: String): PanelTerminal = PanelTerminal(
    id = ref.id,
    name = name,
    detail = "$folder · $host",
    state = when (phase) {
        is TerminalPhase.Live, is TerminalPhase.Connecting -> TerminalRowState.Live
        is TerminalPhase.Reconnecting -> TerminalRowState.Reconnecting
        else -> TerminalRowState.Ended
    },
)
