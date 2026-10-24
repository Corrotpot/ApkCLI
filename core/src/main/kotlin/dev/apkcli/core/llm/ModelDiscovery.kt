package dev.apkcli.core.llm

import dev.apkcli.core.model.ModelSpec
import dev.apkcli.core.model.ReasoningLevels
import dev.apkcli.core.model.ReasoningSupport
import dev.apkcli.core.util.bool
import dev.apkcli.core.util.obj
import dev.apkcli.core.util.str
import dev.apkcli.core.util.strings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.net.URLEncoder

/**
 * Builds [ModelSpec]s from whatever an endpoint's model routes actually return.
 *
 * There is no table of model names here on purpose. A hardcoded list of
 * "claude-x has a 200k window, gpt-y reasons" is wrong within weeks — new
 * models ship constantly, gateways rename and prefix ids, and the list silently
 * misreports the one number the whole budgeting system depends on.
 *
 * What there is instead is a table of *field names*, because every server
 * spells the same facts differently:
 *
 * | Server | Shape | Context window | Not a chat model |
 * | --- | --- | --- | --- |
 * | OpenRouter | `data[]` | `top_provider.context_length` | `output_modalities` without text |
 * | AnyModel | `data[]` | `contextWindow` on `/models/info?id=` | `kind` (image, tts, …) |
 * | OpenAI, DeepSeek, xAI | `data[]` | — | — |
 * | Anthropic | `data[]`, paged by `after_id` | — | — |
 * | vLLM, SGLang | `data[]` | `max_model_len` | — |
 * | LM Studio | `data[]` | `loaded_context_length`, `max_context_length` | `type: embeddings` |
 * | llama.cpp | `data[]`, `models[]` | `meta.n_ctx_train` | — |
 * | Ollama | `models[]` | — | — |
 * | Together | bare array | `context_length` | `type` |
 * | Groq | `data[]` | `context_window` | `active: false` |
 * | Mistral | `data[]` | `max_context_length` | `capabilities.completion_chat` |
 * | Fireworks | `data[]` | `context_length` | `supports_chat` |
 * | DeepInfra | `data[]` | `metadata.context_length` | — |
 * | Gemini (native) | `models[]`, paged by `pageToken` | `inputTokenLimit` | `supportedGenerationMethods` |
 * | LiteLLM | `data[]` | `model_info.max_input_tokens` | `mode` |
 *
 * So: find the list wherever it is, read every field name any of them uses,
 * drop what the endpoint itself says is not a chat model, derive what is
 * derivable, and mark the rest as unreported so the UI can ask the user to
 * confirm it rather than quietly pretending to know.
 */
object ModelDiscovery {

    /**
     * Used only when an endpoint reports nothing at all.
     *
     * Deliberately small. Under-using a window wastes money; over-stating it
     * makes the first long session die with a 400 the user cannot diagnose.
     * Erring low fails safe, and the value is editable.
     */
    const val FALLBACK_CONTEXT = 32_768
    const val FALLBACK_MAX_OUTPUT = 4_096

    /** One page of a listing, and the query that asks for the next one, if any. */
    data class Page(val models: List<ModelSpec>, val nextQuery: String?)

    /* --------------------------------------------------------- field names */

    /** Where the array of models lives, in the order servers are checked. */
    private val LIST_KEYS = listOf("data", "models", "result", "results", "items", "model_list", "available_models")

    private val ID_KEYS = listOf("id", "model", "model_id", "modelId", "name", "slug", "model_name")

    private val DISPLAY_KEYS = listOf("display_name", "displayName", "title", "label")

