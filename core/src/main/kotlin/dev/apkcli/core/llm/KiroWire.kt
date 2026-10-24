package dev.apkcli.core.llm

import dev.apkcli.core.model.Block
import dev.apkcli.core.model.Message
import dev.apkcli.core.model.ModelSpec
import dev.apkcli.core.model.PromptCaching
import dev.apkcli.core.model.ReasoningLevels
import dev.apkcli.core.model.ReasoningSupport
import dev.apkcli.core.model.Role
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.UUID

/**
 * The shape of Kiro's `generateAssistantResponse`, as pure functions: the
 * request body, the model list, the stream's events, the errors.
 *
 * Kiro is Amazon Q Developer underneath, and nothing here is documented by
 * AWS for third-party use. The shape is Kiro Account Manager's
 * (github.com/chaogei/Kiro-account-manager), which builds its requests the
 * way Kiro IDE does; it is AGPL, so this is written from its facts, not its
 * code (docs/PROVIDERS.md). The rules that decide whether a request is
 * accepted at all:
 *
 *  * Turns alternate, user first, and the last one is the current message,
 *    outside `history`. Two user turns in a row are one turn.
 *  * Every tool call in an assistant turn is answered, one to one and by id,
 *    in the user turn right after it, and names a tool that is declared.
 *    A pair that does not match goes in as text instead of being dropped.
 *  * Tools are declared on the current message only.
 *  * No turn is empty, and there is no top-level system prompt: a body that
 *    carries one is refused as malformed. The system prompt is a user turn
 *    of its own at the head of the history, answered "I will follow these
 *    instructions.", as Kiro IDE sends it.
 *  * Reasoning is not sent back: `reasoningContent` in the history is
 *    refused as malformed.
 */
object KiroWire {

    /* ---------------------------------------------------------- the request */

    data class Body(
        val json: JsonObject,
        /** Wire tool name → the app's own, for tool calls coming back. */
        val toolNames: Map<String, String>,
    )

    fun body(
        request: CompletionRequest,
        profileArn: String?,
        conversationId: String,
        /** New for every request, as Kiro IDE makes one. */
        continuationId: String = UUID.randomUUID().toString(),
    ): Body {
        val modelId = request.model.id
        val (specs, toWire) = toolSpecs(request.tools.takeIf { request.model.supportsTools }.orEmpty())
        val turns = canonical(turns(request.messages), toWire)
        val current = turns.last()

        val past = turns.dropLast(1)

        // The prompt in its two parts (SystemPrompt.Parts). The stable one is
        // the system turn, the same on every request of the session, which is
        // what the service's cache can reuse; the part that changes every
        // request — the window's fill, the open files, the terminals — goes
        // with the current message, after everything a cached prefix covers.
        // Left in the system turn, it made every request cost the cold price
        // (KiroPromptCache).
        val split = request.systemStableLength.takeIf { it in 0..request.system.length } ?: request.system.length
        val stableSystem = request.system.substring(0, split).trim()
        val volatileSystem = request.system.substring(split).trim()

        // Where the prompt cache points go, if this model has one at all.
        val cache = KiroPromptCache.plan(
            model = request.model,
            systemChars = stableSystem.length,
            historyChars = past.sumOf { it.text.length },
            historyTurns = past.size,
        )
        val lastUserTurn = past.indexOfLast { it.user }

        val history = buildJsonArray {
            // The system prompt the way Kiro IDE sends it: a turn of its own at
            // the head of the history, answered, since the body has no field
            // for one.
            if (stableSystem.isNotBlank()) {
                addJsonObject {
                    putJsonObject("userInputMessage") {
                        put("content", stableSystem)
                        put("modelId", modelId)
                        put("origin", ORIGIN)
                        putJsonObject("userInputMessageContext") {}
                        if (cache.system) putJsonObject("cachePoint") { put("type", CACHE_POINT) }
                    }
                }
                addJsonObject { putJsonObject("assistantResponseMessage") { put("content", SYSTEM_ANSWER) } }
            }
            past.forEachIndexed { index, turn ->
                if (turn.user) {
                    val point = cache.lastHistoryTurn && index == lastUserTurn
                    addJsonObject { put("userInputMessage", turn.userJson(modelId, tools = null, cachePoint = point)) }
                } else {
                    addJsonObject { put("assistantResponseMessage", turn.assistantJson()) }
                }
            }
        }
        val json = buildJsonObject {
            putJsonObject("conversationState") {
                put("agentContinuationId", continuationId)
                put("agentTaskType", AGENT_TASK_TYPE)
                put("chatTriggerType", "MANUAL")
                put("conversationId", conversationId)
                putJsonObject("currentMessage") {
                    put(
                        "userInputMessage",
                        current.userJson(modelId, tools = specs, preamble = volatileSystem.takeIf { it.isNotBlank() }?.let(::appContext)),
                    )
                }
                if (history.isNotEmpty()) put("history", history)
            }
            if (!profileArn.isNullOrBlank()) put("profileArn", profileArn)
            effortFields(request.model, request.reasoningEffort)?.let { put("additionalModelRequestFields", it) }
            putJsonObject("inferenceConfig") {
                put("maxTokens", request.maxOutputTokens.coerceIn(1, MAX_OUTPUT_TOKENS))
                request.temperature?.let { put("temperature", it) }
                request.topP?.let { put("topP", it) }
            }
        }
        return Body(json, toWire.entries.associate { (app, wire) -> wire to app })
    }

