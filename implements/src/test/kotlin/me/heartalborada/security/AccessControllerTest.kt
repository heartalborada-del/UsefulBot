package me.heartalborada.security

import me.heartalborada.config.ConfigData
import me.heartalborada.state.BotStateStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class AccessControllerTest {
    @Test
    fun `enforces dynamic bans and platform scoped rate limits`() {
        val directory = Files.createTempDirectory("access-state-").toFile()
        try {
            var now = 1_000L
            val state = BotStateStore(directory.resolve("state.json"))
            val controller = AccessController(
                ConfigData.Access(
                    commandsPerMinute = 1,
                ),
                state,
                clock = { now },
            )
            state.setBanned("tg:3", true)
            assertEquals(AccessDecision.ALLOWED, controller.check("telegram", 1, 10))
            assertEquals(AccessDecision.BLOCKED, controller.check("telegram", 3, 10))
            assertEquals(AccessDecision.ALLOWED, controller.check("napcat", 3, 10))
            assertEquals(AccessDecision.ALLOWED, controller.check("telegram", 2, 10))
            assertEquals(AccessDecision.RATE_LIMITED, controller.check("telegram", 2, 10))
            assertEquals(AccessDecision.ALLOWED, controller.check("napcat", 2, 10))
            now += 60_001
            assertEquals(AccessDecision.ALLOWED, controller.check("telegram", 2, 10))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `normalizes adapter scoped identities`() {
        assertEquals("tg:7", AccessController.identity("telegram", 7))
        assertEquals("qq:7", AccessController.identity("napcat", 7))
        assertEquals("tg:7", AccessController.normalizeScopedIdentity("telegram:7"))
        assertEquals(null, AccessController.normalizeScopedIdentity("7"))
    }
}
