package me.heartalborada.security

import me.heartalborada.config.ConfigData
import me.heartalborada.state.BotStateStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccessControllerTest {
    @Test
    fun `enforces allowlists blocks and rate limits while admins bypass them`() {
        val directory = Files.createTempDirectory("access-state-").toFile()
        try {
            var now = 1_000L
            val state = BotStateStore(directory.resolve("state.json"))
            val controller = AccessController(
                ConfigData.Access(
                    adminUserIds = listOf("tg:1"),
                    allowedUserIds = listOf("tg:2", "tg:3", "qq:2", "qq:3"),
                    blockedUserIds = listOf("tg:3"),
                    commandsPerMinute = 1,
                ),
                state,
                clock = { now },
            )
            assertEquals(AccessDecision.ALLOWED, controller.check("telegram", 1, 10))
            assertEquals(AccessDecision.NOT_ALLOWED, controller.check("napcat", 1, 10))
            assertEquals(AccessDecision.NOT_ALLOWED, controller.check("telegram", 4, 10))
            assertEquals(AccessDecision.BLOCKED, controller.check("telegram", 3, 10))
            assertEquals(AccessDecision.ALLOWED, controller.check("napcat", 3, 10))
            assertEquals(AccessDecision.ALLOWED, controller.check("telegram", 2, 10))
            assertEquals(AccessDecision.RATE_LIMITED, controller.check("telegram", 2, 10))
            assertEquals(AccessDecision.ALLOWED, controller.check("napcat", 2, 10))
            now += 60_001
            assertEquals(AccessDecision.ALLOWED, controller.check("telegram", 2, 10))
            assertTrue(controller.isAdmin("telegram", 1))
            assertFalse(controller.isAdmin("napcat", 1))
            assertEquals(listOf(1L), controller.adminTargets("telegram"))
            assertTrue(controller.adminTargets("napcat").isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `legacy numeric identities remain cross platform wildcards`() {
        val directory = Files.createTempDirectory("legacy-access-state-").toFile()
        try {
            val controller = AccessController(
                ConfigData.Access(adminUserIds = listOf("7")),
                BotStateStore(directory.resolve("state.json")),
            )
            assertTrue(controller.isAdmin("telegram", 7))
            assertTrue(controller.isAdmin("napcat", 7))
        } finally {
            directory.deleteRecursively()
        }
    }
}
