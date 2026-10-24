package dev.apkcli.core.llm

import dev.apkcli.core.model.AppSettings
import dev.apkcli.core.model.AuthScheme
import dev.apkcli.core.model.Endpoint
import dev.apkcli.core.model.ModelSpec
import dev.apkcli.core.model.Protocol
import dev.apkcli.core.store.SettingsRepository
import dev.apkcli.core.store.withSchemes
import dev.apkcli.core.store.withoutUnusedFirstRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionSchemesTest {

    @Test
    fun `an endpoint saved before schemes is recognised by its URL and given the current facts`() {
        val old = Endpoint(
            id = "openrouter",
            name = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1/",
            extraHeaders = mapOf("X-Title" to "ApkCLI"),
        )
        val scheme = ConnectionScheme.of(old)
        assertEquals(ConnectionScheme.OPENROUTER, scheme)
        val applied = scheme.applyTo(old)
        assertEquals("openrouter", applied.scheme)
        assertEquals("https://openrouter.ai/api/v1", applied.baseUrl)
        assertEquals("ApkCLI", applied.extraHeaders["X-OpenRouter-Title"])
    }

    @Test
    fun `a custom endpoint keeps everything that is the user's`() {
        val local = Endpoint(
            id = "ep-1",
            name = "llama",
            baseUrl = "http://192.168.1.5:8080/v1",
            protocol = Protocol.ANTHROPIC_MESSAGES,
            authScheme = AuthScheme.NONE,
            extraHeaders = mapOf("X-Route" to "a"),
        )
        val applied = ConnectionScheme.of(local).applyTo(local)
        assertEquals(local.copy(scheme = "custom"), applied)
    }

    @Test
    fun `a scheme endpoint is the scheme's whatever it stored`() {
        val drifted = ConnectionScheme.ANTHROPIC.newEndpoint("a").copy(baseUrl = "https://example.com", protocol = Protocol.OPENAI_CHAT)
        val applied = ConnectionScheme.of(drifted).applyTo(drifted)
        assertEquals("https://api.anthropic.com/v1", applied.baseUrl)
        assertEquals(Protocol.ANTHROPIC_MESSAGES, applied.protocol)
        assertEquals(AuthScheme.X_API_KEY, applied.authScheme)
    }

    @Test
    fun `pasted keys are checked against the service's prefix`() {
        assertTrue(ConnectionScheme.OPENROUTER.looksLikeKey(" sk-or-v1-abc "))
        assertFalse(ConnectionScheme.OPENROUTER.looksLikeKey("sk-ant-api03-x"))
        assertTrue(ConnectionScheme.KIRO.looksLikeKey("aorAAAAAGxyz"))
        assertTrue(
            "a Builder ID sign-in pastes as JSON",
            ConnectionScheme.KIRO.looksLikeKey("""{"refreshToken":"t","clientId":"c","clientSecret":"s"}"""),
        )
        assertFalse(ConnectionScheme.KIRO.looksLikeKey("sk-or-v1-abc"))
        assertTrue("nothing to check for a custom server", ConnectionScheme.CUSTOM.looksLikeKey("anything"))
    }

    @Test
    fun `an AnyModel nobody used is removed once, a used one stays as a custom endpoint`() {
        val openRouter = ConnectionScheme.OPENROUTER.newEndpoint("openrouter")
        val unused = Endpoint(id = "anymodel", name = "AnyModel", baseUrl = "https://anymodel.org/v1")
        val before = AppSettings(endpoints = listOf(unused, openRouter), activeEndpointId = "anymodel", schemaVersion = 2)

        val migrated = before.withSchemes()
        assertEquals(listOf("openrouter"), migrated.endpoints.map { it.id })
        assertEquals("openrouter", migrated.activeEndpointId)

        val used = unused.copy(models = listOf(ModelSpec(id = "am/x")))
        val kept = before.copy(endpoints = listOf(used)).withSchemes()
        assertEquals("custom", kept.endpoints.single().scheme)

        val current = AppSettings(endpoints = listOf(unused), schemaVersion = AppSettings.CURRENT_SCHEMA_VERSION)
        assertEquals("only once: a version-3 blob keeps it", 1, current.withSchemes().endpoints.size)
    }

    @Test
    fun `a new install has no connection, and the keyless OpenRouter earlier versions created goes once`() {
        val fresh = SettingsRepository.defaults()
        assertTrue(fresh.endpoints.isEmpty())
        assertNull(fresh.activeEndpoint)

        val seeded = ConnectionScheme.OPENROUTER.newEndpoint(EndpointDefaults.RETIRED_FIRST_RUN_ID)
            .copy(models = listOf(ModelSpec(id = "listed/without-a-key")))
        val mine = ConnectionScheme.ANTHROPIC.newEndpoint("ep-1")
        val before = AppSettings(
            endpoints = listOf(seeded, mine),
            activeEndpointId = seeded.id,
            activeModelId = "listed/without-a-key",
            schemaVersion = 3,
        )

        val migrated = before.withoutUnusedFirstRun { false }
        assertEquals(listOf("ep-1"), migrated.endpoints.map { it.id })
        assertEquals("ep-1", migrated.activeEndpointId)
        assertNull("the model was the removed connection's", migrated.activeModelId)

        assertEquals("with a key it is the user's", before, before.withoutUnusedFirstRun { it == seeded.id })
        val current = before.copy(schemaVersion = AppSettings.CURRENT_SCHEMA_VERSION)
        assertEquals("only once: a version-4 blob keeps it", current, current.withoutUnusedFirstRun { false })
    }
}
