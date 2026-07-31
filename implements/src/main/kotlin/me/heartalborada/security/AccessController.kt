package me.heartalborada.security

import me.heartalborada.config.ConfigData
import me.heartalborada.commons.permissions.PermissionSubject
import me.heartalborada.state.BotStateStore
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

enum class AccessDecision {
    ALLOWED,
    BLOCKED,
    RATE_LIMITED,
}

class AccessController(
    private val config: ConfigData.Access,
    private val state: BotStateStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val requests = ConcurrentHashMap<String, ArrayDeque<Long>>()

    fun check(adapter: String, userId: Long, @Suppress("UNUSED_PARAMETER") chatId: Long): AccessDecision {
        val userIdentity = identity(adapter, userId)
        if (state.isBanned(userIdentity)) {
            return AccessDecision.BLOCKED
        }
        if (!acquire(userIdentity)) return AccessDecision.RATE_LIMITED
        return AccessDecision.ALLOWED
    }

    fun consumeDownload(adapter: String, userId: Long): Boolean =
        state.consumeDailyDownload(adapter, userId, config.dailyDownloadLimit)

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

    private data class ParsedIdentity(val platform: String?, val id: Long)

    companion object {
        fun identity(adapter: String, id: Long): String = "${platformFor(adapter)}:$id"

        fun platform(adapter: String): String = platformFor(adapter)

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

        private fun platformFor(adapter: String): String =
            if (adapter.trim().equals("telegrambot", ignoreCase = true)) {
                "tg"
            } else {
                PermissionSubject.normalizePlatform(adapter)
            }
    }
}