    /**
     * Context window fields, in priority order. LM Studio's loaded length comes
     * first because it is what the running instance accepts; its
     * `max_context_length` is what the weights could take if reloaded.
     */
    private val CONTEXT_KEYS = listOf(
        "loaded_context_length",
        "context_length", "context_window", "contextWindow", "contextLength",
        "max_model_len",
        "max_context_length", "maxContextLength", "max_context_window", "max_context_tokens", "max_context",
        "context_size", "contextSize",
        "max_input_tokens", "maxInputTokens", "input_token_limit", "inputTokenLimit", "max_prompt_tokens",
        "max_sequence_length", "max_seq_len",
        "n_ctx", "num_ctx", "n_ctx_train",
    )

    /** `max_tokens` is last: a few servers use it for the whole window. */
    private val OUTPUT_KEYS = listOf(
        "max_completion_tokens", "max_output_tokens", "maxOutputTokens",
        "output_token_limit", "outputTokenLimit", "max_output_length", "max_new_tokens",
        "max_tokens", "maxTokens",
    )

    /** Objects inside an entry that some servers put the numbers in. */
    private val NESTED = listOf(
        "top_provider", "limits", "limit", "meta", "metadata", "model_info", "info",
        "details", "capabilities", "config", "spec", "specs", "architecture", "context",
    )

    /** Fields that name a model's modality or task. */
    private val KIND_KEYS = listOf("kind", "type", "model_type", "modelType", "mode", "task", "category")

    /** Values of [KIND_KEYS] that mean "not callable on /chat/completions". */
    private val NON_CHAT_KINDS = setOf(
        "image", "images", "image_generation", "image-generation", "text-to-image", "text2image",
        "video", "video_generation", "text-to-video",
        "tts", "speech", "audio", "audio_speech", "text-to-speech",
        "stt", "transcription", "audio_transcription", "speech-to-text", "asr",
        "embedding", "embeddings", "text-embedding",
        "rerank", "reranker", "reranking",
        "moderation", "classification",
        "web", "search",
    )

    private val TOOL_FLAGS = listOf(
        "supports_tools", "supportsTools", "supports_function_calling", "supportsFunctionCalling",
        "function_calling", "tool_calling", "supports_tool_calls", "tools",
    )
    private val CAPABILITY_TOOL_FLAGS = listOf(
        "function_calling", "tools", "tool_calling", "tool_use", "functionCalling", "toolCalling", "supports_tools",
    )
    private val TOOL_WORDS = setOf(
        "tools", "tool_choice", "function_calling", "function-calling", "functions",
        "tool_calling", "tool-calling", "tool_use", "tool-use",
    )

    private val VISION_FLAGS = listOf(
        "vision", "supports_vision", "supportsVision", "supports_image_input", "supports_images", "image_input",
    )
    private val CAPABILITY_VISION_FLAGS = listOf("vision", "image_input", "images", "supports_vision")

    private val CONTEXT_RANGE = 1_024..50_000_000
    private val OUTPUT_RANGE = 16..10_000_000

    /* ------------------------------------------------------------- parsing */

    /** Parse one page of a listing: an object holding an array, or a bare array. */
    fun parsePage(body: JsonElement): Page {
        val parsed = entriesOf(body, depth = 0)
            .mapNotNull { (element, keyId) -> parseEntry(element, keyId) }
            .distinctBy { it.id }
        return Page(order(parsed), nextQueryOf(body))
    }

    /** A whole single-page listing. */
    fun parseList(body: JsonElement): List<ModelSpec> = parsePage(body).models

    /**
     * Parse a per-model detail response (`/models/info?id=`, `/models/{id}`).
     *
     * Accepted only when it is about [requestedId]: a server that reads
     * `/models/info` as "the model called info" must not have its answer
     * attached to someone else's model.
     */
    fun parseDetail(body: JsonElement, requestedId: String): ModelSpec? {
        // Some servers answer any sub-path with the whole listing. That has to
        // be recognised first: read as one entry, the wrapper object would
        // pass for the model itself, with none of its fields.
        if (entriesOf(body, depth = 0).isNotEmpty()) {
            return parseList(body).firstOrNull { it.id == requestedId }
        }
        val obj = body as? JsonObject ?: return null
        val candidates = listOfNotNull(obj, obj.obj("data"), obj.obj("model"), obj.obj("model_info"))
        for (candidate in candidates) {
            val ownId = ID_KEYS.firstNotNullOfOrNull { candidate.str(it)?.trim()?.takeIf(String::isNotEmpty) }
            if (ownId != null && ownId != requestedId) continue
            val spec = parseEntry(candidate, fallbackId = requestedId) ?: continue
            return spec.copy(id = requestedId)
        }
        return null
    }

