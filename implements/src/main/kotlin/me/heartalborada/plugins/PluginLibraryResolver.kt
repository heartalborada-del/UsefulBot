package me.heartalborada.plugins

import org.apache.maven.repository.internal.MavenRepositorySystemUtils
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.collection.CollectRequest
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory
import org.eclipse.aether.graph.Dependency
import org.eclipse.aether.impl.DefaultServiceLocator
import org.eclipse.aether.repository.LocalRepository
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.resolution.DependencyRequest
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory
import org.eclipse.aether.spi.connector.transport.TransporterFactory
import org.eclipse.aether.transport.http.HttpTransporterFactory
import org.eclipse.aether.util.filter.DependencyFilterUtils
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URI

/** Resolves descriptor Maven coordinates and their runtime transitive dependencies. */
class PluginLibraryResolver(private val cacheDirectory: File) {
    private val logger = LoggerFactory.getLogger(PluginLibraryResolver::class.java)
    private val repositorySystem: RepositorySystem = createRepositorySystem()

    fun resolve(coordinates: List<String>, repositories: List<String>): List<File> {
        if (coordinates.isEmpty()) return emptyList()
        cacheDirectory.mkdirs()
        val session = createSession(repositorySystem)
        val remotes = repositories.distinct().mapIndexed { index, repository ->
            remoteRepository(index, repository)
        }
        val collectRequest = CollectRequest().apply {
            this.repositories = remotes
        }
        coordinates.distinct().forEach { coordinate ->
            val artifact = runCatching { DefaultArtifact(coordinate) }
                .getOrElse { throw IllegalArgumentException("Invalid Maven coordinate '$coordinate'.", it) }
            collectRequest.addDependency(Dependency(artifact, "runtime"))
        }
        val request = DependencyRequest(
            collectRequest,
            DependencyFilterUtils.classpathFilter("runtime"),
        )
        return repositorySystem.resolveDependencies(session, request)
            .artifactResults
            .mapNotNull { it.artifact.file }
            .distinctBy { it.canonicalFile }
    }

    private fun remoteRepository(index: Int, configuredUrl: String): RemoteRepository {
        val uri = URI(configuredUrl.trim())
        require(uri.scheme.equals("https", ignoreCase = true) || uri.host in LOCAL_HOSTS) {
            "Plugin Maven repository must use HTTPS unless it is local: $configuredUrl"
        }
        val normalized = configuredUrl.trim().let { if (it.endsWith('/')) it else "$it/" }
        return RemoteRepository.Builder("plugin-repository-$index", "default", normalized).build()
    }

    private fun createSession(system: RepositorySystem): RepositorySystemSession {
        val session = MavenRepositorySystemUtils.newSession()
        val localRepository = LocalRepository(cacheDirectory)
        session.localRepositoryManager = system.newLocalRepositoryManager(session, localRepository)
        return session
    }

    @Suppress("DEPRECATION")
    private fun createRepositorySystem(): RepositorySystem {
        val locator: DefaultServiceLocator = MavenRepositorySystemUtils.newServiceLocator()
        locator.addService(RepositoryConnectorFactory::class.java, BasicRepositoryConnectorFactory::class.java)
        locator.addService(TransporterFactory::class.java, HttpTransporterFactory::class.java)
        locator.setErrorHandler(object : DefaultServiceLocator.ErrorHandler() {
            override fun serviceCreationFailed(type: Class<*>, impl: Class<*>, exception: Throwable) {
                logger.error("Could not create Maven Resolver service {} using {}.", type.name, impl.name, exception)
            }
        })
        return checkNotNull(locator.getService(RepositorySystem::class.java)) {
            "Maven Resolver repository system is unavailable."
        }
    }

    private companion object {
        val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "::1")
    }
}
