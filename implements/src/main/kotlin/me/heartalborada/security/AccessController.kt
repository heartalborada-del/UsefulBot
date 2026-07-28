package me.heartalborada.security

import me.heartalborada.config.ConfigData
import me.heartalborada.state.BotStateStore
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

enum class AccessDecision {
    ALLOWED,
    BLOCKED,
    NOT_ALLOWED,
    RATE_LIMITED,
}

class AccessController(
    private val config: ConfigData.Access,
    private val state: BotStateStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val requests = ConcurrentHashMap<String, ArrayDeque<Long>>()

    fun isAdmin(userId: Long): Boolean = userId in config.adminUserIds

    fun check(adapter: String, userId: Long, chatId: Long): AccessDecision {
        if (isAdmin(userId)) return AccessDecision.ALLOWED
        if (state.isBanned(userId) || userId in config.blockedUserIds) return AccessDecision.BLOCKED
        if (config.allowedUserIds.isNotEmpty() && userId !in config.allowedUserIds) {
            return AccessDecision.NOT_ALLOWED
        }
        if (config.allowedChatIds.isNotEmpty() && chatId !in config.allowedChatIds) {
            return AccessDecision.NOT_ALLOWED
        }
        if (!acquire("${adapter.lowercase()}:$userId")) return AccessDecision.RATE_LIMITED
        return AccessDecision.ALLOWED
    }

    fun consumeDownload(adapter: String, userId: Long): Boolean =
        isAdmin(userId) || state.consumeDailyDownload(adapter, userId, config.dailyDownloadLimit)

    private fun acquire(key: String): Boolean {
        if (config.commandsPerMinute <= 0) return true
        val now = clock()
        val cutoff = now - 60_000L
        val window = requests.computeIfAbsent(key) { ArrayDeque() }
        synchronized(window) {
            while (window.firstOrNull()?.let { it <= cutoff } == true) window.removeFirst()
            if (window.size >= config.commandsPerMinute) return false
            window.addLast(now)
            return true
        }
    }
}
