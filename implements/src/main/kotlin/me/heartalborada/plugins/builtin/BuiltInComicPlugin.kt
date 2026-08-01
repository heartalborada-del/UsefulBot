package me.heartalborada.plugins.builtin

import me.heartalborada.comics.ComicProviderRegistry
import me.heartalborada.commons.plugins.UsefulBotPlugin

data class BuiltInComicProvider<P : Any>(
    val id: String,
    val aliases: Array<out String> = emptyArray(),
    val provider: P,
)

/** Owns all built-in comic providers as one plugin lifecycle unit. */
class BuiltInComicPlugin<P : Any>(
    private val registry: ComicProviderRegistry<P>,
    private val providers: List<BuiltInComicProvider<P>>,
) : UsefulBotPlugin {
    override fun onEnable() {
        val registered = mutableListOf<String>()
        try {
            providers.forEach { definition ->
                registry.register(definition.id, definition.provider, *definition.aliases)
                registered += definition.id
            }
        } catch (throwable: Throwable) {
            registered.asReversed().forEach(registry::unregister)
            throw throwable
        }
    }

    override fun onDisable() {
        providers.asReversed().forEach { registry.unregister(it.id) }
    }
}