    /**
     * One entry: an object, or a bare id string.
     *
     * Null when there is no id, or when the entry itself says it is not a
     * chat model — an image generator offered to a coding agent fails every
     * request it is given.
     */
    fun parseEntry(element: JsonElement, fallbackId: String? = null): ModelSpec? = when (element) {
        is JsonObject -> parseObject(element, fallbackId)
        is JsonPrimitive -> element.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
            ?.let { unreported(it) }
        else -> null
    }

    private fun parseObject(entry: JsonObject, fallbackId: String?): ModelSpec? {
        val idKey = ID_KEYS.firstOrNull { !entry.str(it).isNullOrBlank() }
        val id = idKey?.let { entry.str(it)?.trim() }
            ?: fallbackId?.trim()?.takeIf(String::isNotEmpty)
            ?: return null
        if (!isChatModel(entry)) return null

        // --- context window ------------------------------------------------
        // top_provider wins: on OpenRouter the root `context_length` is the
        // model's theoretical maximum, while top_provider reports what the
        // provider that will actually serve the request allows. Budgeting
        // against the larger number produces 400s.
        val topProvider = entry.obj("top_provider")
        val reportedContext = topProvider?.tokenCount("context_length", CONTEXT_RANGE)
            ?: findTokenCount(entry, CONTEXT_KEYS, CONTEXT_RANGE)
        val contextWindow = reportedContext ?: FALLBACK_CONTEXT

        // --- max output ----------------------------------------------------
        // A reported cap at or above the window is a misreading (some servers
        // put the window in max_tokens) and would leave no room for a prompt.
        val reportedMaxOutput = (
            topProvider?.tokenCount("max_completion_tokens", OUTPUT_RANGE)
                ?: findTokenCount(entry, OUTPUT_KEYS, OUTPUT_RANGE)
            )?.takeIf { it < contextWindow }

        // When unreported, derive from the window rather than guessing per
        // model: a quarter of the window, capped, is a safe ceiling that still
        // leaves room for a long reasoning phase.
        val maxOutput = reportedMaxOutput
            ?: (contextWindow / 4).coerceIn(1_024, 32_768).coerceAtMost(contextWindow / 2)

        // --- capabilities --------------------------------------------------
        val supportedParameters = entry.strings("supported_parameters").map(String::lowercase)
        val hints = (
            supportedParameters +
                entry.strings("params") +
                entry.strings("supported_features") +
                entry.strings("features") +
                entry.strings("capabilities")
            ).map(String::lowercase)

        val capabilities = entry.obj("capabilities")
        val toolsFlag = TOOL_FLAGS.firstNotNullOfOrNull { entry.flag(it) }
            ?: capabilities?.let { caps -> CAPABILITY_TOOL_FLAGS.firstNotNullOfOrNull { caps.flag(it) } }
        val supportsTools = when {
            toolsFlag != null -> toolsFlag
            hints.any { it in TOOL_WORDS } -> true
            // OpenRouter's supported_parameters is a complete list; anywhere
            // else a missing word is just a missing word.
            supportedParameters.isNotEmpty() -> false
            // Unknown: tools are overwhelmingly the common case, and a model
            // that lacks them ignores the field.
            else -> true
        }

        val inputModalities = (
            entry.obj("architecture")?.strings("input_modalities").orEmpty() +
                entry.strings("input_modalities") +
                entry.obj("modalities")?.strings("input").orEmpty() +
                entry.strings("modalities")
            ).map(String::lowercase)
        val modalityString = entry.obj("architecture")?.str("modality")?.lowercase()?.substringBefore("->").orEmpty()
        val kind = entry.str("kind")?.lowercase().orEmpty()
        val supportsImages = VISION_FLAGS.firstNotNullOfOrNull { entry.flag(it) }
            ?: capabilities?.let { caps -> CAPABILITY_VISION_FLAGS.firstNotNullOfOrNull { caps.flag(it) } }
            ?: (
                "image" in inputModalities ||
                    "image" in modalityString ||
                    kind == "imagetotext" || kind == "image-to-text" ||
                    hints.any { it == "vision" || it == "image_input" }
                )

        var levels = reasoningLevels(entry)
        if (levels.efforts.isEmpty()) {
            effortCapability(capabilities).takeIf { it.isNotEmpty() }?.let { levels = Levels(it, null, false) }
        }
        var dialect = detectReasoning(entry, supportedParameters, hints)
        // A model publishing levels on an OpenAI-shaped endpoint takes them as
        // `reasoning_effort`, whether or not a parameter list said so.
        if (dialect == ReasoningSupport.NONE && levels.efforts.isNotEmpty()) dialect = ReasoningSupport.EFFORT
        // No list, but capability flags that say what the model can do.
        if (dialect == ReasoningSupport.NONE && levels.efforts.isEmpty()) {
            capabilityLevels(capabilities)?.let { fromFlags ->
                dialect = ReasoningSupport.EFFORT
                levels = fromFlags
            }
        }
        var efforts = levels.efforts
        // OpenRouter turns reasoning off with `enabled: false` for any model
        // that does not report it as mandatory, listed "none" or not.
        if (dialect == ReasoningSupport.OPENROUTER_UNIFIED && efforts.isNotEmpty() &&
            !levels.mandatory && ReasoningLevels.NONE !in efforts
        ) {
            efforts = listOf(ReasoningLevels.NONE) + efforts
        }

        return ModelSpec(
            id = id,
            displayName = DISPLAY_KEYS.firstNotNullOfOrNull { entry.str(it)?.trim()?.takeIf(String::isNotEmpty) }
                ?: entry.str("name")?.trim()?.takeIf { it.isNotEmpty() && idKey != "name" }
                ?: id,
            contextWindow = contextWindow,
            maxOutputTokens = maxOutput,
            supportsTools = supportsTools,
            supportsStreaming = true,
            supportsImages = supportsImages,
            reasoning = dialect,
            reasoningEfforts = efforts,
            defaultReasoningEffort = levels.default?.takeIf { it in efforts },
            reasoningMandatory = levels.mandatory,
            thinkingTypes = thinkingTypes(capabilities),
            metadataFromEndpoint = reportedContext != null,
            notes = buildNotes(entry, reportedContext != null),
        )
    }

