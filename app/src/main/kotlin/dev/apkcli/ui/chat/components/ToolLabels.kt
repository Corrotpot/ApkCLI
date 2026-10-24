package dev.apkcli.ui.chat.components

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import dev.apkcli.R
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * How a tool call reads in the transcript: "Edit file · src/main.go" rather
 * than `fs_edit`. The tool names are the model's vocabulary; the card speaks
 * the user's.
 */
@StringRes
internal fun toolTitleRes(name: String): Int? = when (name) {
    "fs_read" -> R.string.tool_name_fs_read
    "fs_write" -> R.string.tool_name_fs_write
    "fs_edit", "fs_multi_edit" -> R.string.tool_name_fs_edit
    "fs_apply_patch" -> R.string.tool_name_fs_apply_patch
    "fs_delete" -> R.string.tool_name_fs_delete
    "fs_move" -> R.string.tool_name_fs_move
    "fs_list" -> R.string.tool_name_fs_list
    "fs_glob" -> R.string.tool_name_fs_glob
    "fs_stat" -> R.string.tool_name_fs_stat
    "project_tree" -> R.string.tool_name_project_tree
    "outline" -> R.string.tool_name_outline
    "grep" -> R.string.tool_name_grep
    "symbol_search" -> R.string.tool_name_symbol_search
    "revert_edit" -> R.string.tool_name_revert_edit
    "sh_exec" -> R.string.tool_name_sh_exec
    "sh_background" -> R.string.tool_name_sh_background
    "sh_job_output" -> R.string.tool_name_sh_job_output
    "sh_jobs" -> R.string.tool_name_sh_jobs
    "sh_job_kill" -> R.string.tool_name_sh_job_kill
    "build_run" -> R.string.tool_name_build_run
    "test_run" -> R.string.tool_name_test_run
    "env_info" -> R.string.tool_name_env_info
    "task_list" -> R.string.tool_name_task_list
    "subagent_dispatch" -> R.string.tool_name_subagent
    "editor_open" -> R.string.tool_name_editor_open
    "conversation_search" -> R.string.tool_name_conversation_search
    "conversation_read" -> R.string.tool_name_conversation_read
    "memory_read" -> R.string.tool_name_memory_read
    "memory_write" -> R.string.tool_name_memory_write
    "memory_forget" -> R.string.tool_name_memory_forget
    "web_fetch" -> R.string.tool_name_web_fetch
    "web_search" -> R.string.tool_name_web_search
    "pkg_install" -> R.string.tool_name_pkg_install
    "pkg_search" -> R.string.tool_name_pkg_search
    "pkg_remove" -> R.string.tool_name_pkg_remove
    else -> null
}

@Composable
internal fun toolTitle(name: String): String = toolTitleRes(name)?.let { stringResource(it) } ?: name

/**
 * What the call acted on, from its arguments: a path, a command, a query.
 * From the arguments rather than the result, so it is there while the call
 * runs and after the session is reopened.
 */
internal fun toolTarget(name: String, argumentsJson: String): String? {
    val args = runCatching { Json.parseToJsonElement(argumentsJson).jsonObject }.getOrNull() ?: return null
    fun str(key: String): String? = (args[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    fun list(key: String): String? = (args[key] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        ?.takeIf { it.isNotEmpty() }
        ?.joinToString(", ")

    val target = when (name) {
        "fs_move" -> listOfNotNull(str("from"), str("to")).takeIf { it.size == 2 }?.joinToString(" → ")
        "sh_exec", "sh_background", "build_run", "test_run" -> str("command")?.let { "$ $it" }
        "pkg_install", "pkg_remove" -> list("packages") ?: str("package")
        "subagent_dispatch" -> str("prompt")
        "memory_write", "memory_read", "memory_forget" -> str("key")
        "conversation_read" -> str("blobId") ?: str("fromSeq")?.let { "#$it" }
        "sh_job_output", "sh_job_kill" -> str("id")
        else -> null
    } ?: TARGET_KEYS.firstNotNullOfOrNull { key -> str(key) ?: list(key) }

    // One line: a multi-line heredoc command is summarised by its first line.
    return target?.lineSequence()?.firstOrNull()?.trim()?.take(200)
}

private val TARGET_KEYS = listOf("path", "query", "name", "url", "pattern", "command", "paths", "packages")

/**
 * [toolTarget] from arguments still being written: the first complete string
 * value under the call's main key in [head], the arguments' beginning. Null
 * until one has arrived whole.
 */
internal fun toolTargetPartial(name: String, head: String): String? {
    val keys = when (name) {
        "fs_move" -> listOf("from")
        "sh_exec", "sh_background", "build_run", "test_run" -> listOf("command")
        "subagent_dispatch" -> listOf("prompt")
        else -> TARGET_KEYS
    }
    for (key in keys) {
        val match = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(head) ?: continue
        val value = runCatching { Json.decodeFromString<String>("\"${match.groupValues[1]}\"") }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: continue
        val shown = if (key == "command") "$ $value" else value
        return shown.lineSequence().firstOrNull()?.trim()?.take(200)
    }
    return null
}

/**
 * String [key] of arguments still being written, as far as it has come:
 * [head] is the arguments' beginning, so the value may stop midway, even in
 * an escape, which is then left out. Null until the key has arrived. A
 * subagent's task shows as the model writes it.
 */
internal fun partialStringValue(head: String, key: String): String? {
    val opening = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"").find(head) ?: return null
    val out = StringBuilder()
    var i = opening.range.last + 1
    while (i < head.length) {
        val c = head[i]
        if (c == '"') break
        if (c != '\\') {
            out.append(c)
            i++
            continue
        }
        if (i + 1 >= head.length) break
        when (val escaped = head[i + 1]) {
            'n' -> out.append('\n')
            't' -> out.append('\t')
            'r' -> Unit
            'b', 'f' -> Unit
            'u' -> {
                if (i + 6 > head.length) break
                head.substring(i + 2, i + 6).toIntOrNull(16)?.let { out.append(it.toChar()) }
                i += 4
            }
            else -> out.append(escaped)
        }
        i += 2
    }
    return out.toString()
}

/** "4,6 с" / "4.6 s": seconds with one decimal in the user's own notation. */
@Composable
internal fun durationText(ms: Long): String {
    val locale = LocalConfiguration.current.locales[0]
    return when {
        ms < 1_000 -> stringResource(R.string.duration_ms, ms)
        ms < 60_000 -> stringResource(R.string.duration_s, String.format(locale, "%.1f", ms / 1000.0))
        else -> stringResource(R.string.duration_min, (ms / 60_000).toInt(), ((ms % 60_000) / 1000).toInt())
    }
}