    /** One turn on the way to the wire. Mutable while it is being shaped. */
    internal class Turn(val user: Boolean) {
        val text = StringBuilder()
        val images = mutableListOf<Block.Image>()
        val calls = mutableListOf<Call>()
        val results = mutableListOf<Result>()

        fun append(more: String) {
            if (more.isBlank()) return
            if (text.isNotEmpty()) text.append("\n\n")
            text.append(more.trim())
        }

        /**
         * The turn as a `userInputMessage`. [preamble] goes before its text:
         * the per-request part of the system prompt, on the current message.
         */
        fun userJson(modelId: String, tools: JsonArray?, cachePoint: Boolean = false, preamble: String? = null): JsonObject = buildJsonObject {
            val words = text.toString().ifBlank { emptyUserText() }
            put("content", if (preamble == null) words else "$preamble\n\n$words")
            put("modelId", modelId)
            put("origin", ORIGIN)
            // Everything before this turn may be reused next time; see KiroPromptCache.
            if (cachePoint) putJsonObject("cachePoint") { put("type", CACHE_POINT) }
            if (images.isNotEmpty()) {
                putJsonArray("images") {
                    images.forEach { image ->
                        addJsonObject {
                            put("format", image.mimeType.substringAfter('/').substringBefore(';').lowercase().ifBlank { "png" })
                            putJsonObject("source") { put("bytes", image.data) }
                        }
                    }
                }
            }
            val withTools = tools != null && tools.isNotEmpty()
            if (results.isNotEmpty() || withTools) {
                putJsonObject("userInputMessageContext") {
                    if (results.isNotEmpty()) {
                        putJsonArray("toolResults") {
                            results.forEach { result ->
                                addJsonObject {
                                    put("toolUseId", result.id)
                                    // Always "success", as Kiro Account Manager sends it; a
                                    // failure says so in its text instead.
                                    put("status", "success")
                                    val body = result.text.ifBlank { "(no output)" }
                                    putJsonArray("content") { addJsonObject { put("text", if (result.error) "Error: $body" else body) } }
                                }
                            }
                        }
                    }
                    tools?.takeIf { it.isNotEmpty() }?.let { put("tools", it) }
                }
            }
        }

        fun assistantJson(): JsonObject = buildJsonObject {
            // Never empty: a space beside tool calls, a word without them.
            put("content", text.toString().ifBlank { if (calls.isNotEmpty()) " " else "I understand." })
            if (calls.isNotEmpty()) {
                putJsonArray("toolUses") {
                    calls.forEach { call ->
                        addJsonObject {
                            put("toolUseId", call.id)
                            put("name", call.name)
                            put("input", call.input ?: JsonObject(emptyMap()))
                        }
                    }
                }
            }
        }

        private fun emptyUserText(): String = if (results.isNotEmpty()) "Tool results provided." else "Continue"
    }

    internal class Call(var id: String, val name: String, val input: JsonObject?, val rawArguments: String)

    internal class Result(var id: String, val name: String, val text: String, val error: Boolean) {
        /** Claimed by the call it answers; see [canonical]. */
        var paired = false
    }

    /** The transcript as user and assistant turns, same-role neighbours merged. */
    internal fun turns(messages: List<Message>): MutableList<Turn> {
        val turns = mutableListOf<Turn>()
        fun turn(user: Boolean): Turn =
            turns.lastOrNull()?.takeIf { it.user == user } ?: Turn(user).also { turns += it }

        for (message in messages) {
            when (message.role) {
                Role.ASSISTANT -> {
                    val calls = message.toolUses
                    if (message.text.isBlank() && calls.isEmpty()) continue
                    val turn = turn(user = false)
                    turn.append(message.text)
                    calls.forEach { use ->
                        val arguments = use.argumentsJson.ifBlank { "{}" }
                        turn.calls += Call(use.callId, use.name, Http.parseObject(arguments), arguments)
                    }
                }
                Role.TOOL -> {
                    val turn = turn(user = true)
                    message.toolResults.forEach { result ->
                        turn.results += Result(result.callId, result.name, result.content, result.isError)
                    }
                }
                Role.DIGEST, Role.SYSTEM -> turn(user = true).append("[context]\n${message.text}")
                Role.USER -> {
                    val turn = turn(user = true)
                    turn.append(message.text)
                    turn.images += message.content.filterIsInstance<Block.Image>()
                }
            }
        }
        return turns
    }

