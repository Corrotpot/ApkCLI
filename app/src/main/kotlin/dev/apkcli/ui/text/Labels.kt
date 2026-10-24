package dev.apkcli.ui.text

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import dev.apkcli.R
import dev.apkcli.core.llm.LlmError
import dev.apkcli.core.model.AgentDefinition
import dev.apkcli.core.model.ApprovalRule
import dev.apkcli.core.model.Protocol
import dev.apkcli.core.store.BuiltInAgents

/*
 * Display text for core enums.
 *
 * The core module's own `label` properties stay English: they also appear in
 * logs and in prompts, where they must not change with the UI language. The
 * screens use these instead.
 */

/**
 * A reasoning level as the endpoint spells it (`xhigh`), in the user's
 * language ("Очень высокое"). An unknown spelling is shown as sent,
 * capitalised, rather than guessed at.
 */
@Composable
@ReadOnlyComposable
fun reasoningLevelLabel(level: String): String = when (level) {
    "none" -> stringResource(R.string.reasoning_level_none)
    "minimal" -> stringResource(R.string.reasoning_level_minimal)
    "low" -> stringResource(R.string.reasoning_level_low)
    "medium" -> stringResource(R.string.reasoning_level_medium)
    "high" -> stringResource(R.string.reasoning_level_high)
    "xhigh" -> stringResource(R.string.reasoning_level_xhigh)
    "max" -> stringResource(R.string.reasoning_level_max)
    else -> level.replaceFirstChar { it.uppercaseChar() }
}

/** A kind of action in Settings › Rules. */
@StringRes
fun ApprovalRule.titleRes(): Int = when (this) {
    ApprovalRule.EDIT -> R.string.rule_edit
    ApprovalRule.DELETE -> R.string.rule_delete
    ApprovalRule.COMMANDS -> R.string.rule_commands
    ApprovalRule.BACKGROUND -> R.string.rule_background
    ApprovalRule.PACKAGES -> R.string.rule_packages
    ApprovalRule.SUBAGENTS -> R.string.rule_subagents
}

/** What the tools of a rule are, under its title. */
@StringRes
fun ApprovalRule.subtitleRes(): Int = when (this) {
    ApprovalRule.EDIT -> R.string.rule_edit_sub
    ApprovalRule.DELETE -> R.string.rule_delete_sub
    ApprovalRule.COMMANDS -> R.string.rule_commands_sub
    ApprovalRule.BACKGROUND -> R.string.rule_background_sub
    ApprovalRule.PACKAGES -> R.string.rule_packages_sub
    ApprovalRule.SUBAGENTS -> R.string.rule_subagents_sub
}

/** A rule's title inside a sentence: "правка файлов", "commands". */
@Composable
@ReadOnlyComposable
fun ApprovalRule.inSentence(): String {
    val locale = LocalConfiguration.current.locales[0]
    return stringResource(titleRes()).lowercase(locale)
}

/** "Without asking: editing files, subagents", or that the agent asks about everything. */
@Composable
@ReadOnlyComposable
fun rulesSummary(rules: Set<ApprovalRule>): String {
    val free = ApprovalRule.entries.filter { it in rules }
    if (free.isEmpty()) return stringResource(R.string.rules_summary_none)
    return stringResource(R.string.rules_summary_free, free.map { it.inSentence() }.joinToString(", "))
}

@StringRes
fun Protocol.labelRes(): Int = when (this) {
    Protocol.OPENAI_CHAT -> R.string.protocol_openai
    Protocol.ANTHROPIC_MESSAGES -> R.string.protocol_anthropic
    Protocol.KIRO -> R.string.protocol_kiro
}

/**
 * Built-in agents by their own names: the three modes keep the names the
 * user gave them in every language; the Explorer is named in the UI
 * language. User-defined agents keep their own name.
 */
@Composable
@ReadOnlyComposable
fun AgentDefinition.displayName(): String = when (id) {
    BuiltInAgents.CONFIRM -> stringResource(R.string.mode_confirm)
    BuiltInAgents.RULES -> stringResource(R.string.mode_rules)
    BuiltInAgents.BYPASS -> stringResource(R.string.mode_bypass)
    BuiltInAgents.EXPLORER -> stringResource(R.string.agent_explorer)
    else -> name
}

