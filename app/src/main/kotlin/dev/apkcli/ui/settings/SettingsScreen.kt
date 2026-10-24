package dev.apkcli.ui.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.apkcli.R
import dev.apkcli.core.llm.ConnectionScheme
import dev.apkcli.core.model.ApprovalRule
import dev.apkcli.core.model.Endpoint
import dev.apkcli.core.model.ReasoningSupport
import dev.apkcli.core.store.BuiltInAgents
import dev.apkcli.di.ServiceLocator
import dev.apkcli.ui.chat.sheets.AgentSheet
import dev.apkcli.ui.chat.sheets.ModelSheet
import dev.apkcli.ui.chat.sheets.ReasoningSheet
import dev.apkcli.ui.chat.sheets.schemeIcon
import dev.apkcli.ui.components.Badge
import dev.apkcli.ui.components.Card
import dev.apkcli.ui.components.CardDivider
import dev.apkcli.ui.components.CheckRow
import dev.apkcli.ui.components.Chevron
import dev.apkcli.ui.components.ConfirmSheet
import dev.apkcli.ui.components.ListRow
import dev.apkcli.ui.components.LocalToaster
import dev.apkcli.ui.components.Note
import dev.apkcli.ui.components.Section
import dev.apkcli.ui.components.Segmented
import dev.apkcli.ui.components.Spinner
import dev.apkcli.ui.components.Tile
import dev.apkcli.ui.components.TileTone
import dev.apkcli.ui.components.TopBar
import dev.apkcli.ui.icons.Codicons
import dev.apkcli.ui.locale.AppLanguage
import dev.apkcli.ui.locale.AppLocales
import dev.apkcli.ui.text.displayDescription
import dev.apkcli.ui.text.displayName
import dev.apkcli.ui.text.reasoningLevelLabel
import dev.apkcli.ui.text.resolve
import dev.apkcli.ui.text.subtitleRes
import dev.apkcli.ui.text.titleRes
import dev.apkcli.ui.theme.Design
import dev.apkcli.ui.workbench.WorkbenchLayout
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

/** Which sheet is over the settings. */
private sealed interface SettingsSheet {
    data object Presets : SettingsSheet
    data class Scheme(val scheme: ConnectionScheme, val endpointId: String?) : SettingsSheet
    data class Custom(val endpointId: String?) : SettingsSheet

    /** [back] is the form the deletion was asked from; cancelling returns there. */
    data class ConfirmDelete(val endpointId: String, val back: SettingsSheet) : SettingsSheet
    data object Agent : SettingsSheet
    data object Reasoning : SettingsSheet
    data object Model : SettingsSheet
}