    /**
     * Make [turns] acceptable: user first and last, every call answered next
     * door by id, valid unique ids, only declared tools. What cannot be kept
     * structured is kept as text, so the model still sees what happened.
     */
    internal fun canonical(turns: MutableList<Turn>, toWire: Map<String, String>): List<Turn> {
        if (turns.firstOrNull()?.user != true) turns.add(0, Turn(user = true).apply { append("(continuing a previous session)") })
        if (turns.last().user.not()) turns += Turn(user = true).apply { append("Continue.") }

        val usedIds = HashSet<String>()
        turns.forEachIndexed { index, turn ->
            if (turn.user) {
                // The calls right before this turn have claimed their results
                // by now; whatever is left answers nothing.
                val orphans = turn.results.filterNot { it.paired }
                orphans.forEach { turn.append(resultText(it)) }
                turn.results.removeAll(orphans)
                return@forEachIndexed
            }
            val next = turns[index + 1]
            // Each result answers one call, even when a provider repeated an id.
            val unanswered = next.results.toMutableList()
            val kept = mutableListOf<Call>()
            for (call in turn.calls) {
                val wireName = toWire[call.name]
                val result = unanswered.firstOrNull { it.id == call.id }?.also { unanswered.remove(it) }
                if (wireName == null || result == null || call.input == null) {
                    turn.append(callText(call))
                    if (result != null) {
                        next.results.remove(result)
                        next.append(resultText(result))
                    }
                    continue
                }
                val id = uniqueId(call.id, usedIds)
                result.id = id
                result.paired = true
                kept += Call(id, wireName, call.input, call.rawArguments)
            }
            turn.calls.clear()
            turn.calls += kept
        }
        return turns
    }

    private fun callText(call: Call) = "[Tool call: ${call.name}(${clipped(call.rawArguments)})]"

    private fun resultText(result: Result) =
        "[Tool result${if (result.error) " (error)" else ""} for ${result.name}: ${clipped(result.text)}]"

    /** [text] up to [TEXT_CAP], saying how much was left out rather than ending mid-word as if whole. */
    private fun clipped(text: String): String =
        if (text.length <= TEXT_CAP) text else text.take(TEXT_CAP) + "… [${text.length - TEXT_CAP} more characters not shown]"

    private fun uniqueId(raw: String, used: MutableSet<String>): String {
        val base = raw.filter { it.isLetterOrDigit() && it.code < 128 || it == '_' || it == '-' }
            .take(ID_MAX).ifEmpty { "tooluse_${used.size + 1}" }
        var id = base
        var n = 2
        while (!used.add(id)) {
            val tail = "_${n++}"
            id = base.take(ID_MAX - tail.length) + tail
        }
        return id
    }

    /**
     * Tool declarations in Kiro's shape, and the app name → wire name map.
     * Names are limited to letters, digits, `_` and `-`, 64 long; schemas
     * lose `additionalProperties` and empty `required`, which it refuses.
     */
    internal fun toolSpecs(tools: List<ToolDeclaration>): Pair<JsonArray, Map<String, String>> {
        val toWire = LinkedHashMap<String, String>()
        val used = HashSet<String>()
        val specs = buildJsonArray {
            tools.forEachIndexed { index, tool ->
                if (tool.name in toWire) return@forEachIndexed
                val cleaned = tool.name.map { if (it.isLetterOrDigit() && it.code < 128 || it == '_' || it == '-') it else '_' }
                    .joinToString("").trim('_').take(NAME_MAX).ifEmpty { "tool_${index + 1}" }
                var name = cleaned
                var n = 2
                while (!used.add(name)) {
                    val tail = "_${n++}"
                    name = cleaned.take(NAME_MAX - tail.length) + tail
                }
                toWire[tool.name] = name
                addJsonObject {
                    putJsonObject("toolSpecification") {
                        put("name", name)
                        put("description", tool.description.ifBlank { "Tool: ${tool.name}" }.take(DESCRIPTION_MAX))
                        putJsonObject("inputSchema") { put("json", rootSchema(tool.parameters)) }
                    }
                }
            }
        }
        return specs to toWire
    }