    internal class Levels(val efforts: List<String>, val default: String?, val mandatory: Boolean)

    /**
     * The reasoning levels an entry publishes, wherever its server puts them:
     * OpenRouter's `reasoning.supported_efforts` / `default_effort` /
     * `mandatory`, and the variants gateways use — a top-level
     * `reasoning_efforts`, a `capabilities.reasoning` object, or a parameter
     * description such as `params.reasoning_effort.enum`.
     */
    internal fun reasoningLevels(entry: JsonObject): Levels {
        val holders = buildList {
            add(entry)
            entry.obj("reasoning")?.let(::add)
            entry.obj("capabilities")?.let { caps ->
                add(caps)
                caps.obj("reasoning")?.let(::add)
            }
            for (key in listOf("params", "parameters", "supported_parameters")) {
                entry.obj(key)?.let { params ->
                    (params["reasoning_effort"] ?: params["reasoning"])?.let { described ->
                        when (described) {
                            is JsonObject -> {
                                add(described)
                                described.obj("schema")?.let(::add)
                            }
                            is JsonArray -> add(JsonObject(mapOf("levels" to described)))
                            else -> Unit
                        }
                    }
                }
            }
        }
        val found = holders.firstNotNullOfOrNull { holder ->
            LEVEL_LIST_KEYS.firstNotNullOfOrNull { key -> holder.strings(key).takeIf { it.isNotEmpty() } }
        }.orEmpty()
        val efforts = ReasoningLevels.normalize(found)
        val default = holders.firstNotNullOfOrNull { holder ->
            LEVEL_DEFAULT_KEYS.firstNotNullOfOrNull { key -> holder.str(key)?.trim()?.lowercase() }
        } ?: entry.obj("default_parameters")?.str("reasoning_effort")?.trim()?.lowercase()
        val mandatory = entry.obj("reasoning")?.bool("mandatory", false) == true
        return Levels(efforts, default, mandatory)
    }

