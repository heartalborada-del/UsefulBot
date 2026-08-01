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

class BuiltInComicPluginTest {
    @Test
    fun `all provider registrations follow the comic plugin lifecycle`() {
        val root = Files.createTempDirectory("builtin-comic-").toFile()
        val registry = ComicProviderRegistry<String>()
        val plugin = BuiltInComicPlugin(
            registry = registry,
            providers = listOf(
                BuiltInComicProvider("eh", arrayOf("ex"), "eh-provider"),
                BuiltInComicProvider("jm", provider = "jm-provider"),
            ),
        )
        val manager = PluginManager(
            pluginDirectory = File(root, "plugins"),
            bots = emptyList(),
            externalPluginsEnabled = false,
            builtInPlugins = listOf(
                BuiltInPlugin(
                    PluginDescriptor(
                        id = "comic",
                        name = "Comic",
                        version = "1.0.0",
                        main = plugin.javaClass.name,
                    ),
                    plugin,
                ),
            ),
        )

        try {
            assertNull(registry.resolve("eh"))
            assertNull(registry.resolve("jm"))
            manager.loadAndEnableAll()
            assertEquals("eh-provider", registry.resolve("eh"))
            assertEquals("eh-provider", registry.resolve("ex"))
            assertEquals("jm-provider", registry.resolve("jm"))
            manager.close()
            assertNull(registry.resolve("eh"))
            assertNull(registry.resolve("ex"))
            assertNull(registry.resolve("jm"))
        } finally {
            manager.close()
            root.deleteRecursively()
        }
    }
}
