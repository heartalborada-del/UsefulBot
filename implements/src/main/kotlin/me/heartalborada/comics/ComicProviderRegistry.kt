package me.heartalborada.comics

class ComicProviderRegistry<P : Any> {
    private val providers = linkedMapOf<String, P>()
    private val aliases = linkedMapOf<String, String>()

    fun register(id: String, provider: P, vararg providerAliases: String) {
        val canonical = id.trim().lowercase()
        require(canonical.isNotEmpty() && canonical !in providers) { "Comic provider $canonical is already registered." }
        providers[canonical] = provider
        (listOf(canonical) + providerAliases).forEach { alias ->
            val normalized = alias.trim().lowercase()
            require(normalized.isNotEmpty() && normalized !in aliases) { "Comic provider alias $normalized is already registered." }
            aliases[normalized] = canonical
        }
    }

    fun resolve(id: String): P? = aliases[id.trim().lowercase()]?.let(providers::get)
    fun entries(): List<Pair<String, P>> = providers.entries.map { it.key to it.value }
}
