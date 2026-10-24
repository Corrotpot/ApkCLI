package dev.apkcli.core.agent

import android.util.Log
import dev.apkcli.core.llm.ToolDeclaration
import dev.apkcli.core.model.AgentDefinition
import dev.apkcli.core.model.ToolPermission
import dev.apkcli.core.nativebridge.NativeCore
import dev.apkcli.core.nativebridge.NativeException
import dev.apkcli.core.util.bool
import dev.apkcli.core.util.strings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The tool catalogue, and the single path through which tools are invoked.
 *
 * Everything that must happen around every tool call lives here rather than in
 * the tools: permission checks, approval prompts, timeouts, error shaping, and
 * spilling oversized output to the blob store. A tool that forgot one of those
 * would be a security or context-budget hole, so none of them get the chance.
 */
class ToolRegistry(tools: List<Tool>) {

    private val byName: Map<String, Tool> = tools.associateBy { it.name }

    val all: List<Tool> = tools.sortedBy { it.name }

    fun get(name: String): Tool? = byName[name]

    /**
     * Tools visible to [agent], as the model sees them.
     *
     * Denied tools are omitted entirely rather than declared and refused. A
     * declared-but-refused tool wastes tokens on its schema and invites the
     * model to keep trying it; an absent one makes it plan differently from the
     * start.
     */
    fun declarationsFor(agent: AgentDefinition): List<ToolDeclaration> = all
        .filter { agent.permissionFor(it.name) != ToolPermission.DENY }
        .map { ToolDeclaration(it.name, it.description, it.parameters) }

    private val declarationTokenCache = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /**
     * Token cost of the declarations, needed by the context budget.
     *
     * Cached per visible tool set. Computing it means serialising forty JSON
     * schemas and crossing JNI once per field, and the agent loop asks for it
     * on **every iteration** of every turn — so uncached it was several hundred
     * native calls per turn for a number that only changes when the agent's
     * permissions do.
     */
    fun declarationTokens(agent: AgentDefinition): Int {
        val declarations = declarationsFor(agent)
        val key = declarations.joinToString(",") { it.name }
        declarationTokenCache[key]?.let { return it }

        val total = declarations.sumOf {
            NativeCore.estimateTokens(it.name) +
                NativeCore.estimateTokens(it.description) +
                NativeCore.estimateTokens(it.parameters.toString()) +
                ContextBudget.TOOL_DEF_OVERHEAD
        }
        declarationTokenCache[key] = total
        return total
    }

    /**
     * Invoke a tool.
     *
     * Never throws. Every failure becomes a [ToolResult] with `isError`, because
     * an exception here would abort the agent's turn, discard the other tool
     * calls in the same batch, and leave the model with no way to recover. A
     * failed tool call that reports why is something the model can work with.
     */
    suspend fun execute(
        name: String,
        rawArguments: String,
        ctx: ToolContext,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): ToolResult {
        val tool = byName[name] ?: return ToolResult.refused(
            "No tool named '$name'. Available: ${
                declarationsFor(ctx.agent).joinToString(", ") { it.name }
            }",
        )

        when (ctx.agent.permissionFor(name)) {
            ToolPermission.DENY -> return ToolResult.refused(
                "The '$name' tool is not available to the ${ctx.agent.name} agent " +
                    "(${ctx.agent.autonomy.label}). Do not retry it — use a different " +
                    "approach, or tell the user which capability you need.",
            )

            ToolPermission.ASK -> Unit // handled below, after arguments parse
            ToolPermission.ALLOW -> Unit
        }

        val args = parseArguments(rawArguments)
            ?: return ToolResult.error(
                "Could not parse the arguments for '$name' as JSON. Received: " +
                    rawArguments.take(400) +
                    "\n\nSend a single JSON object matching the tool's schema.",
            )

        if (needsApproval(tool, ctx.agent)) {
            val approved = ctx.approvals.request(
                callId = ctx.toolCallId,
                toolName = name,
                summary = summarise(tool, args, ctx),
                // Whole and as written. A preview capped at 1 200 characters
                // with its whitespace squeezed stopped being JSON for a long
                // command, and the sheet then showed the 120-character summary:
                // the user approved a command they could not read to the end.
                detail = rawArguments,
                destructive = tool.destructive,
            )
            if (!approved) {
                return ToolResult.refused(
                    "The user declined this '$name' call. Do not repeat it. Either " +
                        "proceed differently or ask what they would prefer.",
                )
            }
        }

