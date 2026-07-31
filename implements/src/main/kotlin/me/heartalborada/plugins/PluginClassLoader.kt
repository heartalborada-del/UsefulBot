package me.heartalborada.plugins

import java.net.URL
import java.net.URLClassLoader
import java.util.Collections
import java.util.Enumeration

/**
 * Child-first loader for plugin implementation classes and libraries.
 * Host API, Kotlin and logging types remain parent-first to preserve type identity.
 */
class PluginClassLoader(
    urls: Array<URL>,
    parent: ClassLoader,
    private val dependencyLoaders: List<PluginClassLoader>,
) : URLClassLoader(urls, parent) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(getClassLoadingLock(name)) {
        findLoadedClass(name)?.let { return@synchronized it }
        val loaded = if (PARENT_FIRST_PREFIXES.any(name::startsWith)) {
            super.loadClass(name, false)
        } else {
            try {
                findClass(name)
            } catch (_: ClassNotFoundException) {
                loadFromDependencies(name) ?: super.loadClass(name, false)
            }
        }
        if (resolve) resolveClass(loaded)
        loaded
    }

    override fun getResource(name: String): URL? =
        findResource(name)
            ?: dependencyLoaders.firstNotNullOfOrNull { it.getResource(name) }
            ?: super.getResource(name)

    override fun getResources(name: String): Enumeration<URL> {
        val resources = linkedSetOf<URL>()
        findResources(name).toList(resources)
        dependencyLoaders.forEach { it.getResources(name).toList(resources) }
        super.getResources(name).toList(resources)
        return Collections.enumeration(resources)
    }

    private fun loadFromDependencies(name: String): Class<*>? =
        dependencyLoaders.firstNotNullOfOrNull { dependency ->
            try {
                dependency.loadClass(name)
            } catch (_: ClassNotFoundException) {
                null
            }
        }

    private fun Enumeration<URL>.toList(target: MutableCollection<URL>) {
        while (hasMoreElements()) target += nextElement()
    }

    private companion object {
        val PARENT_FIRST_PREFIXES = listOf(
            "java.",
            "javax.",
            "jdk.",
            "sun.",
            "kotlin.",
            "kotlinx.coroutines.",
            "me.heartalborada.commons.",
            "org.slf4j.",
        )
    }
}