    /**
     * Reasoning as a catalog's capability flags describe it, where it publishes
     * no level list. AnyModel's `/models`, for each chat model:
     *
     *     "capabilities": { "reasoning": true, "thinkingFormat": "deepseek",
     *                       "thinkingCanDisable": true, … }
     *
     * It takes `reasoning_effort` for every format and translates it. Checked
     * against it in October 2026:
     *
     *  * `"none"` turns thinking off where `thinkingCanDisable` says it can
     *    (DeepSeek V4: no reasoning tokens; Nemotron: 2 tokens instead of
     *    300) — except on the `openai` format, where GPT-OSS answers it with a
     *    400 although its flag says it can be disabled.
     *  * `low` / `medium` / `high` — the values its API documentation gives
     *    for `reasoning_effort` — change how long `openai`-format models
     *    reason (GPT-OSS 20B: 399 / 749 / 1229 characters). `xhigh` and `max`
     *    are refused there; other formats ignore the scale.
     *
     * So: the documented scale for the `openai` format, off where the flags
     * allow it elsewhere, and in every case the model's own default, which is
     * what an empty choice sends. Null when the flags say the model does not
     * reason, or the entry has no such flags.
     */
    private fun capabilityLevels(caps: JsonObject?): Levels? {
        if (caps == null || "thinkingFormat" !in caps) return null
        if (caps.flag("reasoning") != true) return null
        val format = caps.str("thinkingFormat")?.lowercase()
        val canDisable = caps.flag("thinkingCanDisable") == true
        val efforts = when {
            format == "openai" -> DOCUMENTED_EFFORT_SCALE
            canDisable -> listOf(ReasoningLevels.NONE)
            else -> emptyList()
        }
        return Levels(efforts, default = null, mandatory = !canDisable)
    }

    /** AnyModel's documented `reasoning_effort` values, less "none" (see [capabilityLevels]). */
    private val DOCUMENTED_EFFORT_SCALE = listOf("low", "medium", "high")

    private val LEVEL_LIST_KEYS = listOf(
        "supported_efforts", "reasoning_efforts", "supported_reasoning_efforts", "efforts",
        "reasoning_levels", "supported_levels", "levels", "effort_levels", "enum", "values", "options",
    )
    private val LEVEL_DEFAULT_KEYS = listOf("default_effort", "default_reasoning_effort", "default_level", "default")

    /** What the entry itself says about its modality. Name-based hints are not used here. */
    private fun isChatModel(entry: JsonObject): Boolean {
        for (key in KIND_KEYS) {
            val value = entry.str(key)?.trim()?.lowercase() ?: continue
            if (value in NON_CHAT_KINDS) return false
        }
        val outputs = (entry.obj("architecture")?.strings("output_modalities").orEmpty() + entry.strings("output_modalities"))
            .map(String::lowercase)
        if (outputs.isNotEmpty() && "text" !in outputs) return false
        // Mistral marks embedding models this way.
        if (entry.obj("capabilities")?.flag("completion_chat") == false) return false
        // Fireworks.
        if (entry.flag("supports_chat") == false) return false
        // Groq keeps decommissioned models in the list, marked inactive.
        if (entry.flag("active") == false) return false
        // Gemini's native listing: embedding models cannot generateContent.
        val methods = entry.strings("supportedGenerationMethods")
        if (methods.isNotEmpty() && "generateContent" !in methods) return false
        return true
    }