/**
 * Settings: design/prototype/app.js settingsScreen(), without its About group.
 *
 * Four groups: the connections, the agent (who, how hard it thinks, which
 * model), the rules From Rules follows, and the interface (language, split).
 * The icon sets' licence notices ship in assets/licenses.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    layout: WorkbenchLayout,
    onUpdateLayout: ((WorkbenchLayout) -> WorkbenchLayout) -> Unit,
    modifier: Modifier = Modifier,
    /** Opened from the mode sheet's "Rules": scroll to them once, then [onRulesShown]. */
    showRules: Boolean = false,
    onRulesShown: () -> Unit = {},
) {
    val context = LocalContext.current
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(ServiceLocator.runtime(context)))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val toaster = LocalToaster.current
    var sheet by remember { mutableStateOf<SettingsSheet?>(null) }
    val settings = state.settings
    // Opened from the mode sheet's "Rules": the page starts at them, their
    // header near the top as in the prototype — not just somewhere on screen,
    // which is where the header already was, half the list below the edge.
    val scroll = rememberScrollState()
    var rulesTop by remember { mutableStateOf<Int?>(null) }
    val rulesGap = with(LocalDensity.current) { 8.dp.roundToPx() }
    var rulesWanted by remember { mutableStateOf(showRules) }
    LaunchedEffect(showRules) {
        if (showRules) {
            rulesWanted = true
            onRulesShown()
        }
    }
    LaunchedEffect(rulesWanted) {
        if (rulesWanted) {
            // Once laid out, so the rules have a place on the page.
            val top = snapshotFlow { rulesTop }.filterNotNull().first()
            scroll.scrollTo((top - rulesGap).coerceAtLeast(0))
            rulesWanted = false
        }
    }

    val message = state.message?.resolve()
    LaunchedEffect(message) {
        if (message != null) {
            toaster.show(message, Codicons.Warning)
            viewModel.dismissMessage()
        }
    }

    Column(modifier.fillMaxSize().background(Design.Colors.Bg).statusBarsPadding()) {
        TopBar(
            title = stringResource(R.string.nav_settings),
            onBack = onBack,
            backDescription = stringResource(R.string.a11y_back),
        )
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(scroll)
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 28.dp),
        ) {
            Section(stringResource(R.string.settings_connections), top = 8.dp)
            Card {
                settings.endpoints.forEach { endpoint ->
                    val usable = state.usable(endpoint)
                    EndpointRow(
                        endpoint = endpoint,
                        // Not for one still waiting for its key: nothing can be
                        // sent through it, whatever the setting points at.
                        active = usable && endpoint.id == settings.activeEndpoint?.id,
                        usable = usable,
                        loading = endpoint.id in state.loading,
                        failed = endpoint.id in state.failures,
                        onClick = {
                            val scheme = ConnectionScheme.of(endpoint)
                            sheet = if (scheme.keyOnly) SettingsSheet.Scheme(scheme, endpoint.id) else SettingsSheet.Custom(endpoint.id)
                        },
                    )
                    CardDivider()
                }
                ListRow(
                    title = stringResource(R.string.settings_add_connection),
                    subtitle = stringResource(R.string.settings_add_connection_sub),
                    lead = { Tile(Codicons.Add) },
                    onClick = { sheet = SettingsSheet.Presets },
                )
            }

            Section(stringResource(R.string.settings_agent))
            Card {
                val agent = settings.activeAgent
                ListRow(
                    title = agent?.displayName().orEmpty(),
                    // What the mode does, whole: the choice is read, not skimmed.
                    subtitle = agent?.displayDescription(),
                    subtitleMaxLines = 4,
                    lead = { Tile(Codicons.Agent, TileTone.Accent) },
                    onClick = { sheet = SettingsSheet.Agent },
                )
                CardDivider()
                val model = settings.activeModel
                val level = settings.activeEndpoint?.let { endpoint -> model?.let { settings.reasoningEffortFor(endpoint.id, it) } }
                val reasons = model != null && (model.reasoningEfforts.isNotEmpty() || model.reasoning != ReasoningSupport.NONE)
                ListRow(
                    title = stringResource(R.string.settings_reasoning),
                    subtitle = when {
                        !reasons -> stringResource(R.string.settings_reasoning_none)
                        level != null -> reasoningLevelLabel(level)
                        else -> stringResource(R.string.reasoning_default_chip)
                    },
                    lead = { Tile(Codicons.Thinking) },
                    onClick = { sheet = SettingsSheet.Reasoning },
                )
                CardDivider()
                ListRow(
                    title = stringResource(R.string.settings_model),
                    subtitle = model?.id?.substringAfterLast('/') ?: stringResource(R.string.composer_no_model),
                    lead = { Tile(Codicons.Chip) },
                    onClick = { sheet = SettingsSheet.Model },
                )
            }

            // What From Rules lets through. Editable in any mode: the ticks
            // apply once From Rules is the mode, and the note says so until then.
            val rulesActive = settings.activeAgent?.id == BuiltInAgents.RULES
            Section(
                stringResource(R.string.rules_title),
                modifier = Modifier.onPlaced { rulesTop = it.positionInParent().y.roundToInt() },
                linkText = stringResource(R.string.rules_use).takeUnless { rulesActive },
                onLink = { viewModel.setActiveAgent(BuiltInAgents.RULES) }.takeUnless { rulesActive },
            )
            Card {
                ApprovalRule.entries.forEachIndexed { index, rule ->
                    if (index > 0) CardDivider(inset = 48.dp)
                    CheckRow(
                        title = stringResource(rule.titleRes()),
                        subtitle = stringResource(rule.subtitleRes()),
                        checked = rule in settings.approvalRules,
                        onCheckedChange = { viewModel.setApprovalRule(rule, it) },
                    )
                }
            }
            Note(stringResource(if (rulesActive) R.string.rules_note_active else R.string.rules_note_inactive))

            Section(stringResource(R.string.settings_interface))
            Card { InterfaceSettings(layout = layout, onUpdateLayout = onUpdateLayout) }
        }
    }

    val close = { sheet = null }
    when (val current = sheet) {
        null -> Unit

        SettingsSheet.Presets -> PresetsSheet(
            endpoints = settings.endpoints,
            onPick = { scheme ->
                // A connection to it that is still waiting for its key is
                // the one to give it, rather than a second copy beside it.
                val waiting = settings.endpoints.firstOrNull { ConnectionScheme.of(it) == scheme && !state.usable(it) }
                sheet = when {
                    !scheme.keyOnly -> SettingsSheet.Custom(null)
                    else -> SettingsSheet.Scheme(scheme, waiting?.id)
                }
            },
            onDismiss = close,
        )

        is SettingsSheet.Scheme -> {
            val existing = viewModel.endpoint(current.endpointId)
            SchemeSheet(
                scheme = current.scheme,
                existing = existing,
                keyStored = existing?.let { state.keyPresence[it.id] } == true,
                failure = current.endpointId?.let { state.failures[it] },
                viewModel = viewModel,
                onSaved = close,
                onBack = { sheet = SettingsSheet.Presets },
                onDelete = { current.endpointId?.let { sheet = SettingsSheet.ConfirmDelete(it, current) } },
                onDismiss = close,
            )
        }

        is SettingsSheet.Custom -> {
            val existing = viewModel.endpoint(current.endpointId)
            CustomSheet(
                existing = existing,
                keyStored = existing?.let { state.keyPresence[it.id] } == true,
                failure = current.endpointId?.let { state.failures[it] },
                viewModel = viewModel,
                onSaved = close,
                onBack = { sheet = SettingsSheet.Presets },
                onDelete = { current.endpointId?.let { sheet = SettingsSheet.ConfirmDelete(it, current) } },
                onDismiss = close,
            )
        }

        is SettingsSheet.ConfirmDelete -> {
            val name = viewModel.endpoint(current.endpointId)?.name.orEmpty()
            ConfirmSheet(
                title = stringResource(R.string.settings_delete_endpoint_title, name),
                body = stringResource(R.string.settings_delete_endpoint_body),
                confirm = stringResource(R.string.common_delete),
                dismiss = stringResource(R.string.common_cancel),
                onConfirm = {
                    viewModel.removeEndpoint(current.endpointId)
                    sheet = null
                },
                onDismiss = { sheet = current.back },
            )
        }

        SettingsSheet.Agent -> AgentSheet(
            agents = settings.agents,
            activeId = settings.activeAgent?.id,
            rules = settings.approvalRules,
            onSelect = { id ->
                viewModel.setActiveAgent(id)
                sheet = null
            },
            onRules = {
                sheet = null
                rulesWanted = true
            },
            onDismiss = close,
        )

        SettingsSheet.Reasoning -> ReasoningSheet(
            model = settings.activeModel,
            selected = settings.activeEndpoint?.let { endpoint -> settings.activeModel?.let { settings.reasoningEffortFor(endpoint.id, it) } },
            onSelect = { level ->
                viewModel.setReasoningEffort(level)
                sheet = null
            },
            onDismiss = close,
        )

        SettingsSheet.Model -> {
            val active = settings.activeEndpoint
            val keyFirst = stringResource(R.string.connection_key_first)
            ModelSheet(
                endpoint = active,
                others = settings.endpoints.filter { it.enabled && it.id != active?.id },
                hasKey = state::usable,
                active = settings.activeModel,
                loading = active?.id in state.loading,
                error = active?.id?.let { state.failures[it] },
                onSelect = { id ->
                    active?.let { viewModel.selectModel(it.id, id) }
                    sheet = null
                },
                onSelectEndpoint = { id ->
                    val other = viewModel.endpoint(id)
                    if (other != null && !state.usable(other)) {
                        toaster.show(keyFirst.format(other.name), Codicons.Warning)
                    } else {
                        viewModel.setActiveEndpoint(id)
                    }
                },
                onRefresh = { active?.let { viewModel.refreshModels(it.id) } },
                onDismiss = close,
            )
        }
    }
}

