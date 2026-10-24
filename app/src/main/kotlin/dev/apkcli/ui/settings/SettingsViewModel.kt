package dev.apkcli.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.apkcli.R
import dev.apkcli.core.AgentRuntime
import dev.apkcli.core.llm.ConnectionScheme
import dev.apkcli.core.llm.EndpointDefaults
import dev.apkcli.core.llm.Http
import dev.apkcli.core.llm.KiroCredential
import dev.apkcli.core.model.AppSettings
import dev.apkcli.core.model.ApprovalRule
import dev.apkcli.core.model.AuthScheme
import dev.apkcli.core.model.Endpoint
import dev.apkcli.core.model.Protocol
import dev.apkcli.core.store.SettingsRepository
import dev.apkcli.ui.text.UiText
import dev.apkcli.ui.text.explanationText
import dev.apkcli.ui.text.headlineText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings: design/prototype/app.js settingsScreen() and its sheets.
 *
 * A connection to a known service is its credential and nothing else
 * ([ConnectionScheme]); a custom one is a name, a URL, a wire format, an
 * optional key and optional headers. Saving checks the credential where the
 * listing would not, then lists the models — which is what proves the rest.
 */
class SettingsViewModel(
    private val runtime: AgentRuntime,
) : ViewModel() {

    data class UiState(
        val settings: AppSettings = AppSettings(),
        /** Endpoint id -> a credential is stored. */
        val keyPresence: Map<String, Boolean> = emptyMap(),
        /** Endpoints whose credential is being checked or whose models are being listed. */
        val loading: Set<String> = emptySet(),
        /** Endpoint id -> why its last check or listing failed. */
        val failures: Map<String, UiText> = emptyMap(),
        /** One line for a toast. */
        val message: UiText? = null,
    ) {
        /** [endpoint] can be used: it needs no credential, or one is stored. */
        fun usable(endpoint: Endpoint): Boolean =
            endpoint.authScheme == AuthScheme.NONE || keyPresence[endpoint.id] == true
    }

    /** What is wrong with a pasted credential, before anything is sent. */
    sealed interface KeyProblem {
        /** Nothing pasted, and nothing stored to keep. */
        data object Missing : KeyProblem

        /** Characters a header cannot carry: a pasted line break, usually. */
        data object BadCharacters : KeyProblem

        /**
         * Not what this service's credentials start with. A warning: the
         * prefixes are observed, not all documented, so saving the same
         * paste a second time goes ahead.
         */
        data class Unexpected(val prefix: String) : KeyProblem
    }

    /** A custom endpoint's form, as typed. */
    data class CustomForm(
        /** Null for a new one. */
        val id: String? = null,
        val name: String = "",
        val url: String = "",
        val protocol: Protocol = Protocol.OPENAI_CHAT,
        /** A new key; blank keeps the stored one, or means none is needed. */
        val key: String = "",
        /** One `Name: value` per line. */
        val headers: String = "",
    ) {
        companion object {
            fun from(endpoint: Endpoint) = CustomForm(
                id = endpoint.id,
                name = endpoint.name,
                url = endpoint.baseUrl,
                protocol = endpoint.protocol.takeIf { it == Protocol.ANTHROPIC_MESSAGES } ?: Protocol.OPENAI_CHAT,
                headers = endpoint.extraHeaders.entries.joinToString("\n") { "${it.key}: ${it.value}" },
            )
        }
    }

    enum class Field { URL, KEY, HEADERS }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // Credentials live outside the settings blob: a saved key changes
            // what the list says without any setting changing.
            combine(runtime.settingsRepository.settings, runtime.secrets.changes) { settings, _ -> settings }
                .collect { settings ->
                    val presence = withContext(Dispatchers.Default) {
                        settings.endpoints.associate { it.id to runtime.secrets.hasApiKey(it.id) }
                    }
                    _state.update { it.copy(settings = settings, keyPresence = presence) }
                }
        }
    }

    fun endpoint(id: String?): Endpoint? = _state.value.settings.endpoints.firstOrNull { it.id == id }

    /* ------------------------------------------------------------ schemes */

    /** The problem with saving [key] for [scheme], or null. [stored]: a key is kept if this is blank. */
    fun keyProblem(scheme: ConnectionScheme, key: String, stored: Boolean, warned: String?): KeyProblem? {
        val clean = key.trim()
        // A Kiro sign-in pasted as JSON or as a line is exchanged, never sent
        // as a header, so a header's rules do not apply to it.
        val exchanged = scheme == ConnectionScheme.KIRO && KiroCredential.isStructured(clean)
        return when {
            clean.isEmpty() -> if (stored || !scheme.keyOnly) null else KeyProblem.Missing
            !exchanged && !HEADER_VALUE.matches(clean) -> KeyProblem.BadCharacters
            !scheme.looksLikeKey(clean) && clean != warned -> KeyProblem.Unexpected(scheme.keyPrefix)
            else -> null
        }
    }

    /**
     * Save a connection to [scheme] with [key] — blank keeps the stored one
     * — and list its models. [endpointId] is the connection being edited,
     * null for a new one.
     */
    fun saveScheme(scheme: ConnectionScheme, endpointId: String?, key: String, onSaved: () -> Unit) {
        require(scheme.keyOnly)
        // A Kiro sign-in is stored compact, as one line, with only what a refresh needs.
        val clean = key.trim().let { if (scheme == ConnectionScheme.KIRO && it.isNotEmpty()) KiroCredential.normalize(it) else it }
        viewModelScope.launch {
            val existing = endpoint(endpointId)
            val id = existing?.id ?: SettingsRepository.newId("ep")
            val endpoint = existing?.let(scheme::applyTo) ?: scheme.newEndpoint(id)
            runtime.settingsRepository.upsertEndpoint(endpoint)
            if (clean.isNotEmpty()) {
                withContext(Dispatchers.Default) { runtime.secrets.putApiKey(id, clean) }
                activateIfActiveIsUnusable(id)
            }
            onSaved()
            if (clean.isNotEmpty() || endpoint.models.isEmpty()) check(id)
        }
    }

    /* ------------------------------------------------------------- custom */

    /** Problems with [form], by field; empty means it can be saved. */
    fun validate(form: CustomForm): Map<Field, UiText> = buildMap {
        val url = EndpointDefaults.normaliseBaseUrl(form.url)
        when {
            form.url.isBlank() -> put(Field.URL, UiText.res(R.string.ep_err_url_required))
            url == null -> put(Field.URL, UiText.res(R.string.ep_err_url_invalid))
        }
        parseHeaders(form.headers).exceptionOrNull()?.let {
            put(Field.HEADERS, UiText.res(R.string.ep_err_headers))
        }
        val key = form.key.trim()
        if (key.isNotEmpty() && !HEADER_VALUE.matches(key)) put(Field.KEY, UiText.res(R.string.ep_err_key_chars))
        // A key over plain HTTP to a public host would be refused at request time anyway.
        if (url != null && key.isNotEmpty()) {
            val probe = Endpoint(id = "probe", name = form.name, baseUrl = url, authScheme = AuthScheme.BEARER)
            if (Http.transportRefusal(probe, key) != null) put(Field.URL, UiText.res(R.string.ep_err_cleartext))
        }
    }

    fun saveCustom(form: CustomForm, onSaved: () -> Unit) {
        if (validate(form).isNotEmpty()) return
        val baseUrl = EndpointDefaults.normaliseBaseUrl(form.url) ?: return
        val headers = parseHeaders(form.headers).getOrDefault(emptyMap())
        val key = form.key.trim()
        viewModelScope.launch {
            val existing = endpoint(form.id)
            val id = existing?.id ?: SettingsRepository.newId("ep")
            val stored = existing != null && withContext(Dispatchers.Default) { runtime.secrets.hasApiKey(id) }
            val moved = existing != null && existing.baseUrl != baseUrl
            val endpoint = (existing ?: EndpointDefaults.blank(id)).copy(
                scheme = ConnectionScheme.CUSTOM.id,
                name = form.name.trim().ifBlank { EndpointDefaults.suggestedName(baseUrl) },
                baseUrl = baseUrl,
                protocol = form.protocol,
                // The credential's header follows from the wire format; with
                // none at all, nothing is sent.
                authScheme = when {
                    key.isEmpty() && !stored -> AuthScheme.NONE
                    form.protocol == Protocol.ANTHROPIC_MESSAGES -> AuthScheme.X_API_KEY
                    else -> AuthScheme.BEARER
                },
                authHeaderName = "Authorization",
                extraHeaders = headers,
                // Models listed from another URL are not this server's.
                models = if (moved) emptyList() else existing?.models.orEmpty(),
                defaultModelId = if (moved) null else existing?.defaultModelId,
            )
            runtime.settingsRepository.upsertEndpoint(endpoint)
            if (key.isNotEmpty()) {
                withContext(Dispatchers.Default) { runtime.secrets.putApiKey(id, key) }
            }
            activateIfActiveIsUnusable(id)
            onSaved()
            if (key.isNotEmpty() || endpoint.models.isEmpty()) check(id)
        }
    }

    /* ---------------------------------------------------------- the rest */

    fun removeEndpoint(endpointId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.Default) { runtime.secrets.remove(endpointId) }
            runtime.settingsRepository.removeEndpoint(endpointId)
            _state.update {
                it.copy(failures = it.failures - endpointId, message = UiText.res(R.string.settings_endpoint_removed))
            }
        }
    }

    fun setActiveEndpoint(endpointId: String) {
        viewModelScope.launch { runtime.settingsRepository.setActiveEndpoint(endpointId) }
    }

    /** Fetch [endpointId]'s model list again, from the model sheet. */
    fun refreshModels(endpointId: String) = check(endpointId)

    /**
     * Is the credential accepted, and what does the service serve? The
     * first is asked only where the second would not tell.
     *
     * Reads the endpoint from the repository, not from this screen's state:
     * right after a save the state has not caught up yet.
     */
    private fun check(endpointId: String) {
        if (endpointId in _state.value.loading) return
        _state.update { it.copy(loading = it.loading + endpointId, failures = it.failures - endpointId) }
        viewModelScope.launch {
            try {
                val endpoint = runtime.settingsRepository.current().endpoints.firstOrNull { it.id == endpointId }
                    ?: return@launch
                try {
                    runtime.llm.checkCredential(endpoint)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    fail(endpointId, R.string.settings_key_rejected, error)
                    return@launch
                }
                val models = try {
                    runtime.refreshModels(endpointId)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    fail(endpointId, R.string.settings_models_failed, error)
                    return@launch
                }
                if (models.isEmpty()) {
                    _state.update { it.copy(failures = it.failures + (endpointId to UiText.res(R.string.settings_models_none))) }
                }
                // The model the agent will use gets its window from its own
                // route where the listing left it out.
                val settings = runtime.settingsRepository.current()
                val updated = settings.endpoints.firstOrNull { it.id == endpointId } ?: return@launch
                val model = if (settings.activeEndpoint?.id == endpointId) settings.activeModel else updated.defaultModel
                model?.let { runCatching { runtime.describeIfUnverified(endpointId, it.id) } }
            } finally {
                _state.update { it.copy(loading = it.loading - endpointId) }
            }
        }
    }

    private fun fail(endpointId: String, template: Int, error: Throwable) {
        val failure = Http.classify(error)
        val head = UiText.res(template, failure.headlineText())
        val text = failure.explanationText()?.let { UiText.Concat(listOf(head, it), separator = "\n") } ?: head
        _state.update { it.copy(failures = it.failures + (endpointId to text), message = head) }
    }

    /**
     * Make [endpointId] the active endpoint when the active one cannot work
     * for want of a key: a first connection, or a key for one that was added
     * while another, keyless, was active.
     */
    private suspend fun activateIfActiveIsUnusable(endpointId: String) {
        val settings = runtime.settingsRepository.current()
        if (settings.activeEndpoint?.id == endpointId) return
        val active = settings.activeEndpoint
        val activeUsable = active != null && (
            active.authScheme == AuthScheme.NONE ||
                withContext(Dispatchers.Default) { runtime.secrets.hasApiKey(active.id) }
            )
        if (!activeUsable && settings.endpoints.any { it.id == endpointId }) {
            runtime.settingsRepository.setActiveEndpoint(endpointId)
        }
    }

    /** Picking a model also makes its endpoint the active one. */
    fun selectModel(endpointId: String, modelId: String) {
        viewModelScope.launch {
            runtime.settingsRepository.setActiveEndpoint(endpointId, modelId)
            runCatching { runtime.describeIfUnverified(endpointId, modelId) }
        }
    }

    /** One of the active model's published levels; null goes back to the model's default. */
    fun setReasoningEffort(level: String?) {
        val settings = _state.value.settings
        val endpointId = settings.activeEndpoint?.id ?: return
        val model = settings.activeModel ?: return
        if (level != null && level !in model.reasoningEfforts) return
        viewModelScope.launch { runtime.settingsRepository.setReasoningEffort(endpointId, model.id, level) }
    }

    fun setActiveAgent(agentId: String) {
        viewModelScope.launch { runtime.settingsRepository.setActiveAgent(agentId) }
    }

    /** Settings › Rules: whether From Rules lets [rule] through without asking. */
    fun setApprovalRule(rule: ApprovalRule, allowed: Boolean) {
        viewModelScope.launch { runtime.settingsRepository.setApprovalRule(rule, allowed) }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    companion object {
        /** RFC 9110 token, which is what OkHttp enforces for header names. */
        private val HEADER_NAME = Regex("^[!#$%&'*+\\-.^_`|~0-9A-Za-z]+$")

        /** Printable ASCII and tab: OkHttp rejects anything else in a value. */
        private val HEADER_VALUE = Regex("^[\\t\\x20-\\x7E]*$")

        /** `Name: value` per line; blank lines ignored. */
        fun parseHeaders(text: String): Result<Map<String, String>> = runCatching {
            val headers = LinkedHashMap<String, String>()
            text.lines().forEachIndexed { index, raw ->
                val line = raw.trim()
                if (line.isEmpty()) return@forEachIndexed
                val colon = line.indexOf(':')
                require(colon > 0) { "Line ${index + 1}: expected 'Name: value'" }
                val name = line.substring(0, colon).trim()
                val value = line.substring(colon + 1).trim()
                require(HEADER_NAME.matches(name)) { "Line ${index + 1}: '$name' is not a valid header name" }
                require(HEADER_VALUE.matches(value)) { "Line ${index + 1}: the value has characters a header cannot carry" }
                headers[name] = value
            }
            headers
        }

        fun factory(runtime: AgentRuntime): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SettingsViewModel(runtime) as T
            }
    }
}
