package me.heartalborada.commons.bots

import kotlinx.coroutines.*
import me.heartalborada.commons.ChatType
import me.heartalborada.commons.bots.dto.FileInfo
import me.heartalborada.commons.bots.dto.ForwardMessageNode
import me.heartalborada.commons.bots.dto.ForwardMessageResult
import me.heartalborada.commons.bots.dto.InlineQueryResult
import me.heartalborada.commons.bots.dto.MessageSender
import me.heartalborada.commons.bots.dto.UserInfo
import me.heartalborada.commons.bots.events.EventBus
import me.heartalborada.commons.bots.events.message.GroupMessageEvent
import me.heartalborada.commons.bots.events.message.PrivateMessageEvent
import me.heartalborada.commons.bots.events.request.GroupAddRequestEvent
import me.heartalborada.commons.commands.CommandExecutor
import me.heartalborada.commons.commands.CommandErrorHandler
import me.heartalborada.commons.commands.CommandGuard
import me.heartalborada.commons.commands.SubcommandBuilder
import me.heartalborada.commons.i18n.Translator
import me.heartalborada.commons.permissions.PermissionDefault
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Common API implemented by every bot adapter.
 *
 * Platform-dependent methods and events are marked with [SupportedBotTypes].
 * Plugins should check that annotation when they expose functionality that is not
 * available on every adapter. Command registration and console execution are
 * adapter-independent facilities supplied by this base class.
 *
 * @param commandStartWithAt whether group commands must start by mentioning the bot
 * @param commandOperator command prefix, normally `/`
 * @param commandDivider character separating command names and arguments
 * @param translator translator used for command help and error messages
 */