/** One connection: its scheme's tile, accent when requests go to it. */
@Composable
private fun EndpointRow(
    endpoint: Endpoint,
    active: Boolean,
    usable: Boolean,
    loading: Boolean,
    failed: Boolean,
    onClick: () -> Unit,
) {
    val url = endpoint.displayUrl
    ListRow(
        title = endpoint.name.ifBlank { url },
        subtitle = when {
            loading -> stringResource(R.string.settings_loading_models)
            !usable -> "$url · ${stringResource(R.string.connection_needs_key)}"
            failed -> "$url · ${stringResource(R.string.settings_endpoint_failed)}"
            else -> "$url · ${pluralStringResource(R.plurals.models_count, endpoint.models.size, endpoint.models.size)}"
        },
        lead = { Tile(schemeIcon(ConnectionScheme.of(endpoint)), if (active) TileTone.Accent else TileTone.Neutral) },
        titleTrailing = if (active) {
            { Badge(stringResource(R.string.settings_active)) }
        } else {
            null
        },
        trailing = {
            when {
                loading -> Spinner()
                !usable || failed -> {
                    Icon(Codicons.Warning, contentDescription = null, tint = Design.Colors.Warning, modifier = Modifier.size(Design.Size.IconSmall))
                    Chevron()
                }
                else -> Chevron()
            }
        },
        onClick = onClick,
    )
}

