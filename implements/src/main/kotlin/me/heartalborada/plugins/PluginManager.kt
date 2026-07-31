package me.heartalborada.plugins

import me.heartalborada.commons.bots.AbstractBot
import me.heartalborada.commons.plugins.PLUGIN_API_VERSION
import me.heartalborada.commons.plugins.PluginContext
import me.heartalborada.commons.plugins.PluginMetadata
import me.heartalborada.commons.plugins.PluginServiceRegistry
import me.heartalborada.commons.plugins.UsefulBotPlugin
import org.slf4j.LoggerFactory
import java.io.File
import java.util.jar.JarFile
import java.util.concurrent.atomic.AtomicBoolean

/** Runtime state exposed for health checks and diagnostics. */
enum class PluginStatus {
    DISCOVERED,
    DISABLED,
    ENABLED,
    UNLOADED,
    FAILED,
}

data class PluginSnapshot(
    val metadata: PluginMetadata,
    val status: PluginStatus,
    val jar: File?,
    val builtIn: Boolean,
    val essential: Boolean,
    val failure: String? = null,
)

/** Programmatic plugin shipped with the host rather than discovered from a JAR. */
data class BuiltInPlugin(
    val descriptor: PluginDescriptor,
    val instance: UsefulBotPlugin,
    val essential: Boolean = false,
)

/**
 * Discovers descriptor-based plugin JARs, resolves libraries and owns lifecycle.
 * One JAR contains one `usefulbot.plugin.json5` descriptor and one plugin entry point.
 */