    /**
     * Which reasoning dialect this model wants.
     *
     * Three different shapes exist in the OpenAI-compatible family and sending
     * the wrong one is a 400 on strict gateways:
     *   `reasoning: { effort }`    — OpenRouter's unified form
     *   `reasoning_effort: "high"` — OpenAI proper and close clones
     *   neither                    — no reasoning control
     */
    private fun detectReasoning(
        entry: JsonObject,
        supportedParameters: List<String>,
        hints: List<String>,
    ): ReasoningSupport = when {
        "reasoning" in supportedParameters || "include_reasoning" in supportedParameters ->
            ReasoningSupport.OPENROUTER_UNIFIED
        "reasoning_effort" in supportedParameters || "reasoning_effort" in hints -> ReasoningSupport.EFFORT
        // Newer OpenRouter payloads carry a `reasoning` object describing
        // whether reasoning is mandatory, even for models whose
        // supported_parameters list is incomplete. Only trusted on an
        // OpenRouter-shaped entry: elsewhere the unified form is a 400.
        entry.obj("reasoning") != null && (supportedParameters.isNotEmpty() || entry.obj("top_provider") != null) ->
            ReasoningSupport.OPENROUTER_UNIFIED
        else -> ReasoningSupport.NONE
    }

    /* ------------------------------------------------------------ ordering */

    /**
     * Ids whose words say "not a chat model": embeddings, speech, image and
     * video generators, moderation.
     *
     * Used only to order the list and to keep such a model from being picked
     * as the default — never to drop one. An id is a weak signal, and the
     * entry's own metadata, where it has any, has already decided.
     */
    fun looksNonChat(id: String): Boolean {
        val words = id.lowercase().split(Regex("[^a-z0-9]+")).filter(String::isNotEmpty)
        return words.any { word ->
            word.startsWith("embed") || word == "tts" || word.startsWith("whisper") || word == "dall" ||
                word.startsWith("moderation") || word.startsWith("rerank") || word.startsWith("transcribe") ||
                word == "realtime" || word.startsWith("image") || word == "diffusion" || word == "sora" ||
                word == "veo" || word == "speech" || word in GENERATOR_WORDS
        }
    }

    /**
     * Families of image, video, audio and music generators, by the names they
     * ship under ("nano-banana" is Gemini's image model). The same weak signal
     * as above: ordering and defaults only.
     */
    private val GENERATOR_WORDS = setOf(
        "banana", "imagen", "flux", "sdxl", "midjourney", "kling", "suno", "lyria", "seedream", "seedance",
        "hailuo", "ideogram", "recraft", "kandinsky", "dalle", "photon", "runway", "music", "voice", "audio",
    )

    /** Likely chat models first, each group in the endpoint's own order. */
    fun order(models: List<ModelSpec>): List<ModelSpec> {
        val (others, chat) = models.partition { looksNonChat(it.id) }
        return chat + others
    }

    /**
     * The model to use when nobody chose one: the endpoint's first likely chat
     * model that takes tools — the agent cannot work without them.
     */
    fun pickDefault(models: List<ModelSpec>): ModelSpec? =
        models.firstOrNull { it.supportsTools && !looksNonChat(it.id) }
            ?: models.firstOrNull { !looksNonChat(it.id) }
            ?: models.firstOrNull()

    /* ------------------------------------------------------------- helpers */

