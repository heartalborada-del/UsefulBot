package me.heartalborada.commons.plugins

import java.util.concurrent.ConcurrentHashMap

/** Shared, type-safe services published by plugins for other plugins. */
class PluginServiceRegistry {
    private data class Registration(val owner: String, val service: Any)

    private val services = ConcurrentHashMap<Class<*>, Registration>()

    fun <S : Any> register(owner: String, type: Class<S>, service: S): AutoCloseable {
        val registration = Registration(owner, service)
        require(services.putIfAbsent(type, registration) == null) {
            "Plugin service ${type.name} is already registered."
        }
        return AutoCloseable { services.remove(type, registration) }
    }

    fun <S : Any> find(type: Class<S>): S? = services[type]?.service?.let(type::cast)

    fun <S : Any> require(type: Class<S>): S =
        requireNotNull(find(type)) { "Plugin service ${type.name} is not available." }
}
