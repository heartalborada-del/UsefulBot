package me.heartalborada.cache

import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CacheJanitorTest {
    @Test
    fun `removes expired files and oldest entries over the size limit`() {
        val directory = Files.createTempDirectory("cache-janitor-").toFile()
        try {
            val old = directory.resolve("old.pdf").apply { writeBytes(ByteArray(5)); setLastModified(1_000) }
            val newer = directory.resolve("new.pdf").apply { writeBytes(ByteArray(7)); setLastModified(9_000) }
            val janitor = CacheJanitor(Clock.fixed(Instant.ofEpochMilli(10_000), ZoneOffset.UTC))

            val result = janitor.clean(listOf(directory), maximumBytes = 7, ttlMillis = 5_000)

            assertFalse(old.exists())
            assertTrue(newer.exists())
            assertEquals(1, result.deletedFiles)
            assertEquals(7, result.remainingBytes)
        } finally {
            directory.deleteRecursively()
        }
    }
}
