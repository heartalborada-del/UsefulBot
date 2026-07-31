package me.heartalborada.commons.permissions

/** Type of entity receiving a permission rule. */
enum class PermissionSubjectType(val key: String) {
    USER("user"),
    GROUP("group"),
}

/** Platform-scoped permission subject, for example `tg:user:123` or `qq:group:456`. */
data class PermissionSubject(
    val platform: String,
    val type: PermissionSubjectType,
    val id: Long,
) {
    init {
        require(platform.matches(PLATFORM)) { "Invalid permission platform: $platform" }
    }

    val key: String = "${platform.lowercase()}:${type.key}:$id"

    companion object {
        private val PLATFORM = Regex("[a-z][a-z0-9_-]*")

        /**
         * Parses canonical keys, current-platform `user:id`/`group:id` shorthand,
         * and legacy `platform:id` user keys.
         */
        fun parse(value: String, defaultPlatform: String? = null): PermissionSubject? {
            val parts = value.trim().lowercase().split(':')
            return when (parts.size) {
                2 -> {
                    val id = parts[1].toLongOrNull() ?: return null
                    when (parts[0]) {
                        "user" -> defaultPlatform?.let { PermissionSubject(normalizePlatform(it), PermissionSubjectType.USER, id) }
                        "group" -> defaultPlatform?.let { PermissionSubject(normalizePlatform(it), PermissionSubjectType.GROUP, id) }
                        else -> PermissionSubject(normalizePlatform(parts[0]), PermissionSubjectType.USER, id)
                    }
                }
                3 -> {
                    val type = PermissionSubjectType.entries.firstOrNull { it.key == parts[1] } ?: return null
                    val id = parts[2].toLongOrNull() ?: return null
                    PermissionSubject(normalizePlatform(parts[0]), type, id)
                }
                else -> null
            }
        }

        private fun normalizePlatform(platform: String): String = when (platform.trim().lowercase()) {
            "telegram" -> "tg"
            "napcat" -> "qq"
            else -> platform.trim().lowercase()
        }
    }
}

/** User plus optional group used when authorizing one command invocation. */
data class PermissionContext(
    val user: PermissionSubject,
    val group: PermissionSubject? = null,
) {
    init {
        require(user.type == PermissionSubjectType.USER) { "Permission context user must be a USER subject." }
        require(group == null || group.type == PermissionSubjectType.GROUP) {
            "Permission context group must be a GROUP subject."
        }
        require(group == null || group.platform == user.platform) {
            "User and group must belong to the same platform."
        }
    }
}

/**
 * Composable command permission policy.
 *
 * Kotlin uses the infix `or` function for bit flags, for example:
 * `PermissionDefault.DENY or PermissionDefault.ALLOW_CONSOLE`.
 */
@JvmInline
value class PermissionDefault private constructor(private val bits: Int) {
    val allowsUnconfigured: Boolean
        get() = bits and MODE_MASK == MODE_ALLOW

    val requiresAdministrator: Boolean
        get() = bits and MODE_MASK == MODE_ADMIN

    val allowsConsole: Boolean
        get() = bits and FLAG_CONSOLE != 0

    infix fun or(other: PermissionDefault): PermissionDefault {
        val leftMode = bits and MODE_MASK
        val rightMode = other.bits and MODE_MASK
        require(leftMode == MODE_DENY || rightMode == MODE_DENY || leftMode == rightMode) {
            "ALLOW and ADMIN cannot be combined in one permission policy."
        }
        return PermissionDefault(bits or other.bits)
    }

    companion object {
        private const val MODE_MASK = 0b11
        private const val MODE_DENY = 0b00
        private const val MODE_ALLOW = 0b01
        private const val MODE_ADMIN = 0b10
        private const val FLAG_CONSOLE = 0b100

        val DENY = PermissionDefault(MODE_DENY)
        val ALLOW = PermissionDefault(MODE_ALLOW)
        val ADMIN = PermissionDefault(MODE_ADMIN)
        val ALLOW_CONSOLE = PermissionDefault(FLAG_CONSOLE)

        /** Alias matching descriptor-style flag naming. */
        val ALLOWCONSOLE = ALLOW_CONSOLE
    }
}

/** Persistent permission service exposed by the mandatory `permissions` plugin. */
interface PermissionService {
    fun hasPermission(
        context: PermissionContext,
        permission: String,
        default: PermissionDefault = PermissionDefault.DENY,
    ): Boolean

    fun grant(subject: PermissionSubject, permission: String): Boolean
    fun deny(subject: PermissionSubject, permission: String): Boolean
    fun clear(subject: PermissionSubject, permission: String): Boolean

    /** Rules use a leading `-` for explicit denies. */
    fun rules(subject: PermissionSubject): Set<String>
}
