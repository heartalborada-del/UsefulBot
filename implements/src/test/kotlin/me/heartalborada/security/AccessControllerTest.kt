package me.heartalborada.security

import me.heartalborada.config.ConfigData
import me.heartalborada.state.BotStateStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class AccessControllerTest {
    @Test
    fun `enforces allowlists blocks and rate limits while admins bypass them`() {
        val directory = Files.createTempDirectory("access-state-").toFile()
        try {
            var now = 1_000L
            val state = BotStateStore(directory.resolve("state.json"))
            val controller = AccessController(
                ConfigData.Access(
                    adminUserIds = listOf(1),
                    allowedUserIds = listOf(2, 3),
                    blockedUserIds = listOf(3),
                    commandsPerMinute = 1,
                ),
                state,
                clock = { now },
            )
            assertEquals(AccessDecision.ALLOWED, controller.check("telegram", 1, 10))
            assertEquals(AccessDecision.NOT_ALLOWED, controller.check("telegram", 4, 10))
            assertEquals(AccessDecision.BLOCKED, controller.check("telegram", 3, 10))
            assertEquals(AccessDecision.ALLOWED, controller.check("telegram", 2, 10))
            assertEquals(AccessDecision.RATE_LIMITED, controller.check("telegram", 2, 10))
            now += 60_001
            assertEquals(AccessDecision.ALLOWED, controller.check("telegram", 2, 10))
        } finally {
            directory.deleteRecursively()
        }
    }
}
