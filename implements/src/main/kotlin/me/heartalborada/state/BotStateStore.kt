package me.heartalborada.state

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import me.heartalborada.commons.ChatType
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

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

private data class BotState(
    val bannedUsers: MutableSet<String> = mutableSetOf(),
    val permissions: MutableMap<String, MutableSet<String>> = mutableMapOf(),
    val preferences: MutableMap<String, UserPreference> = mutableMapOf(),
    val dailyUsage: MutableMap<String, DailyUsage> = mutableMapOf(),
    val pendingTasks: MutableMap<String, PersistentTask> = mutableMapOf(),
    val outbox: MutableMap<String, OutboxDelivery> = mutableMapOf(),
)

class BotStateStore(private val file: File) {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private var state = load()

    @Synchronized
    fun isBanned(identity: String): Boolean = identity.lowercase() in state.bannedUsers

    @Synchronized
    fun setBanned(identity: String, banned: Boolean) {
        val normalized = identity.trim().lowercase()
        require(normalized.isNotEmpty()) { "Identity must not be blank." }
        val changed = if (banned) state.bannedUsers.add(normalized) else state.bannedUsers.remove(normalized)
        if (changed) save()
    }

    @Synchronized
    fun permissions(identity: String): Set<String> =
        state.permissions[identity.trim().lowercase()]?.toSet().orEmpty()

    @Synchronized
    fun setPermissionRule(subject: String, permission: String, effect: Boolean?): Boolean {
        val normalizedIdentity = subject.trim().lowercase()
        val normalizedPermission = permission.trim().lowercase()
        require(normalizedIdentity.isNotEmpty()) { "Identity must not be blank." }
        require(normalizedPermission.isNotEmpty()) { "Permission must not be blank." }
        val current = state.permissions.getOrPut(normalizedIdentity) { mutableSetOf() }
        val allowRule = normalizedPermission
        val denyRule = "-$normalizedPermission"
        val changed = when (effect) {
            true -> current.remove(denyRule) or current.add(allowRule)
            false -> current.remove(allowRule) or current.add(denyRule)
            null -> current.remove(allowRule) or current.remove(denyRule)
        }
        if (current.isEmpty()) state.permissions.remove(normalizedIdentity)
        if (changed) save()
        return changed
    }

    @Synchronized
    fun preference(adapter: String, userId: Long): UserPreference =
        state.preferences[userKey(adapter, userId)] ?: UserPreference()

    @Synchronized
    fun updatePreference(adapter: String, userId: Long, preference: UserPreference) {
        state.preferences[userKey(adapter, userId)] = preference
        save()
    }

    @Synchronized
    fun consumeDailyDownload(adapter: String, userId: Long, maximum: Int): Boolean {
        if (maximum <= 0) return true
        val key = userKey(adapter, userId)
        val today = LocalDate.now(ZoneOffset.UTC).toString()
        val current = state.dailyUsage[key]?.takeIf { it.date == today } ?: DailyUsage(today, 0)
        if (current.count >= maximum) return false
        state.dailyUsage[key] = current.copy(count = current.count + 1)
        save()
        return true
    }

    @Synchronized
    fun addSubscriber(task: PersistentTask, subscriber: PersistentSubscriber) {
        val current = state.pendingTasks.getOrPut(task.id) { task.copy(subscribers = mutableListOf()) }
        if (current.subscribers.none { it.adapter == subscriber.adapter && it.userId == subscriber.userId }) {
            current.subscribers.add(subscriber)
            save()
        }
    }

    @Synchronized
    fun removeSubscriber(taskId: String, adapter: String, userId: Long) {
        val task = state.pendingTasks[taskId] ?: return
        task.subscribers.removeAll { it.adapter == adapter && it.userId == userId }
        if (task.subscribers.isEmpty()) state.pendingTasks.remove(taskId)
        save()
    }

    @Synchronized
    fun completeTask(taskId: String) {
        if (state.pendingTasks.remove(taskId) != null) save()
    }

    @Synchronized
    fun pendingTasks(): List<PersistentTask> = state.pendingTasks.values.map { task ->
        task.copy(subscribers = task.subscribers.toMutableList())
    }

    @Synchronized
    fun enqueueDelivery(delivery: OutboxDelivery) {
        state.outbox[delivery.id] = delivery
        save()
    }

    @Synchronized
    fun dueDeliveries(now: Long = System.currentTimeMillis()): List<OutboxDelivery> =
        state.outbox.values.filter { it.nextAttemptAt <= now }

    @Synchronized
    fun deliverySucceeded(id: String) {
        if (state.outbox.remove(id) != null) save()
    }

    @Synchronized
    fun deliveryFailed(id: String, delayMillis: Long, maximumAttempts: Int) {
        val current = state.outbox[id] ?: return
        val attempts = current.attempts + 1
        if (attempts >= maximumAttempts) {
            state.outbox[id] = current.copy(attempts = attempts, nextAttemptAt = Long.MAX_VALUE)
        } else {
            state.outbox[id] = current.copy(
                attempts = attempts,
                nextAttemptAt = System.currentTimeMillis() + delayMillis,
            )
        }
        save()
    }

    @Synchronized
    fun outboxSize(): Int = state.outbox.size

    @Synchronized
    fun retryAllDeliveries(): Int {
        val count = state.outbox.size
        val now = System.currentTimeMillis()
        state.outbox.replaceAll { _, delivery -> delivery.copy(attempts = 0, nextAttemptAt = now) }
        if (count > 0) save()
        return count
    }

    @Synchronized
    fun outboxFilePaths(): Set<String> = state.outbox.values.mapTo(mutableSetOf()) { File(it.filePath).absolutePath }

    private fun load(): BotState {
        if (!file.isFile || file.length() == 0L) return BotState()
        return runCatching {
            val root = JsonParser.parseString(file.readText(Charsets.UTF_8)).asJsonObject
            root.getAsJsonArray("bannedUsers")?.let { existing ->
                val normalized = JsonArray()
                existing.forEach { item ->
                    val value = item.asString.trim().lowercase()
                    if (':' in value) {
                        normalized.add(value)
                    } else if (value.toLongOrNull() != null) {
                        normalized.add("tg:$value")
                        normalized.add("qq:$value")
                    }
                }
                root.add("bannedUsers", normalized)
            }
            root.getAsJsonObject("permissions")?.let { existing ->
                val normalized = com.google.gson.JsonObject()
                existing.entrySet().forEach { (key, rules) ->
                    val parts = key.trim().lowercase().split(':')
                    val normalizedKey = if (parts.size == 2 && parts[1].toLongOrNull() != null) {
                        "${parts[0]}:user:${parts[1]}"
                    } else {
                        key.trim().lowercase()
                    }
                    normalized.add(normalizedKey, rules.deepCopy())
                }
                root.add("permissions", normalized)
            }
            val defaults = gson.toJsonTree(BotState()).asJsonObject
            defaults.entrySet().forEach { (key, value) ->
                if (!root.has(key)) root.add(key, value.deepCopy())
            }
            gson.fromJson(root, BotState::class.java)
        }
            .getOrElse { BotState() }
    }

    private fun save() {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile ?: File("."), "${file.name}.tmp")
        temporary.writeText(gson.toJson(state), Charsets.UTF_8)
        runCatching {
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.getOrElse {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun userKey(adapter: String, userId: Long): String = "${adapter.lowercase()}:$userId"
}
