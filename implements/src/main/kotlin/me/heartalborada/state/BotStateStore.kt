package me.heartalborada.state

import me.heartalborada.commons.ChatType
import me.heartalborada.commons.permissions.PermissionSubject
import java.io.File
import java.sql.Connection
import java.sql.ResultSet
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

data class UserPreference(
    val language: String = "",
    val blurImages: Boolean? = null,
    val notifyProgress: Boolean = true,
)

data class PersistentSubscriber(
    val adapter: String,
    val target: Long,
    val userId: Long,
    val username: String,
    val role: String? = null,
    val card: String? = null,
    val chatType: ChatType,
    val messageId: Long,
    val blurImages: Boolean,
    val language: String,
    val notifyProgress: Boolean = true,
)

data class PersistentTask(
    val id: String,
    val source: String,
    val target: String,
    val subscribers: MutableList<PersistentSubscriber> = mutableListOf(),
    val createdAt: Long = System.currentTimeMillis(),
)

data class OutboxDelivery(
    val id: String = UUID.randomUUID().toString(),
    val adapter: String,
    val chatType: ChatType,
    val target: Long,
    val messageId: Long,
    val name: String,
    val filePath: String,
    val password: String? = null,
    val attempts: Int = 0,
    val nextAttemptAt: Long = System.currentTimeMillis(),
)

private data class DailyUsage(val date: String, val count: Int)

/**
 * Routes persistent state to three transactional databases.
 *
 * Preferences use the host database, comic runtime state uses the comic plugin
 * database, and permission rules/bans use the permissions plugin database.
 */
