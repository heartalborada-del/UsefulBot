package me.heartalborada.plugins.builtin

import me.heartalborada.comics.ComicProviderRegistry
import me.heartalborada.plugins.BuiltInPlugin
import me.heartalborada.plugins.PluginDescriptor
import me.heartalborada.plugins.PluginManager
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BuiltInComicProviderPluginTest {
    @Test
    fun `provider registration follows plugin lifecycle`() {
        val root = Files.createTempDirectory("builtin-provider-").toFile()
        val registry = ComicProviderRegistry<String>()
        val plugin = BuiltInComicProviderPlugin(
            id = "eh",
            aliases = arrayOf("ex"),
            registry = registry,
            provider = "provider",
        )
        val manager = PluginManager(
            pluginDirectory = File(root, "plugins"),
            rootDirectory = root,
            bots = emptyList(),
            externalPluginsEnabled = false,
            builtInPlugins = listOf(
                BuiltInPlugin(
                    PluginDescriptor(
                        id = "eh",
                        name = "E-Hentai Provider",
                        version = "1.0.0",
                        main = plugin.javaClass.name,
                    ),
                    plugin,
                ),
            ),
        )

        try {
            assertNull(registry.resolve("eh"))
            manager.loadAndEnableAll()
            assertEquals("provider", registry.resolve("eh"))
            assertEquals("provider", registry.resolve("ex"))
            manager.close()
            assertNull(registry.resolve("eh"))
            assertNull(registry.resolve("ex"))
        } finally {
            manager.close()
            root.deleteRecursively()
        }
    }
}
