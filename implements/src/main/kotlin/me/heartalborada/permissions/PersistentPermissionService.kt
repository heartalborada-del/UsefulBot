package me.heartalborada.permissions

import me.heartalborada.commons.permissions.PermissionContext
import me.heartalborada.commons.permissions.PermissionDefault
import me.heartalborada.commons.permissions.PermissionService
import me.heartalborada.commons.permissions.PermissionSubject
import me.heartalborada.state.BotStateStore

/** Permission rules backed by the existing atomic bot state file. */
class PersistentPermissionService(
    private val state: BotStateStore,
) : PermissionService {
    override fun hasPermission(
        context: PermissionContext,
        permission: String,
        default: PermissionDefault,
    ): Boolean {
        val node = requirePermission(permission)
        evaluate(context.user, node)?.let { return it }
        context.group?.let { group -> evaluate(group, node)?.let { return it } }
        if (default.allowsUnconfigured) return true
        if (default.requiresAdministrator) return evaluate(context.user, ADMIN_PERMISSION) == true
        return false
    }

    override fun grant(subject: PermissionSubject, permission: String): Boolean =
        state.setPermissionRule(subject.key, requirePermission(permission), true)

    override fun deny(subject: PermissionSubject, permission: String): Boolean =
        state.setPermissionRule(subject.key, requirePermission(permission), false)

    override fun clear(subject: PermissionSubject, permission: String): Boolean =
        state.setPermissionRule(subject.key, requirePermission(permission), null)

    override fun rules(subject: PermissionSubject): Set<String> = state.permissions(subject.key)

    private fun evaluate(subject: PermissionSubject, permission: String): Boolean? {
        val matches = rules(subject).mapNotNull { stored ->
            val denied = stored.startsWith('-')
            val rule = stored.removePrefix("-")
            val specificity = specificity(rule, permission) ?: return@mapNotNull null
            RuleMatch(allowed = !denied, specificity = specificity)
        }
        val mostSpecific = matches.maxOfOrNull(RuleMatch::specificity) ?: return null
        // An explicit deny wins when allow and deny have equal specificity.
        return matches.filter { it.specificity == mostSpecific }.all(RuleMatch::allowed)
    }

    private fun specificity(rule: String, permission: String): Int? = when {
        rule == permission -> Int.MAX_VALUE
        rule == "*" -> 0
        rule.endsWith(".*") && permission.startsWith(rule.removeSuffix("*")) ->
            rule.removeSuffix(".*").count { it == '.' } * 2 + 2
        permission.startsWith("$rule.") -> rule.count { it == '.' } * 2 + 1
        else -> null
    }

    private fun requirePermission(permission: String): String {
        val normalized = permission.trim().lowercase()
        require(PERMISSION_NODE.matches(normalized)) {
            "Permission must be '*' or a dot-separated node; a trailing '.*' wildcard is allowed."
        }
        return normalized
    }

    private data class RuleMatch(val allowed: Boolean, val specificity: Int)

    private companion object {
        const val ADMIN_PERMISSION = "usefulbot.admin"
        val PERMISSION_NODE = Regex("\\*|[a-z0-9_-]+(?:\\.[a-z0-9_-]+)*(?:\\.\\*)?")
    }
}
