package me.heartalborada.bots.napcat

import me.heartalborada.commons.ChatType
import org.h2.jdbcx.JdbcConnectionPool
import org.jetbrains.exposed.v1.jdbc.Database
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NapcatFileIdCacheTest {
    @Test
    fun `persists cached file ids by bot and content hash`() {
        withCache("napcat-file-cache") { cache ->
            cache.put(BOT_ID, HASH, "file-id", "comic.pdf", 123L)

            assertEquals(CachedNapcatFile("file-id", "comic.pdf", 123L), cache.find(BOT_ID, HASH))
            assertEquals(null, cache.find(BOT_ID + 1, HASH))
            assertEquals(null, cache.find(BOT_ID, "b".repeat(64)))
        }
    }

    @Test
    fun `uploads a comic once and reuses its file id for later recipients`() {
        val directory = Files.createTempDirectory("napcat-cached-sender-").toFile()
        val comic = directory.resolve("comic.pdf").apply { writeText("comic") }
        try {
            withCache("napcat-cached-sender") { cache ->
                val client = FakeNapcatFileClient()
                val sender = NapcatCachedFileSender(client, cache)

                assertTrue(sender.send(ChatType.PRIVATE, 100L, comic.name, comic))
                assertTrue(sender.send(ChatType.GROUP, 200L, comic.name, comic))

                assertEquals(listOf(100L), client.uploads.map { it.target })
                assertEquals(listOf(200L), client.resends.map { it.target })
                assertEquals(client.uploads.single().fileId, client.resends.single().fileId)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `invalid cached file id is replaced by a fresh upload`() {
        val directory = Files.createTempDirectory("napcat-cache-repair-").toFile()
        val comic = directory.resolve("comic.pdf").apply { writeText("comic") }
        try {
            withCache("napcat-cache-repair") { cache ->
                val client = FakeNapcatFileClient()
                val sender = NapcatCachedFileSender(client, cache)
                sender.send(ChatType.PRIVATE, 100L, comic.name, comic)
                client.rejectCachedIds = true

                sender.send(ChatType.PRIVATE, 200L, comic.name, comic)

                assertEquals(2, client.uploads.size)
                assertEquals(1, client.resends.size)
                assertTrue(client.uploads[0].fileId != client.uploads[1].fileId)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun withCache(name: String, block: (NapcatFileIdCache) -> Unit) {
        val pool = JdbcConnectionPool.create(
            "jdbc:h2:mem:$name-${System.nanoTime()};DB_CLOSE_DELAY=-1",
            "sa",
            "",
        )
        try {
            block(NapcatFileIdCache(Database.connect(pool)))
        } finally {
            pool.dispose()
        }
    }

    private class FakeNapcatFileClient : NapcatFileClient {
        override val napcatBotId: Long = BOT_ID
        val uploads = mutableListOf<Transfer>()
        val resends = mutableListOf<Transfer>()
        var rejectCachedIds = false

        override fun uploadFile(
            type: ChatType,
            target: Long,
            name: String,
            file: File,
        ): NapcatFileReceipt {
            val fileId = "file-${uploads.size + 1}"
            uploads += Transfer(target, fileId)
            return NapcatFileReceipt(uploads.size.toLong(), fileId)
        }

        override fun resendFile(
            type: ChatType,
            target: Long,
            name: String,
            fileId: String,
        ): NapcatFileReceipt {
            resends += Transfer(target, fileId)
            if (rejectCachedIds) throw NapcatApiException("send_private_msg", 1200, "invalid file")
            return NapcatFileReceipt(100L + resends.size, fileId)
        }
    }

    private data class Transfer(val target: Long, val fileId: String)

    private companion object {
        const val BOT_ID = 42L
        val HASH = "a".repeat(64)
    }
}
