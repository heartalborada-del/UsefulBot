package me.heartalborada.commons.permissions

/** Type of entity receiving a permission rule. */
enum class PermissionSubjectType(val key: String) {
    ALL("*"),
    USER("user"),
    GROUP("group"),
}

/** Permission subject or wildcard selector, for example `tg:user:123`, `qq:group:*`, or `*`. */
data class PermissionSubject(
    val platform: String,
    val type: PermissionSubjectType,
    val id: Long,
) {
    val canonicalPlatform: String = normalizePlatform(platform)
    val isWildcard: Boolean = id == WILDCARD_ID

    init {
        require(canonicalPlatform == "*" || canonicalPlatform.matches(PLATFORM)) {
            "Invalid permission platform: $platform"
        }
        require(type != PermissionSubjectType.ALL || isWildcard) {
            "An all-subject selector must use a wildcard ID."
        }
        require(canonicalPlatform != "*" || type == PermissionSubjectType.ALL) {
            "The global platform wildcard must select all subject types."
        }
    }

    val key: String = when (type) {
        PermissionSubjectType.ALL -> if (canonicalPlatform == "*") "*" else "$canonicalPlatform:*"
        else -> "$canonicalPlatform:${type.key}:${if (isWildcard) "*" else id}"
    }

    override fun equals(other: Any?): Boolean = other is PermissionSubject &&
        canonicalPlatform == other.canonicalPlatform && type == other.type && id == other.id

    override fun hashCode(): Int = 31 * (31 * canonicalPlatform.hashCode() + type.hashCode()) + id.hashCode()

    companion object {
        const val WILDCARD_ID: Long = Long.MIN_VALUE
        private val PLATFORM = Regex("[a-z][a-z0-9_-]*")

        fun all(platform: String = "*"): PermissionSubject =
            PermissionSubject(platform, PermissionSubjectType.ALL, WILDCARD_ID)

        fun wildcard(platform: String, type: PermissionSubjectType): PermissionSubject {
            require(type != PermissionSubjectType.ALL) { "Use all(platform) for an all-subject selector." }
            return PermissionSubject(platform, type, WILDCARD_ID)
        }

        /**
         * Parses wildcard selectors, canonical keys, current-platform
         * `user:id`/`group:id` shorthand, and legacy `platform:id` user keys.
         */
        fun parse(value: String, defaultPlatform: String? = null): PermissionSubject? {
            val parts = value.trim().lowercase().split(':')
            return when (parts.size) {
                1 -> if (parts[0] == "*") all() else null
                2 -> {
                    if (parts[1] == "*" && parts[0] !in setOf("user", "group")) {
                        return all(parts[0])
                    }
                    val id = parseId(parts[1]) ?: return null
                    when (parts[0]) {
                        "user" -> defaultPlatform?.let { PermissionSubject(normalizePlatform(it), PermissionSubjectType.USER, id) }
                        "group" -> defaultPlatform?.let { PermissionSubject(normalizePlatform(it), PermissionSubjectType.GROUP, id) }
                        else -> PermissionSubject(normalizePlatform(parts[0]), PermissionSubjectType.USER, id)
                    }
                }
                3 -> {
                    val type = PermissionSubjectType.entries.firstOrNull { it.key == parts[1] } ?: return null
                    if (parts[0] == "*" || type == PermissionSubjectType.ALL) return null
                    val id = parseId(parts[2]) ?: return null
                    PermissionSubject(normalizePlatform(parts[0]), type, id)
                }
                else -> null
            }
        }

        fun normalizePlatform(platform: String): String = when (platform.trim().lowercase()) {
            "telegram" -> "tg"
            "napcat" -> "qq"
            else -> platform.trim().lowercase()
        }

        private fun parseId(value: String): Long? =
            if (value == "*") WILDCARD_ID else value.toLongOrNull()
    }
}

/** User plus optional group used when authorizing one command invocation. */
data class PermissionContext(
    val user: PermissionSubject,
    val group: PermissionSubject? = null,
) {
    init {
        require(user.type == PermissionSubjectType.USER) { "Permission context user must be a USER subject." }
        require(!user.isWildcard) { "Permission context user must be an exact subject." }
        require(group == null || group.type == PermissionSubjectType.GROUP) {
            "Permission context group must be a GROUP subject."
        }
        require(group == null || !group.isWildcard) { "Permission context group must be an exact subject." }
        require(group == null || group.canonicalPlatform == user.canonicalPlatform) {
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

    /** Rules may use `+` for allows and `-` for denies; an omitted prefix means allow. */
    fun rules(subject: PermissionSubject): Set<String>
}

/** Registry of permission nodes currently exposed by loaded commands. */
interface PermissionNodeRegistry {
    fun register(node: String)
    fun unregister(node: String)
    fun suggestions(prefix: String = ""): List<String>
}