    /** Arrays of entries, wherever the server put them. Each with the map key it was filed under, if any. */
    private fun entriesOf(body: JsonElement, depth: Int): List<Pair<JsonElement, String?>> {
        if (body is JsonArray) return body.map { it to null }
        if (body !is JsonObject || depth > 2) return emptyList()
        for (key in LIST_KEYS) {
            when (val value = body[key]) {
                is JsonArray -> return value.map { it to null }
                is JsonObject -> {
                    // {"data": {"models": [...]}}
                    val nested = entriesOf(value, depth + 1)
                    if (nested.isNotEmpty()) return nested
                    // {"models": {"model-a": {...}, "model-b": {...}}}
                    if (value.isNotEmpty() && value.values.all { it is JsonObject }) {
                        return value.map { (mapKey, entry) -> entry to mapKey }
                    }
                }
                else -> Unit
            }
        }
        return emptyList()
    }

    /** Anthropic pages by `after_id`; Gemini's native listing by `pageToken`. */
    private fun nextQueryOf(body: JsonElement): String? {
        val obj = body as? JsonObject ?: return null
        if (obj.bool("has_more", false)) {
            obj.str("last_id")?.takeIf(String::isNotBlank)?.let { return "after_id=${encode(it)}" }
        }
        obj.str("nextPageToken")?.takeIf(String::isNotBlank)?.let { return "pageToken=${encode(it)}" }
        return null
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun findTokenCount(entry: JsonObject, keys: List<String>, range: IntRange): Int? {
        keys.firstNotNullOfOrNull { entry.tokenCount(it, range) }?.let { return it }
        for (nested in NESTED) {
            val inner = entry.obj(nested) ?: continue
            keys.firstNotNullOfOrNull { inner.tokenCount(it, range) }?.let { return it }
        }
        return null
    }

    private fun JsonObject.tokenCount(key: String, range: IntRange): Int? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        return parseTokenCount(primitive.content)?.takeIf { it in range }
    }

    /**
     * A boolean that may also arrive as "true"/"false", or as Anthropic's
     * `{ "supported": true }`. Null when absent or not a boolean.
     */
    private fun JsonObject.flag(key: String): Boolean? {
        val value = this[key]
        val primitive = (value as? JsonObject)?.get("supported") as? JsonPrimitive
            ?: value as? JsonPrimitive
            ?: return null
        if (primitive is JsonNull) return null
        return primitive.booleanOrNull
    }

    /**
     * The `thinking.type` values Anthropic's Models API says the model takes:
     * `capabilities.thinking.types.{adaptive,enabled,disabled}.supported`.
     * Empty when the listing does not say.
     */
    private fun thinkingTypes(capabilities: JsonObject?): List<String> {
        val thinking = capabilities?.obj("thinking") ?: return emptyList()
        // No thinking at all: "disabled" is all such a model takes.
        if (thinking.flag("supported") == false) return listOf(THINKING_DISABLED)
        val types = thinking.obj("types") ?: return emptyList()
        return THINKING_TYPES.filter { types.flag(it) == true }
    }

    /** Anthropic's `capabilities.effort`: `{ supported, low: { supported }, …, max: { supported } }`. */
    private fun effortCapability(capabilities: JsonObject?): List<String> {
        val effort = capabilities?.obj("effort") ?: return emptyList()
        if (effort.flag("supported") == false) return emptyList()
        return ReasoningLevels.ORDER.filter { it != ReasoningLevels.NONE && effort.flag(it) == true }
    }

    private const val THINKING_DISABLED = "disabled"
    private val THINKING_TYPES = listOf("adaptive", "enabled", THINKING_DISABLED)

    /** `131072`, `"131072"`, `"131,072"`, `131072.0`, `"128k"`, `"1M"`. */
    internal fun parseTokenCount(raw: String): Int? {
        val text = raw.trim().replace("_", "").replace(",", "").lowercase()
        val match = TOKEN_COUNT.matchEntire(text) ?: return null
        val number = match.groupValues[1].toDoubleOrNull() ?: return null
        val scaled = when (match.groupValues[2]) {
            "k" -> number * 1_000
            "m" -> number * 1_000_000
            else -> number
        }
        return if (scaled < 0 || scaled > Int.MAX_VALUE) null else scaled.toInt()
    }

