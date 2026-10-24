package dev.apkcli.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.apkcli.R
import dev.apkcli.core.llm.ConnectionScheme
import dev.apkcli.core.model.Endpoint
import dev.apkcli.core.model.Protocol
import dev.apkcli.ui.chat.sheets.schemeIcon
import dev.apkcli.ui.components.Badge
import dev.apkcli.ui.components.BadgeTone
import dev.apkcli.ui.components.Btn
import dev.apkcli.ui.components.BtnKind
import dev.apkcli.ui.components.Card
import dev.apkcli.ui.components.CardDivider
import dev.apkcli.ui.components.Field
import dev.apkcli.ui.components.IconBtn
import dev.apkcli.ui.components.IconBtnSize
import dev.apkcli.ui.components.ListRow
import dev.apkcli.ui.components.Note
import dev.apkcli.ui.components.Segmented
import dev.apkcli.ui.components.Sheet
import dev.apkcli.ui.components.SheetActions
import dev.apkcli.ui.components.SheetBody
import dev.apkcli.ui.components.SheetHead
import dev.apkcli.ui.components.Tile
import dev.apkcli.ui.components.TileTone
import dev.apkcli.ui.icons.Codicons
import dev.apkcli.ui.text.UiText
import dev.apkcli.ui.text.resolve
import dev.apkcli.ui.theme.Design

/*
 * Adding and editing a connection: design/prototype/app.js presetsSheet()
 * and endpointSheet().
 *
 * A known service is one field, the credential: its address, wire format
 * and headers are the scheme's (ConnectionScheme), shown underneath as facts
 * rather than asked for. Only a custom endpoint asks for more.
 */

/** "Новое подключение": the schemes, and a custom URL last. */
@Composable
internal fun PresetsSheet(
    endpoints: List<Endpoint>,
    onPick: (ConnectionScheme) -> Unit,
    onDismiss: () -> Unit,
) {
    Sheet(onDismiss = onDismiss) {
        SheetHead(
            title = stringResource(R.string.presets_title),
            subtitle = stringResource(R.string.presets_body),
            icon = Codicons.Plug,
        )
        SheetBody {
            Card(Modifier.padding(top = 16.dp)) {
                ConnectionScheme.entries.forEachIndexed { index, scheme ->
                    if (index > 0) CardDivider()
                    val added = scheme.keyOnly && endpoints.any { ConnectionScheme.of(it) == scheme }
                    ListRow(
                        title = schemeName(scheme),
                        subtitle = stringResource(schemeSubRes(scheme)),
                        lead = {
                            Tile(schemeIcon(scheme), if (scheme == ConnectionScheme.CUSTOM) TileTone.Neutral else TileTone.Accent)
                        },
                        titleTrailing = if (added) {
                            { Badge(stringResource(R.string.presets_added), tone = BadgeTone.Neutral) }
                        } else {
                            null
                        },
                        subtitleMaxLines = 2,
                        onClick = { onPick(scheme) },
                    )
                }
            }
        }
    }
}

/**
 * A known service's form: the credential, then the facts that come with
 * the scheme, then what is worth knowing about the service.
 *
 * A saved credential is never read back out of the keystore, so the field
 * starts empty and an empty field means "keep the one you have".
 */
@Composable
internal fun SchemeSheet(
    scheme: ConnectionScheme,
    existing: Endpoint?,
    keyStored: Boolean,
    failure: UiText?,
    viewModel: SettingsViewModel,
    onSaved: () -> Unit,
    onBack: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var key by rememberSaveable { mutableStateOf("") }
    var reveal by rememberSaveable { mutableStateOf(false) }
    // The paste a prefix warning was shown for: saving it again goes ahead.
    var warned by rememberSaveable { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<SettingsViewModel.KeyProblem?>(null) }

    val save = {
        val found = viewModel.keyProblem(scheme, key, keyStored, warned)
        problem = found
        when (found) {
            null -> viewModel.saveScheme(scheme, existing?.id, key, onSaved)
            is SettingsViewModel.KeyProblem.Unexpected -> warned = key.trim()
            else -> Unit
        }
    }

    Sheet(onDismiss = onDismiss) {
        SheetHead(
            title = existing?.name?.ifBlank { null } ?: schemeName(scheme),
            subtitle = stringResource(schemeSubRes(scheme)),
            icon = schemeIcon(scheme),
        )
        SheetBody {
            KeyField(
                label = stringResource(if (scheme == ConnectionScheme.KIRO) R.string.key_label_kiro else R.string.key_label_api),
                value = key,
                onValueChange = {
                    key = it
                    problem = null
                },
                placeholder = if (keyStored) stringResource(R.string.key_kept) else keyHint(scheme),
                reveal = reveal,
                onReveal = { reveal = !reveal },
                helper = when (val current = problem) {
                    SettingsViewModel.KeyProblem.Missing -> stringResource(
                        if (scheme == ConnectionScheme.KIRO) R.string.key_missing_kiro else R.string.key_missing_api,
                    )
                    SettingsViewModel.KeyProblem.BadCharacters -> stringResource(R.string.ep_err_key_chars)
                    is SettingsViewModel.KeyProblem.Unexpected -> stringResource(R.string.key_unexpected, current.prefix)
                    null -> failure?.resolve() ?: stringResource(R.string.key_where, keyWhere(scheme))
                },
                error = problem != null || failure != null,
                onDone = save,
            )
            Card(Modifier.padding(top = 20.dp)) {
                Fact(stringResource(R.string.fact_address), scheme.baseUrl + protocolPath(scheme.protocol))
                CardDivider(inset = 14.dp)
                Fact(stringResource(R.string.fact_format), protocolName(scheme.protocol))
                CardDivider(inset = 14.dp)
                Fact(stringResource(R.string.fact_check), checkRoute(scheme))
            }
            Note(stringResource(schemeNoteRes(scheme)))
        }
        SheetActions {
            if (existing != null) {
                Btn(stringResource(R.string.common_delete), onClick = onDelete, kind = BtnKind.Danger, modifier = Modifier.weight(1f))
            } else {
                Btn(stringResource(R.string.common_back), onClick = onBack, modifier = Modifier.weight(1f))
            }
            Btn(stringResource(R.string.common_save), onClick = save, kind = BtnKind.Primary, modifier = Modifier.weight(1f))
        }
    }
}

