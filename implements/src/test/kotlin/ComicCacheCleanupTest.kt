import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ComicCacheCleanupTest {
    @Test
    fun `deletes files and directories only within the declared cache root`() {
        val parent = Files.createTempDirectory("comic-cache-cleanup-")
        val root = Files.createDirectory(parent.resolve("cache"))
        val cachedFile = root.resolve("archive.zip").also { Files.writeString(it, "archive") }
        val cachedDirectory = Files.createDirectory(root.resolve("images"))
        Files.writeString(cachedDirectory.resolve("page.jpg"), "image")
        val outside = parent.resolve("keep.txt").also { Files.writeString(it, "keep") }
        try {
            assertTrue(deleteCacheEntry(root.toFile(), cachedFile.toFile()))
            assertTrue(!Files.exists(cachedFile))
            assertTrue(deleteCacheEntry(root.toFile(), cachedDirectory.toFile()))
            assertTrue(!Files.exists(cachedDirectory))

            assertFailsWith<IllegalArgumentException> {
                deleteCacheEntry(root.toFile(), outside.toFile())
            }
            assertFailsWith<IllegalArgumentException> {
                deleteCacheEntry(root.toFile(), root.toFile())
            }
            assertTrue(Files.exists(outside))
        } finally {
            outside.deleteIfExists()
            root.deleteIfExists()
            parent.deleteIfExists()
        }
    }
}
