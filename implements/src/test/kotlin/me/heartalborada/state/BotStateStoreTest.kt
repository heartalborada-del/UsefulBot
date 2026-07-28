package me.heartalborada.state

import me.heartalborada.commons.ChatType
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BotStateStoreTest {
    @Test
    fun `persists preferences tasks bans quotas and outbox`() {
        val directory = Files.createTempDirectory("bot-state-").toFile()
        val file = directory.resolve("state.json")
        try {
            val store = BotStateStore(file)
            store.setBanned("tg:5", true)
            store.updatePreference("telegram", 5, UserPreference(language = "zh-CN", blurImages = false))
            assertTrue(store.consumeDailyDownload("telegram", 5, 1))
            assertFalse(store.consumeDailyDownload("telegram", 5, 1))
            store.addSubscriber(
                PersistentTask("jm-1", "jm", "JM1"),
                PersistentSubscriber("telegram", 5, 5, "user", chatType = ChatType.PRIVATE, messageId = 9, blurImages = false, language = "zh-CN"),
            )
            val delivery = OutboxDelivery(adapter = "telegram", chatType = ChatType.PRIVATE, target = 5, messageId = 9, name = "a.pdf", filePath = "a.pdf")
            store.enqueueDelivery(delivery)
            store.deliveryFailed(delivery.id, delayMillis = 1, maximumAttempts = 1)
            assertTrue(store.dueDeliveries().isEmpty())
            assertEquals(1, store.retryAllDeliveries())
            assertEquals(1, store.dueDeliveries().size)

            val restored = BotStateStore(file)
            assertTrue(restored.isBanned("tg:5"))
            assertFalse(restored.isBanned("qq:5"))
            assertEquals("zh-CN", restored.preference("telegram", 5).language)
            assertEquals("jm-1", restored.pendingTasks().single().id)
            assertEquals(1, restored.outboxSize())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `legacy numeric bans are migrated to both scoped platforms`() {
        val directory = Files.createTempDirectory("legacy-bot-state-").toFile()
        val file = directory.resolve("state.json")
        try {
            file.writeText("""{"bannedUsers":[5]}""")
            val store = BotStateStore(file)
            assertTrue(store.isBanned("tg:5"))
            assertTrue(store.isBanned("qq:5"))
            store.setBanned("tg:5", false)
            assertFalse(store.isBanned("tg:5"))
            assertTrue(store.isBanned("qq:5"))
        } finally {
            directory.deleteRecursively()
        }
    }
}