abstract class AbstractBot(
    private val commandStartWithAt: Boolean = true,
    private val commandOperator: Char = '/',
    private val commandDivider: Char = ' ',
    protected val translator: Translator,
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
        class Leaf(
            val executor: CommandExecutor,
            val permissionDefault: PermissionDefault,
        ) : CommandRoute
        class Branch(val subcommands: Map<String, RegisteredSubcommand>) : CommandRoute
    }

    private class RegisteredSubcommand(
        val canonicalName: String,
        val executor: CommandExecutor,
        val usage: String,
        val permissionDefault: PermissionDefault,
    )

    private val commandMap = linkedMapOf<String, CommandDefinition>()
    private val beforeCommandExecutors = mutableListOf<CommandExecutor>()
    private val commandGuards = mutableListOf<CommandGuard>()
    private val commandVisibility = mutableMapOf<CommandDefinition, (MessageSender) -> Boolean>()
    private var commandErrorHandler: CommandErrorHandler? = null
    private var isRegistered: Boolean = false
    private val consoleOutputs = ConcurrentHashMap<Long, (String) -> Unit>()
    private val consoleExecutionIds = AtomicLong(Long.MIN_VALUE)

    init {
        registerCommand(
            "help",
            "h",
            usage = translator.translate("command.help.usage"),
            permissionDefault = PermissionDefault.ALLOW or PermissionDefault.ALLOW_CONSOLE,
        ) {
            sender, _, args, messageID ->
            val requestedCommand = args.toString()
                .trim()
                .substringBefore(commandDivider)
                .lowercase(Locale.ROOT)
            val definition = commandMap[requestedCommand]
                ?.takeIf { isCommandVisible(it, sender) }
                ?.takeIf { sender.type != ChatType.SELF || isConsoleAvailable(it) }
            val helpText = definition?.let { buildCommandHelp(it, sender) } ?: buildGlobalHelp(sender)
            reply(sender, messageID, helpText)
        }
    }

    /**
     * Closes the adapter and releases its event and command resources.
     *
     * Implementations should call `super.close()` after stopping platform clients.
     * Calling this method may publish a platform-specific offline event before the
     * event bus is closed.
     *
     * @return `true` when shutdown was accepted
     */
    @SupportedBotTypes(BotType.NAPCAT, BotType.TELEGRAM)
    open fun close(): Boolean {
        commonScope.cancel()
        getEventBus().close()
        commandMap.clear()
        beforeCommandExecutors.clear()
        commandGuards.clear()
        commandVisibility.clear()
        commandErrorHandler = null
        consoleOutputs.clear()
        return true
    }

    /**
     * Starts the adapter and installs the common command event handlers.
     *
     * Implementations should establish their platform connection and call
     * `super.connect()` once they are ready to receive commands.
     *
     * @return `true` when startup was accepted
     */
    @SupportedBotTypes(BotType.NAPCAT, BotType.TELEGRAM)
    open fun connect(): Boolean {
        if (!isRegistered)
            registerCommandEvent(
                operator = commandOperator,
                isStartWithAtBot = commandStartWithAt,
                divider = commandDivider
            )
        return true
    }

    /** Returns the event bus owned by this adapter. */
    @SupportedBotTypes(BotType.NAPCAT, BotType.TELEGRAM)
    abstract fun getEventBus(): EventBus

    /**
     * Sends a message to a private chat or group.
     *
     * @param type target chat type
     * @param id user or group ID selected by [type]
     * @param message message elements to send
     * @return platform message ID, or `0` when the send failed
     */
    @SupportedBotTypes(BotType.NAPCAT, BotType.TELEGRAM)
    abstract fun sendMessage(type: ChatType, id: Long, message: MessageChain): Long

    /**
     * Sends a merged-forward message to a private chat or group.
     *
     * @param type target chat type; only [ChatType.PRIVATE] and [ChatType.GROUP] are supported
     * @param target user ID or group ID, according to [type]
     * @param messages nodes containing either an existing message ID or custom content
     * @return IDs and platform metadata for the forwarded message
     */
    @SupportedBotTypes(BotType.NAPCAT, BotType.TELEGRAM)
    abstract fun sendForwardMessage(
        type: ChatType,
        target: Long,
        messages: List<ForwardMessageNode>,
    ): ForwardMessageResult

    /**
     * Answers a platform-native inline query.
     *
     * @param queryID identifier supplied by the corresponding inline-query event
     * @param results results to display to the user
     * @param nextOffset pagination token, or `null` when no next page exists
     * @return `true` when the platform accepted the answer; unsupported adapters return `false`
     */
    @SupportedBotTypes(BotType.TELEGRAM)
    open fun answerInlineQuery(
        queryID: String,
        results: List<InlineQueryResult>,
        nextOffset: String? = null,
    ): Boolean = false

    /**
     * Answers a platform-native interactive button callback.
     *
     * @param queryID identifier supplied by the corresponding callback event
     * @param text optional notification text
     * @param showAlert whether to display [text] as an alert instead of a transient notification
     * @return `true` when the platform accepted the answer; unsupported adapters return `false`
     */
    @SupportedBotTypes(BotType.TELEGRAM)
    open fun answerCallbackQuery(
        queryID: String,
        text: String? = null,
        showAlert: Boolean = false,
    ): Boolean = false

    /**
     * Accepts or rejects a friend request identified by its event token.
     *
     * @param requestFlag token from `FriendAddRequestEvent.requestFlag`
     * @param approve `true` to accept the request
     * @param remark optional friend remark used when accepting
     * @return `true` when the platform accepted the response; unsupported adapters return `false`
     */
    @SupportedBotTypes(BotType.NAPCAT)
    open fun respondFriendRequest(
        requestFlag: String,
        approve: Boolean,
        remark: String? = null,
    ): Boolean = false

    /**
     * Accepts or rejects a group join request or bot invitation.
     *
     * @param requestFlag token from [GroupAddRequestEvent.requestFlag]
     * @param requestType whether the request is a join request or invitation
     * @param approve `true` to accept the request
     * @param reason optional rejection reason
     * @return `true` when the platform accepted the response; unsupported adapters return `false`
     */
    @SupportedBotTypes(BotType.NAPCAT)
    open fun respondGroupRequest(
        requestFlag: String,
        requestType: GroupAddRequestEvent.ActionType,
        approve: Boolean,
        reason: String? = null,
    ): Boolean = false

    /**
     * Recalls a message previously sent or observed by this bot.
     *
     * @param messageID platform message ID
     * @return `true` when the platform accepted the recall
     */
    @SupportedBotTypes(BotType.NAPCAT, BotType.TELEGRAM)
    abstract fun recallMessage(messageID: Long): Boolean

    /**
     * Recalls a message using an explicit chat target.
     *
     * This overload is preferable when the adapter needs both a chat ID and a
     * message ID, or when the message was created before the current process.
     * The default implementation delegates to [recallMessage].
     *
     * @param type target chat type
     * @param target user or group ID selected by [type]
     * @param messageID platform message ID
     */
    @SupportedBotTypes(BotType.NAPCAT, BotType.TELEGRAM)
    open fun recallMessage(type: ChatType, target: Long, messageID: Long): Boolean =
        recallMessage(messageID)

    /**
     * Replaces the content of an existing text message.
     *
     * @return `true` when the platform accepted the edit; unsupported adapters return `false`
     */
    @SupportedBotTypes(BotType.TELEGRAM)
    open fun editMessage(
        type: ChatType,
        target: Long,
        messageID: Long,
        message: MessageChain,
    ): Boolean = false

    /**
     * Pins a message in its chat.
     *
     * @param notify whether members should receive a pin notification when supported
     * @return `true` when the platform accepted the operation; unsupported targets return `false`
     */
    @SupportedBotTypes(BotType.NAPCAT, BotType.TELEGRAM)
    open fun pinMessage(
        type: ChatType,
        target: Long,
        messageID: Long,
        notify: Boolean = false,
    ): Boolean = false

    /** Unpins one specific message from its chat. */
    @SupportedBotTypes(BotType.NAPCAT, BotType.TELEGRAM)
    open fun unpinMessage(type: ChatType, target: Long, messageID: Long): Boolean = false

    /**
     * Sends a file referenced by a URL, data URI, or adapter-supported local path.
     *
     * @param type target chat type
     * @param target user or group ID selected by [type]
     * @param name file name visible to the recipient
     * @param url file source understood by the selected adapter
     * @return `true` when the platform accepted the upload
     */
    @SupportedBotTypes(BotType.NAPCAT, BotType.TELEGRAM)
    abstract fun sendFile(type: ChatType, target: Long, name: String, url: String): Boolean

    /**
     * Sends a local file.
     *
     * @param type target chat type
     * @param target user or group ID selected by [type]
     * @param name file name visible to the recipient
     * @param file readable local file
     * @return `true` when the platform accepted the upload
     */
    @SupportedBotTypes(BotType.NAPCAT, BotType.TELEGRAM)
    abstract fun sendFile(type: ChatType, target: Long, name: String, file: File): Boolean

    /**
     * Registers a leaf command and all of its aliases.
     *
     * @param commands canonical name followed by optional aliases
     * @param usage help text shown for the command
     * @param permissionDefault default permission flags, including console access
     * @param executor command implementation
     * @throws IllegalArgumentException if names are missing, invalid, or already registered
     */
    fun registerCommand(
        vararg commands: String,
        usage: String,
        permissionDefault: PermissionDefault = PermissionDefault.ALLOW,
        executor: CommandExecutor,
    ) {
        registerCommandDefinition(
            commands = commands,
            definition = CommandDefinition(
                usage = usage.trim(),
                route = CommandRoute.Leaf(executor, permissionDefault),
            ),
        )
    }

    /**
     * Registers an executor that runs before every successfully resolved command.
     *
     * Unknown commands and invalid subcommands do not trigger these executors.
     *
     * @param executor hook invoked before the resolved command executor
     */
    fun beforeCommandExecution(executor: CommandExecutor) {
        beforeCommandExecutors += executor
    }

    /**
     * Registers a guard that may reject resolved commands before execution.
     *
     * @param guard predicate invoked in registration order
     */
    fun guardCommands(guard: CommandGuard) {
        commandGuards += guard
    }

    /** Installs the handler used when a command executor throws an exception. */
    fun onCommandError(handler: CommandErrorHandler) {
        commandErrorHandler = handler
    }

    /**
     * Controls whether registered commands appear in help and can be resolved.
     *
     * Aliases of the same command share one visibility predicate.
     *
     * @param commands registered command names or aliases
     * @param visible predicate evaluated for the current sender
     */
    fun setCommandVisibility(vararg commands: String, visible: (MessageSender) -> Boolean) {
        require(commands.isNotEmpty()) { "At least one command name is required." }
        val definitions = commands.map { command ->
            val normalized = command.trim().lowercase(Locale.ROOT)
            requireNotNull(commandMap[normalized]) { "Command $normalized is not registered." }
        }.distinct()
        definitions.forEach { commandVisibility[it] = visible }
    }

    /** Returns each registered command once, using its canonical name. */
    fun registeredCommands(): List<BotCommand> {
        val seen = mutableSetOf<CommandDefinition>()
        return commandMap.mapNotNull { (name, definition) ->
            if (!seen.add(definition)) return@mapNotNull null
            BotCommand(name, definition.usage)
        }
    }

    /**
     * Sends a command response to either its chat adapter or an active console invocation.
     *
     * @return platform message ID for chat responses, otherwise `0`
     */
    @SupportedBotTypes(BotType.NAPCAT, BotType.TELEGRAM)
    fun sendCommandMessage(sender: MessageSender, message: MessageChain): Long {
        if (sender.type != ChatType.SELF) return sendMessage(sender.type, sender.target, message)
        val output = consoleOutputs[sender.target] ?: return 0L
        val text = message.filterNot { it is Reply }.joinToString(separator = "")
        if (text.isNotBlank()) output(text)
        return 0L
    }

    /**
     * Returns command or direct-subcommand names available to the console.
     *
     * @param command canonical parent command, or `null` to complete top-level commands
     * @param prefix incomplete name to filter by
     */
    fun completeConsoleCommand(command: String?, prefix: String = ""): List<String> {
        val normalizedPrefix = prefix.lowercase(Locale.ROOT)
        val candidates = if (command == null) {
            commandMap.filterValues(::isConsoleAvailable).keys
        } else {
            val definition = commandMap[command.lowercase(Locale.ROOT)] ?: return emptyList()
            val route = definition.route as? CommandRoute.Branch ?: return emptyList()
            route.subcommands
                .filterValues { it.permissionDefault.allowsConsole }
                .keys
        }
        return candidates.filter { it.startsWith(normalizedPrefix) }.distinct().sorted()
    }

    /**
     * Executes a command without a chat identity when its policy includes
     * [PermissionDefault.ALLOW_CONSOLE].
     *
     * @param line command line with an optional command prefix
     * @param output receives command responses and validation errors
     * @return `true` only when a console-allowed executor completed successfully
     */
    suspend fun executeConsoleCommand(line: String, output: (String) -> Unit): Boolean {
        val body = line.trim()
            .removePrefix(commandOperator.toString())
            .trimStart()
        if (body.isEmpty()) return false
        val commandName = body.substringBefore(commandDivider).lowercase(Locale.ROOT)
        val definition = commandMap[commandName]
        if (definition == null) {
            output("Unknown console command: $commandName")
            return false
        }
        val arguments = MessageChain().apply {
            body.substringAfter(commandDivider, "")
                .trimStart(commandDivider)
                .takeIf(String::isNotEmpty)
                ?.let { add(PlainText(it)) }
        }
        val execution = when (val route = definition.route) {
            is CommandRoute.Leaf -> CommandExecution(
                executor = route.executor,
                command = commandName,
                arguments = arguments,
                permissionDefault = route.permissionDefault,
            )
            is CommandRoute.Branch -> resolveSubcommand(route, arguments)
        }
        if (execution == null) {
            output(definition.usage)
            return false
        }
        if (!execution.permissionDefault.allowsConsole) {
            output("Command is not available from the console.")
            return false
        }

        val executionId = consoleExecutionIds.getAndIncrement()
        val sender = MessageSender(
            target = executionId,
            user = UserInfo(0L, "CONSOLE"),
            type = ChatType.SELF,
        )
        consoleOutputs[executionId] = output
        return try {
            execution.executor.execute(sender, execution.command, execution.arguments, executionId)
            true
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.error("Console command {} failed.", line, exception)
            output("Command failed: ${exception.message ?: exception.javaClass.simpleName}")
            false
        } finally {
            consoleOutputs.remove(executionId)
        }
    }

    /**
     * Registers a parent command with direct subcommands and aliases.
     *
     * Each subcommand defines its own permission defaults and console support in
     * [SubcommandBuilder].
     *
     * @param commands canonical parent name followed by optional aliases
     * @param usage help text shown for the parent command
     * @param configure subcommand declarations
     * @throws IllegalArgumentException if no subcommands exist or a name is invalid or duplicated
     */
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
                permissionDefault = subcommand.permissionDefault,
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
        commandVisibility.keys.removeAll { definition -> definition !in commandMap.values }
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
        if (definition == null || !isCommandVisible(definition, sender)) {
            reply(
                sender,
                messageID,
                translator.translate("command.unknown", "$commandOperator$commandName") +
                    "\n\n" +
                    buildGlobalHelp(sender)
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
                permissionDefault = route.permissionDefault,
            )
            is CommandRoute.Branch -> resolveSubcommand(route, commandArguments)
        }
        if (execution == null) {
            reply(sender, messageID, buildCommandHelp(definition, sender))
            return true
        }

        commonScope.launch {
            try {
                if (commandGuards.any { !it.allow(sender, execution.command, execution.arguments, messageID) }) {
                    return@launch
                }
                beforeCommandExecutors.forEach {
                    it.execute(sender, execution.command, execution.arguments, messageID)
                }
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
                val operation = messageChain.toString().trim()
                val response = commandErrorHandler?.let { handler ->
                    runCatching { handler.handle(sender, operation, messageID, exception) }
                        .onFailure { logger.error("Failed to persist command error report.", it) }
                        .getOrNull()
                } ?: translator.translate("command.execution_failed")
                reply(sender, messageID, response)
            }
        }
        return true
    }

    private data class CommandExecution(
        val executor: CommandExecutor,
        val command: String,
        val arguments: MessageChain,
        val permissionDefault: PermissionDefault,
    )

    data class BotCommand(val name: String, val description: String)

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
            permissionDefault = subcommand.permissionDefault,
        )
    }

    private fun isConsoleAvailable(definition: CommandDefinition): Boolean = when (val route = definition.route) {
        is CommandRoute.Leaf -> route.permissionDefault.allowsConsole
        is CommandRoute.Branch -> route.subcommands.values.any { it.permissionDefault.allowsConsole }
    }

    private fun isCommandVisible(definition: CommandDefinition, sender: MessageSender): Boolean =
        commandVisibility[definition]?.invoke(sender) != false

    private fun buildGlobalHelp(sender: MessageSender): String {
        val definitions = commandMap.values.distinct().filter { definition ->
            isCommandVisible(definition, sender) &&
                (sender.type != ChatType.SELF || isConsoleAvailable(definition))
        }
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
                    route.subcommands.values.distinct()
                        .filter { sender.type != ChatType.SELF || it.permissionDefault.allowsConsole }
                        .forEach { subcommand ->
                        appendLine()
                        append("    ${subcommand.usage}")
                    }
                }
                if (index != definitions.lastIndex) appendLine()
            }
        }
    }

    private fun buildCommandHelp(definition: CommandDefinition, sender: MessageSender): String {
        val route = definition.route
        if (route !is CommandRoute.Branch) return definition.usage
        val subcommands = route.subcommands.values.distinct()
            .filter { sender.type != ChatType.SELF || it.permissionDefault.allowsConsole }
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
        sendCommandMessage(sender, MessageChain.replyTo(messageID, text))
    }
}