    private fun rootSchema(schema: JsonObject): JsonObject {
        val cleaned = clean(schema) as? JsonObject ?: JsonObject(emptyMap())
        val properties = cleaned["properties"] as? JsonObject ?: JsonObject(emptyMap())
        val required = (cleaned["required"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.filter { it in properties }
            ?.distinct()
            .orEmpty()
        return JsonObject(
            cleaned.toMutableMap().apply {
                put("type", JsonPrimitive("object"))
                put("properties", properties)
                if (required.isEmpty()) remove("required") else put("required", JsonArray(required.map(::JsonPrimitive)))
            },
        )
    }

    private fun clean(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.filter { (key, value) ->
                key != "additionalProperties" && !(key == "required" && value is JsonArray && value.isEmpty())
            }.mapValues { (_, value) -> clean(value) },
        )
        is JsonArray -> JsonArray(element.map(::clean))
        else -> element
    }

    /* --------------------------------------------------------- reasoning */

    /**
     * The reasoning fields for [effort] on [model], or null to send none.
     *
     * Two shapes, as Kiro IDE maps them and as the listing's
     * `additionalModelRequestFieldsSchema` says which a model takes:
     * `output_config` (Claude from 4.6: adaptive thinking, summarised, with
     * an effort; [ReasoningSupport.ADAPTIVE]) or `reasoning` (an effort
     * alone; [ReasoningSupport.EFFORT]). "None" is sending nothing, and a
     * level the model does not list becomes its highest, as the manager does.
     */
    fun effortFields(model: ModelSpec, effort: String?): JsonObject? {
        if (effort == null || effort == ReasoningLevels.NONE) return null
        val levels = model.reasoningEfforts.filter { it != ReasoningLevels.NONE }
        val level = if (levels.isEmpty() || effort in levels) effort else levels.last()
        return when (shape(model)) {
            Family.CLAUDE_EFFORT -> buildJsonObject {
                putJsonObject("thinking") {
                    put("type", "adaptive")
                    put("display", "summarized")
                }
                putJsonObject("output_config") { put("effort", level) }
            }
            Family.GPT_EFFORT -> buildJsonObject {
                putJsonObject("reasoning") { put("effort", level) }
            }
            Family.NONE -> null
        }
    }

    /**
     * Which shape a model's reasoning takes. A model listed before the shape
     * was read from the schema says EFFORT for Claude too; Claude never takes
     * the bare `reasoning` field, so its family decides.
     */
    private fun shape(model: ModelSpec): Family = when (model.reasoning) {
        ReasoningSupport.ADAPTIVE -> Family.CLAUDE_EFFORT
        ReasoningSupport.EFFORT -> if (family(model.id) == Family.CLAUDE_EFFORT) Family.CLAUDE_EFFORT else Family.GPT_EFFORT
        else -> Family.NONE
    }

    /**
     * How a listed model reasons: from `additionalModelRequestFieldsSchema`
     * when the listing carries one (`output_config.effort.enum` or
     * `reasoning.effort.enum`), else from an effort enum anywhere in the
     * entry, else the family's documented tiers. 4.6 Claude models stop at
     * `max` without `xhigh`, which the service refuses on them.
     */
    internal fun reasoningOf(modelId: String, entry: JsonObject? = null): Pair<ReasoningSupport, List<String>> {
        val properties = (entry?.get("additionalModelRequestFieldsSchema") as? JsonObject)?.get("properties") as? JsonObject
        schemaEfforts(properties, "output_config")?.let { return ReasoningSupport.ADAPTIVE to levelsOf(it) }
        schemaEfforts(properties, "reasoning")?.let { return ReasoningSupport.EFFORT to levelsOf(it) }
        val family = family(modelId)
        entry?.let(::publishedEfforts)?.takeIf { it.isNotEmpty() }?.let { published ->
            return (if (family == Family.CLAUDE_EFFORT) ReasoningSupport.ADAPTIVE else ReasoningSupport.EFFORT) to levelsOf(published)
        }
        return when (family) {
            Family.CLAUDE_EFFORT -> ReasoningSupport.ADAPTIVE to if (claudeVersion(modelId)?.let { it.first == 4 && (it.second ?: 0) <= 6 } == true) {
                listOf("none", "low", "medium", "high", "max")
            } else {
                listOf("none", "low", "medium", "high", "xhigh", "max")
            }
            Family.GPT_EFFORT -> ReasoningSupport.EFFORT to listOf("none", "low", "medium", "high", "xhigh")
            Family.NONE -> ReasoningSupport.NONE to emptyList()
        }
    }

    /** The levels of a schema's `<field>.properties.effort.enum`, or null when it has none. */
    private fun schemaEfforts(properties: JsonObject?, field: String): List<String>? {
        val effort = ((properties?.get(field) as? JsonObject)?.get("properties") as? JsonObject)?.get("effort") as? JsonObject
        return (effort?.get("enum") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.lowercase() }
            ?.takeIf { it.isNotEmpty() }
    }

    private fun levelsOf(published: List<String>): List<String> = ReasoningLevels.normalize(listOf("none") + published)

    private enum class Family { CLAUDE_EFFORT, GPT_EFFORT, NONE }

    private fun family(modelId: String): Family {
        val id = modelId.lowercase().replace('-', '.')
        if (Regex("""(^|[/.])gpt[/.]5[/.]6([/.]|$)""").containsMatchIn(id)) return Family.GPT_EFFORT
        val version = claudeVersion(modelId) ?: return Family.NONE
        val (major, minor) = version
        // Dated ids (claude-sonnet-4-20250514) are the old generation.
        val dated = minor != null && minor >= 1000
        return if (major > 4 || (major == 4 && minor != null && minor >= 6 && !dated)) Family.CLAUDE_EFFORT else Family.NONE
    }

    private fun claudeVersion(modelId: String): Pair<Int, Int?>? {
        val id = modelId.lowercase().replace('-', '.')
        val match = Regex("""(?:^|[/.])claude(?:[/.][a-z]+)*[/.](\d+)(?:[/.](\d+))?(?:[/.]|$)""").find(id) ?: return null
        return match.groupValues[1].toInt() to match.groupValues[2].takeIf { it.isNotEmpty() }?.toInt()
    }

    /** Effort values anywhere in a listing entry: an `effort` property with an `enum`. */
    private fun publishedEfforts(element: JsonElement): List<String> = when (element) {
        is JsonObject -> {
            val direct = (element["effort"] as? JsonObject)?.get("enum") as? JsonArray
            direct?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?: element.values.flatMap(::publishedEfforts)
        }
        is JsonArray -> element.flatMap(::publishedEfforts)
        else -> emptyList()
    }

    /* -------------------------------------------------------- the models */

    /**
     * `ListAvailableModels`: `{ models: [{ modelId, modelName, description,
     * rateMultiplier, supportedInputTypes, tokenLimits: { maxInputTokens,
     * maxOutputTokens }, additionalModelRequestFieldsSchema }] }`. The window
     * is the service's own number; the output cap is its own when published,
     * otherwise what the request sends at most. How a model reasons comes
     * from its request-fields schema ([reasoningOf]).
     */
    fun models(response: JsonObject): List<ModelSpec> {
        val entries = response["models"] as? JsonArray ?: return emptyList()
        return entries.mapNotNull { element ->
            val entry = element as? JsonObject ?: return@mapNotNull null
            val id = entry.string("modelId") ?: entry.string("id") ?: return@mapNotNull null
            val limits = entry["tokenLimits"] as? JsonObject
            val input = limits?.int("maxInputTokens")
            val output = limits?.int("maxOutputTokens")
            val (support, levels) = reasoningOf(id, entry)
            val caching = cachingOf(entry)
            val rate = (entry["rateMultiplier"] as? JsonPrimitive)?.doubleOrNull
            // The listing's input limit is the prompt budget; the window is
            // that plus the answer a request asks room for.
            val answer = (output ?: MAX_OUTPUT_TOKENS).coerceAtMost(MAX_OUTPUT_TOKENS)
            ModelSpec(
                id = id,
                displayName = entry.string("modelName")?.takeIf { it.isNotBlank() } ?: id,
                contextWindow = (input ?: DEFAULT_WINDOW) + answer,
                maxOutputTokens = answer,
                supportsTools = true,
                supportsImages = acceptsImages(id, entry),
                reasoning = if (levels.isEmpty()) ReasoningSupport.NONE else support,
                reasoningEfforts = levels,
                caching = caching,
                metadataFromEndpoint = input != null,
                notes = listOfNotNull(
                    entry.string("description")?.takeIf { it.isNotBlank() },
                    rate?.takeIf { kotlin.math.abs(it - 1.0) > 1e-9 && it > 0 }?.let { "×${trim(it)} credits" },
                ).joinToString(" · "),
            )
        }
    }

    /**
     * `promptCaching: { supportsPromptCaching, maximumCacheCheckpointsPerRequest,
     * minimumTokensPerCacheCheckpoint }`, or null when the entry does not say
     * the model caches: a cache point it does not take is not sent.
     */
    private fun cachingOf(entry: JsonObject): PromptCaching? {
        val reported = entry["promptCaching"] as? JsonObject ?: return null
        if ((reported["supportsPromptCaching"] as? JsonPrimitive)?.booleanOrNull != true) return null
        return PromptCaching(
            checkpoints = reported.int("maximumCacheCheckpointsPerRequest")?.takeIf { it > 0 } ?: PromptCaching().checkpoints,
            minimumTokens = reported.int("minimumTokensPerCacheCheckpoint")?.takeIf { it > 0 } ?: PromptCaching().minimumTokens,
        )
    }

    private fun acceptsImages(id: String, entry: JsonObject): Boolean {
        val declared = entry.values.filterIsInstance<JsonArray>().flatten()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.uppercase() }
        if (declared.any { it == "IMAGE" || it == "IMAGES" }) return true
        if (declared.any { it == "TEXT" }) return false
        val lowered = id.lowercase()
        return lowered.startsWith("claude") || lowered.startsWith("gpt") || lowered == "auto"
    }

