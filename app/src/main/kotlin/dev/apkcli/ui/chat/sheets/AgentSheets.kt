package dev.apkcli.ui.chat.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.apkcli.R
import dev.apkcli.core.agent.ContextBudget
import dev.apkcli.core.llm.ConnectionScheme
import dev.apkcli.core.llm.ModelSource
import dev.apkcli.core.model.AgentDefinition
import dev.apkcli.core.model.ApprovalRule
import dev.apkcli.core.model.AuthScheme
import dev.apkcli.core.model.Endpoint
import dev.apkcli.core.model.ModelSpec
import dev.apkcli.core.model.ReasoningLevels
import dev.apkcli.core.model.ReasoningSupport
import dev.apkcli.ui.components.Btn
import dev.apkcli.ui.components.BtnKind
import dev.apkcli.ui.components.Card
import dev.apkcli.ui.components.CardDivider
import dev.apkcli.ui.components.EmptyText
import dev.apkcli.ui.components.ListRow
import dev.apkcli.ui.components.Note
import dev.apkcli.ui.components.RadioRow
import dev.apkcli.ui.components.SearchField
import dev.apkcli.ui.components.IconBtn
import dev.apkcli.ui.components.IconBtnSize
import dev.apkcli.ui.components.Section
import dev.apkcli.ui.components.Sheet
import dev.apkcli.ui.components.SheetActions
import dev.apkcli.ui.components.SheetBody
import dev.apkcli.ui.components.SheetHead
import dev.apkcli.ui.components.Tile
import dev.apkcli.ui.icons.Codicons
import dev.apkcli.ui.text.displayDescription
import dev.apkcli.ui.text.displayName
import dev.apkcli.ui.text.reasoningLevelLabel
import dev.apkcli.ui.text.resolve
import dev.apkcli.ui.text.rulesSummary
import dev.apkcli.ui.text.tokensText
import dev.apkcli.ui.text.UiText
import dev.apkcli.ui.theme.Design

/*
 * The agent tab's sheets that pick something: the agent, the model, the
 * reasoning level; and the context breakdown. design/prototype/app.js
 * agentSheet(), modelSheet(), reasoningSheet(), contextSheet().
 */

/**
 * The agent's mode: when it asks before acting. Confirm, From Rules and
 * Bypass Mode, and under them the rules From Rules follows, one tap from
 * choosing it. The Explorer is not a mode and is not listed.
 */
@Composable
fun AgentSheet(
    agents: List<AgentDefinition>,
    activeId: String?,
    rules: Set<ApprovalRule>,
    onSelect: (String) -> Unit,
    onRules: () -> Unit,
    onDismiss: () -> Unit,
) {
    Sheet(onDismiss = onDismiss) {
        SheetHead(
            title = stringResource(R.string.agent_sheet_title),
            subtitle = stringResource(R.string.agent_sheet_body),
            icon = Codicons.Agent,
        )
        SheetBody {
            Card(Modifier.padding(top = 16.dp)) {
                agents.filterNot { it.hidden }.forEachIndexed { index, agent ->
                    if (index > 0) CardDivider(inset = 48.dp)
                    RadioRow(
                        title = agent.displayName(),
                        subtitle = agent.displayDescription(),
                        selected = agent.id == activeId,
                        onClick = { onSelect(agent.id) },
                    )
                }
            }
            Card(Modifier.padding(top = 16.dp)) {
                ListRow(
                    title = stringResource(R.string.rules_title),
                    subtitle = rulesSummary(rules),
                    subtitleMaxLines = 3,
                    lead = { Tile(Codicons.Checklist) },
                    onClick = onRules,
                )
            }
        }
    }
}

/**
 * The models of the connection requests go to, the switch to another one,
 * and where the list came from — which says how far its numbers can be
 * trusted.
 */