class BotStateStore(
    private val hostDataSource: DataSource,
    private val comicDataSource: DataSource,
    private val permissionDataSource: DataSource,
) {
    init {
        initializeHostSchema()
        initializeComicSchema()
        initializePermissionSchema()
    }

    fun isBanned(identity: String): Boolean {
        val normalized = normalizeUserIdentity(identity)
        return permissionDataSource.read { connection ->
            connection.prepareStatement("SELECT 1 FROM permission_bans WHERE identity = ?").use { statement ->
                statement.setString(1, normalized)
                statement.executeQuery().use(ResultSet::next)
            }
        }
    }

    fun setBanned(identity: String, banned: Boolean) {
        val normalized = normalizeUserIdentity(identity)
        permissionDataSource.transaction { connection ->
            if (banned) {
                connection.prepareStatement(
                    "MERGE INTO permission_bans (identity) KEY(identity) VALUES (?)",
                ).use { statement ->
                    statement.setString(1, normalized)
                    statement.executeUpdate()
                }
            } else {
                connection.prepareStatement("DELETE FROM permission_bans WHERE identity = ?").use { statement ->
                    statement.setString(1, normalized)
                    statement.executeUpdate()
                }
            }
        }
    }

    fun permissions(identity: String): Set<String> {
        val subject = normalizePermissionIdentity(identity)
        return permissionDataSource.read { connection ->
            connection.prepareStatement(
                "SELECT node, allowed FROM permission_rules WHERE subject = ? ORDER BY node",
            ).use { statement ->
                statement.setString(1, subject)
                statement.executeQuery().use { results ->
                    buildSet {
                        while (results.next()) {
                            val node = results.getString("node")
                            add(if (results.getBoolean("allowed")) node else "-$node")
                        }
                    }
                }
            }
        }
    }

    fun setPermissionRule(subject: String, permission: String, effect: Boolean?): Boolean {
        val normalizedSubject = normalizePermissionIdentity(subject)
        val normalizedPermission = permission.trim().lowercase().removePrefix("+").removePrefix("-")
        require(normalizedSubject.isNotEmpty()) { "Identity must not be blank." }
        require(normalizedPermission.isNotEmpty()) { "Permission must not be blank." }
        return permissionDataSource.transaction { connection ->
            val previous = connection.prepareStatement(
                "SELECT allowed FROM permission_rules WHERE subject = ? AND node = ?",
            ).use { statement ->
                statement.setString(1, normalizedSubject)
                statement.setString(2, normalizedPermission)
                statement.executeQuery().use { results ->
                    if (results.next()) results.getBoolean(1) else null
                }
            }
            if (effect == null) {
                connection.prepareStatement(
                    "DELETE FROM permission_rules WHERE subject = ? AND node = ?",
                ).use { statement ->
                    statement.setString(1, normalizedSubject)
                    statement.setString(2, normalizedPermission)
                    statement.executeUpdate()
                }
            } else {
                connection.prepareStatement(
                    "MERGE INTO permission_rules (subject, node, allowed) KEY(subject, node) VALUES (?, ?, ?)",
                ).use { statement ->
                    statement.setString(1, normalizedSubject)
                    statement.setString(2, normalizedPermission)
                    statement.setBoolean(3, effect)
                    statement.executeUpdate()
                }
            }
            previous != effect
        }
    }

    fun preference(adapter: String, userId: Long): UserPreference = hostDataSource.read { connection ->
        connection.prepareStatement(
            "SELECT language, blur_images, notify_progress FROM user_preferences WHERE adapter = ? AND user_id = ?",
        ).use { statement ->
            statement.setString(1, adapter.lowercase())
            statement.setLong(2, userId)
            statement.executeQuery().use { results ->
                if (!results.next()) return@read UserPreference()
                UserPreference(
                    language = results.getString("language"),
                    blurImages = results.getObject("blur_images") as Boolean?,
                    notifyProgress = results.getBoolean("notify_progress"),
                )
            }
        }
    }

    fun updatePreference(adapter: String, userId: Long, preference: UserPreference) {
        hostDataSource.transaction { connection ->
            connection.prepareStatement(
                """
                    MERGE INTO user_preferences
                    (adapter, user_id, language, blur_images, notify_progress)
                    KEY(adapter, user_id) VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, adapter.lowercase())
                statement.setLong(2, userId)
                statement.setString(3, preference.language)
                statement.setObject(4, preference.blurImages)
                statement.setBoolean(5, preference.notifyProgress)
                statement.executeUpdate()
            }
        }
    }

    @Synchronized
    fun consumeDailyDownload(adapter: String, userId: Long, maximum: Int): Boolean {
        if (maximum <= 0) return true
        val normalizedAdapter = adapter.lowercase()
        val today = LocalDate.now(ZoneOffset.UTC).toString()
        return comicDataSource.transaction { connection ->
            val current = connection.prepareStatement(
                "SELECT usage_date, usage_count FROM comic_daily_usage WHERE adapter = ? AND user_id = ?",
            ).use { statement ->
                statement.setString(1, normalizedAdapter)
                statement.setLong(2, userId)
                statement.executeQuery().use { results ->
                    if (results.next()) DailyUsage(results.getString(1), results.getInt(2)) else null
                }
            }?.takeIf { it.date == today } ?: DailyUsage(today, 0)
            if (current.count >= maximum) return@transaction false
            connection.prepareStatement(
                """
                    MERGE INTO comic_daily_usage (adapter, user_id, usage_date, usage_count)
                    KEY(adapter, user_id) VALUES (?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, normalizedAdapter)
                statement.setLong(2, userId)
                statement.setString(3, today)
                statement.setInt(4, current.count + 1)
                statement.executeUpdate()
            }
            true
        }
    }

    fun addSubscriber(task: PersistentTask, subscriber: PersistentSubscriber) {
        comicDataSource.transaction { connection ->
            upsertTask(connection, task)
            upsertSubscriber(connection, task.id, subscriber)
        }
    }

    fun removeSubscriber(taskId: String, adapter: String, userId: Long) {
        comicDataSource.transaction { connection ->
            connection.prepareStatement(
                "DELETE FROM comic_task_subscribers WHERE task_id = ? AND adapter = ? AND user_id = ?",
            ).use { statement ->
                statement.setString(1, taskId)
                statement.setString(2, adapter)
                statement.setLong(3, userId)
                statement.executeUpdate()
            }
            val subscribers = connection.prepareStatement(
                "SELECT COUNT(*) FROM comic_task_subscribers WHERE task_id = ?",
            ).use { statement ->
                statement.setString(1, taskId)
                statement.executeQuery().use { results -> results.next(); results.getLong(1) }
            }
            if (subscribers == 0L) deleteTask(connection, taskId)
        }
    }

    fun completeTask(taskId: String) {
        comicDataSource.transaction { connection -> deleteTask(connection, taskId) }
    }

    fun pendingTasks(): List<PersistentTask> = comicDataSource.read { connection ->
        connection.prepareStatement(
            "SELECT id, source, target, created_at FROM comic_tasks ORDER BY created_at, id",
        ).use { statement ->
            statement.executeQuery().use { tasks ->
                buildList {
                    while (tasks.next()) {
                        val id = tasks.getString("id")
                        add(
                            PersistentTask(
                                id = id,
                                source = tasks.getString("source"),
                                target = tasks.getString("target"),
                                subscribers = subscribers(connection, id).toMutableList(),
                                createdAt = tasks.getLong("created_at"),
                            ),
                        )
                    }
                }
            }
        }
    }

    fun enqueueDelivery(delivery: OutboxDelivery) {
        comicDataSource.transaction { connection -> upsertDelivery(connection, delivery) }
    }

    fun dueDeliveries(now: Long = System.currentTimeMillis()): List<OutboxDelivery> =
        comicDataSource.read { connection ->
            connection.prepareStatement(
                "SELECT * FROM comic_outbox WHERE next_attempt_at <= ? ORDER BY next_attempt_at, id",
            ).use { statement ->
                statement.setLong(1, now)
                statement.executeQuery().use { results ->
                    buildList { while (results.next()) add(results.toDelivery()) }
                }
            }
        }

    fun deliverySucceeded(id: String) {
        comicDataSource.transaction { connection ->
            connection.prepareStatement("DELETE FROM comic_outbox WHERE id = ?").use { statement ->
                statement.setString(1, id)
                statement.executeUpdate()
            }
        }
    }

    fun deliveryFailed(id: String, delayMillis: Long, maximumAttempts: Int) {
        comicDataSource.transaction { connection ->
            connection.prepareStatement(
                """
                    UPDATE comic_outbox
                    SET attempts = attempts + 1,
                        next_attempt_at = CASE WHEN attempts + 1 >= ? THEN ? ELSE ? END
                    WHERE id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setInt(1, maximumAttempts)
                statement.setLong(2, Long.MAX_VALUE)
                statement.setLong(3, System.currentTimeMillis() + delayMillis)
                statement.setString(4, id)
                statement.executeUpdate()
            }
        }
    }

    fun outboxSize(): Int = comicDataSource.read { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM comic_outbox").use { results ->
                results.next()
                results.getInt(1)
            }
        }
    }

    fun retryAllDeliveries(): Int = comicDataSource.transaction { connection ->
        connection.prepareStatement(
            "UPDATE comic_outbox SET attempts = 0, next_attempt_at = ?",
        ).use { statement ->
            statement.setLong(1, System.currentTimeMillis())
            statement.executeUpdate()
        }
    }

    fun outboxFilePaths(): Set<String> = comicDataSource.read { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT file_path FROM comic_outbox").use { results ->
                buildSet { while (results.next()) add(File(results.getString(1)).absolutePath) }
            }
        }
    }

    private fun initializeHostSchema() = hostDataSource.initialize(
        """
            CREATE TABLE IF NOT EXISTS user_preferences (
                adapter VARCHAR(32) NOT NULL,
                user_id BIGINT NOT NULL,
                language VARCHAR(32) NOT NULL,
                blur_images BOOLEAN,
                notify_progress BOOLEAN NOT NULL,
                PRIMARY KEY (adapter, user_id)
            )
        """.trimIndent(),
    )

    private fun initializeComicSchema() = comicDataSource.initialize(
        """
            CREATE TABLE IF NOT EXISTS comic_daily_usage (
                adapter VARCHAR(32) NOT NULL,
                user_id BIGINT NOT NULL,
                usage_date VARCHAR(10) NOT NULL,
                usage_count INT NOT NULL,
                PRIMARY KEY (adapter, user_id)
            )
        """.trimIndent(),
        """
            CREATE TABLE IF NOT EXISTS comic_tasks (
                id VARCHAR(255) PRIMARY KEY,
                source VARCHAR(32) NOT NULL,
                target VARCHAR(2048) NOT NULL,
                created_at BIGINT NOT NULL
            )
        """.trimIndent(),
        """
            CREATE TABLE IF NOT EXISTS comic_task_subscribers (
                task_id VARCHAR(255) NOT NULL,
                adapter VARCHAR(32) NOT NULL,
                user_id BIGINT NOT NULL,
                target_id BIGINT NOT NULL,
                username VARCHAR(512) NOT NULL,
                role_name VARCHAR(64),
                card VARCHAR(512),
                chat_type VARCHAR(16) NOT NULL,
                message_id BIGINT NOT NULL,
                blur_images BOOLEAN NOT NULL,
                language VARCHAR(32) NOT NULL,
                notify_progress BOOLEAN NOT NULL,
                PRIMARY KEY (task_id, adapter, user_id),
                FOREIGN KEY (task_id) REFERENCES comic_tasks(id) ON DELETE CASCADE
            )
        """.trimIndent(),
        """
            CREATE TABLE IF NOT EXISTS comic_outbox (
                id VARCHAR(64) PRIMARY KEY,
                adapter VARCHAR(32) NOT NULL,
                chat_type VARCHAR(16) NOT NULL,
                target_id BIGINT NOT NULL,
                message_id BIGINT NOT NULL,
                display_name VARCHAR(1024) NOT NULL,
                file_path VARCHAR(4096) NOT NULL,
                password VARCHAR(1024),
                attempts INT NOT NULL,
                next_attempt_at BIGINT NOT NULL
            )
        """.trimIndent(),
    )

    private fun initializePermissionSchema() = permissionDataSource.initialize(
        "CREATE TABLE IF NOT EXISTS permission_bans (identity VARCHAR(255) PRIMARY KEY)",
        """
            CREATE TABLE IF NOT EXISTS permission_rules (
                subject VARCHAR(255) NOT NULL,
                node VARCHAR(255) NOT NULL,
                allowed BOOLEAN NOT NULL,
                PRIMARY KEY (subject, node)
            )
        """.trimIndent(),
    )

    private fun upsertTask(connection: Connection, task: PersistentTask) {
        connection.prepareStatement(
            "MERGE INTO comic_tasks (id, source, target, created_at) KEY(id) VALUES (?, ?, ?, ?)",
        ).use { statement ->
            statement.setString(1, task.id)
            statement.setString(2, task.source)
            statement.setString(3, task.target)
            statement.setLong(4, task.createdAt)
            statement.executeUpdate()
        }
    }

    private fun upsertSubscriber(connection: Connection, taskId: String, subscriber: PersistentSubscriber) {
        connection.prepareStatement(
            """
                MERGE INTO comic_task_subscribers
                (task_id, adapter, user_id, target_id, username, role_name, card, chat_type,
                 message_id, blur_images, language, notify_progress)
                KEY(task_id, adapter, user_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, taskId)
            statement.setString(2, subscriber.adapter)
            statement.setLong(3, subscriber.userId)
            statement.setLong(4, subscriber.target)
            statement.setString(5, subscriber.username)
            statement.setString(6, subscriber.role)
            statement.setString(7, subscriber.card)
            statement.setString(8, subscriber.chatType.name)
            statement.setLong(9, subscriber.messageId)
            statement.setBoolean(10, subscriber.blurImages)
            statement.setString(11, subscriber.language)
            statement.setBoolean(12, subscriber.notifyProgress)
            statement.executeUpdate()
        }
    }

    private fun subscribers(connection: Connection, taskId: String): List<PersistentSubscriber> =
        connection.prepareStatement(
            "SELECT * FROM comic_task_subscribers WHERE task_id = ? ORDER BY adapter, user_id",
        ).use { statement ->
            statement.setString(1, taskId)
            statement.executeQuery().use { results ->
                buildList {
                    while (results.next()) {
                        add(
                            PersistentSubscriber(
                                adapter = results.getString("adapter"),
                                target = results.getLong("target_id"),
                                userId = results.getLong("user_id"),
                                username = results.getString("username"),
                                role = results.getString("role_name"),
                                card = results.getString("card"),
                                chatType = ChatType.valueOf(results.getString("chat_type")),
                                messageId = results.getLong("message_id"),
                                blurImages = results.getBoolean("blur_images"),
                                language = results.getString("language"),
                                notifyProgress = results.getBoolean("notify_progress"),
                            ),
                        )
                    }
                }
            }
        }

    private fun deleteTask(connection: Connection, taskId: String) {
        connection.prepareStatement("DELETE FROM comic_tasks WHERE id = ?").use { statement ->
            statement.setString(1, taskId)
            statement.executeUpdate()
        }
    }

    private fun upsertDelivery(connection: Connection, delivery: OutboxDelivery) {
        connection.prepareStatement(
            """
                MERGE INTO comic_outbox
                (id, adapter, chat_type, target_id, message_id, display_name, file_path,
                 password, attempts, next_attempt_at)
                KEY(id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, delivery.id)
            statement.setString(2, delivery.adapter)
            statement.setString(3, delivery.chatType.name)
            statement.setLong(4, delivery.target)
            statement.setLong(5, delivery.messageId)
            statement.setString(6, delivery.name)
            statement.setString(7, delivery.filePath)
            statement.setString(8, delivery.password)
            statement.setInt(9, delivery.attempts)
            statement.setLong(10, delivery.nextAttemptAt)
            statement.executeUpdate()
        }
    }

    private fun ResultSet.toDelivery() = OutboxDelivery(
        id = getString("id"),
        adapter = getString("adapter"),
        chatType = ChatType.valueOf(getString("chat_type")),
        target = getLong("target_id"),
        messageId = getLong("message_id"),
        name = getString("display_name"),
        filePath = getString("file_path"),
        password = getString("password"),
        attempts = getInt("attempts"),
        nextAttemptAt = getLong("next_attempt_at"),
    )

    private fun normalizePermissionIdentity(identity: String): String =
        PermissionSubject.parse(identity)?.key ?: identity.trim().lowercase()

    private fun normalizeUserIdentity(identity: String): String {
        val parts = identity.trim().lowercase().split(':')
        require(parts.size == 2 && parts[1].toLongOrNull() != null) { "Invalid user identity: $identity" }
        val platform = PermissionSubject.normalizePlatform(parts[0])
        require(platform != "*") { "A user identity must have a concrete platform." }
        return "$platform:${parts[1]}"
    }

}

private fun DataSource.initialize(vararg statements: String) {
    transaction { connection ->
        connection.createStatement().use { statement -> statements.forEach(statement::execute) }
    }
}

private inline fun <T> DataSource.read(block: (Connection) -> T): T = connection.use(block)

private inline fun <T> DataSource.transaction(block: (Connection) -> T): T = connection.use { connection ->
    val previousAutoCommit = connection.autoCommit
    connection.autoCommit = false
    try {
        block(connection).also { connection.commit() }
    } catch (throwable: Throwable) {
        runCatching(connection::rollback)
        throw throwable
    } finally {
        connection.autoCommit = previousAutoCommit
    }
}