    private fun trim(value: Double): String =
        if (value == kotlin.math.floor(value)) value.toLong().toString() else value.toString()

    /* -------------------------------------------------------- the errors */

    /**
     * An HTTP failure, classified by Kiro's own `reason` before the status:
     * every refusal is a 400 here, and the reason is what tells "too long"
     * from "quota spent" from "malformed".
     */
    fun error(status: Int, body: String?, payloadBytes: Int, estimatedPromptTokens: Int, maxOutput: Int): LlmError {
        val parsed = body?.let(Http::parseObject)
        val reason = parsed?.string("reason")
        val message = parsed?.string("message") ?: parsed?.string("Message")
        val base = Http.classify(status, "", body)
        return when {
            reason == "CONTENT_LENGTH_EXCEEDS_THRESHOLD" ||
                (status == 400 && payloadBytes > PAYLOAD_SOFT_LIMIT && reason in setOf(null, "REQUEST_BODY_INVALID")) ->
                base.copy(
                    kind = LlmError.Kind.CONTEXT_OVERFLOW,
                    message = "Kiro refused the request as too long" +
                        (message?.let { ": $it" } ?: "") + ". The conversation will be compacted to fit.",
                    detail = message ?: base.detail,
                    // The limit is the request's size, not the model's window: the
                    // window that fits is the share of this prompt that would have
                    // gone through, plus the room kept for the answer.
                    discoveredContextLimit = if (payloadBytes > 0 && estimatedPromptTokens > 0) {
                        (estimatedPromptTokens.toLong() * PAYLOAD_SOFT_LIMIT * 9 / 10 / payloadBytes).toInt() + maxOutput
                    } else {
                        null
                    },
                )
            reason == "MONTHLY_REQUEST_COUNT" -> base.copy(
                kind = LlmError.Kind.PAYMENT_REQUIRED,
                message = "The Kiro account has used its monthly requests.",
                detail = message ?: base.detail,
                hint = LlmError.Hint.TOP_UP,
            )
            reason == "INVALID_MODEL_ID" -> base.copy(
                kind = LlmError.Kind.MODEL_UNAVAILABLE,
                message = "Kiro does not offer this model to this account.",
                detail = message ?: base.detail,
                hint = LlmError.Hint.PICK_ANOTHER_MODEL,
            )
            status == 401 || status == 403 -> base.copy(
                kind = LlmError.Kind.AUTH,
                message = "Kiro rejected the account token. Copy a fresh refreshToken from the Kiro IDE " +
                    "and paste it again." + (message?.let { " ($it)" } ?: ""),
                hint = LlmError.Hint.CHECK_KEY,
            )
            else -> base.copy(detail = message ?: base.detail)
        }
    }