@Composable
fun ModelSheet(
    endpoint: Endpoint?,
    others: List<Endpoint>,
    hasKey: (Endpoint) -> Boolean,
    active: ModelSpec?,
    loading: Boolean,
    error: UiText?,
    onSelect: (String) -> Unit,
    onSelectEndpoint: (String) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
    /** Starred models, keyed by ReasoningLevels.key; listed first. */
    favorites: Set<String> = emptySet(),
    /** Models picked by hand, the latest first; listed after the starred. */
    recent: List<String> = emptyList(),
    onToggleFavorite: ((String) -> Unit)? = null,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val models = endpoint?.models.orEmpty()
    Sheet(onDismiss = onDismiss) {
        SheetHead(
            title = stringResource(R.string.model_sheet_title),
            subtitle = (endpoint?.name ?: stringResource(R.string.model_sheet_no_connection)) +
                " · " + pluralStringResource(R.plurals.models_count, models.size, models.size),
            icon = Codicons.Chip,
        )
        if (models.size > SEARCH_FROM) {
            SearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = stringResource(R.string.model_sheet_search),
                clearDescription = stringResource(R.string.a11y_clear),
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        SheetBody {
            val groups = remember(models, query, favorites, recent, endpoint?.id) {
                val q = query.trim()
                groupModels(
                    models.filter { q.isEmpty() || it.id.contains(q, ignoreCase = true) || it.displayName.contains(q, ignoreCase = true) },
                    endpoint?.id,
                    favorites,
                    recent,
                )
            }
            if (groups.isEmpty()) {
                EmptyText(
                    when {
                        models.isNotEmpty() -> stringResource(R.string.projects_nothing_found, query.trim())
                        loading -> stringResource(R.string.composer_loading_models)
                        else -> stringResource(R.string.model_sheet_empty)
                    },
                )
            } else {
                // Headings only when there is more than one group to tell apart.
                val headed = groups.starred.isNotEmpty() || groups.recent.isNotEmpty()
                listOf(
                    R.string.model_sheet_starred to groups.starred,
                    R.string.model_sheet_recent to groups.recent,
                    R.string.model_sheet_all to groups.rest,
                ).filter { (_, list) -> list.isNotEmpty() }.forEachIndexed { groupIndex, (heading, list) ->
                    if (headed) Section(stringResource(heading)) else if (groupIndex == 0) Spacer(Modifier.height(16.dp))
                    Card {
                        list.forEachIndexed { index, model ->
                            if (index > 0) CardDivider(inset = 48.dp)
                            val key = endpoint?.let { ReasoningLevels.key(it.id, model.id) }
                            val starred = key != null && key in favorites
                            RadioRow(
                                title = model.id.substringAfterLast('/'),
                                subtitle = modelLine(model, endpoint),
                                selected = model.id == active?.id,
                                onClick = { onSelect(model.id) },
                                trailing = onToggleFavorite?.let { toggle ->
                                    {
                                        IconBtn(
                                            if (starred) Codicons.StarFull else Codicons.StarEmpty,
                                            contentDescription = stringResource(
                                                if (starred) R.string.a11y_unstar_model else R.string.a11y_star_model,
                                            ),
                                            onClick = { toggle(model.id) },
                                            size = IconBtnSize.Small,
                                            tint = if (starred) Design.Colors.Warning else Design.Colors.TextTertiary,
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
            error?.let { Note(it.resolve(), icon = Codicons.Warning, color = Design.Colors.Error) }
            if (endpoint != null && models.isNotEmpty()) {
                Note(
                    stringResource(
                        when (ModelSource.of(endpoint)) {
                            ModelSource.FULL -> R.string.model_source_full
                            ModelSource.IDS -> R.string.model_source_ids
                        },
                    ),
                )
            }
            if (endpoint != null) {
                Btn(
                    text = stringResource(if (models.isEmpty()) R.string.composer_load_models else R.string.composer_refresh_models),
                    onClick = onRefresh,
                    kind = BtnKind.Ghost,
                    icon = Codicons.Refresh,
                    busy = loading,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            if (others.isNotEmpty()) {
                Section(stringResource(R.string.model_sheet_others))
                Card {
                    others.forEachIndexed { index, other ->
                        if (index > 0) CardDivider()
                        ListRow(
                            title = other.name.ifBlank { other.displayUrl },
                            subtitle = if (other.authScheme == AuthScheme.NONE || hasKey(other)) {
                                pluralStringResource(R.plurals.models_count, other.models.size, other.models.size)
                            } else {
                                stringResource(R.string.connection_needs_key)
                            },
                            lead = { Tile(schemeIcon(ConnectionScheme.of(other))) },
                            onClick = { onSelectEndpoint(other.id) },
                        )
                    }
                }
            }
        }
    }
}

/** The model sheet's list in its three groups; see [groupModels]. */
internal data class ModelGroups(val starred: List<ModelSpec>, val recent: List<ModelSpec>, val rest: List<ModelSpec>) {
    fun isEmpty() = starred.isEmpty() && recent.isEmpty() && rest.isEmpty()
}

/**
 * [models] as the sheet lists them: the starred in the endpoint's order, then
 * up to [RECENT_SHOWN] of those picked lately, the latest first, then the
 * rest in the endpoint's order. Each model once. Keys are ReasoningLevels.key
 * of [endpointId]; with none, everything is the rest.
 */
internal fun groupModels(
    models: List<ModelSpec>,
    endpointId: String?,
    favorites: Set<String>,
    recent: List<String>,
): ModelGroups {
    if (endpointId == null) return ModelGroups(emptyList(), emptyList(), models)
    fun key(model: ModelSpec) = ReasoningLevels.key(endpointId, model.id)
    val starred = models.filter { key(it) in favorites }
    val byKey = models.associateBy(::key)
    val lately = recent.mapNotNull { byKey[it] }.filter { key(it) !in favorites }.take(RECENT_SHOWN)
    val taken = (starred + lately).map { it.id }.toSet()
    return ModelGroups(starred, lately, models.filter { it.id !in taken })
}

private const val RECENT_SHOWN = 5

/** "context 200k · answer 32k · images": the spec in one line. */
@Composable
private fun modelLine(model: ModelSpec, endpoint: Endpoint?): String {
    val parts = mutableListOf(
        if (model.metadataFromEndpoint || model.userEdited) {
            stringResource(R.string.model_line_context, tokensText(model.contextWindow))
        } else {
            stringResource(R.string.model_line_context_unknown, tokensText(model.contextWindow))
        },
        stringResource(R.string.model_line_output, tokensText(model.maxOutputTokens)),
    )
    if (model.supportsImages) parts += stringResource(R.string.model_line_images)
    // OpenAI's pro and codex models answer only on /responses, which no
    // adapter here speaks: flagged, not offered as if they would work.
    if (endpoint?.let(ConnectionScheme::of) == ConnectionScheme.OPENAI && Regex("-(pro|codex)\\b").containsMatchIn(model.id)) {
        parts += stringResource(R.string.model_line_responses_only)
    }
    model.notes.takeIf { it.contains("credits") }?.let { parts += it.substringAfterLast(" · ") }
    return parts.joinToString(" · ")
}

/** The icon a connection's scheme is drawn with, as the scheme sheet draws it. */
fun schemeIcon(scheme: ConnectionScheme) = when (scheme) {
    ConnectionScheme.ANTHROPIC -> Codicons.Hubot
    ConnectionScheme.OPENAI -> Codicons.Sparkle
    ConnectionScheme.OPENROUTER -> Codicons.Globe
    ConnectionScheme.KIRO -> Codicons.ServerEnvironment
    ConnectionScheme.CUSTOM -> Codicons.Server
}

/**
 * The model's own levels, as its endpoint publishes them — no scale of our
 * own. "Default" is a choice when the endpoint names no default: nothing is
 * sent and the model decides.
 */
@Composable
fun ReasoningSheet(
    model: ModelSpec?,
    selected: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val levels = model?.reasoningEfforts.orEmpty()
    val offersDefault = levels.isNotEmpty() && model?.defaultReasoningEffort == null
    Sheet(onDismiss = onDismiss) {
        SheetHead(
            title = stringResource(R.string.reasoning_sheet_title),
            subtitle = stringResource(R.string.reasoning_sheet_body),
            icon = Codicons.Thinking,
        )
        SheetBody {
            if (levels.isEmpty()) {
                Note(
                    if (model != null && model.reasoning != ReasoningSupport.NONE) {
                        stringResource(R.string.reasoning_not_published, model.displayName)
                    } else {
                        stringResource(R.string.reasoning_none_for_model)
                    },
                )
            } else {
                Card(Modifier.padding(top = 16.dp)) {
                    if (offersDefault) {
                        RadioRow(
                            title = stringResource(R.string.reasoning_default_chip),
                            subtitle = stringResource(R.string.reasoning_default_item_desc),
                            selected = selected == null,
                            onClick = { onSelect(null) },
                        )
                    }
                    ReasoningLevels.normalize(levels).forEachIndexed { index, level ->
                        if (index > 0 || offersDefault) CardDivider(inset = 48.dp)
                        RadioRow(
                            title = reasoningLevelLabel(level),
                            subtitle = reasoningLevelDescription(level, isDefault = level == model?.defaultReasoningEffort),
                            selected = level == selected,
                            onClick = { onSelect(level) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun reasoningLevelDescription(level: String, isDefault: Boolean): String {
    val description = when (level) {
        "none" -> stringResource(R.string.reasoning_desc_none)
        "minimal" -> stringResource(R.string.reasoning_desc_minimal)
        "low" -> stringResource(R.string.reasoning_desc_low)
        "medium" -> stringResource(R.string.reasoning_desc_medium)
        "high" -> stringResource(R.string.reasoning_desc_high)
        "xhigh" -> stringResource(R.string.reasoning_desc_xhigh)
        "max" -> stringResource(R.string.reasoning_desc_max)
        else -> ""
    }
    val suffix = if (isDefault) stringResource(R.string.reasoning_model_default) else null
    return listOfNotNull(description.ifBlank { null }, suffix).joinToString(" · ")
}

/**
 * What fills the window: the conversation, the tool descriptions, the
 * instructions, memory; and "Compact now", /compact without typing it.
 */
@Composable
fun ContextSheet(
    accounting: ContextBudget.Accounting?,
    model: ModelSpec?,
    /** /compact cannot run now (ChatViewModel.UiState.compactBlock). */
    compactBlocked: Boolean,
    onCompact: () -> Unit,
    onDismiss: () -> Unit,
) {
    val window = model?.contextWindow ?: accounting?.promptBudget ?: 0
    Sheet(onDismiss = onDismiss) {
        SheetHead(
            title = stringResource(R.string.context_usage),
            subtitle = listOfNotNull(
                model?.id?.substringAfterLast('/'),
                stringResource(R.string.context_window, tokensText(window)).takeIf { window > 0 },
            ).joinToString(" · "),
            icon = Codicons.Pulse,
        )
        SheetBody {
            if (accounting == null) {
                Note(stringResource(R.string.context_after_first))
                Note(stringResource(R.string.context_compact_note), icon = Codicons.Fold)
                return@SheetBody
            }
            val fraction = accounting.fraction.coerceIn(0f, 1f)
            Box(
                Modifier
                    .padding(top = 18.dp)
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Design.Colors.Surface3)
                    .drawBehind {
                        drawRoundRect(
                            Design.Colors.Accent,
                            size = Size(size.width * fraction, size.height),
                            cornerRadius = CornerRadius(3.dp.toPx()),
                        )
                    },
            )
            Text(
                stringResource(
                    R.string.context_used_left,
                    tokensText(accounting.promptTokens),
                    tokensText(accounting.remaining),
                ),
                style = Design.Type.BodySmall,
                color = Design.Colors.TextSecondary,
                modifier = Modifier.padding(top = 12.dp),
            )
            Card(Modifier.padding(top = 16.dp)) {
                val rows = listOfNotNull(
                    stringResource(R.string.context_conversation) to accounting.liveMessageTokens,
                    stringResource(R.string.context_tools) to accounting.toolSchemaTokens,
                    stringResource(R.string.context_instructions) to accounting.systemTokens,
                    (stringResource(R.string.context_digest) to accounting.digestTokens).takeIf { accounting.digestTokens > 0 },
                    (stringResource(R.string.context_memory) to accounting.memoryTokens).takeIf { accounting.memoryTokens > 0 },
                )
                rows.forEachIndexed { index, (label, value) ->
                    if (index > 0) CardDivider(inset = 14.dp)
                    ListRow(
                        title = label,
                        compact = true,
                        trailing = {
                            Text(tokensText(value), style = Design.Type.MonoCaption, color = Design.Colors.TextSecondary)
                        },
                    )
                }
            }
            Note(stringResource(R.string.context_reply_reserve, tokensText(accounting.maxOutputTokens)))
            Note(stringResource(R.string.context_compact_note), icon = Codicons.Fold)
        }
        SheetActions {
            Btn(
                stringResource(R.string.context_compact_now),
                onClick = onCompact,
                icon = Codicons.Fold,
                enabled = !compactBlocked,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** A connection's models are listed only above this many with a search box. */
private const val SEARCH_FROM = 6