/** Whether both panes are on screen: decided by size, or forced either way. */
private enum class SplitChoice { AUTO, ONE, BOTH }

/** `.setting`s: the language and the split, each a line of explanation over a segmented control. */
@Composable
private fun InterfaceSettings(
    layout: WorkbenchLayout,
    onUpdateLayout: ((WorkbenchLayout) -> WorkbenchLayout) -> Unit,
) {
    val context = LocalContext.current
    var language by remember { mutableStateOf(AppLocales.current(context)) }
    Setting(
        title = stringResource(R.string.settings_language),
        subtitle = if (language == AppLanguage.SYSTEM) {
            stringResource(R.string.settings_language_system_is, stringResource(systemLanguageName()))
        } else {
            stringResource(R.string.settings_language_fixed)
        },
    ) {
        Segmented(
            options = listOf(AppLanguage.SYSTEM, AppLanguage.RUSSIAN, AppLanguage.ENGLISH),
            selected = language,
            onSelect = { choice ->
                language = choice
                AppLocales.apply(context, choice)
            },
            label = {
                when (it) {
                    AppLanguage.SYSTEM -> stringResource(R.string.settings_language_system)
                    // Each language in its own name, as language pickers do:
                    // someone who cannot read the current UI still finds theirs.
                    AppLanguage.RUSSIAN -> "Русский"
                    AppLanguage.ENGLISH -> "English"
                }
            },
            modifier = Modifier.padding(top = 10.dp),
        )
    }
    CardDivider(inset = 0.dp)
    Setting(
        title = stringResource(R.string.wb_split_screen),
        subtitle = stringResource(R.string.settings_split_help),
    ) {
        Segmented(
            options = SplitChoice.entries,
            selected = when (layout.split) {
                null -> SplitChoice.AUTO
                false -> SplitChoice.ONE
                true -> SplitChoice.BOTH
            },
            onSelect = { choice ->
                onUpdateLayout {
                    it.copy(
                        split = when (choice) {
                            SplitChoice.AUTO -> null
                            SplitChoice.ONE -> false
                            SplitChoice.BOTH -> true
                        },
                    )
                }
            },
            label = {
                stringResource(
                    when (it) {
                        SplitChoice.AUTO -> R.string.split_auto
                        SplitChoice.ONE -> R.string.split_one
                        SplitChoice.BOTH -> R.string.split_both
                    },
                )
            },
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

@Composable
private fun Setting(title: String, subtitle: String, control: @Composable () -> Unit) {
    Column(Modifier.padding(14.dp)) {
        Text(title, style = Design.Type.Label, color = Design.Colors.Text)
        Text(subtitle, style = Design.Type.BodySmall, color = Design.Colors.TextSecondary, modifier = Modifier.padding(top = 2.dp))
        control()
    }
}

/**
 * The language the app shows when it follows the system: Russian for a
 * Russian device, English otherwise — the two it ships.
 */
private fun systemLanguageName(): Int {
    val system = android.content.res.Resources.getSystem().configuration.locales[0]
    return if (system.language == "ru") R.string.language_name_ru else R.string.language_name_en
}
