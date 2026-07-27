package me.heartalborada.bots.telegram

import me.heartalborada.commons.ChatType
import me.heartalborada.commons.comic.PDFGenerator
import me.heartalborada.commons.comic.SizeBoundedPdfSplitter
import org.h2.jdbcx.JdbcConnectionPool
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TelegramLargeDocumentSenderTest {
    @Test
    fun `uploads each part once then reuses persistent file ids for other recipients`() {
        val directory = Files.createTempDirectory("telegram-large-sender-").toFile()
        val source = createEncryptedPdf(directory)
        val splitter = SizeBoundedPdfSplitter()
        val singlePage = directory.resolve("single.pdf")
        splitter.writePart(source, PASSWORD, 1, 1, singlePage)
        val maximumBytes = singlePage.length() + singlePage.length() / 2
        val pool = JdbcConnectionPool.create(
            "jdbc:h2:mem:telegram-large-sender-${System.nanoTime()};DB_CLOSE_DELAY=-1",
            "sa",
            "",
        )
        try {
            val client = FakeTelegramDocumentClient()
            val sender = TelegramLargeDocumentSender(
                client = client,
                cache = TelegramFileIdCache(pool),
                splitter = splitter,
                tempDirectory = directory.resolve("parts"),
                maximumPartBytes = maximumBytes,
            )

            sender.send(ChatType.PRIVATE, 100L, "comic.pdf", source, PASSWORD)
            val initialUploads = client.uploads.toList()
            assertTrue(initialUploads.size > 1)
            assertTrue(initialUploads.all { it.size <= maximumBytes })
            assertTrue(directory.resolve("parts").listFiles().orEmpty().isEmpty())

            sender.send(ChatType.PRIVATE, 200L, "comic.pdf", source, PASSWORD)

            assertEquals(initialUploads, client.uploads)
            assertEquals(initialUploads.map { it.fileId }, client.resends.map { it.fileId })
            assertTrue(client.resends.all { it.target == 200L })
        } finally {
            pool.dispose()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `regenerates only a cached part whose file id Telegram rejects`() {
        val directory = Files.createTempDirectory("telegram-large-repair-").toFile()
        val source = createEncryptedPdf(directory)
        val splitter = SizeBoundedPdfSplitter()
        val singlePage = directory.resolve("single.pdf")
        splitter.writePart(source, PASSWORD, 1, 1, singlePage)
        val maximumBytes = singlePage.length() + singlePage.length() / 2
        val pool = JdbcConnectionPool.create(
            "jdbc:h2:mem:telegram-large-repair-${System.nanoTime()};DB_CLOSE_DELAY=-1",
            "sa",
            "",
        )
        try {
            val client = FakeTelegramDocumentClient()
            val sender = TelegramLargeDocumentSender(
                client = client,
                cache = TelegramFileIdCache(pool),
                splitter = splitter,
                tempDirectory = directory.resolve("parts"),
                maximumPartBytes = maximumBytes,
            )
            sender.send(ChatType.PRIVATE, 100L, "comic.pdf", source, PASSWORD)
            val originalUploadCount = client.uploads.size
            val rejectedFileId = client.uploads[1].fileId
            client.rejectedFileIds += rejectedFileId

            sender.send(ChatType.PRIVATE, 200L, "comic.pdf", source, PASSWORD)

            assertEquals(originalUploadCount + 1, client.uploads.size)
            assertEquals(200L, client.uploads.last().target)
            assertTrue(client.uploads.last().fileId != rejectedFileId)
            assertTrue(directory.resolve("parts").listFiles().orEmpty().isEmpty())
        } finally {
            pool.dispose()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `serializes concurrent delivery of the same PDF and uploads each part once`() {
        val directory = Files.createTempDirectory("telegram-large-concurrent-").toFile()
        val source = createEncryptedPdf(directory)
        val splitter = SizeBoundedPdfSplitter()
        val singlePage = directory.resolve("single.pdf")
        splitter.writePart(source, PASSWORD, 1, 1, singlePage)
        val maximumBytes = singlePage.length() + singlePage.length() / 2
        val pool = JdbcConnectionPool.create(
            "jdbc:h2:mem:telegram-large-concurrent-${System.nanoTime()};DB_CLOSE_DELAY=-1",
            "sa",
            "",
        )
        val executor = Executors.newFixedThreadPool(2)
        try {
            val client = FakeTelegramDocumentClient()
            val sender = TelegramLargeDocumentSender(
                client = client,
                cache = TelegramFileIdCache(pool),
                splitter = splitter,
                tempDirectory = directory.resolve("parts"),
                maximumPartBytes = maximumBytes,
            )
            val ready = CountDownLatch(2)
            val start = CountDownLatch(1)
            val deliveries = listOf(100L, 200L).map { target ->
                executor.submit<Boolean> {
                    ready.countDown()
                    start.await()
                    sender.send(ChatType.PRIVATE, target, "comic.pdf", source, PASSWORD)
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            deliveries.forEach { assertTrue(it.get(30, TimeUnit.SECONDS)) }

            assertTrue(client.uploads.size > 1)
            assertEquals(1, client.uploads.map { it.target }.distinct().size)
            assertEquals(client.uploads.size, client.resends.size)
            assertEquals(1, client.resends.map { it.target }.distinct().size)
            assertTrue(client.uploads.first().target != client.resends.first().target)
            assertTrue(directory.resolve("parts").listFiles().orEmpty().isEmpty())
        } finally {
            executor.shutdownNow()
            pool.dispose()
            directory.deleteRecursively()
        }
    }

    private fun createEncryptedPdf(directory: File): File {
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
        return directory.resolve("source.pdf").also { pdf ->
            PDFGenerator.generatePDF(
                images = images,
                pdfFile = pdf,
                password = PASSWORD,
                tempDir = directory.resolve("pdfbox"),
            )
        }
    }

    private data class Uploaded(
        val target: Long,
        val fileId: String,
        val size: Long,
    )

    private data class Resent(
        val target: Long,
        val fileId: String,
    )

    private class FakeTelegramDocumentClient : TelegramDocumentClient {
        override val telegramBotId: Long = 42L
        val uploads = mutableListOf<Uploaded>()
        val resends = mutableListOf<Resent>()
        val rejectedFileIds = mutableSetOf<String>()

        @Synchronized
        override fun uploadDocument(
            type: ChatType,
            target: Long,
            name: String,
            file: File,
        ): TelegramDocumentReceipt {
            assertTrue(file.isFile)
            val fileId = "file-${uploads.size + 1}"
            uploads += Uploaded(target, fileId, file.length())
            return TelegramDocumentReceipt(
                messageId = uploads.size.toLong(),
                fileId = fileId,
                fileUniqueId = "unique-${uploads.size}",
            )
        }

        @Synchronized
        override fun resendDocument(
            type: ChatType,
            target: Long,
            fileId: String,
        ): TelegramDocumentReceipt {
            if (fileId in rejectedFileIds) {
                rejectedFileIds -= fileId
                throw TelegramApiException(
                    method = "sendDocument",
                    statusCode = 400,
                    description = "Bad Request: wrong remote file identifier specified",
                )
            }
            resends += Resent(target, fileId)
            return TelegramDocumentReceipt(
                messageId = resends.size.toLong(),
                fileId = fileId,
                fileUniqueId = null,
            )
        }
    }

    private companion object {
        const val PASSWORD = "secret"
    }
}