@Composable
@ReadOnlyComposable
fun AgentDefinition.displayDescription(): String = when (id) {
    BuiltInAgents.CONFIRM -> stringResource(R.string.mode_confirm_desc)
    BuiltInAgents.RULES -> stringResource(R.string.mode_rules_desc)
    BuiltInAgents.BYPASS -> stringResource(R.string.mode_bypass_desc)
    BuiltInAgents.EXPLORER -> stringResource(R.string.agent_explorer_desc)
    else -> description
}

/** The one-line headline of a failed request, with the HTTP status when there was one. */
fun LlmError.headlineText(): UiText {
    val base = UiText.res(
        when (kind) {
            LlmError.Kind.NETWORK -> R.string.llm_error_network
            LlmError.Kind.TIMEOUT -> R.string.llm_error_timeout
            LlmError.Kind.AUTH -> R.string.llm_error_auth
            LlmError.Kind.RATE_LIMIT -> R.string.llm_error_rate_limit
            LlmError.Kind.BAD_REQUEST -> R.string.llm_error_bad_request
            LlmError.Kind.MODEL_UNAVAILABLE -> R.string.llm_error_model_unavailable
            LlmError.Kind.CONTEXT_OVERFLOW -> R.string.llm_error_context_overflow
            LlmError.Kind.SERVER -> R.string.llm_error_server
            LlmError.Kind.PROTOCOL -> R.string.llm_error_protocol
            LlmError.Kind.CANCELLED -> R.string.llm_error_cancelled
            LlmError.Kind.INSECURE_TRANSPORT -> R.string.llm_error_insecure
            LlmError.Kind.PAYMENT_REQUIRED -> R.string.llm_error_payment
            LlmError.Kind.CONFIGURATION -> R.string.llm_error_configuration
            LlmError.Kind.UNKNOWN -> R.string.llm_error_unknown
        },
    )
    val status = httpStatus ?: return base
    return UiText.Concat(listOf(base, UiText.raw(" (HTTP $status)")))
}

/** The advice that goes with the error, in the UI language; null when there is none. */
fun LlmError.hintText(): UiText? = hint?.let {
    UiText.res(
        when (it) {
            LlmError.Hint.CHECK_KEY -> R.string.llm_hint_check_key
            LlmError.Hint.CHECK_BASE_PATH -> R.string.llm_hint_check_base_path
            LlmError.Hint.PICK_ANOTHER_MODEL -> R.string.llm_hint_pick_another_model
            LlmError.Hint.TOP_UP -> R.string.llm_hint_top_up
            LlmError.Hint.RAISE_IDLE_TIMEOUT -> R.string.llm_hint_raise_idle_timeout
            LlmError.Hint.CHECK_HOST -> R.string.llm_hint_check_host
            LlmError.Hint.CHECK_SERVER_RUNNING -> R.string.llm_hint_check_server_running
            LlmError.Hint.TLS -> R.string.llm_hint_tls
            LlmError.Hint.USE_HTTPS -> R.string.llm_hint_use_https
            LlmError.Hint.MODELS_ROUTE_IS_HTML -> R.string.llm_hint_models_html
            LlmError.Hint.MODELS_ROUTE_NOT_JSON -> R.string.llm_hint_models_not_json
        },
    )
}

/**
 * The provider's own words, marked as such: they stay in whatever language
 * the server wrote them, so they are quoted rather than passed off as the
 * app's text. Null when the server said nothing beyond the status.
 */
fun LlmError.providerText(maxChars: Int = 300): UiText? =
    detail?.trim()?.takeIf { it.isNotEmpty() }?.let { said ->
        val clipped = if (said.length > maxChars) said.take(maxChars).trimEnd() + "…" else said
        UiText.res(R.string.llm_provider_said, clipped)
    }

/** Advice first, then the server's words: one line each, either may be missing. */
fun LlmError.explanationText(): UiText? {
    val lines = listOfNotNull(hintText(), providerText())
    return if (lines.isEmpty()) null else UiText.Concat(lines, separator = "\n")
}
