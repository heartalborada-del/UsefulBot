package me.heartalborada.comics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ComicProviderRegistryTest {
    @Test
    fun `resolves canonical ids and aliases and rejects collisions`() {
        val registry = ComicProviderRegistry<String>()
        registry.register("eh", "provider", "ex")

        assertEquals("provider", registry.resolve("EH"))
        assertEquals("provider", registry.resolve("ex"))
        assertFailsWith<IllegalArgumentException> { registry.register("jm", "other", "ex") }
        assertEquals("provider", registry.unregister("eh"))
        assertEquals(null, registry.resolve("eh"))
        assertEquals(null, registry.resolve("ex"))
    }
}
