package me.heartalborada.plugins.builtin

import me.heartalborada.comics.ComicProviderRegistry
import me.heartalborada.commons.plugins.UsefulBotPlugin

/** Owns one built-in comic provider registration through the plugin lifecycle. */
class BuiltInComicProviderPlugin<P : Any>(
    private val id: String,
    private val aliases: Array<out String>,
    private val registry: ComicProviderRegistry<P>,
    private val provider: P,
) : UsefulBotPlugin {
    override fun onEnable() {
        registry.register(id, provider, *aliases)
    }

    override fun onDisable() {
        registry.unregister(id)
    }
}