class PluginManager(
    private val pluginDirectory: File,
    private val rootDirectory: File,
    private val bots: List<AbstractBot>,
    disabledPluginIds: Set<String> = emptySet(),
    private val builtInPlugins: List<BuiltInPlugin> = emptyList(),
    private val externalPluginsEnabled: Boolean = true,
    private val mandatoryPluginIds: Set<String> = emptySet(),
    private val platformResolver: (AbstractBot) -> String = { bot -> bot.javaClass.simpleName.lowercase() },
) : AutoCloseable {
    private data class ManagedPlugin(
        val descriptor: PluginDescriptor,
        val metadata: PluginMetadata,
        val jar: File?,
        val builtInInstance: UsefulBotPlugin? = null,
        val essential: Boolean = false,
        var status: PluginStatus,
        var failure: String? = null,
        var instance: UsefulBotPlugin? = null,
        var context: PluginContext? = null,
        var classLoader: PluginClassLoader? = null,
    )

    private val logger = LoggerFactory.getLogger(PluginManager::class.java)
    private val disabledIds = disabledPluginIds.mapTo(mutableSetOf()) { it.trim().lowercase() }
    private val libraryResolver by lazy { PluginLibraryResolver(File(pluginDirectory, ".libraries")) }
    private val serviceRegistry = PluginServiceRegistry()
    private val plugins = linkedMapOf<String, ManagedPlugin>()
    private val enableOrder = mutableListOf<ManagedPlugin>()
    private val started = AtomicBoolean()
    private val closed = AtomicBoolean()

    /** Discovers and enables every valid plugin. Repeated calls are rejected. */
    fun loadAndEnableAll() {
        check(started.compareAndSet(false, true)) { "Plugins have already been loaded." }
        pluginDirectory.mkdirs()
        discoverBuiltIns()
        if (externalPluginsEnabled) discover()

        resolveLoadOrder().forEach { plugin ->
            if (plugin.status != PluginStatus.DISCOVERED) return@forEach
            val unavailableDependency = plugin.metadata.dependencies.firstOrNull { dependency ->
                plugins[dependency]?.status != PluginStatus.ENABLED
            }
            if (unavailableDependency != null) {
                fail(plugin, "Required plugin '$unavailableDependency' is not enabled.")
                return@forEach
            }
            enable(plugin)
        }
    }

    fun snapshots(): List<PluginSnapshot> = plugins.values.map { plugin ->
        PluginSnapshot(
            metadata = plugin.metadata,
            status = plugin.status,
            jar = plugin.jar,
            builtIn = plugin.builtInInstance != null,
            essential = plugin.essential,
            failure = plugin.failure,
        )
    }

    /**
     * Unloads an enabled plugin and every enabled plugin that depends on it.
     * Dependents are unloaded first so no live plugin retains a dead dependency.
     */
    fun unload(pluginId: String): Boolean {
        check(!closed.get()) { "Plugin manager is closed." }
        val normalizedId = pluginId.trim().lowercase()
        val target = plugins[normalizedId] ?: return false
        if (target.status != PluginStatus.ENABLED) return false

        val unloadIds = linkedSetOf(normalizedId)
        do {
            val previousSize = unloadIds.size
            enableOrder.forEach { plugin ->
                if (plugin.status == PluginStatus.ENABLED &&
                    plugin.metadata.dependencies.any(unloadIds::contains)
                ) {
                    unloadIds += plugin.metadata.id
                }
            }
        } while (unloadIds.size != previousSize)

        val essentialPlugin = enableOrder.firstOrNull { plugin ->
            plugin.metadata.id in unloadIds && plugin.essential
        }
        if (essentialPlugin != null) {
            logger.warn(
                "Refusing to unload plugin {}; it would unload essential built-in plugin {}.",
                normalizedId,
                essentialPlugin.metadata.id,
            )
            return false
        }

        enableOrder.asReversed()
            .filter { it.metadata.id in unloadIds }
            .forEach(::unloadPlugin)
        enableOrder.removeAll { it.metadata.id in unloadIds }
        return true
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        enableOrder.asReversed().forEach(::unloadPlugin)
        enableOrder.clear()
    }

    private fun discover() {
        val jars = pluginDirectory.listFiles { file ->
            file.isFile && file.extension.equals("jar", ignoreCase = true)
        }?.sortedBy { it.name.lowercase() }.orEmpty()

        jars.forEach { jar ->
            val descriptor = runCatching { readDescriptor(jar) }
                .onFailure { logger.error("Could not read plugin descriptor from {}.", jar.absolutePath, it) }
                .getOrNull()
            if (descriptor == null) {
                logger.debug("Ignoring JAR {} without {}.", jar.name, PluginDescriptor.RESOURCE_NAME)
                return@forEach
            }
            addDiscoveredPlugin(descriptor, jar)
        }
    }

    private fun discoverBuiltIns() {
        builtInPlugins.forEach { builtIn ->
            addDiscoveredPlugin(
                builtIn.descriptor,
                jar = null,
                builtInInstance = builtIn.instance,
                essential = builtIn.essential,
            )
        }
    }

    private fun readDescriptor(jar: File): PluginDescriptor? = JarFile(jar).use { archive ->
        val entry = archive.getJarEntry(PluginDescriptor.RESOURCE_NAME) ?: return null
        archive.getInputStream(entry).bufferedReader(Charsets.UTF_8).use(PluginDescriptor::parse)
    }

    private fun addDiscoveredPlugin(
        descriptor: PluginDescriptor,
        jar: File?,
        builtInInstance: UsefulBotPlugin? = null,
        essential: Boolean = false,
    ) {
        val effectiveDescriptor = descriptor.copy(
            dependencies = descriptor.dependencies + mandatoryPluginIds.filter { it != descriptor.id },
        )
        val metadata = effectiveDescriptor.metadata()
        val validationFailure = validate(effectiveDescriptor)
        val status = when {
            validationFailure != null -> PluginStatus.FAILED
            metadata.id in disabledIds && !essential -> PluginStatus.DISABLED
            else -> PluginStatus.DISCOVERED
        }
        if (essential && metadata.id in disabledIds) {
            logger.warn("Ignoring disable request for essential built-in plugin {}.", metadata.id)
        }
        val managed = ManagedPlugin(
            descriptor = effectiveDescriptor,
            metadata = metadata,
            jar = jar,
            builtInInstance = builtInInstance,
            essential = essential,
            status = status,
            failure = validationFailure,
        )
        val previous = plugins.putIfAbsent(metadata.id, managed)
        if (previous != null) {
            val source = jar?.name ?: "a built-in plugin"
            if (previous.builtInInstance != null && builtInInstance == null) {
                logger.error(
                    "Ignoring external plugin {} from {}; the ID is reserved by a built-in plugin.",
                    metadata.id,
                    source,
                )
            } else {
                fail(previous, "Duplicate plugin ID '${metadata.id}' is also provided by $source.")
                logger.error("Ignoring duplicate plugin {} from {}.", metadata.id, source)
            }
        }
    }

    private fun validate(descriptor: PluginDescriptor): String? = with(descriptor) {
        when {
            !PLUGIN_ID.matches(id) -> "Plugin ID '$id' must match ${PLUGIN_ID.pattern}."
            name.isBlank() -> "Plugin name must not be blank."
            version.isBlank() -> "Plugin version must not be blank."
            !MAIN_CLASS.matches(main) -> "Plugin main class '$main' is invalid."
            apiVersion != PLUGIN_API_VERSION ->
                "Plugin API $apiVersion is incompatible with host API $PLUGIN_API_VERSION."
            id in dependencies -> "A plugin cannot depend on itself."
            dependencies.any { !PLUGIN_ID.matches(it) } -> "A required plugin ID is invalid."
            libraries.any(String::isBlank) -> "Maven library coordinates must not be blank."
            repositories.any(String::isBlank) -> "Maven repository URLs must not be blank."
            else -> null
        }
    }

    private fun resolveLoadOrder(): List<ManagedPlugin> {
        val result = mutableListOf<ManagedPlugin>()
        val visiting = linkedSetOf<String>()
        val visited = mutableSetOf<String>()

        fun visit(plugin: ManagedPlugin) {
            val id = plugin.metadata.id
            if (id in visited || plugin.status != PluginStatus.DISCOVERED) return
            if (!visiting.add(id)) {
                val cycle = (visiting.dropWhile { it != id } + id).joinToString(" -> ")
                visiting.forEach { member -> plugins[member]?.let { fail(it, "Plugin dependency cycle: $cycle") } }
                return
            }
            plugin.metadata.dependencies.sorted().forEach { dependencyId ->
                val dependency = plugins[dependencyId]
                if (dependency == null) {
                    fail(plugin, "Required plugin '$dependencyId' was not found.")
                } else if (dependency.status == PluginStatus.DISABLED) {
                    fail(plugin, "Required plugin '$dependencyId' is disabled.")
                } else {
                    visit(dependency)
                    if (dependency.status == PluginStatus.FAILED) {
                        fail(plugin, "Required plugin '$dependencyId' failed validation or loading.")
                    }
                }
            }
            visiting.remove(id)
            visited += id
            if (plugin.status == PluginStatus.DISCOVERED) result += plugin
        }

        plugins.values.forEach(::visit)
        return result
    }

    private fun enable(plugin: ManagedPlugin) {
        val id = plugin.metadata.id
        var context: PluginContext? = null
        var classLoader: PluginClassLoader? = null
        var instance: UsefulBotPlugin? = null
        try {
            instance = plugin.builtInInstance ?: run {
                val pluginJar = checkNotNull(plugin.jar) { "External plugin $id has no JAR." }
                val libraries = libraryResolver.resolve(
                    plugin.descriptor.libraries,
                    plugin.descriptor.repositories,
                )
                classLoader = PluginClassLoader(
                    urls = (listOf(pluginJar) + libraries).map { it.toURI().toURL() }.toTypedArray(),
                    parent = UsefulBotPlugin::class.java.classLoader,
                    dependencyLoaders = plugin.metadata.dependencies.mapNotNull { dependencyId ->
                        plugins[dependencyId]?.classLoader
                    },
                )
                val entryPoint = Class.forName(plugin.descriptor.main, true, classLoader)
                require(UsefulBotPlugin::class.java.isAssignableFrom(entryPoint)) {
                    "Plugin main class ${plugin.descriptor.main} must implement ${UsefulBotPlugin::class.java.name}."
                }
                @Suppress("UNCHECKED_CAST")
                (entryPoint as Class<out UsefulBotPlugin>).getDeclaredConstructor().newInstance()
            }
            val pluginRoot = File(pluginDirectory, id)
            context = PluginContext(
                pluginId = id,
                rootDirectory = rootDirectory,
                configDirectory = File(pluginRoot, "config"),
                dataDirectory = File(pluginRoot, "data"),
                bots = bots,
                logger = LoggerFactory.getLogger("plugin.$id"),
                services = serviceRegistry,
                platformResolver = platformResolver,
            )
            plugin.classLoader = classLoader
            plugin.instance = instance
            plugin.context = context
            instance.onLoad(context)
            instance.onEnable()
            plugin.status = PluginStatus.ENABLED
            enableOrder += plugin
            logger.info(
                "Enabled plugin {} {} from {}.",
                id,
                plugin.metadata.version,
                plugin.jar?.name ?: "built-in",
            )
        } catch (throwable: Throwable) {
            instance?.let { runCatching { it.onDisable() } }
            context?.close()
            instance?.let { pluginInstance ->
                runCatching { pluginInstance.onUnload() }
                    .onFailure { logger.warn("Plugin {} failed while unloading after an enable failure.", id, it) }
            }
            runCatching { classLoader?.close() }
            plugin.instance = null
            plugin.context = null
            plugin.classLoader = null
            fail(plugin, throwable.message ?: throwable.javaClass.name)
            logger.error("Plugin {} failed to enable.", id, throwable)
        }
    }

    private fun unloadPlugin(plugin: ManagedPlugin) {
        plugin.instance?.let { instance ->
            runCatching { instance.onDisable() }
                .onFailure { logger.warn("Plugin {} failed while disabling.", plugin.metadata.id, it) }
        }
        plugin.context?.close()
        plugin.instance?.let { instance ->
            runCatching { instance.onUnload() }
                .onFailure { logger.warn("Plugin {} failed while unloading.", plugin.metadata.id, it) }
        }
        runCatching { plugin.classLoader?.close() }
            .onFailure { logger.warn("Could not close class loader for plugin {}.", plugin.metadata.id, it) }
        plugin.instance = null
        plugin.context = null
        plugin.classLoader = null
        plugin.status = PluginStatus.UNLOADED
    }

    private fun fail(plugin: ManagedPlugin, reason: String) {
        plugin.status = PluginStatus.FAILED
        plugin.failure = reason
        logger.warn("Plugin {} is unavailable: {}", plugin.metadata.id, reason)
    }

    private companion object {
        val PLUGIN_ID = Regex("[a-z][a-z0-9._-]{0,63}")
        val MAIN_CLASS = Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+")
    }
}