    /* -------------------------------------------------------- the stream */

    /**
     * Kiro's events in the app's terms. Fed one frame at a time; [finish]
     * closes what is still open.
     */
    class Decoder(
        private val toolNames: Map<String, String>,
    ) {
        /**
         * What the request cost, in Kiro's credits (`meteringEvent`): the only
         * measure of a request's price the stream carries.
         */
        var credits: Double = 0.0
            private set

        /**
         * How full the conversation's window is once this answer is in, in
         * percent (`contextUsageEvent`). Not a prompt count: it includes the
         * answer and whatever the service adds to the prompt itself, and it is
         * a share of a window the listing does not always state.
         */
        var contextPercent: Double? = null
            private set

        private val splitter = ThinkTagSplitter()
        private val leak = KiroToolLeak(declared = toolNames.keys)
        private val openTools = LinkedHashMap<String, Boolean>()
        /** Per open call: its wire name and the input so far, to know a leaked call that was made already. */
        private val callNames = HashMap<String, String>()
        private val callInputs = HashMap<String, StringBuilder>()
        private val madeCalls = HashSet<String>()
        private val finishedCalls = HashSet<String>()
        private var toolCounter = 0
        private var stopReason: String? = null
        private var inputTokens = 0
        private var outputTokens = 0
        private var cachedTokens = 0
        private var cacheWriteTokens = 0
        private var failed = false

        fun accept(frame: AwsEventStream.Frame): List<StreamEvent> {
            val out = ArrayList<StreamEvent>(2)
            when (frame.messageType) {
                "error", "exception" -> {
                    failed = true
                    val payload = Http.parseObject(frame.payloadText)
                    val type = frame.exceptionType ?: frame.header(":error-code") ?: "error"
                    val text = payload?.string("message") ?: payload?.string("Message") ?: frame.payloadText.take(500)
                    out += StreamEvent.Failed(streamError(type, text, frame.payloadText))
                    return out
                }
            }
            val payload = Http.parseObject(frame.payloadText) ?: return out
            val type = frame.eventType.orEmpty()
            // Each event's fields, whether or not they are wrapped in its name.
            val value = payload[type] as? JsonObject ?: payload
            when (type) {
                "assistantResponseEvent", "codeEvent" -> value.string("content")?.let { text(it, out) }
                "reasoningContentEvent" -> {
                    // Only the text: the signature and any redacted part are
                    // never sent back (the history refuses them), so they are not kept.
                    (value.string("text") ?: value.string("content"))?.takeIf { it.isNotEmpty() }
                        ?.let { out += StreamEvent.ThinkingDelta(it) }
                }
                "toolUseEvent" -> toolUse(payload, out)
                "messageStopEvent" -> value.string("stopReason")?.let { stopReason = it }
                "messageMetadataEvent", "metadataEvent", "MetadataEvent" -> {
                    val metadata = payload["messageMetadataEvent"] as? JsonObject
                        ?: payload["metadataEvent"] as? JsonObject
                        ?: payload["metadata"] as? JsonObject
                        ?: payload
                    metadata.string("stopReason")?.let { stopReason = it }
                    (metadata["tokenUsage"] as? JsonObject)?.let(::usage)
                    usage(metadata)
                }
                "metricsEvent", "usageEvent", "usage" -> usage(value)
                "contextUsageEvent" -> (value["contextUsagePercentage"] as? JsonPrimitive)?.doubleOrNull?.let { contextPercent = it }
                "meteringEvent" -> (value["usage"] as? JsonPrimitive)?.doubleOrNull?.let { credits += it }
                // Said in the reply, as Kiro Account Manager says them.
                "invalidStateEvent" -> text(
                    "\n\nWarning: ${value.string("message") ?: "invalid state"} (reason: ${value.string("reason") ?: "UNKNOWN"})",
                    out,
                )
                "supplementaryWebLinksEvent" -> links(value["supplementaryWebLinks"] as? JsonArray, "Web references", out)
                "citationEvent" -> links(value["citations"] as? JsonArray, "Citations", out)
            }
            // An error inside an event rather than as an exception frame.
            if (payload["error"] != null || payload["_type"] != null) {
                failed = true
                val error = payload["error"] as? JsonObject
                val message = payload.string("message") ?: error?.string("message") ?: "Unknown stream error"
                out += StreamEvent.Failed(streamError(payload.string("_type") ?: "error", message, frame.payloadText))
            }
            return out
        }

        fun finish(): List<StreamEvent> {
            val out = ArrayList<StreamEvent>(4)
            leak.flush().takeIf { it.isNotEmpty() }?.let { held -> splitter.accept(held).forEach { piece(it, out) } }
            splitter.finish().forEach { piece -> piece(piece, out) }
            openTools.keys.toList().forEach { id -> endCall(id, out) }
            openTools.clear()
            if (failed) return out
            // Calls the model wrote out as text: made after all, unless made already.
            for (tool in leak.tools) {
                if (!madeCalls.add(signature(tool.name, tool.input))) continue
                val id = "toolleak_${++toolCounter}"
                out += StreamEvent.ToolCallStart(id, toolNames[tool.name] ?: tool.name)
                out += StreamEvent.ToolCallArgsDelta(id, tool.input.toString())
                out += StreamEvent.ToolCallEnd(id)
            }
            // Counts only when the service gave them. Kiro's stream, as seen,
            // gives none — `metadataEvent` carries the stop reason alone — and
            // a count made up from `contextPercent` would be wrong by the
            // answer, by what the service adds, and by whatever window it is a
            // share of; the token estimator is calibrated against this number.
            if (inputTokens > 0 || outputTokens > 0) {
                out += StreamEvent.Usage(
                    promptTokens = inputTokens,
                    completionTokens = outputTokens,
                    cachedPromptTokens = cachedTokens,
                    cacheWriteTokens = cacheWriteTokens,
                )
            }
            out += StreamEvent.Done(stopReason ?: if (toolCounter > 0) "tool_use" else "end_turn")
            return out
        }

        private fun text(chunk: String, out: MutableList<StreamEvent>) {
            val shown = leak.accept(chunk)
            if (shown.isNotEmpty()) splitter.accept(shown).forEach { piece(it, out) }
        }

        /** Links an event carries, as a list at the end of the reply. */
        private fun links(items: JsonArray?, heading: String, out: MutableList<StreamEvent>) {
            val lines = items.orEmpty().mapIndexedNotNull { index, item ->
                val link = item as? JsonObject ?: return@mapIndexedNotNull null
                val url = link.string("url")
                val title = link.string("title") ?: url ?: return@mapIndexedNotNull null
                if (url == null) "${index + 1}. $title" else "- [$title]($url)"
            }
            if (lines.isNotEmpty()) text("\n\n**$heading:**\n${lines.joinToString("\n")}", out)
        }

        private fun endCall(id: String, out: MutableList<StreamEvent>) {
            out += StreamEvent.ToolCallEnd(id)
            val name = callNames.remove(id) ?: return
            val input = callInputs.remove(id)?.toString().orEmpty()
            madeCalls += signature(name, Http.parseObject(input.ifBlank { "{}" }) ?: JsonObject(emptyMap()))
        }

        /** A call by name and input, keys in order, to tell the same call written twice. */
        private fun signature(name: String, input: JsonObject): String =
            name + "|" + JsonObject(input.toSortedMap()).toString()

        private fun piece(piece: ThinkTagSplitter.Piece, out: MutableList<StreamEvent>) {
            out += when (piece) {
                is ThinkTagSplitter.Piece.Text -> StreamEvent.TextDelta(piece.text)
                is ThinkTagSplitter.Piece.Thinking -> StreamEvent.ThinkingDelta(piece.text)
                ThinkTagSplitter.Piece.EarlierTextWasThinking -> StreamEvent.TextWasThinking
            }
        }

        /**
         * A call arrives as several events with the same id: the name, then
         * the input as string fragments of JSON (or once, as an object), then
         * one with `stop`. Some carry a list of calls instead of one.
         */
        private fun toolUse(payload: JsonObject, out: MutableList<StreamEvent>) {
            val values = (payload["toolUseEvent"] as? JsonObject)?.let { listOf(it) }
                ?: (payload["toolUses"] as? JsonArray)?.filterIsInstance<JsonObject>()
                ?: listOf(payload)
            for (value in values) {
                val wireName = value.string("name")?.trim().orEmpty()
                val id = value.string("toolUseId")?.takeIf { it.isNotBlank() }
                    ?: openTools.keys.lastOrNull()
                    ?: "tooluse_${toolCounter + 1}"
                // A call already finished does not start again when an event repeats.
                if (id in finishedCalls) continue
                if (id !in openTools) {
                    if (wireName.isEmpty()) continue
                    openTools[id] = true
                    callNames[id] = wireName
                    toolCounter++
                    out += StreamEvent.ToolCallStart(id, toolNames[wireName] ?: wireName)
                }
                val fragment = when (val input = value["input"]) {
                    is JsonPrimitive -> input.contentOrNull?.takeIf { input.isString && it.isNotEmpty() }
                    is JsonObject -> input.toString()
                    else -> null
                }
                if (fragment != null) {
                    callInputs.getOrPut(id) { StringBuilder() }.append(fragment)
                    out += StreamEvent.ToolCallArgsDelta(id, fragment)
                }
                if ((value["stop"] as? JsonPrimitive)?.booleanOrNull == true) {
                    openTools.remove(id)
                    finishedCalls += id
                    endCall(id, out)
                }
            }
        }

        /**
         * Token counts, in any of the shapes they come in: `inputTokens`, or
         * Kiro's `tokenUsage` split into uncached, cache-read and cache-written
         * input, with `totalTokens` as a last resort.
         */
        private fun usage(value: JsonObject) {
            val uncached = value.int("uncachedInputTokens") ?: 0
            val cacheRead = value.int("cacheReadInputTokens") ?: value.int("cache_read_input_tokens") ?: 0
            val cacheWrite = value.int("cacheWriteInputTokens") ?: 0
            val split = uncached + cacheRead + cacheWrite
            when {
                split > 0 -> inputTokens = split
                else -> value.int("inputTokens")?.takeIf { it > 0 }?.let { inputTokens = it }
            }
            value.int("outputTokens")?.takeIf { it > 0 }?.let { outputTokens = it }
            if (inputTokens == 0 && outputTokens > 0) {
                value.int("totalTokens")?.takeIf { it > outputTokens }?.let { inputTokens = it - outputTokens }
            }
            if (cacheRead > 0) cachedTokens = cacheRead
            if (cacheWrite > 0) cacheWriteTokens = cacheWrite
            (value["contextUsagePercentage"] as? JsonPrimitive)?.doubleOrNull?.let { contextPercent = it }
        }

        private fun streamError(type: String, message: String, raw: String): LlmError {
            val kind = when {
                type.contains("Throttling", ignoreCase = true) -> LlmError.Kind.RATE_LIMIT
                type.contains("AccessDenied", ignoreCase = true) || type.contains("Unauthorized", ignoreCase = true) ->
                    LlmError.Kind.AUTH
                type.contains("Validation", ignoreCase = true) -> LlmError.Kind.BAD_REQUEST
                type.contains("ServiceUnavailable", ignoreCase = true) || type.contains("Internal", ignoreCase = true) ->
                    LlmError.Kind.SERVER
                else -> LlmError.Kind.SERVER
            }
            return LlmError(kind, "Kiro: $type: $message", body = raw.take(2_000), detail = message)
        }
    }

