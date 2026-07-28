package me.heartalborada.errors

import me.heartalborada.commons.ChatType
import me.heartalborada.commons.bots.dto.MessageSender
import me.heartalborada.commons.bots.dto.UserInfo
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommandErrorLoggerTest {
    @Test
    fun `writes timestamped command context and stack trace`() {
        val directory = Files.createTempDirectory("command-errors-").toFile()
        try {
            val logger = CommandErrorLogger(
                directory,
                Clock.fixed(Instant.parse("2026-07-28T15:30:45.123456789Z"), ZoneOffset.UTC),
            )
            val report = logger.record(
                adapter = "telegram",
                sender = MessageSender(99, UserInfo(42, "alice"), ChatType.PRIVATE),
                operation = "/get jm JM123",
                messageID = 7,
                error = IllegalStateException("download failed"),
            )

            assertEquals("20260728-153045-123456789.err.log", report.file.name)
            val content = report.file.readText(Charsets.UTF_8)
            assertTrue(content.contains("timestamp=2026-07-28T15:30:45.123456789Z"))
            assertTrue(content.contains("adapter=telegram"))
            assertTrue(content.contains("user_id=42"))
            assertTrue(content.contains("command=/get jm JM123"))
            assertTrue(content.contains("java.lang.IllegalStateException: download failed"))
        } finally {
            directory.deleteRecursively()
        }
    }
}
