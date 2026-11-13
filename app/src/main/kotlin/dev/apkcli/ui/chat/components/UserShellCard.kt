package dev.apkcli.ui.chat.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.apkcli.core.agent.ToolDisplay
import dev.apkcli.core.model.ToolCallStatus
import dev.apkcli.core.model.UserShell
import dev.apkcli.core.runtime.TerminalBuffer
import dev.apkcli.ui.chat.ChatViewModel
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The name the cards know a command the user ran (`!`) by, for its title and
 * glyph (ToolLabels, toolIcon). No tool has it.
 */
internal const val USER_SHELL = "user_shell"

/**
 * A command the user ran with `!`, drawn as the agent's commands are: the
 * line, how it ended, and what it printed. Open from the start: its output is
 * what the user asked to see, where an agent's call is one of dozens.
 */
@Composable
fun UserShellCard(id: String, run: UserShell.Run, modifier: Modifier = Modifier) {
    val call = remember(id, run) {
        ChatViewModel.LiveToolCall(
            callId = id,
            name = USER_SHELL,
            status = when {
                run.stopped -> ToolCallStatus.ABORTED
                run.succeeded -> ToolCallStatus.OK
                else -> ToolCallStatus.ERROR
            },
            display = ToolDisplay.Command(
                command = run.command,
                exitCode = run.exitCode,
                output = run.output,
                durationMs = run.durationMs,
                timedOut = run.timedOut,
            ),
            durationMs = run.durationMs,
            isError = !run.succeeded && !run.stopped,
            argumentsJson = commandArguments(run.command),
        )
    }
    ToolCallCard(call, modifier, initiallyExpanded = true)
}

/** A `!` command still running: its terminal's last [rows], and a clock from [since]. */
@Composable
fun RunningShellCard(id: String, command: String, since: Long, rows: List<TerminalBuffer.Row>, modifier: Modifier = Modifier) {
    val arguments = remember(command) { commandArguments(command) }
    ToolCallCard(
        ChatViewModel.LiveToolCall(
            callId = id,
            name = USER_SHELL,
            status = ToolCallStatus.RUNNING,
            argumentsJson = arguments,
            liveRows = rows,
            runningSince = since,
        ),
        modifier,
    )
}

private fun commandArguments(command: String): String = buildJsonObject { put("command", command) }.toString()