    private val TOKEN_COUNT = Regex("""^(\d+(?:\.\d+)?)\s*([km]?)$""")

    private fun unreported(id: String): ModelSpec = ModelSpec(
        id = id,
        displayName = id,
        contextWindow = FALLBACK_CONTEXT,
        maxOutputTokens = FALLBACK_MAX_OUTPUT,
        metadataFromEndpoint = false,
        notes = "context window not reported — verify this value",
    )

    /**
     * Short note kept with the model.
     *
     * Pricing is included because it changes the decision: on a metered
     * gateway, picking a model for a long agentic session is a cost decision as
     * much as a capability one.
     */
    private fun buildNotes(entry: JsonObject, contextReported: Boolean): String {
        val parts = mutableListOf<String>()

        // OpenRouter: USD per token, as strings.
        entry.obj("pricing")?.let { pricing ->
            val promptPerM = pricing.str("prompt")?.toDoubleOrNull()?.times(1_000_000)
            val completionPerM = pricing.str("completion")?.toDoubleOrNull()?.times(1_000_000)
            if (promptPerM != null && completionPerM != null) {
                parts += if (promptPerM == 0.0 && completionPerM == 0.0) {
                    "free"
                } else {
                    "in ${formatPrice(promptPerM)} / out ${formatPrice(completionPerM)} per 1M"
                }
            }
            pricing.str("input_cache_read")?.toDoubleOrNull()?.let {
                if (it > 0) parts += "cached in ${formatPrice(it * 1_000_000)}"
            }
        }

        // AnyModel: balance tokens burned per raw token, per direction.
        entry.obj("billing")?.obj("coefficient")?.let { coefficient ->
            val input = (coefficient["input"] as? JsonPrimitive)?.doubleOrNull
            val output = (coefficient["output"] as? JsonPrimitive)?.doubleOrNull
            when {
                input != null && output != null && input == output -> parts += "×${formatFactor(input)} per token"
                input != null && output != null -> parts += "×${formatFactor(input)} in / ×${formatFactor(output)} out"
            }
        }
        if (entry.str("owned_by") == "combo") parts += "fallback chain; cost depends on the member that answers"

        entry.str("knowledge_cutoff")?.let { parts += "cutoff $it" }
        if (entry.obj("reasoning")?.bool("mandatory", false) == true) {
            parts += "reasoning always on"
        }
        if (!contextReported) {
            parts += "context window not reported — verify this value"
        }
        return parts.joinToString(" · ")
    }

    private fun formatPrice(perMillion: Double): String = when {
        perMillion >= 100 -> "$${perMillion.toInt()}"
        perMillion >= 1 -> "$${"%.2f".format(perMillion)}"
        else -> "$${"%.3f".format(perMillion)}"
    }

    private fun formatFactor(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else value.toBigDecimal().stripTrailingZeros().toPlainString()

    /** Human-readable context size for the model picker: `1M`, `262k`, `1.05M`. */
    fun formatWindow(tokens: Int): String = when {
        tokens >= 1_000_000 -> {
            val millions = tokens / 1_000_000.0
            if (millions % 1.0 < 0.05) "${millions.toInt()}M" else "%.1fM".format(millions)
        }
        tokens >= 1_000 -> "${tokens / 1_000}k"
        else -> tokens.toString()
    }

    /**
     * Fallback for a model the user typed in by hand.
     *
     * No inference from the name — the user is asked for the window in the
     * editor, with [FALLBACK_CONTEXT] prefilled.
     */
    fun manualEntry(id: String, contextWindow: Int = FALLBACK_CONTEXT): ModelSpec = ModelSpec(
        id = id,
        displayName = id,
        contextWindow = contextWindow,
        maxOutputTokens = (contextWindow / 4).coerceIn(1_024, 32_768),
        metadataFromEndpoint = false,
        notes = "added manually",
    )
}