    /* ------------------------------------------------------------ helpers */

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    /**
     * The most one answer is given room for: plenty for an agent's turn, and
     * what is left of the window stays the conversation's. Kiro takes what a
     * client asks, up to the model's own limit in the listing.
     */
    const val MAX_OUTPUT_TOKENS = 32_000

    /** Every user turn's `origin`: Kiro IDE's chat. */
    private const val ORIGIN = "AI_EDITOR"

    /** Kiro IDE's chat mode, in the body as in the header. */
    private const val AGENT_TASK_TYPE = "vibe"

    /** The answer to the system prompt's turn, in Kiro IDE's words. */
    private const val SYSTEM_ANSWER = "I will follow these instructions."

    /** A prompt cache point's type, as Kiro takes it: Bedrock's only one. */
    private const val CACHE_POINT = "default"

    /**
     * The per-request part of the system prompt, as the current message
     * carries it: marked as the app's, so it is not read as the user's words.
     */
    private fun appContext(text: String): String =
        "<app-context>\nSent by the app with this request, not written by the user.\n\n$text\n</app-context>"

    /** The window of a listed model that does not say: the service's usual one. */
    private const val DEFAULT_WINDOW = 200_000

    /**
     * Requests past about 615 KB are refused with a bare "Improperly formed
     * request". A refusal of a request over this size is read as "too long".
     */
    const val PAYLOAD_SOFT_LIMIT = 600_000

    private const val NAME_MAX = 64
    private const val ID_MAX = 64
    private const val DESCRIPTION_MAX = 10_000
    private const val TEXT_CAP = 4_000
}
