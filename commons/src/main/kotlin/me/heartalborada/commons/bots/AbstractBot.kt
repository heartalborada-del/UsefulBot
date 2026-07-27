package me.heartalborada.commons.bots

import kotlinx.coroutines.*
import me.heartalborada.commons.ChatType
import me.heartalborada.commons.bots.dto.FileInfo
import me.heartalborada.commons.bots.dto.ForwardMessageNode
import me.heartalborada.commons.bots.dto.ForwardMessageResult
import me.heartalborada.commons.bots.dto.MessageSender
import me.heartalborada.commons.bots.events.EventBus
import me.heartalborada.commons.bots.events.message.GroupMessageEvent
import me.heartalborada.commons.bots.events.message.PrivateMessageEvent
import me.heartalborada.commons.commands.CommandExecutor
import me.heartalborada.commons.commands.SubcommandBuilder
import me.heartalborada.commons.i18n.Translator
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.util.Locale

abstract class AbstractBot(
    private val commandStartWithAt: Boolean = true,
    private val commandOperator: Char = '/',
    private val commandDivider: Char = ' ',
    protected val translator: Translator = Translator(),
) {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        logger.error("An unexpected error occurred.", throwable)
    }
    private val commonScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler + CoroutineName("BotExecutorScope"))

    private class CommandDefinition(
        val usage: String,
        val route: CommandRoute,
    )

    private sealed interface CommandRoute {
        class Leaf(val executor: CommandExecutor) : CommandRoute
        class Branch(val subcommands: Map<String, RegisteredSubcommand>) : CommandRoute
    }

    private class RegisteredSubcommand(
        val canonicalName: String,
        val executor: CommandExecutor,
        val usage: String,
    )

    private val commandMap = linkedMapOf<String, CommandDefinition>()
    private var isRegistered: Boolean = false

    init {
        registerCommand("help", "h", usage = translator.translate("command.help.usage")) {
            sender, _, args, messageID ->
            val requestedCommand = args.toString()
                .trim()
                .substringBefore(commandDivider)
                .lowercase(Locale.ROOT)
            val helpText = commandMap[requestedCommand]
                ?.let(::buildCommandHelp)
                ?: buildGlobalHelp()
            reply(sender, messageID, helpText)
        }
    }

    open fun close(): Boolean {
        commonScope.cancel()
        commandMap.clear()
        return true
    }

    open fun connect(): Boolean {
        if (!isRegistered)
            registerCommandEvent(
                operator = commandOperator,
                isStartWithAtBot = commandStartWithAt,
                divider = commandDivider
            )
        return true
    }

    abstract fun getEventBus(): EventBus

    abstract fun sendMessage(type: ChatType, id: Long, message: MessageChain): Long

    /**
     * Sends a merged-forward message to a private chat or group.
     *
     * @param type target chat type; only [ChatType.PRIVATE] and [ChatType.GROUP] are supported
     * @param target user ID or group ID, according to [type]
     * @param messages nodes containing either an existing message ID or custom content
     */
    abstract fun sendForwardMessage(
        type: ChatType,
        target: Long,
        messages: List<ForwardMessageNode>,
    ): ForwardMessageResult

    abstract fun recallMessage(messageID: Long): Boolean

    /**
     * @param type ChatType
     * @param target Long
     * @param url <bold>base64</bold> or <bold>local file relative path</bold> with scheme
     * @throws IllegalArgumentException when uploadActionType is STREAMAPI
     * @author heartalborada-del
     */
    abstract fun sendFile(type: ChatType, target: Long, name: String, url: String): Boolean

    /**
     * @param type ChatType
     * @param target Long
     * @param file File
     */
    abstract fun sendFile(type: ChatType, target: Long, name: String, file: File): Boolean

    fun registerCommand(vararg commands: String, usage: String, executor: CommandExecutor) {
        registerCommandDefinition(
            commands = commands,
            definition = CommandDefinition(
                usage = usage.trim(),
                route = CommandRoute.Leaf(executor),
            ),
        )
    }

    fun registerCommand(
        vararg commands: String,
        usage: String,
        configure: SubcommandBuilder.() -> Unit,
    ) {
        val subcommands = SubcommandBuilder().apply(configure).build()
        require(subcommands.isNotEmpty()) { "At least one subcommand is required." }
        val subcommandMap = linkedMapOf<String, RegisteredSubcommand>()
        subcommands.forEach { subcommand ->
            require(subcommand.commands.isNotEmpty()) { "At least one subcommand name is required." }
            require(subcommand.usage.isNotBlank()) { "Subcommand usage must not be blank." }
            val normalizedNames = normalizeCommandNames(subcommand.commands)
            normalizedNames.firstOrNull(subcommandMap::containsKey)?.let {
                throw IllegalArgumentException("Subcommand $it is already registered.")
            }
            val registered = RegisteredSubcommand(
                canonicalName = normalizedNames.first(),
                executor = subcommand.executor,
                usage = subcommand.usage.trim(),
            )
            normalizedNames.forEach { subcommandMap[it] = registered }
        }
        registerCommandDefinition(
            commands = commands,
            definition = CommandDefinition(
                usage = usage.trim(),
                route = CommandRoute.Branch(subcommandMap),
            ),
        )
    }

    private fun registerCommandDefinition(
        commands: Array<out String>,
        definition: CommandDefinition,
    ) {
        require(commands.isNotEmpty()) { "At least one command name is required." }
        require(definition.usage.isNotBlank()) { "Command usage must not be blank." }
        val normalizedCommands = normalizeCommandNames(commands.toList())

        normalizedCommands.firstOrNull(commandMap::containsKey)?.let {
            throw IllegalArgumentException("Command $it is already registered.")
        }

        normalizedCommands.forEach { commandMap[it] = definition }
    }

    private fun normalizeCommandNames(commands: Collection<String>): List<String> =
        commands.map { it.trim().lowercase(Locale.ROOT) }
            .also { names ->
                require(names.none(String::isBlank)) { "Command name must not be blank." }
                require(names.distinct().size == names.size) { "Command aliases must be unique." }
                require(names.none { commandDivider in it || commandOperator in it }) {
                    "Command name must not contain the operator or divider."
                }
            }

    fun unregisterCommand(vararg commands: String) {
        val normalizedCommands = commands.map { it.trim().lowercase(Locale.ROOT) }
        normalizedCommands.firstOrNull { it !in commandMap }?.let {
            throw IllegalArgumentException("Command $it is not registered.")
        }
        normalizedCommands.forEach(commandMap::remove)
    }

    private fun registerCommandEvent(isStartWithAtBot: Boolean = true, operator: Char? = null, divider: Char = ' ') {
        this.getEventBus().register(GroupMessageEvent::class.java) {
            logger.info(
                "[Receive] {} <- [GROUP] [{}] [{}] {}",
                it.botID,
                it.groupID,
                it.sender.userID,
                it.message.toString()
            )
            if (isStartWithAtBot && (it.message.firstOrNull() as? At)?.target != it.botID) return@register
            val copy = MessageChain()
            copy.addAll(it.message)
            if (isStartWithAtBot) copy.removeAt(0)
            commandParser(MessageSender(it.groupID, it.sender, ChatType.GROUP), copy, operator, divider, it.messageID)
        }
        this.getEventBus().register(PrivateMessageEvent::class.java) {
            logger.info("[Receive] {} <- [PRIVATE] [{}] {}", it.botID, it.sender.userID, it.message.toString())
            commandParser(
                MessageSender(it.sender.userID, it.sender, ChatType.PRIVATE),
                it.message,
                operator,
                divider,
                it.messageID
            )
        }
        isRegistered = true
    }

    private fun commandParser(
        sender: MessageSender,
        messageChain: MessageChain,
        operator: Char?,
        divider: Char,
        messageID: Long
    ): Boolean {
        val firstText = (messageChain.firstOrNull() as? PlainText)?.text?.trimStart() ?: return false
        if (firstText.isEmpty() || operator != null && !firstText.startsWith(operator)) return false

        val commandBody = if (operator == null) firstText else firstText.drop(1)
        val commandName = commandBody
            .substringBefore(divider)
            .trim()
            .lowercase(Locale.ROOT)
        if (commandName.isEmpty()) return false

        val definition = commandMap[commandName]
        if (definition == null) {
            reply(
                sender,
                messageID,
                translator.translate("command.unknown", "$commandOperator$commandName") +
                    "\n\n" +
                    buildGlobalHelp()
            )
            return true
        }

        val commandArguments = MessageChain()
        val firstArgument = commandBody.substringAfter(divider, "").trimStart(divider)
        if (firstArgument.isNotEmpty()) {
            commandArguments.add(PlainText(firstArgument))
        }
        messageChain.drop(1).forEach(commandArguments::add)

        val execution = when (val route = definition.route) {
            is CommandRoute.Leaf -> CommandExecution(
                executor = route.executor,
                command = commandName,
                arguments = commandArguments,
            )
            is CommandRoute.Branch -> resolveSubcommand(route, commandArguments)
        }
        if (execution == null) {
            reply(sender, messageID, buildCommandHelp(definition))
            return true
        }

        commonScope.launch {
            try {
                execution.executor.execute(sender, execution.command, execution.arguments, messageID)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                logger.error(
                    "Failed to execute command {} for user {} in {} {}.",
                    commandName,
                    sender.user.userID,
                    sender.type,
                    sender.target,
                    exception
                )
                reply(
                    sender,
                    messageID,
                    translator.translate("command.execution_failed")
                )
            }
        }
        return true
    }

    private data class CommandExecution(
        val executor: CommandExecutor,
        val command: String,
        val arguments: MessageChain,
    )

    private fun resolveSubcommand(
        route: CommandRoute.Branch,
        arguments: MessageChain,
    ): CommandExecution? {
        val firstText = (arguments.firstOrNull() as? PlainText)?.text?.trimStart() ?: return null
        val subcommandName = firstText
            .substringBefore(commandDivider)
            .trim()
            .lowercase(Locale.ROOT)
        val subcommand = route.subcommands[subcommandName] ?: return null
        val remainingArguments = MessageChain()
        val remainingText = firstText.substringAfter(commandDivider, "").trimStart(commandDivider)
        if (remainingText.isNotEmpty()) {
            remainingArguments.add(PlainText(remainingText))
        }
        arguments.drop(1).forEach(remainingArguments::add)
        return CommandExecution(
            executor = subcommand.executor,
            command = subcommand.canonicalName,
            arguments = remainingArguments,
        )
    }

    private fun buildGlobalHelp(): String {
        val definitions = commandMap.values.distinct()
        return buildString {
            appendLine(translator.translate("command.help.header"))
            definitions.forEachIndexed { index, definition ->
                val names = commandMap
                    .filterValues { it === definition }
                    .keys
                    .joinToString(", ") { "$commandOperator$it" }
                append("  $names — ${definition.usage}")
                val route = definition.route
                if (route is CommandRoute.Branch) {
                    route.subcommands.values.distinct().forEach { subcommand ->
                        appendLine()
                        append("    ${subcommand.usage}")
                    }
                }
                if (index != definitions.lastIndex) appendLine()
            }
        }
    }

    private fun buildCommandHelp(definition: CommandDefinition): String {
        val route = definition.route
        if (route !is CommandRoute.Branch) return definition.usage
        val subcommands = route.subcommands.values.distinct()
        return buildString {
            appendLine(definition.usage)
            appendLine(translator.translate("command.help.subcommands"))
            subcommands.forEachIndexed { index, subcommand ->
                append("  ${subcommand.usage}")
                if (index != subcommands.lastIndex) appendLine()
            }
        }
    }

    private fun reply(sender: MessageSender, messageID: Long, text: String) {
        sendMessage(sender.type, sender.target, MessageChain.replyTo(messageID, text))
    }
}
