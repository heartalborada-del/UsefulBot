package me.heartalborada.plugins

import me.heartalborada.commons.plugins.PluginContext
import me.heartalborada.commons.plugins.UsefulBotPlugin
import java.io.File
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PluginManagerTest {
    @AfterTest
    fun resetEvents() {
        events.clear()
    }

    @Test
    fun `external jar cannot replace a reserved built-in plugin id`() {
        val root = Files.createTempDirectory("usefulbot-reserved-plugin-").toFile()
        try {
            val pluginDirectory = File(root, "plugins")
            createPluginJar(pluginDirectory, id = "foundation", main = MissingDependencyPlugin::class.java)
            PluginManager(
                pluginDirectory = pluginDirectory,
                bots = emptyList(),
                builtInPlugins = listOf(
                    BuiltInPlugin(
                        PluginDescriptor(
                            id = "foundation",
                            name = "Foundation",
                            version = "1.0.0",
                            main = FoundationPlugin::class.java.name,
                        ),
                        FoundationPlugin(),
                    ),
                ),
            ).use { manager ->
                manager.loadAndEnableAll()
                val snapshot = manager.snapshots().single()
                assertTrue(snapshot.builtIn)
                assertEquals(PluginStatus.ENABLED, snapshot.status)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `loads built-in plugins without an external jar`() {
        val root = Files.createTempDirectory("usefulbot-builtin-plugin-").toFile()
        try {
            val plugin = FoundationPlugin()
            PluginManager(
                pluginDirectory = File(root, "plugins"),
                bots = emptyList(),
                externalPluginsEnabled = false,
                builtInPlugins = listOf(
                    BuiltInPlugin(
                        PluginDescriptor(
                            id = "foundation",
                            name = "Foundation",
                            version = "1.0.0",
                            main = FoundationPlugin::class.java.name,
                        ),
                        plugin,
                    ),
                ),
            ).use { manager ->
                manager.loadAndEnableAll()
                val snapshot = manager.snapshots().single()
                assertEquals(PluginStatus.ENABLED, snapshot.status)
                assertTrue(snapshot.builtIn)
                assertEquals(null, snapshot.jar)
            }
            assertEquals(
                listOf(
                    "foundation-load",
                    "foundation-enable",
                    "foundation-disable",
                    "foundation-cleanup",
                    "foundation-unload",
                ),
                events,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `loads descriptor plugins in dependency order and cleans up in reverse`() {
        val root = Files.createTempDirectory("usefulbot-plugin-test-").toFile()
        try {
            val pluginDirectory = File(root, "plugins")
            createPluginJar(
                pluginDirectory,
                id = "dependent",
                main = DependentPlugin::class.java,
                dependencies = listOf("foundation"),
            )
            createPluginJar(pluginDirectory, id = "foundation", main = FoundationPlugin::class.java)
            val manager = PluginManager(
                pluginDirectory = pluginDirectory,
                bots = emptyList(),
            )

            manager.loadAndEnableAll()

            assertEquals(
                listOf("foundation-load", "foundation-enable", "dependent-load", "dependent-enable"),
                events,
            )
            assertTrue(manager.snapshots().all { it.status == PluginStatus.ENABLED })
            assertTrue(File(pluginDirectory, "foundation").isDirectory)
            assertTrue(!File(pluginDirectory, "foundation/config").exists())
            assertTrue(!File(pluginDirectory, "foundation/data").exists())
            manager.close()
            assertEquals(
                listOf(
                    "foundation-load",
                    "foundation-enable",
                    "dependent-load",
                    "dependent-enable",
                    "dependent-disable",
                    "dependent-cleanup",
                    "dependent-unload",
                    "foundation-disable",
                    "foundation-cleanup",
                    "foundation-unload",
                ),
                events,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `missing required plugin is reported without invoking lifecycle`() {
        val root = Files.createTempDirectory("usefulbot-plugin-missing-").toFile()
        try {
            val pluginDirectory = File(root, "plugins")
            createPluginJar(
                pluginDirectory,
                id = "missing-dependency",
                main = MissingDependencyPlugin::class.java,
                dependencies = listOf("absent"),
            )
            PluginManager(
                pluginDirectory = pluginDirectory,
                bots = emptyList(),
            ).use { manager ->
                manager.loadAndEnableAll()
                val snapshot = manager.snapshots().single()
                assertEquals(PluginStatus.FAILED, snapshot.status)
                assertTrue(snapshot.failure.orEmpty().contains("not found"))
                assertTrue(events.isEmpty())
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `mandatory permission plugin must exist and essential built-in ignores disable config`() {
        val missingRoot = Files.createTempDirectory("usefulbot-mandatory-missing-").toFile()
        val disabledRoot = Files.createTempDirectory("usefulbot-mandatory-disabled-").toFile()
        try {
            val missingDirectory = File(missingRoot, "plugins")
            createPluginJar(missingDirectory, id = "dependent", main = DependentPlugin::class.java)
            PluginManager(
                pluginDirectory = missingDirectory,
                bots = emptyList(),
                mandatoryPluginIds = setOf("permissions"),
            ).use { manager ->
                manager.loadAndEnableAll()
                val dependent = manager.snapshots().single()
                assertEquals(PluginStatus.FAILED, dependent.status)
                assertTrue(dependent.failure.orEmpty().contains("permissions"))
                assertTrue(dependent.failure.orEmpty().contains("not found"))
            }

            val disabledDirectory = File(disabledRoot, "plugins")
            createPluginJar(disabledDirectory, id = "dependent", main = DependentPlugin::class.java)
            PluginManager(
                pluginDirectory = disabledDirectory,
                bots = emptyList(),
                disabledPluginIds = setOf("permissions"),
                mandatoryPluginIds = setOf("permissions"),
                builtInPlugins = listOf(permissionBuiltIn()),
            ).use { manager ->
                manager.loadAndEnableAll()
                val snapshots = manager.snapshots().associateBy { it.metadata.id }
                assertEquals(PluginStatus.ENABLED, snapshots.getValue("permissions").status)
                assertTrue(snapshots.getValue("permissions").essential)
                assertEquals(PluginStatus.ENABLED, snapshots.getValue("dependent").status)
            }
        } finally {
            missingRoot.deleteRecursively()
            disabledRoot.deleteRecursively()
        }
    }

    @Test
    fun `mandatory permission plugin enables before every other plugin`() {
        val root = Files.createTempDirectory("usefulbot-mandatory-order-").toFile()
        try {
            val pluginDirectory = File(root, "plugins")
            createPluginJar(pluginDirectory, id = "dependent", main = DependentPlugin::class.java)
            PluginManager(
                pluginDirectory = pluginDirectory,
                bots = emptyList(),
                mandatoryPluginIds = setOf("permissions"),
                builtInPlugins = listOf(permissionBuiltIn()),
            ).use { manager ->
                manager.loadAndEnableAll()
                assertTrue(manager.snapshots().all { it.status == PluginStatus.ENABLED })
                assertEquals(
                    listOf("permissions-load", "permissions-enable", "dependent-load", "dependent-enable"),
                    events,
                )
            }
            assertEquals(
                listOf(
                    "permissions-load",
                    "permissions-enable",
                    "dependent-load",
                    "dependent-enable",
                    "dependent-disable",
                    "dependent-cleanup",
                    "dependent-unload",
                    "permissions-disable",
                    "permissions-cleanup",
                    "permissions-unload",
                ),
                events,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `explicit unload cannot remove an essential built-in or its dependents`() {
        val root = Files.createTempDirectory("usefulbot-explicit-unload-").toFile()
        try {
            val pluginDirectory = File(root, "plugins")
            createPluginJar(pluginDirectory, id = "dependent", main = DependentPlugin::class.java)
            PluginManager(
                pluginDirectory = pluginDirectory,
                bots = emptyList(),
                mandatoryPluginIds = setOf("permissions"),
                builtInPlugins = listOf(permissionBuiltIn()),
            ).use { manager ->
                manager.loadAndEnableAll()
                assertTrue(!manager.unload("permissions"))
                assertEquals(
                    PluginStatus.ENABLED,
                    manager.snapshots().single { it.metadata.id == "permissions" }.status,
                )
                assertEquals(
                    PluginStatus.ENABLED,
                    manager.snapshots().single { it.metadata.id == "dependent" }.status,
                )
                assertEquals(
                    listOf(
                        "permissions-load",
                        "permissions-enable",
                        "dependent-load",
                        "dependent-enable",
                    ),
                    events,
                )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun permissionBuiltIn() = BuiltInPlugin(
        PluginDescriptor(
            id = "permissions",
            name = "Permissions",
            version = "1.0.0",
            main = PermissionsPlugin::class.java.name,
        ),
        PermissionsPlugin(),
        essential = true,
    )

    private fun createPluginJar(
        directory: File,
        id: String,
        main: Class<out UsefulBotPlugin>,
        dependencies: List<String> = emptyList(),
    ) {
        directory.mkdirs()
        JarOutputStream(File(directory, "$id.jar").outputStream()).use { jar ->
            jar.putNextEntry(JarEntry(PluginDescriptor.RESOURCE_NAME))
            jar.write(
                """
                    {
                      // JSON5 syntax is intentionally accepted.
                      id: '$id',
                      name: '${id.replaceFirstChar(Char::uppercase)}',
                      version: '1.0.0',
                      main: '${main.name}',
                      dependencies: [${dependencies.joinToString { "'$it'" }}],
                    }
                """.trimIndent().toByteArray(),
            )
            jar.closeEntry()
        }
    }

    class FoundationPlugin : UsefulBotPlugin {
        override fun onLoad(context: PluginContext) {
            events += "foundation-load"
            context.onClose { events += "foundation-cleanup" }
        }

        override fun onEnable() {
            events += "foundation-enable"
        }

        override fun onDisable() {
            events += "foundation-disable"
        }

        override fun onUnload() {
            events += "foundation-unload"
        }
    }

    class DependentPlugin : UsefulBotPlugin {
        override fun onLoad(context: PluginContext) {
            events += "dependent-load"
            context.onClose { events += "dependent-cleanup" }
        }

        override fun onEnable() {
            events += "dependent-enable"
        }

        override fun onDisable() {
            events += "dependent-disable"
        }

        override fun onUnload() {
            events += "dependent-unload"
        }
    }

    class MissingDependencyPlugin : UsefulBotPlugin {
        override fun onLoad(context: PluginContext) {
            events += "should-not-load"
        }
    }

    class PermissionsPlugin : UsefulBotPlugin {
        override fun onLoad(context: PluginContext) {
            events += "permissions-load"
            context.onClose { events += "permissions-cleanup" }
        }

        override fun onEnable() {
            events += "permissions-enable"
        }

        override fun onDisable() {
            events += "permissions-disable"
        }

        override fun onUnload() {
            events += "permissions-unload"
        }
    }

    companion object {
        private val events = mutableListOf<String>()
    }
}
