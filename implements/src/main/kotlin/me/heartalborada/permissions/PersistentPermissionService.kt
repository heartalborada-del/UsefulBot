package me.heartalborada.permissions

import me.heartalborada.commons.permissions.PermissionContext
import me.heartalborada.commons.permissions.PermissionDefault
import me.heartalborada.commons.permissions.PermissionNodeRegistry
import me.heartalborada.commons.permissions.PermissionService
import me.heartalborada.commons.permissions.PermissionSubject
import me.heartalborada.state.BotStateStore

/** Permission rules backed by the existing atomic bot state file. */
class PersistentPermissionService(
    private val state: BotStateStore,
) : PermissionService, PermissionNodeRegistry {
    private val registeredNodes = mutableMapOf<String, Int>()

    override fun hasPermission(
        context: PermissionContext,
        permission: String,
        default: PermissionDefault,
    ): Boolean {
        val node = requirePermission(permission)
        evaluate(subjectMatches(context.user, context.group), node)?.let { return it }
        if (default.allowsUnconfigured) return true
        if (default.requiresAdministrator) {
            return evaluate(subjectMatches(context.user), ADMIN_PERMISSION) == true
        }
        return false
    }

    override fun grant(subject: PermissionSubject, permission: String): Boolean =
        state.setPermissionRule(subject.key, requirePermission(permission), true)

    override fun deny(subject: PermissionSubject, permission: String): Boolean =
        state.setPermissionRule(subject.key, requirePermission(permission), false)

    override fun clear(subject: PermissionSubject, permission: String): Boolean =
        state.setPermissionRule(subject.key, requirePermission(permission), null)

    override fun rules(subject: PermissionSubject): Set<String> = state.permissions(subject.key)

    @Synchronized
    override fun register(node: String) {
        val normalized = requirePermission(node)
        registeredNodes[normalized] = registeredNodes.getOrDefault(normalized, 0) + 1
    }

    @Synchronized
    override fun unregister(node: String) {
        val normalized = requirePermission(node)
        val count = registeredNodes[normalized] ?: return
        if (count <= 1) registeredNodes.remove(normalized) else registeredNodes[normalized] = count - 1
    }

    @Synchronized
    override fun suggestions(prefix: String): List<String> {
        val normalized = prefix.trim().lowercase()
        return registeredNodes.keys.filter { it.startsWith(normalized) }.sorted()
    }

    private fun evaluate(subjects: Collection<SubjectMatch>, permission: String): Boolean? {
        val matches = subjects.flatMap { subject ->
            rules(subject.subject).mapNotNull { stored ->
                val rule = parseRule(stored) ?: return@mapNotNull null
                val specificity = specificity(rule.node, permission) ?: return@mapNotNull null
                RuleMatch(
                    allowed = rule.allowed,
                    subjectSpecificity = subject.specificity,
                    permissionSpecificity = specificity,
                )
            }
        }
        val subjectSpecificity = matches.maxOfOrNull(RuleMatch::subjectSpecificity) ?: return null
        val subjectMatches = matches.filter { it.subjectSpecificity == subjectSpecificity }
        val permissionSpecificity = subjectMatches.maxOf(RuleMatch::permissionSpecificity)
        // An explicit deny wins when allow and deny have equal specificity.
        return subjectMatches
            .filter { it.permissionSpecificity == permissionSpecificity }
            .all(RuleMatch::allowed)
    }

    private fun subjectMatches(vararg subjects: PermissionSubject?): List<SubjectMatch> {
        val exactSubjects = subjects.filterNotNull()
        val platforms = exactSubjects.map(PermissionSubject::canonicalPlatform).distinct()
        return buildList {
            add(SubjectMatch(PermissionSubject.all(), SUBJECT_GLOBAL))
            platforms.forEach { platform ->
                add(SubjectMatch(PermissionSubject.all(platform), SUBJECT_PLATFORM))
            }
            exactSubjects.forEach { subject ->
                add(
                    SubjectMatch(
                        PermissionSubject.wildcard(subject.canonicalPlatform, subject.type),
                        SUBJECT_TYPE,
                    ),
                )
                add(SubjectMatch(subject, SUBJECT_EXACT))
            }
        }.distinctBy { it.subject.key }
    }

    private fun parseRule(stored: String): PermissionRule? {
        val normalized = stored.trim().lowercase()
        val allowed = !normalized.startsWith('-')
        val node = normalized.removePrefix("+").removePrefix("-")
        return node.takeIf(PERMISSION_NODE::matches)?.let { PermissionRule(it, allowed) }
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

    private data class SubjectMatch(val subject: PermissionSubject, val specificity: Int)
    private data class RuleMatch(
        val allowed: Boolean,
        val subjectSpecificity: Int,
        val permissionSpecificity: Int,
    )
    private data class PermissionRule(val node: String, val allowed: Boolean)

    private companion object {
        const val ADMIN_PERMISSION = "usefulbot.admin"
        const val SUBJECT_GLOBAL = 0
        const val SUBJECT_PLATFORM = 1
        const val SUBJECT_TYPE = 2
        const val SUBJECT_EXACT = 3
        val PERMISSION_NODE = Regex("\\*|[a-z0-9_-]+(?:\\.[a-z0-9_-]+)*(?:\\.\\*)?")
    }
}
