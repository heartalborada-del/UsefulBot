package me.heartalborada.plugins

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PluginLibraryResolverTest {
    @Test
    fun `resolves runtime transitive dependencies into plugin cache`() {
        val root = Files.createTempDirectory("usefulbot-maven-repository-").toFile()
        val repository = File(root, "repository")
        val cache = File(root, "cache")
        createArtifact(repository, "dependency", emptyList())
        createArtifact(repository, "root", listOf("dependency"))
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = Executors.newCachedThreadPool()
            createContext("/") { exchange ->
                val requested = File(repository, exchange.requestURI.path.removePrefix("/"))
                if (!requested.isFile) {
                    exchange.sendResponseHeaders(404, -1)
                } else {
                    val bytes = requested.readBytes()
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                exchange.close()
            }
            start()
        }

        try {
            val files = PluginLibraryResolver(cache).resolve(
                coordinates = listOf("test.plugins:root:1.0.0"),
                repositories = listOf("http://127.0.0.1:${server.address.port}/"),
            )

            assertEquals(setOf("root-1.0.0.jar", "dependency-1.0.0.jar"), files.map(File::getName).toSet())
            assertTrue(files.all(File::isFile))
            assertTrue(files.all { it.canonicalPath.startsWith(cache.canonicalPath) })
        } finally {
            server.stop(0)
            (server.executor as java.util.concurrent.ExecutorService).shutdownNow()
            root.deleteRecursively()
        }
    }

    private fun createArtifact(repository: File, artifact: String, dependencies: List<String>) {
        val directory = File(repository, "test/plugins/$artifact/1.0.0").apply { mkdirs() }
        JarOutputStream(File(directory, "$artifact-1.0.0.jar").outputStream()).use { }
        File(directory, "$artifact-1.0.0.pom").writeText(
            """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>test.plugins</groupId>
                  <artifactId>$artifact</artifactId>
                  <version>1.0.0</version>
                  <dependencies>
                    ${dependencies.joinToString("\n") { dependency ->
                        """
                          <dependency>
                            <groupId>test.plugins</groupId>
                            <artifactId>$dependency</artifactId>
                            <version>1.0.0</version>
                            <scope>runtime</scope>
                          </dependency>
                        """.trimIndent()
                    }}
                  </dependencies>
                </project>
            """.trimIndent(),
        )
    }
}