        // The deadline holds whatever the tool is doing. `admit` runs the tool
        // detached and only awaits it, so when the time is up this returns
        // even if the tool is stuck inside a call that ignores cancellation;
        // the tool is cancelled, and what it started (a command's process
        // group) is stopped as it unwinds.
        val limit = tool.timeoutMs ?: timeoutMs
        val started = AtomicBoolean(false)
        return try {
            withTimeout(limit) {
                ctx.governor.admit(tool.taskClass) {
                    started.set(true)
                    tool.execute(args, ctx)
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            ToolResult.error(
                if (started.get()) {
                    "'$name' did not finish within ${limit / 1000}s and was stopped, along with " +
                        "anything it started. For long-running commands use sh_background and " +
                        "read it with sh_job_output instead."
                } else {
                    "'$name' could not start within ${limit / 1000}s: other heavy work was still " +
                        "running. Retry once it has finished."
                },
            )
        } catch (cancelled: CancellationException) {
            // The turn was stopped: let it unwind. A cancellation from inside
            // the tool while the turn goes on is the tool's failure, and
            // rethrowing it would end the turn instead.
            if (!currentCoroutineContext().isActive) throw cancelled
            ToolResult.error("'$name' was cancelled: ${cancelled.message ?: "no reason given"}")
        } catch (refused: dev.apkcli.core.sched.LoadGovernor.RefusedByGovernor) {
            ToolResult.refused(
                "Declined: ${refused.message}. The device is under thermal or power " +
                    "pressure. Retry when it cools down, or ask the user to plug in.",
            )
        } catch (native: NativeException) {
            ToolResult.error(
                "'$name' failed in the native core (${native.kind}): ${native.detail}" +
                    if (native.retryable) "\nThis may be transient; one retry is reasonable." else "",
            )
        } catch (t: Throwable) {
            Log.w(TAG, "tool $name threw", t)
            ToolResult.error(
                "'$name' threw ${t.javaClass.simpleName}: ${t.message ?: "no message"}",
            )
        }
    }

    /**
     * Whether a call to [name] by [agent] waits for the user, or null for an
     * unknown tool. The chat asks this when the user switches agents while a
     * question is on screen: one the new agent would not ask is answered.
     */
    fun needsApproval(name: String, agent: AgentDefinition): Boolean? =
        byName[name]?.let { needsApproval(it, agent) }

    // Supervision is over changes: the user approves a file being written or a
    // command being run. A call that changes nothing goes ahead, unless the
    // user set that tool to ask. Asking about every read made a supervised
    // session a stream of taps, and a supervised subagent unable to look at
    // anything.
    //
    // The mode decides the rest, and only the mode: Confirm asks about every
    // change, From Rules about the kinds the user has not ticked, Bypass Mode
    // about nothing. A destructive call (a deletion, a revert) used to ask
    // under every mode; now From Rules asks it unless deletions are ticked,
    // and Bypass Mode is what its name says.
    private fun needsApproval(tool: Tool, agent: AgentDefinition): Boolean =
        agent.permissionFor(tool.name) == ToolPermission.ASK &&
            (tool.supervised || agent.toolPermissions[tool.name] == ToolPermission.ASK)

    /** Whether [rawArguments] read as the object [execute] needs, salvage included. */
    fun parses(rawArguments: String): Boolean = parseArguments(rawArguments) != null

    /**
     * Whether a call to [name] with [rawArguments] may run beside the other
     * calls of its batch: the tool's [Tool.parallelSafe], then what its
     * arguments say. Unknown tools and arguments that do not parse run alone.
     */
    fun parallelSafe(name: String, rawArguments: String): Boolean {
        val tool = byName[name] ?: return false
        if (!tool.parallelSafe) return false
        val args = parseArguments(rawArguments) ?: return false
        return tool.parallelSafe(args)
    }

    private fun parseArguments(raw: String): JsonObject? {
        val text = raw.trim()
        if (text.isEmpty()) return JsonObject(emptyMap())
        return runCatching {
            NativeCore.json.parseToJsonElement(text).jsonObject
        }.getOrElse {
            // Models occasionally wrap the object in a fenced code block or
            // emit a trailing comma. One salvage attempt is cheaper than a
            // round trip asking for valid JSON.
            val salvaged = text
                .removePrefix("```json").removePrefix("```").removeSuffix("```")
                .trim()
                .replace(Regex(",\\s*([}\\]])"), "$1")
            runCatching { NativeCore.json.parseToJsonElement(salvaged).jsonObject }.getOrNull()
        }
    }

    /**
     * One-line description of a pending call, for the approval prompt.
     *
     * Generic argument dumps make approval meaningless — nobody reads a JSON
     * blob forty times an hour. The handful of tools that get asked about most
     * get a purpose-written line naming the file or the command.
     */
    private fun summarise(tool: Tool, args: JsonObject, ctx: ToolContext): String {
        fun arg(key: String) = (args[key] as? kotlinx.serialization.json.JsonPrimitive)?.content
        fun packageNames(): String? = (args.strings("packages") + listOfNotNull(arg("package")))
            .distinct()
            .takeIf { it.isNotEmpty() }
            ?.joinToString(", ")
        return when (tool.name) {
            "fs_write" -> "Write ${arg("path") ?: "a file"}"
            "fs_edit", "fs_multi_edit" -> "Edit ${arg("path") ?: "a file"}"
            "fs_apply_patch" -> "Apply a patch to ${arg("path") ?: "a file"}"
            "fs_delete" -> "Delete ${arg("path") ?: "a path"}"
            "fs_move" -> "Move ${arg("from") ?: "?"} to ${arg("to") ?: "?"}"
            "sh_exec", "sh_background" -> "Run: ${arg("command")?.take(120) ?: "a command"}"
            "pkg_install" -> packageNames()?.let { "Install $it" }
                ?: if (args.bool("upgradeAll")) "Upgrade all installed packages" else "Install packages"
            "pkg_remove" -> "Remove ${packageNames() ?: "packages"}"
            "web_fetch" -> "Fetch ${arg("url") ?: "a URL"}"
            "build_run", "test_run" -> "Run ${tool.name.removeSuffix("_run")} in ${ctx.display(ctx.workspace)}"
            else -> "${tool.name}(${args.keys.joinToString(", ")})"
        }
    }

    companion object {
        private const val TAG = "ToolRegistry"

        /**
         * Default ceiling per tool call.
         *
         * Generous because a build legitimately takes minutes. Tools that know
         * better override it, and anything genuinely unbounded belongs in
         * `sh_background`.
         */
        const val DEFAULT_TIMEOUT_MS = 300_000L
    }
}
