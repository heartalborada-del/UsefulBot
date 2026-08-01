package me.heartalborada.plugins

import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComicDataMigrationTest {
    @Test
    fun `migrates both legacy layouts without overwriting current comic data`() {
        val root = Files.createTempDirectory("comic-data-migration-").toFile()
        val plugins = File(root, "plugins")
        try {
            File(root, "data/eh/pdf").apply { mkdirs() }.resolve("old.pdf").writeText("old")
            File(plugins, "jm/data/img").apply { mkdirs() }.resolve("page.jpg").writeText("image")
            File(plugins, "comic/eh/pdf").apply { mkdirs() }.resolve("keep.pdf").writeText("current")
            File(root, "data/eh/pdf/keep.pdf").writeText("legacy")

            val report = ComicDataMigration.migrate(
                root,
                plugins,
                LoggerFactory.getLogger("comic-data-migration-test"),
            )

            assertEquals("old", File(plugins, "comic/eh/pdf/old.pdf").readText())
            assertEquals("image", File(plugins, "comic/jm/img/page.jpg").readText())
            assertEquals("current", File(plugins, "comic/eh/pdf/keep.pdf").readText())
            assertEquals("legacy", File(root, "data/eh/pdf/keep.pdf").readText())
            assertEquals(1, report.conflicts.size)
            assertTrue(report.movedEntries >= 2)
            assertFalse(File(plugins, "jm").exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
