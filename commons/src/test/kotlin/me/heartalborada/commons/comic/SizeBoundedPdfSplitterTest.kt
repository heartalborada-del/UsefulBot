package me.heartalborada.commons.comic

import org.apache.pdfbox.Loader
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.util.Random
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SizeBoundedPdfSplitterTest {
    @Test
    fun `reads an encrypted pdf and emits unlocked bounded parts one at a time`() {
        val directory = Files.createTempDirectory("pdf-splitter-test-").toFile()
        val images = (1..4).map { index ->
            val imageFile = directory.resolve("page-$index.jpg")
            val image = BufferedImage(500, 500, BufferedImage.TYPE_INT_RGB)
            val random = Random(index.toLong())
            for (x in 0 until image.width) {
                for (y in 0 until image.height) {
                    image.setRGB(x, y, random.nextInt(0x1000000))
                }
            }
            ImageIO.write(image, "jpg", imageFile)
            imageFile
        }
        val source = directory.resolve("source.pdf")
        PDFGenerator.generatePDF(
            images = images,
            pdfFile = source,
            password = "secret",
            tempDir = directory.resolve("pdfbox"),
        )
        val splitter = SizeBoundedPdfSplitter()
        val unlocked = directory.resolve("unlocked.pdf")
        splitter.writeUnlockedCopy(source, "secret", unlocked)
        Loader.loadPDF(unlocked).use { document ->
            assertTrue(!document.isEncrypted)
            assertEquals(4, document.numberOfPages)
        }
        val singlePage = directory.resolve("single-page.pdf")
        splitter.writePart(source, "secret", 1, 1, singlePage)
        val maximumPartBytes = singlePage.length() + singlePage.length() / 2
        val observedRanges = mutableListOf<PdfPartRange>()
        var temporaryPartCount = 0

        val ranges = splitter.forEachPart(
            source = source,
            password = "secret",
            tempDirectory = directory.resolve("parts"),
            maximumPartBytes = maximumPartBytes,
            outputPassword = null,
        ) { part ->
            temporaryPartCount++
            assertTrue(part.file.isFile)
            assertTrue(part.file.length() <= maximumPartBytes)
            Loader.loadPDF(part.file).use { document ->
                assertTrue(!document.isEncrypted)
                assertEquals(part.endPage - part.startPage + 1, document.numberOfPages)
            }
            observedRanges += PdfPartRange(part.index, part.startPage, part.endPage)
        }

        assertEquals(ranges, observedRanges)
        assertEquals(4, ranges.sumOf { it.endPage - it.startPage + 1 })
        assertTrue(temporaryPartCount > 1)
        assertTrue(directory.resolve("parts").listFiles().orEmpty().isEmpty())
        directory.deleteRecursively()
    }

    @Test
    fun `only removes stale splitter-owned files`() {
        val directory = Files.createTempDirectory("pdf-splitter-cleanup-").toFile()
        val stale = directory.resolve("usefulbot-tg-part-stale.pdf").apply {
            writeText("stale")
            setLastModified(1_000L)
        }
        val recent = directory.resolve("usefulbot-tg-part-recent.pdf").apply {
            writeText("recent")
            setLastModified(9_000L)
        }
        val unrelated = directory.resolve("keep.pdf").apply {
            writeText("keep")
            setLastModified(1_000L)
        }

        SizeBoundedPdfSplitter().cleanupStaleParts(
            directory = directory,
            olderThanMillis = 5_000L,
            nowMillis = 10_000L,
        )

        assertTrue(!stale.exists())
        assertTrue(recent.exists())
        assertTrue(unrelated.exists())
        directory.deleteRecursively()
    }
}
