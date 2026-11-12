package dev.apkcli.ui.chat.sheets

import dev.apkcli.core.model.ModelSpec
import dev.apkcli.core.model.ReasoningLevels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelGroupsTest {

    private val models = listOf("a", "b", "c", "d", "e", "f", "g", "h").map { ModelSpec(id = it) }
    private fun key(id: String, endpoint: String = "ep") = ReasoningLevels.key(endpoint, id)
    private fun List<ModelSpec>.ids() = map { it.id }

    @Test
    fun `starred first in the endpoint's order, then recent latest first, then the rest`() {
        val groups = groupModels(models, "ep", favorites = setOf(key("f"), key("b")), recent = listOf(key("g"), key("a")))
        assertEquals(listOf("b", "f"), groups.starred.ids())
        assertEquals(listOf("g", "a"), groups.recent.ids())
        assertEquals(listOf("c", "d", "e", "h"), groups.rest.ids())
    }

    @Test
    fun `each model is listed once`() {
        // Starred and recent at once: it is starred.
        val groups = groupModels(models, "ep", favorites = setOf(key("a")), recent = listOf(key("a"), key("c")))
        assertEquals(listOf("a"), groups.starred.ids())
        assertEquals(listOf("c"), groups.recent.ids())
        val all = groups.starred + groups.recent + groups.rest
        assertEquals(models.size, all.size)
        assertEquals(models.ids().toSet(), all.ids().toSet())
    }

    @Test
    fun `recent keeps five, and only this endpoint's models that still exist`() {
        val recent = listOf(key("h"), key("x"), key("a", endpoint = "other"), key("g"), key("f"), key("e"), key("d"), key("c"))
        val groups = groupModels(models, "ep", favorites = emptySet(), recent = recent)
        assertEquals(listOf("h", "g", "f", "e", "d"), groups.recent.ids())
        assertTrue("a" in groups.rest.ids())
    }

    @Test
    fun `with no endpoint everything is the rest`() {
        val groups = groupModels(models, null, favorites = setOf(key("a")), recent = listOf(key("b")))
        assertTrue(groups.starred.isEmpty() && groups.recent.isEmpty())
        assertEquals(models.ids(), groups.rest.ids())
    }
}