/** A custom endpoint: name, URL, wire format, an optional key, optional headers. */
@Composable
internal fun CustomSheet(
    existing: Endpoint?,
    keyStored: Boolean,
    failure: UiText?,
    viewModel: SettingsViewModel,
    onSaved: () -> Unit,
    onBack: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val initial = existing?.let(SettingsViewModel.CustomForm::from) ?: SettingsViewModel.CustomForm()
    var name by rememberSaveable { mutableStateOf(initial.name) }
    var url by rememberSaveable { mutableStateOf(initial.url) }
    var protocol by rememberSaveable { mutableStateOf(initial.protocol) }
    var key by rememberSaveable { mutableStateOf("") }
    var headers by rememberSaveable { mutableStateOf(initial.headers) }
    var reveal by rememberSaveable { mutableStateOf(false) }
    // Problems show once Save was tried, not while the form is being filled.
    var tried by rememberSaveable { mutableStateOf(false) }

    val form = SettingsViewModel.CustomForm(existing?.id, name, url, protocol, key, headers)
    val problems = if (tried) viewModel.validate(form) else emptyMap()
    val save = {
        tried = true
        if (viewModel.validate(form).isEmpty()) viewModel.saveCustom(form, onSaved)
    }

    Sheet(onDismiss = onDismiss) {
        SheetHead(
            title = existing?.name?.ifBlank { null } ?: stringResource(R.string.scheme_custom_name),
            subtitle = stringResource(R.string.custom_sub),
            icon = Codicons.Server,
        )
        SheetBody {
            Field(
                value = name,
                onValueChange = { name = it },
                label = stringResource(R.string.custom_name),
                placeholder = stringResource(R.string.custom_name_hint),
            )
            Field(
                value = url,
                onValueChange = { url = it },
                label = stringResource(R.string.custom_url),
                placeholder = "https://host/v1",
                helper = problems[SettingsViewModel.Field.URL]?.resolve() ?: stringResource(R.string.custom_url_help),
                error = SettingsViewModel.Field.URL in problems,
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                autoCorrect = false,
            )
            Column(Modifier.padding(top = 20.dp)) {
                Text(stringResource(R.string.custom_format), style = Design.Type.LabelSmall, color = Design.Colors.TextSecondary)
                Segmented(
                    options = listOf(Protocol.OPENAI_CHAT, Protocol.ANTHROPIC_MESSAGES),
                    selected = protocol,
                    onSelect = { protocol = it },
                    label = { protocolName(it) },
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    stringResource(if (protocol == Protocol.ANTHROPIC_MESSAGES) R.string.protocol_anthropic_effect else R.string.protocol_openai_effect),
                    style = Design.Type.Caption,
                    color = Design.Colors.TextTertiary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            KeyField(
                label = stringResource(R.string.key_label_api),
                value = key,
                onValueChange = { key = it },
                placeholder = if (keyStored) stringResource(R.string.key_kept) else "",
                reveal = reveal,
                onReveal = { reveal = !reveal },
                helper = problems[SettingsViewModel.Field.KEY]?.resolve()
                    ?: failure?.resolve()
                    ?: stringResource(R.string.key_optional),
                error = SettingsViewModel.Field.KEY in problems || failure != null,
                onDone = null,
            )
            Field(
                value = headers,
                onValueChange = { headers = it },
                label = stringResource(R.string.custom_headers),
                placeholder = "X-Title: ApkCLI",
                helper = problems[SettingsViewModel.Field.HEADERS]?.resolve() ?: stringResource(R.string.custom_headers_help),
                error = SettingsViewModel.Field.HEADERS in problems,
                mono = true,
                singleLine = false,
                maxLines = 4,
                imeAction = androidx.compose.ui.text.input.ImeAction.Default,
            )
            Note(stringResource(R.string.note_custom))
        }
        SheetActions {
            if (existing != null) {
                Btn(stringResource(R.string.common_delete), onClick = onDelete, kind = BtnKind.Danger, modifier = Modifier.weight(1f))
            } else {
                Btn(stringResource(R.string.common_back), onClick = onBack, modifier = Modifier.weight(1f))
            }
            Btn(stringResource(R.string.common_save), onClick = save, kind = BtnKind.Primary, modifier = Modifier.weight(1f))
        }
    }
}

/** The credential field: a key icon, hidden until the eye is tapped. */
@Composable
private fun KeyField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    reveal: Boolean,
    onReveal: () -> Unit,
    helper: String,
    error: Boolean,
    onDone: (() -> Unit)?,
) {
    Field(
        value = value,
        onValueChange = onValueChange,
        label = label,
        placeholder = placeholder,
        helper = helper,
        error = error,
        leading = Codicons.Key,
        secret = !reveal,
        autoCorrect = false,
        onImeAction = onDone,
        trailing = {
            IconBtn(
                if (reveal) Codicons.EyeClosed else Codicons.Eye,
                stringResource(if (reveal) R.string.ep_hide_key else R.string.ep_show_key),
                onClick = onReveal,
                size = IconBtnSize.Composer,
            )
        },
    )
}

/** `.facts` row: what, and its value in mono on the right. */
@Composable
private fun Fact(title: String, value: String) {
    ListRow(
        title = title,
        compact = true,
        titleStyle = Design.Type.LabelSmall,
        trailing = {
            Text(
                value,
                style = Design.Type.MonoCaption,
                color = Design.Colors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 220.dp),
            )
        },
    )
}

