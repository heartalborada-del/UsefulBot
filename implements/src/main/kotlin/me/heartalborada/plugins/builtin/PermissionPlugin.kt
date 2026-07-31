package me.heartalborada.plugins.builtin

import me.heartalborada.commons.ChatType
import me.heartalborada.commons.bots.AbstractBot
import me.heartalborada.commons.bots.MessageChain
import me.heartalborada.commons.bots.dto.MessageSender
import me.heartalborada.commons.i18n.Translator
import me.heartalborada.commons.permissions.PermissionContext
import me.heartalborada.commons.permissions.PermissionDefault
import me.heartalborada.commons.permissions.PermissionService
import me.heartalborada.commons.permissions.PermissionSubject
import me.heartalborada.commons.permissions.PermissionSubjectType
import me.heartalborada.commons.plugins.PluginContext
import me.heartalborada.commons.plugins.UsefulBotPlugin
import me.heartalborada.security.AccessController
import me.heartalborada.state.BotStateStore

/** Built-in permission service and `/permission` management command. */
class PermissionPlugin(
    private val permissions: PermissionService,
    private val state: BotStateStore,
    private val adapterKey: (AbstractBot) -> String,
    private val translatorFor: (AbstractBot, Long) -> Translator,
    private val defaultTranslator: Translator,
) : UsefulBotPlugin {
    override fun onLoad(context: PluginContext) {
        context.registerService(PermissionService::class.java, permissions)
        context.registerCommand(
            "permission",
            "perm",
            usage = defaultTranslator.translate("command.permission.usage"),
        ) {
            subcommand(
                "show",
                usage = defaultTranslator.translate("command.permission.show.usage"),
                permissionDefault = PermissionDefault.ALLOW or PermissionDefault.ALLOW_CONSOLE,
            ) { bot, sender, _, args, messageID ->
                show(bot, sender, messageID, args.toString())
            }
            subcommand(
                "grant",
                usage = defaultTranslator.translate("command.permission.grant.usage"),
                permission = MANAGE_PERMISSION,
                permissionDefault = PermissionDefault.ADMIN or PermissionDefault.ALLOW_CONSOLE,
            ) { bot, sender, _, args, messageID ->
                mutateRule(bot, sender, messageID, args.toString(), RuleAction.GRANT)
            }
            subcommand(
                "deny",
                usage = defaultTranslator.translate("command.permission.deny.usage"),
                permission = MANAGE_PERMISSION,
                permissionDefault = PermissionDefault.ADMIN or PermissionDefault.ALLOW_CONSOLE,
            ) { bot, sender, _, args, messageID ->
                mutateRule(bot, sender, messageID, args.toString(), RuleAction.DENY)
            }
            subcommand(
                "revoke",
                usage = defaultTranslator.translate("command.permission.revoke.usage"),
                permission = MANAGE_PERMISSION,
                permissionDefault = PermissionDefault.ADMIN or PermissionDefault.ALLOW_CONSOLE,
            ) { bot, sender, _, args, messageID ->
                mutateRule(bot, sender, messageID, args.toString(), RuleAction.CLEAR)
            }
            subcommand(
                "ban",
                usage = defaultTranslator.translate("command.permission.ban.usage"),
                permission = MANAGE_PERMISSION,
                permissionDefault = PermissionDefault.ADMIN or PermissionDefault.ALLOW_CONSOLE,
            ) { bot, sender, _, args, messageID ->
                mutateBan(bot, sender, messageID, args.toString(), true)
            }
            subcommand(
                "unban",
                usage = defaultTranslator.translate("command.permission.unban.usage"),
                permission = MANAGE_PERMISSION,
                permissionDefault = PermissionDefault.ADMIN or PermissionDefault.ALLOW_CONSOLE,
            ) { bot, sender, _, args, messageID ->
                mutateBan(bot, sender, messageID, args.toString(), false)
            }
        }
    }

    private fun show(bot: AbstractBot, sender: MessageSender, messageID: Long, argument: String) {
        val translator = translatorFor(bot, sender.user.userID)
        val actor = permissionContext(bot, sender)
        val requested = argument.trim()
        val subject = if (requested.isEmpty()) actor.user else resolveSubject(bot, sender, requested)
        if (subject == null) {
            reply(bot, sender, messageID, translator.translate("permission.invalid_identity"))
            return
        }
        if (sender.type != ChatType.SELF && subject != actor.user && !permissions.hasPermission(
                actor,
                MANAGE_PERMISSION,
                PermissionDefault.ADMIN,
            )
        ) {
            reply(bot, sender, messageID, translator.translate("admin.denied"))
            return
        }
        val rules = permissions.rules(subject).sorted().joinToString().ifEmpty { "-" }
        val banned = if (subject.type == PermissionSubjectType.USER) {
            state.isBanned(AccessController.identity(subject.platform, subject.id)).toString()
        } else {
            "-"
        }
        reply(
            bot,
            sender,
            messageID,
            translator.translate("permission.show", subject.key, banned, rules),
        )
    }

    private fun mutateRule(
        bot: AbstractBot,
        sender: MessageSender,
        messageID: Long,
        arguments: String,
        action: RuleAction,
    ) {
        val translator = translatorFor(bot, sender.user.userID)
        val parts = arguments.trim().split(Regex("\\s+"), limit = 2)
        val subject = parts.getOrNull(0)?.let { resolveSubject(bot, sender, it) }
        val node = parts.getOrNull(1).orEmpty()
        if (subject == null) {
            reply(bot, sender, messageID, translator.translate("permission.invalid_arguments"))
            return
        }
        val changed = runCatching {
            when (action) {
                RuleAction.GRANT -> permissions.grant(subject, node)
                RuleAction.DENY -> permissions.deny(subject, node)
                RuleAction.CLEAR -> permissions.clear(subject, node)
            }
        }.getOrElse {
            reply(bot, sender, messageID, translator.translate("permission.invalid_arguments"))
            return
        }
        val key = when (action) {
            RuleAction.GRANT -> "permission.granted"
            RuleAction.DENY -> "permission.denied"
            RuleAction.CLEAR -> "permission.revoked"
        }
        reply(bot, sender, messageID, translator.translate(key, node.lowercase(), subject.key, changed))
    }

    private fun mutateBan(
        bot: AbstractBot,
        sender: MessageSender,
        messageID: Long,
        argument: String,
        banned: Boolean,
    ) {
        val translator = translatorFor(bot, sender.user.userID)
        val subject = resolveSubject(bot, sender, argument)
        if (subject == null || subject.type != PermissionSubjectType.USER) {
            reply(bot, sender, messageID, translator.translate("permission.invalid_user"))
            return
        }
        val identity = AccessController.identity(subject.platform, subject.id)
        state.setBanned(identity, banned)
        reply(
            bot,
            sender,
            messageID,
            translator.translate(if (banned) "permission.banned" else "permission.unbanned", subject.key),
        )
    }

    private fun permissionContext(bot: AbstractBot, sender: MessageSender): PermissionContext {
        val platform = AccessController.platform(adapterKey(bot))
        return PermissionContext(
            user = PermissionSubject(platform, PermissionSubjectType.USER, sender.user.userID),
            group = sender.target.takeIf { sender.type == ChatType.GROUP }
                ?.let { PermissionSubject(platform, PermissionSubjectType.GROUP, it) },
        )
    }

    private fun resolveSubject(bot: AbstractBot, sender: MessageSender, value: String): PermissionSubject? {
        val context = permissionContext(bot, sender)
        return when (value.trim().lowercase()) {
            "user", "self" -> context.user
            "group", "here" -> context.group
            else -> PermissionSubject.parse(value, context.user.platform)
        }
    }

    private fun reply(bot: AbstractBot, sender: MessageSender, messageID: Long, text: String) {
        bot.sendCommandMessage(sender, MessageChain.replyTo(messageID, text))
    }

    private enum class RuleAction {
        GRANT,
        DENY,
        CLEAR,
    }

    companion object {
        const val MANAGE_PERMISSION = "usefulbot.permissions.manage"
    }
}
