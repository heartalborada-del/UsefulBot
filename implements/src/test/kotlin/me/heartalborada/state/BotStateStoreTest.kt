package me.heartalborada.state

import me.heartalborada.commons.ChatType
import java.nio.file.Files
import javax.sql.DataSource
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BotStateStoreTest {
    @Test
    fun `persists preferences tasks bans quotas and outbox`() {
        val directory = Files.createTempDirectory("bot-state-").toFile()
        try {
            val databases = testStateDatabases(directory)
            val store = databases.store()
            store.setBanned("tg:5", true)
            store.setPermissionRule("tg:user:5", "comic.download", true)
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

            val restored = testStateDatabases(directory).store()
            assertTrue(restored.isBanned("tg:5"))
            assertFalse(restored.isBanned("qq:5"))
            assertEquals("zh-CN", restored.preference("telegram", 5).language)
            assertEquals("jm-1", restored.pendingTasks().single().id)
            assertEquals(1, restored.outboxSize())

            assertEquals(setOf("USER_PREFERENCES"), tableNames(databases.host))
            assertEquals(
                setOf("COMIC_DAILY_USAGE", "COMIC_TASKS", "COMIC_TASK_SUBSCRIBERS", "COMIC_OUTBOX"),
                tableNames(databases.comic),
            )
            assertEquals(setOf("PERMISSION_BANS", "PERMISSION_RULES"), tableNames(databases.permissions))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `concurrent delivery failures are updated atomically`() {
        val directory = Files.createTempDirectory("concurrent-outbox-").toFile()
        try {
            val store = testStateStore(directory)
            val delivery = OutboxDelivery(
                adapter = "telegram",
                chatType = ChatType.PRIVATE,
                target = 5,
                messageId = 9,
                name = "a.pdf",
                filePath = "a.pdf",
                nextAttemptAt = 0,
            )
            store.enqueueDelivery(delivery)

            List(16) {
                thread { store.deliveryFailed(delivery.id, delayMillis = 0, maximumAttempts = 100) }
            }.forEach(Thread::join)

            assertEquals(16, store.dueDeliveries().single().attempts)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun tableNames(dataSource: DataSource): Set<String> = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = 'PUBLIC'",
        ).use { statement ->
            statement.executeQuery().use { results ->
                buildSet { while (results.next()) add(results.getString(1)) }
            }
        }
    }
}