/* ---------------------------------------------------- what a scheme says */

@Composable
internal fun schemeName(scheme: ConnectionScheme): String =
    if (scheme == ConnectionScheme.CUSTOM) stringResource(R.string.scheme_custom_name) else scheme.displayName

internal fun schemeSubRes(scheme: ConnectionScheme): Int = when (scheme) {
    ConnectionScheme.ANTHROPIC -> R.string.scheme_sub_anthropic
    ConnectionScheme.OPENAI -> R.string.scheme_sub_openai
    ConnectionScheme.OPENROUTER -> R.string.scheme_sub_openrouter
    ConnectionScheme.KIRO -> R.string.scheme_sub_kiro
    ConnectionScheme.CUSTOM -> R.string.scheme_sub_custom
}

private fun schemeNoteRes(scheme: ConnectionScheme): Int = when (scheme) {
    ConnectionScheme.ANTHROPIC -> R.string.note_anthropic
    ConnectionScheme.OPENAI -> R.string.note_openai
    ConnectionScheme.OPENROUTER -> R.string.note_openrouter
    ConnectionScheme.KIRO -> R.string.note_kiro
    ConnectionScheme.CUSTOM -> R.string.note_custom
}

/** What a credential looks like, as a placeholder. */
private fun keyHint(scheme: ConnectionScheme): String = when (scheme) {
    ConnectionScheme.ANTHROPIC -> "sk-ant-api03-…"
    ConnectionScheme.OPENAI -> "sk-proj-…"
    ConnectionScheme.OPENROUTER -> "sk-or-v1-…"
    ConnectionScheme.KIRO -> "aorAAAAAG…"
    ConnectionScheme.CUSTOM -> ""
}

/** Where a credential is made: addresses and paths, not words to translate. */
private fun keyWhere(scheme: ConnectionScheme): String = when (scheme) {
    ConnectionScheme.ANTHROPIC -> "console.anthropic.com → API keys"
    ConnectionScheme.OPENAI -> "platform.openai.com → API keys"
    ConnectionScheme.OPENROUTER -> "openrouter.ai/keys"
    ConnectionScheme.KIRO -> "~/.aws/sso/cache/kiro-auth-token.json → refreshToken · Builder ID: + <clientIdHash>.json → clientId, clientSecret"
    ConnectionScheme.CUSTOM -> ""
}

/** The request that proves the credential: [ConnectionScheme.checkPath], or the listing, or Kiro's exchange. */
private fun checkRoute(scheme: ConnectionScheme): String = when (scheme) {
    ConnectionScheme.OPENROUTER -> "GET /api/v1/" + scheme.checkPath
    ConnectionScheme.KIRO -> "POST refreshToken · oidc/token"
    else -> "GET /v1/models"
}

private fun protocolPath(protocol: Protocol): String = when (protocol) {
    Protocol.OPENAI_CHAT -> "/chat/completions"
    Protocol.ANTHROPIC_MESSAGES -> "/messages"
    Protocol.KIRO -> "/generateAssistantResponse"
}

internal fun protocolName(protocol: Protocol): String = when (protocol) {
    Protocol.OPENAI_CHAT -> "OpenAI"
    Protocol.ANTHROPIC_MESSAGES -> "Anthropic"
    Protocol.KIRO -> "Kiro"
}
