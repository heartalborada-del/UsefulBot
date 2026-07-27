package me.heartalborada.bots.telegram

import org.h2.jdbcx.JdbcConnectionPool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TelegramFileIdCacheTest {
    @Test
    fun `persists only complete ordered part manifests`() {
        val pool = JdbcConnectionPool.create(
            "jdbc:h2:mem:telegram-file-cache-${System.nanoTime()};DB_CLOSE_DELAY=-1",
            "sa",
            "",
        )
        try {
            val cache = TelegramFileIdCache(pool)
            val parts = listOf(
                part(index = 1, count = 2, fileId = "file-1", startPage = 1, endPage = 4),
                part(index = 2, count = 2, fileId = "file-2", startPage = 5, endPage = 8),
            )

            cache.replace(BOT_ID, HASH, MAXIMUM_BYTES, parts)

            assertEquals(parts, cache.findComplete(BOT_ID, HASH, MAXIMUM_BYTES))
            assertTrue(cache.findComplete(BOT_ID + 1, HASH, MAXIMUM_BYTES).isEmpty())
            assertTrue(cache.findComplete(BOT_ID, "other", MAXIMUM_BYTES).isEmpty())
        } finally {
            pool.dispose()
        }
    }

    @Test
    fun `updates a rejected file id without replacing the manifest`() {
        val pool = JdbcConnectionPool.create(
            "jdbc:h2:mem:telegram-file-cache-update-${System.nanoTime()};DB_CLOSE_DELAY=-1",
            "sa",
            "",
        )
        try {
            val cache = TelegramFileIdCache(pool)
            cache.replace(
                BOT_ID,
                HASH,
                MAXIMUM_BYTES,
                listOf(part(index = 1, count = 1, fileId = "old", startPage = 1, endPage = 3)),
            )

            cache.updateFileId(
                botId = BOT_ID,
                contentSha256 = HASH,
                maximumPartBytes = MAXIMUM_BYTES,
                partIndex = 1,
                fileId = "new",
                fileUniqueId = "unique-new",
                fileSize = 456L,
            )

            val updated = cache.findComplete(BOT_ID, HASH, MAXIMUM_BYTES).single()
            assertEquals("new", updated.fileId)
            assertEquals("unique-new", updated.fileUniqueId)
            assertEquals(456L, updated.fileSize)
        } finally {
            pool.dispose()
        }
    }

    private fun part(
        index: Int,
        count: Int,
        fileId: String,
        startPage: Int,
        endPage: Int,
    ) = CachedTelegramPdfPart(
        index = index,
        partCount = count,
        startPage = startPage,
        endPage = endPage,
        fileName = "comic.part-$index.pdf",
        fileId = fileId,
        fileUniqueId = "unique-$index",
        fileSize = 123L,
    )

    private companion object {
        const val BOT_ID = 42L
        const val MAXIMUM_BYTES = 48L * 1024 * 1024
        val HASH = "a".repeat(64)
    }
}
