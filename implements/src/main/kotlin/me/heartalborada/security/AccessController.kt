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

    fun isAdmin(adapter: String, userId: Long): Boolean =
        matches(config.adminUserIds, adapter, userId)

    fun check(adapter: String, userId: Long, chatId: Long): AccessDecision {
        if (isAdmin(adapter, userId)) return AccessDecision.ALLOWED
        val userIdentity = identity(adapter, userId)
        if (state.isBanned(userIdentity) || matches(config.blockedUserIds, adapter, userId)) {
            return AccessDecision.BLOCKED
        }
        if (config.allowedUserIds.isNotEmpty() && !matches(config.allowedUserIds, adapter, userId)) {
            return AccessDecision.NOT_ALLOWED
        }
        if (config.allowedChatIds.isNotEmpty() && !matches(config.allowedChatIds, adapter, chatId)) {
            return AccessDecision.NOT_ALLOWED
        }
        if (!acquire(userIdentity)) return AccessDecision.RATE_LIMITED
        return AccessDecision.ALLOWED
    }

    fun consumeDownload(adapter: String, userId: Long): Boolean =
        isAdmin(adapter, userId) || state.consumeDailyDownload(adapter, userId, config.dailyDownloadLimit)

    fun adminTargets(adapter: String): List<Long> = config.adminUserIds.mapNotNull { value ->
        parse(value)?.takeIf { it.matches(adapter, it.id) }?.id
    }.distinct()

    private fun matches(entries: Collection<String>, adapter: String, id: Long): Boolean =
        entries.any { value -> parse(value)?.matches(adapter, id) == true }

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

    private data class ParsedIdentity(val platform: String?, val id: Long) {
        fun matches(adapter: String, candidateId: Long): Boolean =
            id == candidateId && (platform == null || platform == platformFor(adapter))
    }

    companion object {
        fun identity(adapter: String, id: Long): String = "${platformFor(adapter)}:$id"

        fun normalizeScopedIdentity(value: String): String? {
            val parsed = parse(value) ?: return null
            val platform = parsed.platform ?: return null
            return "$platform:${parsed.id}"
        }

        private fun parse(value: String): ParsedIdentity? {
            val normalized = value.trim().lowercase()
            if (normalized.isEmpty()) return null
            if (':' !in normalized) return normalized.toLongOrNull()?.let { ParsedIdentity(null, it) }
            val prefix = normalized.substringBefore(':')
            val id = normalized.substringAfter(':').toLongOrNull() ?: return null
            val platform = when (prefix) {
                "tg", "telegram" -> "tg"
                "qq", "napcat" -> "qq"
                "*" -> null
                else -> return null
            }
            return ParsedIdentity(platform, id)
        }

        private fun platformFor(adapter: String): String = when (adapter.trim().lowercase()) {
            "tg", "telegram", "telegrambot" -> "tg"
            "qq", "napcat" -> "qq"
            else -> adapter.trim().lowercase()
        }
    }
}
