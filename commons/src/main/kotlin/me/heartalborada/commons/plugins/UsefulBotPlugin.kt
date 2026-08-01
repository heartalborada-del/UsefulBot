package me.heartalborada.commons.plugins

/** Current binary contract version understood by the host. */
const val PLUGIN_API_VERSION: Int = 3

/** Human-readable and machine-readable identity read from the plugin descriptor. */
data class PluginMetadata(
    val id: String,
    val name: String,
    val version: String,
    val description: String = "",
    val apiVersion: Int = PLUGIN_API_VERSION,
    val dependencies: Set<String> = emptySet(),
)

/**
 * Entry point named by `main` in `usefulbot.plugin.json5`.
 *
 * Metadata intentionally lives outside this class, allowing the host to validate
 * compatibility and resolve libraries before loading any plugin bytecode.
 */
interface UsefulBotPlugin {
    /** Allocates registrations and resources through [context]. */
    fun onLoad(context: PluginContext) = Unit

    /** Starts work after this plugin and all required dependencies are loaded. */
    fun onEnable() = Unit

    /** Stops plugin-owned work. The host subsequently releases context resources. */
    fun onDisable() = Unit

    /** Runs after context resources are released and immediately before the plugin is unloaded. */
    fun onUnload() = Unit
}
